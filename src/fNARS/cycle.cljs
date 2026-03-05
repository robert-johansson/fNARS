(ns fNARS.cycle
  "Main inference cycle matching ONA's Cycle.c.
   Pure function: (cycle-perform state) -> state."
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
            [fNARS.implication :as implication]))

;; -- Event Selection --

(defn- select-belief-events
  "Select belief events from the cycling belief queue for processing."
  [state]
  (let [config (:config state)
        n (:belief-event-selections config)
        belief-pq (:cycling-belief-events state)]
    (loop [pq belief-pq
           selected []
           i 0]
      (if (or (>= i n) (zero? (pq/pq-count pq)))
        (assoc state
          :cycling-belief-events pq
          :selected-beliefs selected)
        (let [result (pq/pq-pop-max pq)]
          (recur (:pq result)
                 (conj selected {:event (:item result) :priority (:priority result)})
                 (inc i)))))))

(defn- select-goal-events
  "Select goal events from the cycling goal queue (layer 0) for processing."
  [state]
  (let [config (:config state)
        n (:goal-event-selections config)
        goal-pqs (:cycling-goal-events state)]
    (loop [pqs goal-pqs
           selected []
           layer 0]
      (if (or (>= (count selected) n)
              (>= layer (:cycling-goal-events-layers config)))
        (assoc state
          :cycling-goal-events pqs
          :selected-goals selected)
        (let [pq (get pqs layer (pq/pq-init (:cycling-goal-events-max config)))]
          (if (zero? (pq/pq-count pq))
            (recur pqs selected (inc layer))
            (let [result (pq/pq-pop-max pq)]
              (recur (assoc pqs layer (:pq result))
                     (conj selected {:event (:item result)
                                     :priority (:priority result)
                                     :layer layer})
                     layer))))))))

;; -- Belief Processing --

