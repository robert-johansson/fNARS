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
            [fNARS.implication :as implication]
            [fNARS.rule-table :as rule-table]))

;; -- Declarative Inference (NAL 1-5) --
;; Matches ONA's Cycle_Inference (Cycle.c lines 971-1069).

(defn- add-derived-belief
  "Add a derived belief event to cycling PQ and conceptualize.
   Matches Memory_AddEvent for derived beliefs."
  [state ev priority eternalize?]
  (let [config (:config state)
        current-time (:current-time state)]
    (if (< (:confidence (:truth ev)) (:min-confidence config))
      state
      (let [{:keys [pq]} (pq/pq-push (:cycling-belief-events state) priority ev)
            state (assoc state :cycling-belief-events pq)
            ;; Conceptualize and update beliefs
            [state concept] (memory/conceptualize state (:term ev) current-time)]
        (if-not concept
          state
          (let [temporal? (not= (:occurrence-time ev) truth/occurrence-eternal)
                ;; Update belief spike for temporal events
                state (if temporal?
                        (memory/update-concept state (:term ev)
                          (fn [c]
                            (let [result (inference/revision-and-choice
                                           (:belief-spike c) ev current-time config)]
                              (-> c
                                  (assoc :belief-spike (:event result))
                                  (update :usage concept/usage-use current-time false)
                                  (update :priority max priority)))))
                        state)
                ;; Eternalize and update eternal belief
                state (if eternalize?
                        (let [eternal-ev (event/event-eternalized ev (:horizon config))]
                          (memory/update-concept state (:term ev)
                            (fn [c]
                              (let [result (inference/revision-and-choice
                                             (:belief c) eternal-ev current-time config)]
                                (assoc c :belief (:event result))))))
                        state)]
            ;; Print derivation if configured
            (if (:print-derivations config)
              (update state :output conj
                {:type :derived
                 :term (:term ev)
                 :truth (:truth ev)
                 :occurrence-time (:occurrence-time ev)
                 :time current-time})
              state)))))))

(defn- select-belief-for-concept
  "Select the best belief from a concept for double-premise inference.
   Prefers temporal spike within EVENT_BELIEF_DISTANCE, else uses eternal.
   Returns {:belief event :eternalize? bool} or nil."
  [concept event-occurrence config]
  (let [eternal (:belief concept)
        spike (:belief-spike concept)
        spike-ok? (and (not (event/event-deleted? spike))
                       (not= (:occurrence-time spike) truth/occurrence-eternal))
        ;; When selected event is temporal, prefer spike within EVENT_BELIEF_DISTANCE.
        ;; When selected event is eternal, still allow spike (better confidence).
        use-spike? (and spike-ok?
                        (or (= event-occurrence truth/occurrence-eternal)
                            (< (js/Math.abs (- event-occurrence (:occurrence-time spike)))
                               (:event-belief-distance config))))
        belief (if use-spike?
                 (if (= event-occurrence truth/occurrence-eternal)
                   spike ;; no projection needed, conclusion will be eternal
                   (assoc spike :truth
                     (truth/truth-projection (:truth spike)
                       (:occurrence-time spike) event-occurrence
                       (:projection-decay config))))
                 eternal)]
    (when (and belief (not (event/event-deleted? belief)))
      {:belief belief :eternalize? (or (not use-spike?)
                                       (= event-occurrence truth/occurrence-eternal))})))

(defn- try-derive-special
  "Try to derive a conclusion from special inference. Adds to state if valid."
  [state conclusion-term conclusion-truth conclusion-stamp conclusion-occ
   parent-priority concept-priority eternalize? nal-level]
  (let [config (:config state)
        current-time (:current-time state)]
    (if-not (rule-table/valid-conclusion? conclusion-term nal-level)
      state
      (let [derived-ev (event/make-event
                         {:term conclusion-term
                          :type event/event-type-belief
                          :truth conclusion-truth
                          :stamp conclusion-stamp
                          :occurrence-time conclusion-occ
                          :creation-time current-time})
            der-priority (* parent-priority concept-priority
                           (truth/truth-expectation conclusion-truth))]
        (add-derived-belief state derived-ev der-priority eternalize?)))))

