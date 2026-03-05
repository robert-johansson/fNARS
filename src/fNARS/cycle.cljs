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

;; Goal selection is now integrated into process-goal-events (layer-by-layer)

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
        ;; Deduplicate by concept id, matching ONA's processID2 mechanism
        ;; (the time index can contain the same term-key multiple times)
        recent-concepts
        (second
          (reduce
            (fn [[seen concepts] term-key]
              (let [c (memory/find-concept state term-key)]
                (if (and c
                         (not (contains? seen (:id c)))
                         (not (event/event-deleted? (:belief-spike c)))
                         (let [bt (:occurrence-time (:belief-spike c))]
                           (and (<= bt post-time)
                                (<= (- post-time bt) max-dist))))
                  [(conj seen (:id c)) (conj concepts c)]
                  [seen concepts])))
            [#{} []]
            items))]
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
  "Call anticipation for input belief events with priority >= 1.0.
   Matches ONA Cycle.c line 875-878: for every selected belief with input priority,
   call Decision_Anticipate(op_id, op_term, false, currentTime).
   For non-operation beliefs (ball_right etc.), op_id=0, which triggers
   negative confirmation on temporal implications in precondition-beliefs[0]."
  [state ev priority current-time]
  (if-not (>= priority 1.0)
    state
    (let [op-id (memory/get-operation-id state (:term ev))]
      (decision/anticipate state op-id current-time))))

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

