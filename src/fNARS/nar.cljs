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
            [fNARS.nar-config :as nar-config]))

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
    :time-index {:items [] :current-index 0}
    :cycling-belief-events (pq/pq-init (:cycling-belief-events-max config))
    :cycling-goal-events {}
    :operations {}
    :rng-state 42
    :output []}))

(defn nar-add-operation
  "Register an operation with the NAR.
   action-fn: (fn [state args] -> state) called when operation executes."
  [state op-name action-fn]
  (let [op-atom (keyword op-name)
        existing-id (first (keep (fn [[k v]] (when (= (:atom v) op-atom) k))
                                 (:operations state)))
        op-id (or existing-id
                  (inc (count (:operations state))))]
    (assoc-in state [:operations op-id]
      {:name op-name
       :atom op-atom
       :action action-fn})))

(defn- add-event-to-memory
  "Add an event to the NAR's memory (cycling events and concept updates).
   Matches Memory_AddEvent + Memory_ProcessNewBeliefEvent."
  [state ev priority input? derived? revised? layer eternalize?]
  (let [config (:config state)
        current-time (:current-time state)
        min-conf (:min-confidence config)]
    ;; Skip if confidence too low
    (if (< (:confidence (:truth ev)) min-conf)
      state
      (let [;; Add to cycling events queue
            state
            (cond
              (= (:type ev) event/event-type-belief)
              (let [pq (:cycling-belief-events state)
                    {:keys [pq added?]} (pq/pq-push pq priority ev)]
                (assoc state :cycling-belief-events pq))

              (= (:type ev) event/event-type-goal)
              (let [layer-key (min layer (dec (:cycling-goal-events-layers config)))
                    pqs (:cycling-goal-events state)
                    pq (get pqs layer-key (pq/pq-init (:cycling-goal-events-max config)))
                    {:keys [pq added?]} (pq/pq-push pq priority ev)]
                (assoc-in state [:cycling-goal-events layer-key] pq))

              :else state)

            ;; Process belief events: conceptualize, update spike/eternal
            state
            (if (= (:type ev) event/event-type-belief)
              (let [;; Conceptualize and update
                    [state concept] (memory/conceptualize state (:term ev) current-time)]
                (if concept
                  (let [;; Update occurrence time index
                        state (if (not= (:occurrence-time ev) truth/occurrence-eternal)
                                (update state :time-index
                                  memory/time-index-add (:term ev)
                                  (:occurrence-time-index-size config))
                                state)
                        ;; Update belief spike (temporal)
                        state (if (and (not= (:occurrence-time ev) truth/occurrence-eternal)
                                       (<= (:occurrence-time ev) current-time))
                                (memory/update-concept state (:term ev)
                                  (fn [c]
                                    (let [result (inference/revision-and-choice
                                                   (:belief-spike c) ev current-time config)]
                                      (-> c
                                          (assoc :belief-spike (:event result))
                                          (update :usage concept/usage-use current-time false)
                                          (update :priority max priority)))))
                                state)
                        ;; Update predicted belief (future)
                        state (if (and (not= (:occurrence-time ev) truth/occurrence-eternal)
                                       (> (:occurrence-time ev) current-time))
                                (memory/update-concept state (:term ev)
                                  (fn [c]
                                    (let [result (inference/revision-and-choice
                                                   (:predicted-belief c) ev current-time config)]
                                      (assoc c :predicted-belief (:event result)))))
                                state)
                        ;; Update eternal belief
                        state (if eternalize?
                                (let [eternal-ev (event/event-eternalized ev (:horizon config))]
                                  (memory/update-concept state (:term ev)
                                    (fn [c]
                                      (let [result (inference/revision-and-choice
                                                     (:belief c) eternal-ev current-time config)]
                                        (assoc c :belief (:event result))))))
                                state)]
                    state)
                  state))
              state)

            ;; Print input
            state
            (if (and input? (:print-input config))
              (update state :output conj
                {:type :input
                 :event-type (:type ev)
                 :term (:term ev)
                 :truth (:truth ev)
                 :occurrence-time (:occurrence-time ev)
                 :time current-time})
              state)]
        state))))

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

