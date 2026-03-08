(ns fNARS.nar
  "Top-level NAR API matching ONA's NAR.c.
   The entire NAR is a single immutable map."
  (:require [fNARS.truth :as truth]
            [fNARS.term :as term]
            [fNARS.stamp :as stamp]
            [fNARS.event :as event]
            [fNARS.inference :as inference]
            [fNARS.narsese :as narsese]
            [fNARS.variable :as variable]
            [fNARS.decision :as decision]
            [fNARS.memory :as memory]
            [fNARS.concept :as concept]
            [fNARS.priority-queue :as pq]
            [fNARS.table :as table]
            [fNARS.cycle :as cycle]
            [fNARS.nar-config :as nar-config]
            [fNARS.rule-table :as rule-table]))

(defn nar-init
  "Create a fresh NAR state with the given config (or defaults)."
  ([] (nar-init nar-config/default-config))
  ([config]
   {:current-time 1
    :stamp-counter 1
    :next-concept-id 0
    :config config
    :concepts {}
    :atom-index {}
    :atom-values {}
    :time-index {:items [] :current-index 0}
    :cycling-belief-events (pq/pq-init (:cycling-belief-events-max config))
    :cycling-goal-events {}
    :operations {}
    :rng-state [0 42]
    :nal-rules (rule-table/rules-for-level (:semantic-inference-nal-level config))
    :output []}))

(defn nar-add-operation
  "Register an operation with the NAR.
   action-fn: (fn [state args] -> state) called when operation executes.
   opts: {:args [term-vec ...]} — argument variants for babbling/execution."
  [state op-name action-fn & [{:keys [args]}]]
  (let [op-atom (keyword op-name)
        existing-id (first (keep (fn [[k v]] (when (= (:atom v) op-atom) k))
                                 (:operations state)))
        op-id (or existing-id
                  (inc (count (:operations state))))]
    (assoc-in state [:operations op-id]
      (cond-> {:name op-name
               :atom op-atom
               :action action-fn}
        args (assoc :args (vec args))))))

(defn nar-register-atom-value
  "Register a numeric value and measurement name for an atom keyword.
   Atoms with the same measurement name can be analogically unified.
   Matches ONA's Narsese_setAtomValue."
  [state atom-kw value measurement-name]
  (assoc-in state [:atom-values atom-kw] {:value value :measurement measurement-name}))

(defn- enqueue-event
  "Add event to the appropriate cycling priority queue."
  [state ev priority layer]
  (let [config (:config state)]
    (cond
      (= (:type ev) event/event-type-belief)
      (let [{:keys [pq]} (pq/pq-push (:cycling-belief-events state) priority ev)]
        (assoc state :cycling-belief-events pq))

      (= (:type ev) event/event-type-goal)
      (let [layer-key (min layer (dec (:cycling-goal-events-layers config)))
            pq (get-in state [:cycling-goal-events layer-key]
                       (pq/pq-init (:cycling-goal-events-max config)))
            {:keys [pq]} (pq/pq-push pq priority ev)]
        (assoc-in state [:cycling-goal-events layer-key] pq))

      :else state)))

(defn- process-new-belief
  "Conceptualize a belief event and update spike/predicted/eternal beliefs."
  [state ev priority eternalize?]
  (let [config (:config state)
        current-time (:current-time state)
        [state concept] (memory/conceptualize state (:term ev) current-time)]
    (if-not concept
      state
      (let [temporal? (not= (:occurrence-time ev) truth/occurrence-eternal)
            state (if temporal?
                    (update state :time-index
                      memory/time-index-add (:term ev) (:occurrence-time-index-size config))
                    state)
            state (if (and temporal? (<= (:occurrence-time ev) current-time))
                    (memory/update-concept state (:term ev)
                      (fn [c]
                        (let [result (inference/revision-and-choice
                                       (:belief-spike c) ev current-time config)]
                          (-> c
                              (assoc :belief-spike (:event result))
                              (update :usage concept/usage-use current-time false)
                              (update :priority max priority)))))
                    state)
            state (if (and temporal? (> (:occurrence-time ev) current-time))
                    (memory/update-concept state (:term ev)
                      (fn [c]
                        (let [result (inference/revision-and-choice
                                       (:predicted-belief c) ev current-time config)]
                          (assoc c :predicted-belief (:event result)))))
                    state)
            state (if eternalize?
                    (let [eternal-ev (event/event-eternalized ev (:horizon config))]
                      (memory/update-concept state (:term ev)
                        (fn [c]
                          (let [result (inference/revision-and-choice
                                         (:belief c) eternal-ev current-time config)]
                            (assoc c :belief (:event result))))))
                    state)]
        state))))

(defn- add-event-to-memory
  "Add an event to the NAR's memory (cycling events and concept updates).
   Matches Memory_AddEvent + Memory_ProcessNewBeliefEvent."
  [state ev priority input? derived? revised? layer eternalize?]
  (if (< (:confidence (:truth ev)) (:min-confidence (:config state)))
    state
    (let [state (enqueue-event state ev priority layer)
          state (if (= (:type ev) event/event-type-belief)
                  (process-new-belief state ev priority eternalize?)
                  state)
          state (if (and input? (:print-input (:config state)))
                  (update state :output conj
                    {:type :input
                     :event-type (:type ev)
                     :term (:term ev)
                     :truth (:truth ev)
                     :occurrence-time (:occurrence-time ev)
                     :time (:current-time state)})
                  state)]
      state)))