(defn- propagate-subgoals
  "Propagate subgoals via implications for a goal that didn't trigger execution."
  [state goal-entry current-time]
  (let [config (:config state)
        goal (:event goal-entry)
        term (:term goal)
        related (memory/related-concepts state term (:unification-depth config))]
    (reduce
      (fn [state related-concept]
        (reduce
          (fn [state [op-id table]]
            (reduce
              (fn [state imp]
                (let [postcondition (term/extract-subterm (:term imp) 2)
                      unification (variable/unify postcondition term)]
                  (if (:success unification)
                    (let [subgoal (inference/goal-deduction goal imp current-time)
                          subgoal-exp (truth/truth-expectation (:truth subgoal))]
                      (if (> subgoal-exp (:decision-threshold config))
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
      related)))

(defn- activate-goal-concept
  "Activate a concept for a goal event: set goal spike, call Decision_Suggest.
   Matches ONA's Cycle_ActivateSensorimotorConcept for goal events."
  [state concept-term goal current-time]
  (let [concept (memory/find-concept state concept-term)]
    (if (nil? concept)
      {:decision {:execute? false :desire 0} :state state}
      (let [config (:config state)
            state (memory/update-concept state concept-term
                    #(-> %
                         (update :goal-spike
                           (fn [spike]
                             (if (or (= (:type spike) :deleted) (nil? spike)
                                     (> (:occurrence-time goal) (:occurrence-time spike)))
                               goal spike)))
                         (update :usage concept/usage-use current-time false)))
            concept (memory/find-concept state concept-term)]
        (decision/suggest-decision state concept goal current-time)))))

(defn- process-sensorimotor-goal
  "Process a goal event against all related concepts, matching ONA's
   Cycle_ProcessSensorimotorEvent. Conceptualizes the term, then iterates
   related concepts calling activate-sensorimotor-concept for each.
   Returns {:decision best-decision :state state}."
  [state goal current-time]
  (let [config (:config state)
        term (:term goal)
        [state _concept] (memory/conceptualize state term current-time)
        related (memory/related-concepts state term (:unification-depth config))]
    (reduce
      (fn [{:keys [decision state] :as acc} related-concept]
        (let [concept-term (:term related-concept)
              subs (variable/unify concept-term term)]
          (if (:success subs)
            (let [{rel-decision :decision rel-state :state}
                  (activate-goal-concept state concept-term goal current-time)]
              (if (and (:execute? rel-decision)
                       (>= (:desire rel-decision 0) (:desire decision 0)))
                {:decision rel-decision :state rel-state}
                {:decision decision :state rel-state}))
            acc)))
      {:decision {:execute? false :desire 0} :state state}
      related)))

(defn- find-best-concept-for-component
  "Find the concept with strongest belief spike matching a goal component.
   Returns {:best-c concept :best-subs substitution} or nil."
  [state component current-time config last-occ-time]
  (let [related (memory/related-concepts state component (:unification-depth config))
        has-vars? (variable/has-variable? component)]
    (loop [remaining (seq related)
           best-c nil
           best-subs nil
           best-exp 0.0]
      (if-not remaining
        (when best-c {:best-c best-c :best-subs best-subs})
        (let [c (first remaining)]
          (if (variable/has-variable? (:term c))
            (if has-vars? (recur (next remaining) best-c best-subs best-exp) ;; continue
                          (when best-c {:best-c best-c :best-subs best-subs})) ;; done (no-var optimization)
            (let [subs (variable/unify component (:term c))
                  spike (:belief-spike c)
                  exp (when (and (:success subs)
                                 (not (event/event-deleted? spike))
                                 (>= (:occurrence-time spike) last-occ-time))
                        (truth/truth-expectation
                          (truth/truth-projection (:truth spike) (:occurrence-time spike)
                                                  current-time (:projection-decay config))))
                  good? (and exp (> exp (:condition-threshold config)) (> exp best-exp))
                  [bc bs be] (if good? [c (:substitution subs) exp] [best-c best-subs best-exp])]
              (if has-vars?
                (recur (next remaining) bc bs be)
                ;; No variables: only check one concept (ONA goto DONE_CONCEPT_ITERATING)
                (when bc {:best-c bc :best-subs bs})))))))))

(defn- goal-sequence-decomposition
  "Decompose sequence goals into component subgoals.
   Matches ONA Cycle.c:158-268 (Cycle_GoalSequenceDecomposition).
   Returns updated state if the goal was a sequence, nil otherwise."
  [state goal priority layer]
  (let [config (:config state)
        current-time (:current-time state)
        goal-term (:term goal)]
    (when (term/copula? (term/term-root goal-term) term/SEQUENCE)
      ;; Extract components right-to-left from left-nested sequence
      ;; (&/ (&/ A B) C) -> [C, B, A] (index 0=rightmost, i=leftmost)
      (let [components
            (loop [cur goal-term, comps []]
              (if (term/copula? (term/term-root cur) term/SEQUENCE)
                (recur (term/extract-subterm cur 1)
                       (conj comps (term/extract-subterm cur 2)))
                (conj comps cur)))
            i (dec (count components)) ;; index of deepest/leftmost
            new-goal (inference/event-update goal current-time config)
            ;; Loop from j=i (deepest) toward j=0 (rightmost)
            [status j new-goal]
            (loop [j i, new-goal new-goal, components components, last-occ -1]
              (if (< j 0)
                [:done -1 new-goal] ;; shouldn't happen (j==0 returns :all-fulfilled)
                (let [match (find-best-concept-for-component
                              state (nth components j) current-time config last-occ)]
                  (if (nil? match)
                    [:broke j new-goal]
                    (if (zero? j)
                      [:all-fulfilled 0 new-goal]
                      ;; Apply substitution to remaining components, derive subgoal
                      (let [{:keys [best-c best-subs]} match
                            components (if (seq best-subs)
                                         (reduce
                                           (fn [comps u]
                                             (assoc comps u (variable/apply-substitute
                                                             (nth comps u) best-subs)))
                                           components (range 0 j))
                                         components)
                            new-goal (inference/goal-sequence-deduction
                                       new-goal (:belief-spike best-c) current-time config)
                            new-goal (assoc new-goal :term (nth components (dec j)))]
                        (recur (dec j) new-goal components
                               (:occurrence-time (:belief-spike best-c)))))))))]
        (case status
          :all-fulfilled state ;; all components matched, nothing to derive
          :broke
          (let [;; If j==i (nothing matched), structural deduction on deepest
                new-goal (if (== j i)
                           (-> new-goal
                               (assoc :term (nth components i))
                               (assoc :truth (truth/truth-structural-deduction
                                               (:truth new-goal) config)))
                           new-goal)
                ;; Add derived subgoal to cycling goal events
                sub-priority (* priority (truth/truth-expectation (:truth new-goal)))
                pq-key (min layer (dec (:cycling-goal-events-layers config)))
                pq (get-in state [:cycling-goal-events pq-key]
                           (pq/pq-init (:cycling-goal-events-max config)))
                {:keys [pq]} (pq/pq-push pq sub-priority new-goal)]
            (assoc-in state [:cycling-goal-events pq-key] pq)))))))

(defn- process-goal-events
  "Process goal events layer by layer, matching ONA's Cycle.c flow.
   For each layer: pop goal, process against related concepts (each calling
   Decision_Suggest), execute if found, otherwise propagate subgoals."
  [state]
  (let [config (:config state)
        current-time (:current-time state)
        n (:goal-event-selections config)
        layers (:cycling-goal-events-layers config)]
    (loop [state state
           layer 0]
      (if (>= layer layers)
        state
        (let [pq (get-in state [:cycling-goal-events layer]
                         (pq/pq-init (:cycling-goal-events-max config)))]
          (if (zero? (pq/pq-count pq))
            (recur state (inc layer))
            ;; Pop one goal from this layer
            (let [result (pq/pq-pop-max pq)
                  state (assoc-in state [:cycling-goal-events layer] (:pq result))
                  goal-entry {:event (:item result)
                              :priority (:priority result)
                              :layer layer}
                  goal (:event goal-entry)]
              ;; Try sequence decomposition first (ONA Cycle.c:582-586)
              (if-let [decomposed-state (goal-sequence-decomposition
                                          state goal (:priority goal-entry) layer)]
                (recur decomposed-state (inc layer))
                ;; Not a sequence goal — proceed with normal processing
                (let [{:keys [decision state]}
                      (process-sensorimotor-goal state goal current-time)]
                  (if (:execute? decision)
                    ;; Execute, reset all cycling goal events, stop
                    (-> (decision/execute-decision state decision current-time)
                        (assoc :cycling-goal-events {}))
                    ;; Propagate subgoals, continue to next layer
                    (recur (propagate-subgoals state goal-entry current-time)
                           (inc layer))))))))))))

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
  "Process pending events from operation execution.
   Activates concepts, adds to time index, and runs anticipation.
   Sets creation-time to current-time - 1 to simulate ONA's behavior
   where each operation gets its own cycle via NAR_AddInputBelief.
   Does NOT add to cycling PQ — this prevents activate-sensorimotor-concept
   from overriding the creation-time when the event would be re-selected."
  [state]
  (let [pending (get state :pending-events [])
        config (:config state)
        current-time (:current-time state)]
    (reduce
      (fn [state ev]
        (let [term (:term ev)
              ;; Set creation-time to previous cycle so it passes creation-time filters
              ;; when other events are mined in the same cycle
              ev (assoc ev :creation-time (dec current-time))
              [state concept] (memory/conceptualize state term current-time)]
          (if-not concept
            state
            (let [;; Update belief spike
                  belief-spike-result (inference/revision-and-choice
                                       (:belief-spike concept) ev current-time config)
                  ;; Eternalize and update eternal belief
                  eternal-ev (event/event-eternalized ev (:horizon config))
                  belief-result (inference/revision-and-choice
                                  (:belief concept) eternal-ev current-time config)
                  state (memory/update-concept state term
                          (fn [c] (-> c
                                      (assoc :belief-spike (:event belief-spike-result))
                                      (assoc :belief (:event belief-result))
                                      (update :usage concept/usage-use current-time false)
                                      (update :priority max 1.0))))
                  ;; Add to time index
                  state (if (not= (:occurrence-time ev) truth/occurrence-eternal)
                          (update state :time-index
                            memory/time-index-add term
                            (:occurrence-time-index-size config))
                          state)
                  ;; Run anticipation for operation events
                  root (term/term-root term)
                  state (if (or (term/is-operator? root) (narsese/is-operation? term))
                          (let [op-id (memory/get-operation-id state term)]
                            (if (pos? op-id)
                              (decision/anticipate state op-id current-time)
                              state))
                          state)]
              state))))
      (dissoc state :pending-events)
      pending)))

;; -- Main Cycle --

(defn cycle-perform
  "Perform one inference cycle. Pure function: state -> state.
   Time increments AFTER processing, matching ONA's flow:
   event at currentTime -> Cycle_Perform(currentTime) -> currentTime++"
  [state]
  (-> state
      process-pending-events
      select-belief-events
      process-belief-events
      process-goal-events
      apply-forgetting
      (dissoc :selected-beliefs)
      (update :current-time inc)))