(defn- special-inferences
  "Higher-order decomposition with variable elimination.
   Matches ONA's Cycle_SpecialInferences (Cycle.c:887-969).
   At NAL level >= 6, handles:
   - A, (A ==> B) |- B (Deduction with var elimination)
   - A, ((A && B) ==> C) |- (B ==> C) (Deduction with remaining condition)
   - B, (A ==> B) |- A (Abduction with var elimination)
   - A, (A <=> B) |- B (Analogy with var elimination)
   - A, (A && B) |- B (Anonymous analogy with dep var elimination)"
  [state term1 term2 truth1 truth2
   conclusion-stamp conclusion-occ parent-priority concept-priority eternalize?]
  (let [config (:config state)
        nal-level (:semantic-inference-nal-level config)
        root2 (term/term-root term2)
        is-impl? (= root2 term/implication)
        is-equiv? (= root2 term/equivalence)]
    (cond-> state
      ;; Implication or equivalence rules
      (or is-impl? is-equiv?)
      (as-> state
        (let [impl-subject (term/extract-subterm term2 1)
              impl-predicate (term/extract-subterm term2 2)
              ;; Deduction/Analogy: unify subject with term1
              subj-subs (variable/unify impl-subject term1)
              state (if (:success subj-subs)
                      (let [conclusion-term (variable/apply-substitute
                                              impl-predicate (:substitution subj-subs))
                            conclusion-truth (if is-impl?
                                               (truth/truth-deduction truth2 truth1)
                                               (truth/truth-analogy truth2 truth1))]
                        (try-derive-special state conclusion-term conclusion-truth
                          conclusion-stamp conclusion-occ parent-priority concept-priority
                          eternalize? nal-level))
                      state)
              ;; Deduction with remaining condition:
              ;; (A && B) ==> C, match A with term1 → (B ==> C)
              state (if (and is-impl? (= (term/term-root impl-subject) term/conjunction))
                      (let [conj-left (term/extract-subterm impl-subject 1)
                            conj-right (term/extract-subterm impl-subject 2)
                            ;; Try left component first
                            subs1 (variable/unify conj-left term1)
                            [subs remaining] (if (:success subs1)
                                               [subs1 conj-right]
                                               ;; Try right component
                                               [(variable/unify conj-right term1) conj-left])]
                        (if (:success subs)
                          (let [conclusion-term (-> (term/atomic-term term/implication)
                                                   (term/override-subterm 1 remaining)
                                                   (term/override-subterm 2 impl-predicate))
                                conclusion-term (variable/apply-substitute
                                                  conclusion-term (:substitution subs))
                                conclusion-truth (truth/truth-deduction truth2 truth1)]
                            (try-derive-special state conclusion-term conclusion-truth
                              conclusion-stamp conclusion-occ parent-priority concept-priority
                              eternalize? nal-level))
                          state))
                      state)
              ;; Abduction: unify predicate with term1
              pred-subs (variable/unify impl-predicate term1)
              state (if (:success pred-subs)
                      (let [conclusion-term (variable/apply-substitute
                                              impl-subject (:substitution pred-subs))
                            conclusion-truth (if is-impl?
                                               (truth/truth-abduction truth2 truth1
                                                 (:horizon config))
                                               (truth/truth-analogy truth2 truth1))]
                        (try-derive-special state conclusion-term conclusion-truth
                          conclusion-stamp conclusion-occ parent-priority concept-priority
                          eternalize? nal-level))
                      state)]
          state))

      ;; Conjunction: anonymous analogy with dep var elimination
      (= root2 term/conjunction)
      (as-> state
        (let [conj-subject (term/extract-subterm term2 1)
              conj-predicate (term/extract-subterm term2 2)
              subj-subs (variable/unify conj-subject term1)]
          (if (:success subj-subs)
            (let [conclusion-term (variable/apply-substitute
                                    conj-predicate (:substitution subj-subs))
                  conclusion-truth (truth/truth-anonymous-analogy truth2 truth1
                                    (:horizon config))]
              (try-derive-special state conclusion-term conclusion-truth
                conclusion-stamp conclusion-occ parent-priority concept-priority
                eternalize? nal-level))
            state))))))