(defn nar-add-input
  "Add an input event (belief or goal) to the NAR.
   Runs one cycle after adding."
  [state term type tv & [{:keys [eternal? occurrence-time-offset]
                          :or {eternal? false occurrence-time-offset 0.0}}]]
  (let [current-time (:current-time state)
        [ev new-counter] (event/make-input-event term type tv current-time
                                                  (:stamp-counter state)
                                                  {:occurrence-time-offset occurrence-time-offset})
        ev (if eternal?
             (assoc ev :occurrence-time truth/occurrence-eternal)
             ev)
        state (assoc state :stamp-counter new-counter)
        state (add-event-to-memory state ev 1.0 true false false 0
                (or eternal? (== (:occurrence-time ev) truth/occurrence-eternal)))]
    ;; Run one cycle
    (cycle/cycle-perform state)))

(defn nar-add-input-belief
  "Add a belief event with default truth."
  [state term]
  (nar-add-input state term event/event-type-belief truth/default-truth))

(defn nar-add-input-goal
  "Add a goal event with default truth."
  [state term]
  (nar-add-input state term event/event-type-goal truth/default-truth))

(defn nar-cycles
  "Run n inference cycles."
  [state n]
  (loop [state state
         i 0]
    (if (>= i n)
      state
      (recur (cycle/cycle-perform state) (inc i)))))

(defn- best-by-expectation
  "Return candidate if its expectation beats best, otherwise return best."
  [best candidate]
  (if (or (nil? best)
          (>= (truth/truth-expectation (:truth candidate))
              (truth/truth-expectation (or (:projected-truth best) (:truth best)))))
    candidate
    best))

(defn- search-implications
  "Search a concept's implication tables for a matching implication."
  [best concept question-term]
  (reduce
    (fn [best [_op-id table]]
      (reduce
        (fn [best imp]
          (if-not (or (:success (variable/unify (:term imp) question-term))
                      (:success (variable/unify-query question-term (:term imp))))
            best
            (best-by-expectation best
              {:term (:term imp)
               :truth (:truth imp)
               :occurrence-time truth/occurrence-eternal})))
        best
        (when table (:items table))))
    best
    (:precondition-beliefs concept)))

(defn- search-temporal-belief
  "Check a concept's belief spike and predicted belief for temporal questions."
  [best concept tense current-time config]
  (let [;; Check belief spike (present/past)
        best (if (and (not (event/event-deleted? (:belief-spike concept)))
                      (or (= tense :present) (= tense :past)))
               (let [projected (truth/truth-projection
                                 (:truth (:belief-spike concept))
                                 (:occurrence-time (:belief-spike concept))
                                 current-time (:projection-decay config))]
                 (best-by-expectation best
                   {:term (:term (:belief-spike concept))
                    :truth (:truth (:belief-spike concept))
                    :projected-truth projected
                    :occurrence-time (:occurrence-time (:belief-spike concept))
                    :concept concept}))
               best)
        ;; Check predicted belief (present/future)
        best (if (and (not (event/event-deleted? (:predicted-belief concept)))
                      (or (= tense :present) (= tense :future)))
               (let [projected (truth/truth-projection
                                 (:truth (:predicted-belief concept))
                                 (:occurrence-time (:predicted-belief concept))
                                 current-time (:projection-decay config))]
                 (best-by-expectation best
                   {:term (:term (:predicted-belief concept))
                    :truth (:truth (:predicted-belief concept))
                    :projected-truth projected
                    :occurrence-time (:occurrence-time (:predicted-belief concept))
                    :concept concept}))
               best)]
    best))

(defn- search-eternal-belief
  "Check a concept's eternal belief."
  [best concept]
  (if (event/event-deleted? (:belief concept))
    best
    (best-by-expectation best
      {:term (:term (:belief concept))
       :truth (:truth (:belief concept))
       :occurrence-time truth/occurrence-eternal
       :concept concept})))

(defn nar-answer-question
  "Answer a question by scanning all concepts for matching beliefs/implications.
   Matches ONA's NAR.c question answering (lines 123-283).
   Returns {:state state :answer {:term t :truth tv :occurrence-time ot} or nil}."
  [state term tense]
  (let [config (:config state)
        current-time (:current-time state)
        is-implication? (= (term/term-root term) term/temporal-implication)
        is-non-temporal-imp? (= (term/term-root term) term/implication)
        is-any-imp? (or is-implication? is-non-temporal-imp?)
        to-compare (if is-implication? (term/extract-subterm term 2) term)
        [state _] (memory/conceptualize state term current-time)
        best (reduce
               (fn [best [_concept-term concept]]
                 (if-not (:success (variable/unify-query to-compare (:term concept)))
                   best
                   (cond-> best
                     is-any-imp?
                     (search-implications concept term)

                     ;; For non-temporal implications, also check eternal belief directly
                     is-non-temporal-imp?
                     (search-eternal-belief concept)

                     (and (not is-any-imp?) (not= tense :eternal))
                     (search-temporal-belief concept tense current-time config)

                     (and (not is-any-imp?) (= tense :eternal))
                     (search-eternal-belief concept))))
               nil
               (:concepts state))
        state (if-let [c (:concept best)]
                (memory/update-concept state (:term c)
                  #(-> %
                       (update :priority max (:question-priming config))
                       (update :usage concept/usage-use current-time (= tense :eternal))))
                state)]
    {:state state
     :answer (when best
               (dissoc best :concept :projected-truth))}))

(defn nar-get-output
  "Get and clear accumulated output."
  [state]
  {:output (:output state)
   :state (assoc state :output [])})
