(ns fNARS.decision
  "Decision making matching ONA's Decision.c.
   Suggest operations, motor babbling, anticipation."
  (:require [fNARS.truth :as truth]
            [fNARS.term :as term]
            [fNARS.stamp :as stamp]
            [fNARS.event :as event]
            [fNARS.inference :as inference]
            [fNARS.narsese :as narsese]
            [fNARS.variable :as variable]
            [fNARS.memory :as memory]
            [fNARS.concept :as concept]
            [fNARS.table :as table]
            [fNARS.implication :as implication]))

(defn- better-decision?
  "Check if decision a is better than decision b."
  [a b]
  (> (:desire a 0.0) (:desire b 0.0)))

(defn- has-closer-precondition-link?
  "Check if a non-variable implication on the goal concept matches the same
   concrete concept term with higher confidence. Matches ONA Decision.c:449-473.
   Only checks real operations (op-id >= 1), not op-id 0.
   Returns true if a closer (more specific) precondition link exists."
  [goal-concept concept-term operations]
  (some
    (fn [[opk table]]
      ;; Only check real operations (ONA: for opk=1; opk<=OPERATIONS_MAX)
      (when (and (number? opk) (pos? opk))
        (some
          (fn [impk]
            (let [left-with-opk (term/extract-subterm (:term impk) 1)
                  left-sidek (narsese/get-precondition-without-op left-with-opk)]
              ;; A non-variable precondition that unifies with the concept is "closer"
              (and (not (variable/has-variable? left-sidek))
                   (:success (variable/unify left-sidek concept-term)))))
          (when table (:items table)))))
    (:precondition-beliefs goal-concept)))

(defn- consider-implication
  "Score a specific implication against a concept's belief for decision making.
   Returns a decision map or nil."
  [goal imp op-id op-entry precondition-truth current-time config]
  (let [decision-threshold (:decision-threshold config)
        subgoal (inference/goal-deduction goal imp current-time)
        desire-truth (truth/truth-goal-deduction (:truth subgoal) precondition-truth)
        desire (truth/truth-expectation desire-truth)]
    (when (> desire decision-threshold)
      {:desire desire
       :execute? true
       :operation-id op-id
       :operation op-entry
       :implication imp
       :arguments (term/extract-subterm (:term imp) 1)
       :precondition-truth precondition-truth})))

(defn- get-precondition-belief
  "Get the best available belief from a concept (spike or eternal)."
  [concept current-time config]
  (let [condition-threshold (:condition-threshold config)
        belief (or (when-not (event/event-deleted? (:belief-spike concept))
                     (:belief-spike concept))
                   (when-not (event/event-deleted? (:belief concept))
                     (:belief concept)))]
    (when belief
      (let [precondition-truth (:truth (inference/event-update belief current-time config))]
        (when (> (truth/truth-expectation precondition-truth) condition-threshold)
          precondition-truth)))))

