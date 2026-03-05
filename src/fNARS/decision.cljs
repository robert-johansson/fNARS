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
  "Check if decision a is better than decision b.
   Ties broken by term complexity (simpler wins), matching ONA Decision.c:509,525."
  [a a-complexity b b-complexity]
  (let [da (:desire a 0.0) db (:desire b 0.0)]
    (or (> da db)
        (and (== da db) (< a-complexity b-complexity)))))

(defn myrand
  "64-bit POSIX LCG PRNG matching ONA on 64-bit platforms.
   State is [hi lo] representing a 64-bit unsigned value.
   Uses 16-bit schoolbook multiplication (all intermediates within float64 safe range).
   Returns [random-value updated-state], value in 0-32767."
  [state]
  (let [[hi lo] (:rng-state state [0 42])
        ;; Multiplier 1103515245 = 0x41C64E6D, split into 16-bit halves
        m0 20077   ;; 0x4E6D
        m1 16838   ;; 0x41C6
        ;; Split state into 16-bit parts
        s0 (bit-and lo 0xFFFF)
        s1 (bit-and (unsigned-bit-shift-right lo 16) 0xFFFF)
        s2 (bit-and hi 0xFFFF)
        s3 (bit-and (unsigned-bit-shift-right hi 16) 0xFFFF)
        ;; Multiply + add 12345, with carries through 16-bit digits (mod 2^64)
        t0 (+ (* s0 m0) 12345)
        r0 (bit-and t0 0xFFFF)
        t1 (+ (* s0 m1) (* s1 m0) (js/Math.trunc (/ t0 65536)))
        r1 (bit-and t1 0xFFFF)
        t2 (+ (* s1 m1) (* s2 m0) (js/Math.trunc (/ t1 65536)))
        r2 (bit-and t2 0xFFFF)
        t3 (+ (* s2 m1) (* s3 m0) (js/Math.trunc (/ t2 65536)))
        r3 (bit-and t3 0xFFFF)
        ;; Assemble 32-bit halves
        new-lo (+ r0 (* r1 65536))
        new-hi (+ r2 (* r3 65536))
        ;; Random value = bits 16-30 of low word = r1 & 0x7FFF
        val (bit-and r1 0x7FFF)]
    [val (assoc state :rng-state [new-hi new-lo])]))

(defn- has-closer-precondition-link?
  "Check if a non-variable implication on the goal concept matches the same
   concrete concept term with higher analogy confidence. Matches ONA Decision.c:449-473.
   Uses UnifyWithAnalogy and compares subs3.truth.confidence > subs2-confidence.
   Only checks real operations (op-id >= 1), not op-id 0."
  [goal-concept concept-term belief-spike-truth subs2-confidence atom-values config]
  (some
    (fn [[opk table]]
      (when (and (number? opk) (pos? opk))
        (some
          (fn [impk]
            (let [left-with-opk (term/extract-subterm (:term impk) 1)
                  left-sidek (narsese/get-precondition-without-op left-with-opk)]
              (when (not (variable/has-variable? left-sidek))
                (let [subs3 (variable/unify-with-analogy
                              belief-spike-truth left-sidek concept-term
                              atom-values config)]
                  (and (:success subs3)
                       (> (:confidence (:truth subs3)) subs2-confidence))))))
          (when table (:items table)))))
    (:precondition-beliefs goal-concept)))

(defn- consider-negative-outcomes
  "Scan all concepts for negative goal spikes that predict bad outcomes from
   the same operation. If accumulated negative expectation < 0.5, cancels decision.
   Matches ONA Decision.c:309-344 (Decision_ConsiderNegativeOutcomes)."
  [state decision current-time config]
  (let [neg-goal-age-max (:neg-goal-age-max config)
        event-belief-distance (:event-belief-distance config)
        op-id (:operation-id decision)
        op-term (:operation-term decision)]
    (if (nil? op-term)
      decision
      (let [accumulated
            (reduce
              (fn [acc [_concept-term c]]
                (let [goal-spike (:goal-spike c)]
                  (if (or (event/event-deleted? goal-spike)
                          (>= (- current-time (:occurrence-time goal-spike)) neg-goal-age-max))
                    acc
                    (let [table (get-in c [:precondition-beliefs op-id])
                          items (when table (:items table))]
                      (reduce
                        (fn [acc imp]
                          (let [prec (when (:source-concept-key imp)
                                       (memory/find-concept state (:source-concept-key imp)))]
                            (if (or (nil? prec)
                                    (event/event-deleted? (:belief-spike prec))
                                    (>= (- current-time (:occurrence-time (:belief-spike prec)))
                                        event-belief-distance))
                              acc
                              (let [imp-subject (term/extract-subterm (:term imp) 1)
                                    imp-op-term (narsese/get-operation-term-from-subject imp-subject)]
                                (if (and imp-op-term (term/term-equal op-term imp-op-term))
                                  (let [ctx-op (inference/goal-deduction goal-spike imp current-time)
                                        op-goal (inference/goal-sequence-deduction
                                                  ctx-op (:belief-spike prec) current-time config)]
                                    (:event (inference/revision-and-choice acc op-goal current-time config)))
                                  acc)))))
                        acc
                        items)))))
              event/deleted-event
              (:concepts state))]
        (if (< (truth/truth-expectation (:truth accumulated)) 0.5)
          nil
          decision)))))