(defn- cycle-inference
  "Run declarative NAL 1-5 inference for a selected belief event.
   Matches ONA's Cycle_Inference (Cycle.c:971-1069).
   Single-premise: apply R1 rules to selected event alone.
   Double-premise: for each related concept, pair with concept's belief and apply R2 rules."
  [state selected-belief]
  (let [config (:config state)
        nal-level (:semantic-inference-nal-level config)]
    (if (<= nal-level 0)
      state
      (let [rules (:nal-rules state)
            ev (:event selected-belief)
            priority (:priority selected-belief)
            current-time (:current-time state)
            term1 (:term ev)
            truth1 (:truth ev)
            occ1 (:occurrence-time ev)
            eternalize? (= occ1 truth/occurrence-eternal)
            ;; Single-premise inference (R1 rules)
            state (reduce
                    (fn [state derivation]
                      (let [conclusion-term (rule-table/reduce-conclusion (:term derivation))
                            conclusion-truth (:truth derivation)]
                        (if-not (rule-table/valid-conclusion? conclusion-term nal-level)
                          state
                          (let [derived-ev (event/make-event
                                            {:term conclusion-term
                                             :type event/event-type-belief
                                             :truth conclusion-truth
                                             :stamp (:stamp ev)
                                             :occurrence-time occ1
                                             :creation-time current-time})
                                der-priority (* priority
                                               (truth/truth-expectation conclusion-truth))]
                            (add-derived-belief state derived-ev der-priority eternalize?)))))
                    state
                    (rule-table/apply-rules rules term1 term/empty-term
                      truth1 truth/default-truth config false))
            ;; Double-premise inference (R2 rules + special inferences)
            related (memory/related-concepts state term1 (:unification-depth config))
            state (reduce
                    (fn [state related-concept]
                      (if-let [{:keys [belief eternalize?]}
                               (select-belief-for-concept related-concept occ1 config)]
                        (let [term2 (:term belief)
                              truth2 (:truth belief)
                              conclusion-stamp (stamp/stamp-make (:stamp ev) (:stamp belief))
                              conclusion-occ (if eternalize? truth/occurrence-eternal occ1)]
                          (if (stamp/stamp-overlap? (:stamp ev) (:stamp belief))
                            state
                            (let [;; Apply rule table
                                  state (reduce
                                          (fn [state derivation]
                                            (let [raw-term (rule-table/reduce-conclusion (:term derivation))
                                                  ;; Variable introduction: replace shared atoms with variables
                                                  conclusion-term (if (:var-intro? derivation)
                                                                    (let [{:keys [term success?]}
                                                                          (variable/introduce-implication-variables raw-term)]
                                                                      (if success? term raw-term))
                                                                    raw-term)
                                                  conclusion-truth (:truth derivation)]
                                              (if-not (rule-table/valid-conclusion? conclusion-term nal-level)
                                                state
                                                (let [derived-ev (event/make-event
                                                                  {:term conclusion-term
                                                                   :type event/event-type-belief
                                                                   :truth conclusion-truth
                                                                   :stamp conclusion-stamp
                                                                   :occurrence-time conclusion-occ
                                                                   :creation-time current-time})
                                                      der-priority (* priority
                                                                     (:priority related-concept 0.5)
                                                                     (truth/truth-expectation conclusion-truth))]
                                                  (add-derived-belief state derived-ev der-priority eternalize?)))))
                                          state
                                          (rule-table/apply-rules rules term1 term2
                                            truth1 truth2 config true))
                                  ;; Special inferences (NAL 6+): both term orderings
                                  state (if (>= nal-level 6)
                                          (-> state
                                              (special-inferences term1 term2 truth1 truth2
                                                conclusion-stamp conclusion-occ priority
                                                (:priority related-concept 0.5) eternalize?)
                                              (special-inferences term2 term1 truth2 truth1
                                                conclusion-stamp conclusion-occ priority
                                                (:priority related-concept 0.5) eternalize?))
                                          state)]
                              state)))
                        state))
                    state
                    related)]
        state))))

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
            state (if (and (= (term/term-root term) term/sequence*)
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
            op-id (if (and (= (term/term-root precondition-term) term/sequence*))
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

(defn- gather-recent-concepts
  "Gather deduplicated recent concepts from the time index whose belief spikes
   fall within max-dist of post-time. Matches ONA's processID2 mechanism."
  [state post-time max-dist]
  (let [items (get-in state [:time-index :items] [])]
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
        items))))

(defn- mine-phase1-triple
  "Try to build <(precondition &/ operation) =/> postcondition> for one
   precondition concept given an operation concept. Returns new state."
  [state prec-concept op-concept postcondition current-time config]
  (let [prec-root (term/term-root (:term prec-concept))]
    (if (or (is-op-concept? prec-concept)
            (= (:term prec-concept) (:term op-concept))
            (= prec-root term/implication)
            (= prec-root term/equivalence)
            (>= (:creation-time (:belief-spike prec-concept)) current-time)
            (stamp/stamp-overlap? (:stamp (:belief-spike prec-concept))
                                  (:stamp postcondition)))
      state
      (let [prec-ev (:belief-spike prec-concept)
            op-ev (:belief-spike op-concept)]
        (if (>= (:occurrence-time prec-ev) (:occurrence-time op-ev))
          state
          (let [seq-event (inference/belief-intersection prec-ev op-ev config)]
            (if (nil? seq-event)
              state
              (let [seq-term (:term (narsese/make-sequence (:term prec-ev) (:term op-ev)))
                    seq-event (assoc seq-event :term seq-term)]
                (reinforce-link state seq-event postcondition current-time)))))))))

(defn- mine-phase1
  "Phase 1: Search for <(precondition &/ operation) =/> postcondition> triples.
   For each operation in recent concepts, find preceding non-op preconditions."
  [state recent-concepts postcondition current-time config]
  (reduce
    (fn [state op-concept]
      (if (or (not (is-op-concept? op-concept))
              (>= (:creation-time (:belief-spike op-concept)) current-time)
              (stamp/stamp-overlap? (:stamp (:belief-spike op-concept)) (:stamp postcondition)))
        state
        (reduce
          (fn [state prec-concept]
            (mine-phase1-triple state prec-concept op-concept postcondition current-time config))
          state
          recent-concepts)))
    state
    recent-concepts))

(defn- mine-phase2
  "Phase 2: For same-type pairs (both non-op or both op), create
   <A =/> B> implications and (A &/ B) sequences."
  [state recent-concepts postcondition post-is-op? current-time config]
  (let [post-time (:occurrence-time postcondition)]
    (reduce
      (fn [state prec-concept]
        (let [prec-ev (:belief-spike prec-concept)
              prec-root (term/term-root (:term prec-concept))
              prec-is-op? (is-op-concept? prec-concept)
              both-non-op? (and (not post-is-op?) (not prec-is-op?))
              both-op? (and post-is-op? prec-is-op?)]
          (if (or (not (or both-non-op? both-op?))
                  (= (:occurrence-time prec-ev) post-time)
                  (> (:creation-time prec-ev) current-time)
                  (= prec-root term/equivalence)
                  (= prec-root term/implication)
                  (stamp/stamp-overlap? (:stamp prec-ev) (:stamp postcondition)))
            state
            (let [seq-event (inference/belief-intersection prec-ev postcondition config)
                  state (if both-non-op?
                          (reinforce-link state prec-ev postcondition current-time)
                          state)
                  max-len (if both-non-op?
                            (:max-sequence-len config)
                            (:max-compound-op-len config))
                  state (if (and seq-event
                                 (<= (narsese/sequence-length (:term seq-event)) max-len))
                          (activate-sensorimotor-concept state seq-event current-time)
                          state)]
              state))))
      state
      recent-concepts)))

(defn- mine-temporal-correlations
  "Mine the occurrence time index for temporal correlations.
   Matches ONA's Cycle_ProcessBeliefEvents mining structure."
  [state selected-belief current-time]
  (let [config (:config state)
        postcondition (:event selected-belief)
        post-time (:occurrence-time postcondition)
        post-is-op? (let [r (term/term-root (:term postcondition))]
                      (or (term/is-operator? r) (narsese/is-operation? (:term postcondition))))
        recent-concepts (gather-recent-concepts state post-time (:event-belief-distance config))]
    (-> state
        (cond-> (not post-is-op?)
          (mine-phase1 recent-concepts postcondition current-time config))
        (mine-phase2 recent-concepts postcondition post-is-op? current-time config))))

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
  "Process selected belief events: activate concepts, mine temporal correlations,
   and run declarative NAL 1-5 inference."
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
                (maybe-anticipate ev (:priority selected) current-time)
                (cycle-inference selected)))))
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
    (when (= (term/term-root goal-term) term/sequence*)
      ;; Extract components right-to-left from left-nested sequence
      ;; (&/ (&/ A B) C) -> [C, B, A] (index 0=rightmost, i=leftmost)
      (let [components
            (loop [cur goal-term, comps []]
              (if (= (term/term-root cur) term/sequence*)
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
            (persistent!
              (reduce-kv
                (fn [m k c] (assoc! m k (update c :priority * concept-dur)))
                (transient concepts)
                concepts)))))))

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