(defn suggest-decision
  "Suggest the best operation for a given goal concept.
   Iterates through implication tables, finds best operation.
   For variable preconditions, scans all concepts to find matches
   (matching ONA's Decision_BestCandidate lines 441-537).
   Returns {:decision decision :state state}."
  [state goal-concept goal current-time]
  (let [config (:config state)
        best-decision (atom {:desire 0.0 :execute? false})
        operations (:operations state)]
    ;; Check each operation's implication table
    (doseq [[op-id op-entry] operations]
      (let [table (get-in goal-concept [:precondition-beliefs op-id])
            items (when table (:items table))]
        (doseq [imp items]
          ;; Unify postcondition with goal term
          (let [postcondition (term/extract-subterm (:term imp) 2)
                unification (variable/unify postcondition (:term goal))]
            (when (:success unification)
              ;; Apply postcondition substitution to implication
              (let [imp-term (if (seq (:substitution unification))
                               (variable/apply-substitute (:term imp) (:substitution unification))
                               (:term imp))
                    imp (assoc imp :term imp-term)
                    precondition-with-op (term/extract-subterm imp-term 1)
                    precondition-term (narsese/get-precondition-without-op precondition-with-op)
                    has-vars? (variable/has-variable? precondition-term)]
                (if has-vars?
                  ;; Variable precondition: scan all concepts (ONA lines 441-537)
                  (doseq [[concept-term concept] (:concepts state)]
                    (when (and (not (variable/has-variable? concept-term))
                               (not (event/event-deleted? (:belief-spike concept))))
                      (let [subs2 (variable/unify precondition-term concept-term)]
                        (when (:success subs2)
                          ;; hasCloserPreconditionLink check (ONA lines 449-473):
                          ;; Skip if a non-variable implication matches this concept
                          (when-not (has-closer-precondition-link?
                                      goal-concept concept-term operations)
                            (let [precondition-truth (get-precondition-belief concept current-time config)]
                              (when precondition-truth
                                (let [specific-imp (update imp :term variable/apply-substitute (:substitution subs2))
                                      decision (consider-implication goal specific-imp op-id op-entry
                                                 precondition-truth current-time config)]
                                  (when (and decision (better-decision? decision @best-decision))
                                    (reset! best-decision decision))))))))))
                  ;; Non-variable: use source-concept lookup (fast path)
                  (let [source-concept (memory/find-concept state (:source-concept-key imp))]
                    (when (and source-concept
                               (== (:id source-concept) (:source-concept-id imp)))
                      (let [precondition-truth (get-precondition-belief source-concept current-time config)]
                        (when precondition-truth
                          (let [decision (consider-implication goal imp op-id op-entry
                                           precondition-truth current-time config)]
                            (when (and decision (better-decision? decision @best-decision))
                              (reset! best-decision decision))))))))))))))
    {:decision @best-decision :state state}))

(defn- random-operation
  "Select a random operation (with argument variant) for motor babbling.
   For operations with :args, each arg is a separate variant."
  [state]
  (let [ops (:operations state)
        ;; Build all (op-id, op-entry, arg-or-nil) variants
        variants (vec (for [[op-id op-entry] ops
                            :when (some? (:action op-entry))
                            v (if-let [args (:args op-entry)]
                                (map (fn [arg] [op-id op-entry arg]) args)
                                [[op-id op-entry nil]])]
                        v))]
    (when (seq variants)
      (let [rng (:rng-state state 0)
            ;; Simple LCG PRNG
            new-rng (bit-and (+ (js/Math.imul rng 1103515245) 12345) 0x7FFFFFFF)
            idx (mod new-rng (count variants))
            [op-id op-entry arg] (nth variants idx)]
        {:operation-id op-id
         :operation op-entry
         :arg arg
         :rng-state new-rng}))))

(defn motor-babble
  "Select a random operation for exploration.
   Returns {:decision decision :state state}."
  [state]
  (let [result (random-operation state)]
    (if result
      {:decision {:desire 0.0
                  :execute? true
                  :operation-id (:operation-id result)
                  :operation (:operation result)
                  :babbling? true
                  :babbled-arg (:arg result)}
       :state (assoc state :rng-state (:rng-state result))}
      {:decision {:execute? false} :state state})))

(defn execute-decision
  "Execute a decision: call the operation's action function,
   inject the operation event into the system.
   Returns new state."
  [state decision current-time]
  (let [config (:config state)
        op (:operation decision)
        op-id (:operation-id decision)
        ;; Determine operation term:
        ;; 1. From implication: extract compound op from precondition sequence
        ;; 2. From babbling with args: build compound op
        ;; 3. Bare operator (fallback)
        op-term (cond
                  ;; From implication
                  (:implication decision)
                  (let [prec-with-op (term/extract-subterm (:term (:implication decision)) 1)]
                    (or (when (term/copula? (term/term-root prec-with-op) term/SEQUENCE)
                          (let [right (term/extract-subterm prec-with-op 2)]
                            (when (narsese/is-operation? right) right)))
                        (when (narsese/is-operation? prec-with-op) prec-with-op)
                        (term/atomic-term (:atom op))))
                  ;; From babbling with args
                  (:babbled-arg decision)
                  (narsese/make-compound-op-term (:atom op) (:babbled-arg decision))
                  ;; Bare operator
                  :else (term/atomic-term (:atom op)))
        ;; Extract selected argument for output
        selected-arg (narsese/extract-op-arg op-term)
        ;; Call the action
        action-fn (:action op)
        state (if action-fn
                (let [args (:arguments decision)]
                  (action-fn state args))
                state)
        ;; Create operation event
        [op-event stamp-counter] (event/make-input-event
                                   op-term
                                   event/event-type-belief
                                   truth/default-truth
                                   current-time
                                   (:stamp-counter state))
        state (assoc state :stamp-counter stamp-counter)
        ;; Add to output
        state (update state :output conj
                {:type :execution
                 :operation (:name op)
                 :term op-term
                 :arguments selected-arg
                 :time current-time})]
    ;; Add the operation event to cycling beliefs
    (assoc-in state [:pending-events] (conj (get state :pending-events []) op-event))))

(defn- add-negative-confirmation
  "Add negative anticipation evidence for a failed prediction.
   Matches ONA's Decision_AddNegativeConfirmation (Decision.c:34-48).
   Creates an implication with f=0.0, c=ANTICIPATION_CONFIDENCE,
   computes truth via Eternalize(Induction(TNew, TPast)),
   and revises it into the postcondition concept's table."
  [state imp op-id postcondition-term precondition-event current-time]
  (let [config (:config state)
        ;; TNew = {f=0.0, c=anticipation_confidence}
        t-new {:frequency 0.0 :confidence (:anticipation-confidence config)}
        ;; TPast = Truth_Projection(precondition.truth, 0, imp.occurrenceTimeOffset)
        t-past (truth/truth-projection (:truth precondition-event) 0
                                       (js/Math.round (:occurrence-time-offset imp))
                                       (:projection-decay config))
        ;; neg-truth = Truth_Eternalize(Truth_Induction(TNew, TPast))
        neg-truth (truth/truth-eternalize
                    (truth/truth-induction t-new t-past (:horizon config))
                    (:horizon config))
        ;; Create negative confirmation implication with empty stamp
        neg-imp (assoc imp :truth neg-truth
                           :stamp [0])
        ;; Revise into the postcondition concept's table
        post-concept (memory/find-concept state postcondition-term)]
    (if post-concept
      (let [existing-table (get-in post-concept [:precondition-beliefs op-id]
                                   (table/table-init))
            {:keys [table]} (table/table-add-and-revise
                              existing-table neg-imp
                              #(inference/implication-revision %1 %2 config))]
        (memory/update-concept state postcondition-term
          #(assoc-in % [:precondition-beliefs op-id] table)))
      state)))

(defn anticipate
  "After executing an operation, predict postconditions and add negative evidence.
   Matches ONA's Decision_Anticipate (Decision.c:556-812).
   For each concept with implications for this operation:
     Find the precondition concept, check if it has a live belief spike,
     derive predicted event, store as predicted_belief,
     and add negative confirmation for temporal implications.
   Returns new state."
  [state op-id current-time]
  (let [config (:config state)
        anticipation-threshold (:anticipation-threshold config)]
    (reduce
      (fn [state [postcondition-term postc]]
        (let [table (get-in postc [:precondition-beliefs op-id])]
          (if-not (and table (seq (:items table)))
            state
            (reduce
              (fn [state imp]
                (let [;; Extract precondition from implication
                      imp-precon-with-op (term/extract-subterm (:term imp) 1)
                      imp-precon (narsese/get-precondition-without-op imp-precon-with-op)
                      ;; Find precondition concept via source-concept (non-declarative path)
                      prec-concept (when (:source-concept-key imp)
                                     (memory/find-concept state (:source-concept-key imp)))
                      ;; Validate source concept ID
                      prec-concept (when (and prec-concept
                                              (== (:id prec-concept) (:source-concept-id imp)))
                                     prec-concept)]
                  (if-not prec-concept
                    state
                    (let [prec-event (:belief-spike prec-concept)
                          prec-eternal (:belief prec-concept)]
                      (if (and (event/event-deleted? prec-event)
                               (event/event-deleted? prec-eternal))
                        state
                        ;; Check if precondition unifies
                        (let [subs-event (variable/unify imp-precon (:term prec-event))
                              subs-eternal (variable/unify imp-precon (:term prec-eternal))]
                          (if-not (or (:success subs-event) (:success subs-eternal))
                            state
                            (let [;; Derive predicted event via belief deduction
                                  result-event (when (and (:success subs-event)
                                                          (not (event/event-deleted? prec-event)))
                                                 (inference/belief-deduction prec-event imp))
                                  ;; Store as predicted_belief on postcondition concept
                                  state (if (and result-event
                                                 (some? (term/term-root (:term result-event)))
                                                 (term/copula? (term/term-root (:term imp)) term/TEMPORAL-IMPLICATION))
                                          (let [result-term (:term result-event)
                                                ;; Apply substitutions
                                                subs-ev (variable/unify (:term prec-concept) (:term prec-event))
                                                result-term (if (:success subs-ev)
                                                              (variable/apply-substitute result-term (:substitution subs-ev))
                                                              result-term)
                                                result-term (if (:success subs-event)
                                                              (variable/apply-substitute result-term (:substitution subs-event))
                                                              result-term)
                                                result-event (assoc result-event :term result-term)
                                                [state c-event] (memory/conceptualize state result-term current-time)]
                                            (if c-event
                                              (memory/update-concept state result-term
                                                (fn [c]
                                                  (let [result (inference/revision-and-choice
                                                                 (:predicted-belief c) result-event current-time config)]
                                                    (assoc c :predicted-belief (:event result)))))
                                              state))
                                          state)
                                  ;; Add negative confirmation for temporal implications
                                  state (if (and (:success subs-event)
                                                 (not (event/event-deleted? prec-event))
                                                 (term/copula? (term/term-root (:term imp)) term/TEMPORAL-IMPLICATION)
                                                 (or (> (truth/truth-expectation
                                                          (:truth (or result-event {:truth {:frequency 0 :confidence 0}})))
                                                        anticipation-threshold)
                                                     (and (< (:confidence (:truth imp)) 0.01)
                                                          (== (:frequency (:truth imp)) 0.0))))
                                          (add-negative-confirmation state imp op-id postcondition-term
                                                                     prec-event current-time)
                                          state)]
                              state))))))))
              state
              (:items table)))))
      state
      (:concepts state))))