(defn- consider-implication
  "Score a specific implication against a concept's belief for decision making.
   Returns a decision map or nil."
  [state goal imp op-id op-entry precondition-truth precondition-event current-time config]
  (let [decision-threshold (:decision-threshold config)
        subgoal (inference/goal-deduction goal imp current-time)
        desire-truth (truth/truth-goal-deduction (:truth subgoal) precondition-truth)
        desire (truth/truth-expectation desire-truth)
        imp-subject (term/extract-subterm (:term imp) 1)
        op-term (narsese/get-operation-term-from-subject imp-subject)]
    (when (> desire decision-threshold)
      (consider-negative-outcomes state
        {:desire desire
         :execute? true
         :operation-id op-id
         :operation op-entry
         :implication imp
         :arguments imp-subject
         :precondition-truth precondition-truth
         :operation-term op-term
         :reason precondition-event}
        current-time config))))

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

(defn- decision-best-candidate
  "Find the best operation from implication tables for a given goal concept.
   For variable preconditions, scans all concepts to find matches
   (matching ONA's Decision_BestCandidate lines 441-537).
   Pure: uses nested reduce instead of atom. Ties broken by complexity.
   Returns {:decision decision :state state}."
  [state goal-concept goal current-time]
  (let [config (:config state)
        operations (:operations state)
        atom-values (:atom-values state)
        init-best {:decision {:desire 0.0 :execute? false}
                   :complexity term/compound-term-size-max}]
    {:decision
     (:decision
       (reduce
         (fn [best [op-id op-entry]]
           (let [table (get-in goal-concept [:precondition-beliefs op-id])
                 items (when table (:items table))]
             (reduce
               (fn [best imp]
                 (let [postcondition (term/extract-subterm (:term imp) 2)
                       unification (variable/unify postcondition (:term goal))]
                   (if-not (:success unification)
                     best
                     (let [imp-term (if (seq (:substitution unification))
                                      (variable/apply-substitute (:term imp) (:substitution unification))
                                      (:term imp))
                           imp (assoc imp :term imp-term)
                           precondition-with-op (term/extract-subterm imp-term 1)
                           precondition-term (narsese/get-precondition-without-op precondition-with-op)
                           has-vars? (variable/has-variable? precondition-term)]
                       (if has-vars?
                         ;; Variable precondition: scan all concepts (ONA lines 441-537)
                         (reduce
                           (fn [best [concept-term concept]]
                             (if (or (variable/has-variable? concept-term)
                                     (event/event-deleted? (:belief-spike concept)))
                               best
                               (let [belief-spike-truth (:truth (:belief-spike concept))
                                     subs2 (variable/unify-with-analogy
                                             belief-spike-truth precondition-term concept-term
                                             atom-values config)]
                                 (if-not (:success subs2)
                                   best
                                   (let [perfect-match? (== (:confidence (:truth subs2))
                                                            (:confidence belief-spike-truth))]
                                     (if (and (not perfect-match?)
                                              (has-closer-precondition-link?
                                                goal-concept concept-term belief-spike-truth
                                                (:confidence (:truth subs2))
                                                atom-values config))
                                       best
                                       (let [precondition-truth (get-precondition-belief concept current-time config)]
                                         (if-not precondition-truth
                                           best
                                           (let [precondition-truth
                                                 (if perfect-match?
                                                   precondition-truth
                                                   (update precondition-truth :confidence *
                                                     (/ (:confidence (:truth subs2))
                                                        (max 1e-10 (:confidence belief-spike-truth)))))
                                                 specific-imp (-> imp
                                                                  (update :term variable/apply-substitute (:substitution subs2))
                                                                  (assoc :source-concept-key concept-term
                                                                         :source-concept-id (:id concept)))
                                                 ;; Gap 5: Subsumption inhibition (ONA Decision.c:485-503)
                                                 sub-postcondition (term/extract-subterm (:term specific-imp) 2)
                                                 postc (memory/find-concept state sub-postcondition)
                                                 postc-items (when postc
                                                               (when-let [t (get-in postc [:precondition-beliefs op-id])]
                                                                 (:items t)))
                                                 {:keys [existed? inhibited?]}
                                                 (reduce
                                                   (fn [acc existing-imp]
                                                     (if (term/term-equal (:term specific-imp) (:term existing-imp))
                                                       {:existed? true
                                                        :inhibited? (or (:inhibited? acc)
                                                                      (and (> (:confidence (:truth existing-imp))
                                                                              (:subsumption-confidence-threshold config))
                                                                           (< (:frequency (:truth existing-imp))
                                                                              (:subsumption-frequency-threshold config))))}
                                                       acc))
                                                   {:existed? false :inhibited? false}
                                                   postc-items)]
                                             (if inhibited?
                                               best
                                               (let [decision (consider-implication state goal specific-imp op-id op-entry
                                                                precondition-truth (:belief-spike concept)
                                                                current-time config)]
                                                 (if-not decision
                                                   best
                                                   (let [complexity (term/term-complexity (:term specific-imp))
                                                         decision (if-not existed?
                                                                    (assoc decision :missing-specific-implication specific-imp)
                                                                    decision)]
                                                     (if (better-decision? decision complexity
                                                                          (:decision best) (:complexity best))
                                                       {:decision decision :complexity complexity}
                                                       best))))))))))))))
                           best
                           (:concepts state))
                         ;; Non-variable: use source-concept lookup (fast path)
                         (let [source-concept (memory/find-concept state (:source-concept-key imp))]
                           (if-not (and source-concept
                                        (== (:id source-concept) (:source-concept-id imp)))
                             best
                             (let [precondition-truth (get-precondition-belief source-concept current-time config)]
                               (if-not precondition-truth
                                 best
                                 (let [decision (consider-implication state goal imp op-id op-entry
                                                  precondition-truth (:belief-spike source-concept)
                                                  current-time config)]
                                   (if-not decision
                                     best
                                     (let [complexity (term/term-complexity (:term imp))]
                                       (if (better-decision? decision complexity
                                                            (:decision best) (:complexity best))
                                         {:decision decision :complexity complexity}
                                         best)))))))))))))
               best
               items)))
         init-best
         operations))
     :state state}))

(defn- random-operation
  "Select a random operation for motor babbling.
   Matches ONA's Decision_MotorBabbling: picks op first (one PRNG call),
   then arg if needed (second PRNG call). Threads state for PRNG."
  [state]
  (let [ops (:operations state)
        config (:config state)
        n-ops (count ops)
        babbling-ops (:babbling-ops config)]
    (when (pos? n-ops)
      (let [[r1 state] (myrand state)
            op-id (inc (mod r1 (min babbling-ops n-ops)))
            op-entry (get ops op-id)]
        (when (and op-entry (some? (:action op-entry)))
          (if-let [args (:args op-entry)]
            (let [[r2 state] (myrand state)
                  arg (nth args (mod r2 (count args)))]
              {:operation-id op-id :operation op-entry :arg arg :state state})
            {:operation-id op-id :operation op-entry :arg nil :state state}))))))

(defn- motor-babble
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
       :state (:state result)}
      {:decision {:execute? false} :state state})))