(defn nar-answer-question
  "Answer a question by scanning all concepts for matching beliefs/implications.
   Matches ONA's NAR.c question answering (lines 123-283).
   Returns {:state state :answer {:term t :truth tv :occurrence-time ot} or nil}."
  [state term tense]
  (let [config (:config state)
        current-time (:current-time state)
        is-implication? (term/copula? (term/term-root term) term/TEMPORAL-IMPLICATION)
        ;; For implications, compare postcondition; otherwise compare full term
        to-compare (if is-implication? (term/extract-subterm term 2) term)
        ;; Also check non-temporal implication
        is-non-temporal-imp? (term/copula? (term/term-root term) term/IMPLICATION)
        ;; Conceptualize the question term
        [state _] (memory/conceptualize state term current-time)
        ;; Scan all concepts
        best (reduce
               (fn [best [concept-term concept]]
                 (let [;; Try to unify question term with concept term (query-var only)
                       match? (variable/unify-query to-compare concept-term)]
                   (if-not (:success match?)
                     best
                     (let [;; Search implication tables if question is an implication
                           best (if (or is-implication? is-non-temporal-imp?)
                                  ;; Search precondition_beliefs tables
                                  (reduce
                                    (fn [best [op-id table]]
                                      (reduce
                                        (fn [best imp]
                                          (if-not (:success (variable/unify-query term (:term imp)))
                                            best
                                            (let [exp (truth/truth-expectation (:truth imp))]
                                              (if (or (nil? best)
                                                      (>= exp (truth/truth-expectation (:truth best))))
                                                {:term (:term imp)
                                                 :truth (:truth imp)
                                                 :occurrence-time truth/occurrence-eternal}
                                                best))))
                                        best
                                        (when table (:items table))))
                                    best
                                    (:precondition-beliefs concept))
                                  best)
                           ;; Search beliefs based on tense
                           best (if (and (not is-implication?) (not is-non-temporal-imp?))
                                  (cond
                                    ;; Temporal (present/past/future)
                                    (not= tense :eternal)
                                    (let [;; Check belief spike (present/past)
                                          best (if (and (not (event/event-deleted? (:belief-spike concept)))
                                                        (or (= tense :present) (= tense :past)))
                                                 (let [projected (truth/truth-projection
                                                                   (:truth (:belief-spike concept))
                                                                   (:occurrence-time (:belief-spike concept))
                                                                   current-time
                                                                   (:projection-decay config))
                                                       exp (truth/truth-expectation projected)]
                                                   (if (or (nil? best)
                                                           (> exp (truth/truth-expectation
                                                                    (or (:projected-truth best) (:truth best)))))
                                                     {:term (:term (:belief-spike concept))
                                                      :truth (:truth (:belief-spike concept))
                                                      :projected-truth projected
                                                      :occurrence-time (:occurrence-time (:belief-spike concept))
                                                      :concept concept}
                                                     best))
                                                 best)
                                          ;; Check predicted belief (present/future)
                                          best (if (and (not (event/event-deleted? (:predicted-belief concept)))
                                                        (or (= tense :present) (= tense :future)))
                                                 (let [projected (truth/truth-projection
                                                                   (:truth (:predicted-belief concept))
                                                                   (:occurrence-time (:predicted-belief concept))
                                                                   current-time
                                                                   (:projection-decay config))
                                                       exp (truth/truth-expectation projected)]
                                                   (if (or (nil? best)
                                                           (> exp (truth/truth-expectation
                                                                    (or (:projected-truth best) (:truth best)))))
                                                     {:term (:term (:predicted-belief concept))
                                                      :truth (:truth (:predicted-belief concept))
                                                      :projected-truth projected
                                                      :occurrence-time (:occurrence-time (:predicted-belief concept))
                                                      :concept concept}
                                                     best))
                                                 best)]
                                      best)

                                    ;; Eternal
                                    :else
                                    (if (not (event/event-deleted? (:belief concept)))
                                      (let [exp (truth/truth-expectation (:truth (:belief concept)))]
                                        (if (or (nil? best)
                                                (>= exp (truth/truth-expectation (:truth best))))
                                          {:term (:term (:belief concept))
                                           :truth (:truth (:belief concept))
                                           :occurrence-time truth/occurrence-eternal
                                           :concept concept}
                                          best))
                                      best))
                                  best)]
                       best))))
               nil
               (:concepts state))
        ;; Apply question priming
        state (if-let [c (:concept best)]
                (let [priming (:question-priming config)]
                  (memory/update-concept state (:term c)
                    #(-> %
                         (update :priority max priming)
                         (update :usage concept/usage-use current-time (= tense :eternal)))))
                state)]
    {:state state
     :answer (when best
               (dissoc best :concept :projected-truth))}))

(defn nar-get-output
  "Get and clear accumulated output."
  [state]
  {:output (:output state)
   :state (assoc state :output [])})