(defn- activate-sensorimotor-concept
  "Activate a concept with a belief event: update belief_spike, eternal belief.
   For sequences without variables, also adds to the occurrence time index
   (matching ONA's Cycle_ProcessSensorimotorEvent lines 92-95)."
  [state ev current-time]
  (let [config (:config state)
        term (:term ev)
        ;; Override creation-time to currentTime (matching ONA line 104)
        ev (assoc ev :creation-time current-time)
        [state concept] (memory/conceptualize state term current-time)]
    (if concept
      (let [;; Add sequences (without variables) to time index
            ;; Matching ONA: if SEQUENCE && !hasVariable(indep, dep)
            state (if (and (term/copula? (term/term-root term) term/SEQUENCE)
                           (not (variable/has-variable? term true true false)))
                    (update state :time-index
                      memory/time-index-add term
                      (:occurrence-time-index-size config))
                    state)
            ;; Update belief spike
            belief-spike-result (inference/revision-and-choice
                                  (:belief-spike concept) ev current-time config)
            ;; Eternalize and update eternal belief
            eternal-ev (event/event-eternalized ev (:horizon config))
            belief-result (inference/revision-and-choice
                            (:belief concept) eternal-ev current-time config)
            concept (-> concept
                        (assoc :belief-spike (:event belief-spike-result))
                        (assoc :belief (:event belief-result))
                        (update :usage concept/usage-use current-time false)
                        (update :priority max (:priority ev 0.5)))]
        (assoc-in state [:concepts term] concept))
      state)))

(defn- reinforce-link
  "Create or reinforce a temporal link (implication) between events.
   Matches Cycle_ReinforceLink."
  [state precondition postcondition current-time]
  (let [config (:config state)
        ;; Create implication via belief induction
        imp (inference/belief-induction precondition postcondition config)
        ;; Variable introduction
        {:keys [term success?]}
        (if (:allow-var-intro config)
          (variable/introduce-implication-variables (:term imp))
          {:term (:term imp) :success? true})
        imp (if success? (assoc imp :term term) imp)
        ;; Normalize variables
        imp (update imp :term variable/normalize-variables)
        ;; Find postcondition concept
        postcondition-term (term/extract-subterm (:term imp) 2)
        [state post-concept] (memory/conceptualize state postcondition-term current-time)]
    (if post-concept
      (let [;; Determine operation ID for table selection
            precondition-term (term/extract-subterm (:term imp) 1)
            op-id (if (and (term/copula? (term/term-root precondition-term) term/SEQUENCE))
                    (let [op-term (term/extract-subterm precondition-term 2)]
                      (if (narsese/is-operation? op-term)
                        (memory/get-operation-id state op-term)
                        0))
                    0)
            ;; Source concept
            source-term (narsese/get-precondition-without-op precondition-term)
            [state source-concept] (memory/conceptualize state source-term current-time)
            imp (assoc imp
                  :source-concept-key (when source-concept (:term source-concept))
                  :source-concept-id (when source-concept (:id source-concept)))
            ;; Add to table with revision
            table-key op-id
            existing-table (get-in post-concept [:precondition-beliefs table-key]
                                   (table/table-init))
            {:keys [table added]} (table/table-add-and-revise
                                    existing-table imp
                                    #(inference/implication-revision %1 %2 config))]
        ;; Update concept
        (memory/update-concept state postcondition-term
          #(assoc-in % [:precondition-beliefs table-key] table)))
      state)))

(defn- is-op-concept?
  "Check if a concept's term is an operation."
  [concept]
  (let [root (term/term-root (:term concept))]
    (or (term/is-operator? root)
        (narsese/is-operation? (:term concept)))))

(defn- mine-temporal-correlations
  "Mine the occurrence time index for temporal correlations.
   Matches ONA's Cycle_ProcessBeliefEvents mining structure:
   1. Search for operations, then preceding non-ops -> <(A &/ op) =/> B>
   2. For same-type pairs (both non-op), create <A =/> B> and (A &/ B) sequences"
  [state selected-belief current-time]
  (let [config (:config state)
        postcondition (:event selected-belief)
        post-time (:occurrence-time postcondition)
        max-dist (:event-belief-distance config)
        post-is-op? (let [r (term/term-root (:term postcondition))]
                      (or (term/is-operator? r) (narsese/is-operation? (:term postcondition))))
        ;; Gather all recent concepts from time index
        time-index (:time-index state)
        items (:items time-index [])
        recent-concepts
        (for [term-key items
              :let [c (memory/find-concept state term-key)]
              :when (and c
                         (not (event/event-deleted? (:belief-spike c)))
                         (let [bt (:occurrence-time (:belief-spike c))]
                           (and (<= bt post-time)
                                (<= (- post-time bt) max-dist))))]
          c)]
    ;; Phase 1: Search for <(precondition &/ operation) =/> postcondition> pattern
    ;; Only when postcondition is NOT an operation
    (let [state
          (if post-is-op?
            state
            (reduce
              (fn [state op-concept]
                (if (or (not (is-op-concept? op-concept))
                        ;; creation-time filter: exclude same-cycle ops (ONA line 698)
                        (>= (:creation-time (:belief-spike op-concept)) current-time)
                        (stamp/stamp-overlap? (:stamp (:belief-spike op-concept)) (:stamp postcondition)))
                  state
                  ;; Found an operation preceding postcondition
                  ;; Now search for non-op preconditions preceding the operation
                  (let [op-ev (:belief-spike op-concept)]
                    (reduce
                      (fn [state prec-concept]
                        (let [prec-root (term/term-root (:term prec-concept))]
                          (if (or (is-op-concept? prec-concept)
                                  (= (:term prec-concept) (:term op-concept))
                                  ;; Exclude implications and equivalences (NOT sequences - ONA line 709)
                                  (term/copula? prec-root term/IMPLICATION)
                                  (term/copula? prec-root term/EQUIVALENCE)
                                  ;; creation-time filter: exclude same-cycle preconditions (ONA line 707)
                                  (>= (:creation-time (:belief-spike prec-concept)) current-time)
                                  (stamp/stamp-overlap? (:stamp (:belief-spike prec-concept))
                                                        (:stamp postcondition)))
                          state
                          (let [prec-ev (:belief-spike prec-concept)
                                prec-time (:occurrence-time prec-ev)]
                            (if (>= prec-time (:occurrence-time op-ev))
                              state
                              ;; Build (precondition &/ operation) sequence
                              (let [seq-event (inference/belief-intersection prec-ev op-ev config)]
                                (if (nil? seq-event)
                                  state
                                  ;; Override term to use left-nested sequence append
                                  (let [seq-term (:term (narsese/make-sequence (:term prec-ev) (:term op-ev)))
                                        seq-event (assoc seq-event :term seq-term)]
                                    ;; Reinforce <(A &/ op) =/> B>
                                    (reinforce-link state seq-event postcondition current-time)))))))))
                      state
                      recent-concepts))))
              state
              recent-concepts))

          ;; Phase 2: For same-type pairs (both non-op), create implications and sequences
          state
          (reduce
            (fn [state prec-concept]
              (let [prec-ev (:belief-spike prec-concept)
                    prec-root (term/term-root (:term prec-concept))
                    prec-is-op? (is-op-concept? prec-concept)
                    both-non-op? (and (not post-is-op?) (not prec-is-op?))
                    both-op? (and post-is-op? prec-is-op?)]
                (if (or (not (or both-non-op? both-op?))
                        (= (:occurrence-time prec-ev) post-time)
                        ;; creation-time filter (ONA line 736: creationTime <= currentTime)
                        (> (:creation-time prec-ev) current-time)
                        ;; Exclude equivalences and implications (ONA line 738)
                        (term/copula? prec-root term/EQUIVALENCE)
                        (term/copula? prec-root term/IMPLICATION)
                        (stamp/stamp-overlap? (:stamp prec-ev) (:stamp postcondition)))
                  state
                  (let [;; Create sequence
                        seq-event (inference/belief-intersection prec-ev postcondition config)
                        ;; For non-op pairs: create <A =/> B> implication
                        state (if both-non-op?
                                (reinforce-link state prec-ev postcondition current-time)
                                state)
                        ;; Process sequence if length within limits (ONA lines 758-769)
                        max-len (if both-non-op?
                                  (:max-sequence-len config)
                                  (:max-compound-op-len config))
                        state (if (and seq-event
                                       (<= (narsese/sequence-length (:term seq-event)) max-len))
                                (activate-sensorimotor-concept state seq-event current-time)
                                state)]
                    state))))
            state
            recent-concepts)]
      state)))

(defn- maybe-anticipate
  "Call anticipation for input operation events.
   Matches ONA Cycle.c lines 866-878: anticipate after processing ops with priority >= 1.0."
  [state ev priority current-time]
  (let [term (:term ev)
        root (term/term-root term)]
    (if-not (and (>= priority 1.0)
                 (or (term/is-operator? root)
                     (narsese/is-operation? term)))
      state
      (let [op-id (memory/get-operation-id state term)]
        (if (zero? op-id)
          state
          (decision/anticipate state op-id current-time))))))

(defn- process-belief-events
  "Process selected belief events: activate concepts, mine temporal correlations."
  [state]
  (let [current-time (:current-time state)]
    (reduce
      (fn [state selected]
        (let [ev (:event selected)]
          (if (event/event-deleted? ev)
            state
            (-> state
                (activate-sensorimotor-concept ev current-time)
                (mine-temporal-correlations selected current-time)
                (maybe-anticipate ev (:priority selected) current-time)))))
      state
      (:selected-beliefs state))))

;; -- Goal Processing --

(defn- process-goal-event
  "Process a single goal event: activate concept, suggest decision, execute."
  [state goal-entry current-time]
  (let [config (:config state)
        goal (:event goal-entry)
        term (:term goal)
        ;; Activate sensorimotor concept for goal
        [state concept] (memory/conceptualize state term current-time)
        state (if concept
                (let [goal-result (inference/revision-and-choice
                                    (:goal-spike concept) goal current-time config)]
                  (memory/update-concept state term
                    #(-> %
                         (assoc :goal-spike (:event goal-result))
                         (update :usage concept/usage-use current-time false)
                         (update :priority max (:priority goal-entry 0.5)))))
                state)
        ;; Re-fetch concept after update
        concept (memory/find-concept state term)]
    (if concept
      ;; Try to suggest a decision
      (let [{:keys [decision state]} (decision/suggest-decision state concept goal current-time)]
        (if (:execute? decision)
          ;; Execute the decision
          (decision/execute-decision state decision current-time)
          ;; No decision from tables - try motor babbling
          (let [babbling-chance (:motor-babbling-chance config)
                rng (:rng-state state 42)
                new-rng (mod (+ (* rng 1103515245) 12345) 2147483648)
                should-babble? (< (/ (double (mod new-rng 1000)) 1000.0) babbling-chance)
                state (assoc state :rng-state new-rng)]
            (if should-babble?
              (let [{:keys [decision state]} (decision/motor-babble state)]
                (if (:execute? decision)
                  (decision/execute-decision state decision current-time)
                  state))
              ;; Propagate subgoals via implications
              (let [related (memory/related-concepts state term (:unification-depth config))]
                (reduce
                  (fn [state related-concept]
                    ;; Check implication tables for goal derivation
                    (reduce
                      (fn [state [op-id table]]
                        (reduce
                          (fn [state imp]
                            (let [postcondition (term/extract-subterm (:term imp) 2)
                                  unification (variable/unify postcondition term)]
                              (if (:success unification)
                                ;; Derive subgoal
                                (let [subgoal (inference/goal-deduction goal imp current-time)
                                      subgoal-exp (truth/truth-expectation (:truth subgoal))]
                                  (if (> subgoal-exp (:decision-threshold config))
                                    ;; Add subgoal to goal events queue (next layer)
                                    (let [layer (inc (:layer goal-entry 0))
                                          goal-pqs (:cycling-goal-events state)
                                          pq-key (min layer (dec (:cycling-goal-events-layers config)))
                                          pq (get goal-pqs pq-key (pq/pq-init (:cycling-goal-events-max config)))
                                          {:keys [pq added?]} (pq/pq-push pq subgoal-exp subgoal)]
                                      (assoc-in state [:cycling-goal-events pq-key] pq))
                                    state))
                                state)))
                          state
                          (when table (:items table))))
                      state
                      (:precondition-beliefs related-concept)))
                  state
                  related))))))
      state)))

(defn- process-goal-events
  "Process selected goal events."
  [state]
  (let [current-time (:current-time state)]
    (reduce
      (fn [state goal-entry]
        (process-goal-event state goal-entry current-time))
      state
      (:selected-goals state))))

;; -- Forgetting --

(defn- apply-forgetting
  "Apply priority decay to events and concepts."
  [state]
  (let [config (:config state)
        event-dur (:event-durability config)
        concept-dur (:concept-durability config)]
    (-> state
        ;; Decay belief events
        (update :cycling-belief-events pq/pq-rebuild event-dur)
        ;; Decay goal events (all layers)
        (update :cycling-goal-events
          (fn [pqs]
            (reduce-kv
              (fn [m k pq] (assoc m k (pq/pq-rebuild pq event-dur)))
              pqs
              pqs)))
        ;; Decay concept priorities
        (update :concepts
          (fn [concepts]
            (reduce-kv
              (fn [m k c] (assoc m k (update c :priority * concept-dur)))
              {}
              concepts))))))

;; -- Process pending events --

(defn- process-pending-events
  "Add pending events (from operation execution) to cycling events."
  [state]
  (let [pending (get state :pending-events [])
        config (:config state)]
    (reduce
      (fn [state ev]
        (let [pq (:cycling-belief-events state)
              {:keys [pq added?]} (pq/pq-push pq 1.0 ev)]
          (assoc state :cycling-belief-events pq)))
      (dissoc state :pending-events)
      pending)))

;; -- Main Cycle --

(defn cycle-perform
  "Perform one inference cycle. Pure function: state -> state."
  [state]
  (-> state
      (update :current-time inc)
      process-pending-events
      select-belief-events
      select-goal-events
      process-belief-events
      process-goal-events
      apply-forgetting
      (dissoc :selected-beliefs :selected-goals)))