(defn suggest-decision
  "Match ONA's Decision_Suggest: roll babble chance first (always consuming
   PRNG), then check implication tables. If table desire > suppression
   threshold, use table even over babble.
   Returns {:decision decision :state state}."
  [state goal-concept goal current-time]
  (let [config (:config state)
        ;; 1. Roll babble chance (always consumes PRNG)
        [rand-val state] (myrand state)
        should-babble? (< rand-val (int (* (:motor-babbling-chance config) 32767)))
        ;; 2. If should babble, prepare babble decision (may consume more PRNG)
        {babble-decision :decision state :state}
        (if should-babble?
          (motor-babble state)
          {:decision {:execute? false} :state state})
        ;; 3. Always check implication tables
        {table-decision :decision state :state}
        (decision-best-candidate state goal-concept goal current-time)
        ;; 4. If babble didn't fire OR table beats suppression, use table
        suppression (:motor-babbling-suppression config)]
    (if (or (not (:execute? babble-decision))
            (> (:desire table-decision) suppression))
      {:decision table-decision :state state}
      {:decision babble-decision :state state})))

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
        ;; ONA uses (Stamp){0} which is all STAMP_FREE — effectively empty
        neg-imp (assoc imp :truth neg-truth
                           :stamp [])
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
    (let [state (assoc-in state [:pending-events] (conj (get state :pending-events []) op-event))]
      ;; Gap 6: Anticipation for novel specific cases (ONA Decision.c:254-262)
      (if-let [missing (:missing-specific-implication decision)]
        (let [postcondition (term/extract-subterm (:term missing) 2)
              [state postc] (memory/conceptualize state postcondition current-time)]
          (if postc
            (add-negative-confirmation state missing (:operation-id decision)
                                       postcondition (:reason decision) current-time)
            state))
        state))))

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
