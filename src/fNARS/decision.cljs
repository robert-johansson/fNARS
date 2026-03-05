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
            [fNARS.implication :as implication]))

(defn- better-decision?
  "Check if decision a is better than decision b."
  [a b]
  (> (:desire a 0.0) (:desire b 0.0)))

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
                          (let [precondition-truth (get-precondition-belief concept current-time config)]
                            (when precondition-truth
                              (let [specific-imp (update imp :term variable/apply-substitute (:substitution subs2))
                                    decision (consider-implication goal specific-imp op-id op-entry
                                               precondition-truth current-time config)]
                                (when (and decision (better-decision? decision @best-decision))
                                  (reset! best-decision decision)))))))))
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
  "Select a random operation for motor babbling.
   Uses the state's RNG."
  [state]
  (let [ops (:operations state)
        op-entries (vec (filter (fn [[k v]] (some? (:action v))) ops))]
    (when (seq op-entries)
      (let [rng (:rng-state state 0)
            ;; Simple LCG PRNG
            new-rng (mod (+ (* rng 1103515245) 12345) 2147483648)
            idx (mod new-rng (count op-entries))
            [op-id op-entry] (nth op-entries idx)]
        {:operation-id op-id
         :operation op-entry
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
                  :babbling? true}
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
        ;; Build operation term: ^op or <(*,{SELF},args) --> ^op>
        op-term (term/atomic-term (:atom op))
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
                 :time current-time})]
    ;; Add the operation event to cycling beliefs
    (assoc-in state [:pending-events] (conj (get state :pending-events []) op-event))))

(defn- add-negative-confirmation
  "Add negative anticipation evidence for a failed prediction."
  [state imp precondition-belief postcondition-concept current-time]
  (let [config (:config state)
        neg-truth {:frequency 0.0
                   :confidence (:anticipation-confidence config)}
        neg-imp (assoc imp :truth neg-truth
                           :stamp (stamp/make-input-stamp (:stamp-counter state)))
        state (update state :stamp-counter inc)]
    ;; Revise the implication with negative evidence
    (if postcondition-concept
      (let [table-key (:operation-id imp 0)
            table (get-in postcondition-concept [:precondition-beliefs table-key])
            revised-imp (inference/implication-revision imp neg-imp config)]
        ;; Update the concept's table
        (memory/update-concept state (:term postcondition-concept)
          (fn [c]
            (assoc-in c [:precondition-beliefs table-key]
              (if table
                (assoc table :items
                  (mapv (fn [existing]
                          (if (term/term-equal (:term existing) (:term imp))
                            revised-imp
                            existing))
                        (:items table)))
                table)))))
      state)))

(defn anticipate
  "After executing an operation, check predictions and process anticipation.
   For each relevant implication, check if the predicted postcondition arrived.
   If not, add negative confirmation.
   Returns new state."
  [state op-id current-time]
  (let [config (:config state)
        anticipation-threshold (:anticipation-threshold config)]
    ;; Find concepts with implications involving this operation
    (reduce
      (fn [state [term-key concept]]
        (let [table (get-in concept [:precondition-beliefs op-id])]
          (if table
            (reduce
              (fn [state imp]
                (when (> (truth/truth-expectation (:truth imp)) anticipation-threshold)
                  ;; Check if prediction matches any observed event
                  (let [postcondition (term/extract-subterm (:term imp) 2)
                        post-concept (memory/find-concept state postcondition)]
                    (if (and post-concept
                             (not (event/event-deleted? (:predicted-belief post-concept))))
                      ;; Prediction exists, will be confirmed or denied by observation
                      state
                      state)))
                (or state state))
              state
              (when table (:items table)))
            state)))
      state
      (:concepts state))))
