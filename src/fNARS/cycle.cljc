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
            [fNARS.rule-table :as rule-table]
            [fNARS.platform :as p]
            [fNARS.atom-registry :as ar]))

;; -- Declarative Inference (NAL 1-5) --
;; Matches ONA's Cycle_Inference (Cycle.c lines 971-1069).

(defn- now-ns []
  #?(:clj (System/nanoTime)
     :cljs (* 1000000.0 (.now js/performance))))

(defn- perf-enabled?
  [state]
  (true? (get-in state [:config :perf-instrumentation])))

(defn- perf-inc
  [state counter-key]
  (if (perf-enabled? state)
    (update-in state [:perf :counters counter-key] (fnil inc 0))
    state))

(defn- perf-add
  [state counter-key n]
  (if (and (perf-enabled? state) (pos? n))
    (update-in state [:perf :counters counter-key] (fnil + 0) n)
    state))

(defn- timed-phase
  [state phase-key f]
  (if-not (perf-enabled? state)
    (f state)
    (let [t0 (now-ns)
          out (f state)
          dt (- (now-ns) t0)]
      (-> out
          (update-in [:perf :phase-times-ns phase-key] (fnil + 0) dt)
          (update-in [:perf :phase-counts phase-key] (fnil inc 0))))))

(defn- has-concrete-atom?
  "True when term contains at least one non-variable, non-copula atom."
  [t]
  (loop [i 0]
    (if (>= i term/compound-term-size-max)
      false
      (let [id (get t i)]
        (if (and (pos? id)
                 (not (ar/copula-id? id))
                 (not (variable/variable? id)))
          true
          (recur (inc i)))))))

(defn- store-derived-implication
  "Store a derived implication in the appropriate table.
   ==> implications go to implication-links on postcondition concept.
   =/> implications go to precondition-beliefs[op-id] on postcondition concept.
   Matches ONA Memory_ProcessNewBeliefEvent (Memory.c:284-337)."
  [state ev eternalize?]
  (let [config (:config state)
        current-time (:current-time state)
        root (term/term-root (:term ev))
        is-declarative? (== root term/implication)
        is-temporal? (== root term/temporal-implication)]
    (when (or is-declarative? is-temporal?)
      (let [eternal-ev (event/event-eternalized ev (:horizon config))
            subject (term/extract-subterm (:term ev) 1)
            predicate (term/extract-subterm (:term ev) 2)
            [state post-concept] (memory/conceptualize state predicate current-time)]
        (when post-concept
          (let [;; Determine op-id for temporal implications
                op-id (if (and is-temporal? (== (term/term-root subject) term/sequence*))
                        (let [potential-op (term/extract-subterm subject 2)]
                          (if (narsese/is-operation? potential-op)
                            (memory/get-operation-id state potential-op)
                            0))
                        0)
                source-term (if (and is-temporal? (pos? op-id))
                              (narsese/get-precondition-without-op subject)
                              subject)
                [state source-concept] (memory/conceptualize state source-term current-time)
                imp (implication/make-implication
                      {:term (:term ev)
                       :truth (:truth eternal-ev)
                       :stamp (:stamp eternal-ev)
                       :occurrence-time-offset (:occurrence-time-offset ev 0.0)
                       :source-concept-key (when source-concept (:term source-concept))
                       :source-concept-id (when source-concept (:id source-concept))
                       :creation-time current-time})]
            (if is-declarative?
              ;; ==> goes to implication-links
              (let [existing-table (:implication-links post-concept (table/table-init))
                    {:keys [table]} (table/table-add-and-revise
                                      existing-table imp
                                      #(inference/implication-revision %1 %2 config))]
                (memory/update-concept state predicate
                  #(assoc % :implication-links table)))
              ;; =/> goes to precondition-beliefs[op-id]
              (let [existing-table (get-in post-concept [:precondition-beliefs op-id]
                                           (table/table-init))
                    {:keys [table]} (table/table-add-and-revise
                                      existing-table imp
                                      #(inference/implication-revision %1 %2 config))]
                (memory/update-concept state predicate
                  #(assoc-in % [:precondition-beliefs op-id] table))))))))))

(defn- add-derived-belief
  "Add a derived belief event to cycling PQ and conceptualize.
   Matches Memory_AddEvent for derived beliefs.
   For implications, also stores in the appropriate table (implication-links or
   precondition-beliefs) matching ONA Memory_ProcessNewBeliefEvent."
  [state ev priority eternalize?]
  (let [config (:config state)
        current-time (:current-time state)]
    (if (< (:confidence (:truth ev)) (:min-confidence config))
      state
      (let [state (perf-inc state :derived-beliefs-produced)
            root (term/term-root (:term ev))
            is-implication? (or (== root term/implication) (== root term/temporal-implication))
            ;; Store implications in tables
            state (if is-implication?
                    (or (store-derived-implication state ev eternalize?) state)
                    state)
            ;; Derived temporal implications don't go to cycling PQ (ONA Memory.c:341)
            ;; Derived ==> only go to PQ if not excluded (ALLOW_IMPLICATION_EVENTS=1 excludes derived)
            skip-pq? (or (== root term/temporal-implication)
                         (== root term/implication))
            state (if skip-pq?
                    state
                    (let [{:keys [pq]} (pq/pq-push (:cycling-belief-events state) priority ev)]
                      (assoc state :cycling-belief-events pq)))
            ;; Conceptualize and update beliefs (skip for temporal implications)
            state (if (== root term/temporal-implication)
                    state
                    (let [[state concept] (memory/conceptualize state (:term ev) current-time)]
                      (if-not concept
                        state
                        (let [temporal? (not= (:occurrence-time ev) truth/occurrence-eternal)
                              decay-epoch (:decay-epoch state)
                              concept-dur (:concept-durability config)
                              ;; Update belief spike for temporal events
                              state (if temporal?
                                      (memory/update-concept state (:term ev)
                                        (fn [c]
                                          (let [result (inference/revision-and-choice
                                                         (:belief-spike c) ev current-time config)]
                                            (-> c
                                                (assoc :belief-spike (:event result))
                                                (update :usage concept/usage-use current-time false)
                                                (concept/priority-set-max priority decay-epoch concept-dur)))))
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
                            state)))))]
        state))))

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
                            (< (p/abs (- event-occurrence (:occurrence-time spike)))
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
        is-impl? (== root2 term/implication)
        is-equiv? (== root2 term/equivalence)]
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
              state (if (and is-impl? (== (term/term-root impl-subject) term/conjunction))
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
      (== root2 term/conjunction)
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
      (let [rule-index (:nal-rule-index state)
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
                    (rule-table/indexed-apply-rules rule-index term1 term/empty-term
                      truth1 truth/default-truth config false))
            ;; Double-premise inference (R2 rules + special inferences)
            ;; Adaptive concept priority threshold (ONA Cycle.c:982-989)
            threshold (:concept-priority-threshold state 0.0)
            matched-avg (if (pos? current-time)
                          (/ (:concepts-matched-total state 0) current-time)
                          0)
            target (:belief-concept-match-target config)
            increment (* (- matched-avg target) (:concept-threshold-adaptation config))
            threshold (min 1.0 (max 0.0 (+ threshold increment)))
            state (assoc state :concept-priority-threshold threshold)
            related (memory/related-concepts state term1 (:unification-depth config))
            decay-epoch (:decay-epoch state)
            concept-dur (:concept-durability config)
            ;; Process related concepts with priority filtering and cap
            state (loop [remaining (seq related)
                         state state
                         matched 0]
                    (if (or (nil? remaining) (>= matched target))
                      (update state :concepts-matched-total + matched)
                      (let [related-concept (first remaining)
                            eff-priority (concept/effective-priority
                                           related-concept decay-epoch concept-dur)]
                        (if (< eff-priority threshold)
                          (recur (next remaining) state matched)
                          (let [state
                                (if-let [{:keys [belief eternalize?]}
                                         (select-belief-for-concept related-concept occ1 config)]
                                  (let [term2 (:term belief)
                                        truth2 (:truth belief)
                                        conclusion-stamp (stamp/stamp-make (:stamp ev) (:stamp belief))
                                        ;; Only eternalize when selected event is also eternal
                                        eternalize? (and eternalize?
                                                         (= occ1 truth/occurrence-eternal))
                                        conclusion-occ (if eternalize? truth/occurrence-eternal occ1)]
                                    (if (stamp/stamp-overlap? (:stamp ev) (:stamp belief))
                                      state
                                      (let [;; Apply rule table
                                            state (reduce
                                                    (fn [state derivation]
                                                      (let [raw-term (rule-table/reduce-conclusion (:term derivation))
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
                                                                der-priority (* priority eff-priority
                                                                               (truth/truth-expectation conclusion-truth))]
                                                            (add-derived-belief state derived-ev der-priority eternalize?)))))
                                                    state
                                                    (rule-table/indexed-apply-rules rule-index term1 term2
                                                      truth1 truth2 config true))
                                            ;; Special inferences (NAL 6+): both term orderings
                                            state (if (>= nal-level 6)
                                                    (-> state
                                                        (special-inferences term1 term2 truth1 truth2
                                                          conclusion-stamp conclusion-occ priority
                                                          eff-priority eternalize?)
                                                        (special-inferences term2 term1 truth2 truth1
                                                          conclusion-stamp conclusion-occ priority
                                                          eff-priority eternalize?))
                                                    state)]
                                        state)))
                                  state)]
                            (recur (next remaining) state (inc matched)))))))]
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
            state (if (and (== (term/term-root term) term/sequence*)
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
                        (concept/priority-set-max (:priority ev 0.5)
                          (:decay-epoch state) (:concept-durability config)))]
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
            op-id (if (and (== (term/term-root precondition-term) term/sequence*))
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
            (== prec-root term/implication)
            (== prec-root term/equivalence)
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
                  (== prec-root term/equivalence)
                  (== prec-root term/implication)
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
    (when (== (term/term-root goal-term) term/sequence*)
      ;; Extract components right-to-left from left-nested sequence
      ;; (&/ (&/ A B) C) -> [C, B, A] (index 0=rightmost, i=leftmost)
      (let [components
            (loop [cur goal-term, comps []]
              (if (== (term/term-root cur) term/sequence*)
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
        ;; Lazy concept priority decay: increment epoch instead of touching all concepts
        (update :decay-epoch inc))))

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
                                      (concept/priority-set-max 1.0
                                        (:decay-epoch state) (:concept-durability config)))))
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

;; -- Declarative Anticipation --
;; Matches ONA's Decision_Anticipate(0, not_used, true, currentTime) called from Cycle.c:1110-1114.
;; For each concept with implication-links (==> implications), find matching precondition
;; concepts, derive conclusions via belief-deduction, and update postcondition beliefs.

(def ^:const top-k-declarative-implications 20)
(def ^:const belief-last-used-tolerance 5)

(defn- apply-both-substitutions
  "Apply two substitutions to a term, returning [term success?]."
  [result-term subs1 subs2]
  (let [t1 (if (:success subs1)
             (variable/apply-substitute result-term (:substitution subs1))
             nil)
        t2 (if (and t1 (:success subs2))
             (variable/apply-substitute t1 (:substitution subs2))
             nil)]
    (if (and t1 t2)
      [t2 true]
      (cond
        (and t1 (:success subs1)) [t1 true]
        (and (:success subs2))
        (let [t (variable/apply-substitute result-term (:substitution subs2))]
          [t true])
        :else [result-term false]))))

(defn- update-eternal-from-declarative
  "Update postcondition concept's eternal belief from a declarative derivation.
   Matches ONA Decision.c:718-734 (ETERNAL BELIEF UPDATE, no stamp check)."
  [state result-eternal sub-prec sub-additional current-time config]
  (let [[result-term success?]
        (if (or (:success sub-prec) (:success sub-additional))
          (let [t1 (if (:success sub-prec)
                     (variable/apply-substitute (:term result-eternal) (:substitution sub-prec))
                     (:term result-eternal))
                t2 (if (:success sub-additional)
                     (variable/apply-substitute t1 (:substitution sub-additional))
                     t1)]
            [t2 (and (or (:success sub-prec) true)
                     (or (:success sub-additional) true))])
          [(:term result-eternal) false])]
    (if-not (and success? (not (zero? (term/term-root result-term))))
      state
      (let [result-eternal (assoc result-eternal :term result-term)
            ;; Handle negation: if result is (-- X), unwrap and negate truth
            root (term/term-root result-term)
            [result-eternal negated?]
            (if (== root term/negation)
              (let [inner (term/extract-subterm result-term 1)]
                [(assoc result-eternal
                   :term inner
                   :truth (truth/truth-negation (:truth result-eternal))) true])
              [result-eternal false])
            result-term (:term result-eternal)
            [state c] (memory/conceptualize state result-term current-time)]
        (if-not c
          state
          (let [existing (:belief c)]
            (if (stamp/stamp-equal (:stamp existing) (:stamp result-eternal))
              state
              (let [updated (inference/revision-and-choice existing result-eternal current-time config)]
                (if (not (truth/truth-equal (:truth existing) (:truth (:event updated))))
                  (let [state (memory/update-concept state result-term
                                #(assoc % :belief (:event updated)))]
                    ;; Output the derived belief
                    (-> state
                        (perf-inc :derived-beliefs-produced)
                        (update :output conj
                          {:type :derived
                           :term result-term
                           :truth (:truth (:event updated))
                           :event-type event/event-type-belief
                           :occurrence-time truth/occurrence-eternal})))
                  state)))))))))

(defn- update-spike-from-declarative
  "Update postcondition concept's belief spike from a declarative ==> derivation.
   Matches ONA Decision.c:752-774 (BELIEF EVENTS UPDATE for ==> implications)."
  [state result-event sub-prec sub-additional current-time config]
  (let [[result-term success?]
        (if (or (:success sub-prec) (:success sub-additional))
          (let [t1 (if (:success sub-prec)
                     (variable/apply-substitute (:term result-event) (:substitution sub-prec))
                     (:term result-event))
                t2 (if (:success sub-additional)
                     (variable/apply-substitute t1 (:substitution sub-additional))
                     t1)]
            [t2 true])
          [(:term result-event) false])]
    (if-not (and success? (not (zero? (term/term-root result-term))))
      state
      (let [result-event (assoc result-event :term result-term)
            ;; Handle negation
            root (term/term-root result-term)
            [result-event _negated?]
            (if (== root term/negation)
              (let [inner (term/extract-subterm result-term 1)]
                [(assoc result-event
                   :term inner
                   :truth (truth/truth-negation (:truth result-event))) true])
              [result-event false])
            result-term (:term result-event)
            [state c] (memory/conceptualize state result-term current-time)]
        (if-not c
          state
          (let [spike (:belief-spike c)]
            (if (or (event/event-deleted? spike)
                    (>= (:occurrence-time result-event) (:occurrence-time spike)))
              (let [updated (inference/revision-and-choice spike result-event current-time config)]
                (if (or (event/event-deleted? spike)
                        (not (truth/truth-equal (:truth spike) (:truth (:event updated)))))
                  (let [state (memory/update-concept state result-term
                                #(assoc % :belief-spike (:event updated)))
                        ;; Also eternalize into eternal belief
                        eternal-ev (event/event-eternalized (:event updated) (:horizon config))
                        state (memory/update-concept state result-term
                                (fn [c]
                                  (let [r (inference/revision-and-choice
                                            (:belief c) eternal-ev current-time config)]
                                    (assoc c :belief (:event r)
                                             ;; Update creation-time for metrics
                                             ))))]
                    (-> state
                        (perf-inc :derived-beliefs-produced)
                        (update :output conj
                          {:type :derived
                           :term result-term
                           :truth (:truth (:event updated))
                           :event-type event/event-type-belief
                           :occurrence-time (:occurrence-time (:event updated))})))
                  state))
              state)))))))

(defn- process-declarative-implication
  "Process one declarative ==> implication: scan all concepts for matching
   preconditions, derive conclusions, update beliefs.
   Matches ONA Decision.c:609-809 inner loop for declarative implications."
  [state imp current-time config]
  (let [imp-precon (term/extract-subterm (:term imp) 1)
        related (memory/related-concepts state imp-precon (:unification-depth config))
        candidates (if (and (has-concrete-atom? imp-precon)
                            (seq related))
                     related
                     (vals (:concepts state)))]
    (reduce
      (fn [state concept]
        (let [state (perf-inc state :decl-precondition-concepts-scanned)]
        ;; Skip concepts with variables (ONA Decision.c:612-615)
          (if (variable/has-variable? (:term concept))
            state
            (let [prec-eternal (:belief concept)
                  prec-event (:belief-spike concept)
                  ;; BELIEF_LAST_USED_TOLERANCE check (ONA Decision.c:620)
                  skip? (and (< (:creation-time prec-eternal 0) (- current-time belief-last-used-tolerance))
                             (< (:creation-time prec-event 0) (- current-time belief-last-used-tolerance)))]
              (if skip?
                state
                (let [attempts (+ (if (event/event-deleted? prec-eternal) 0 1)
                                  (if (event/event-deleted? prec-event) 0 1))
                      state (perf-add state :decl-unification-attempts attempts)
                      subs-eternal (when-not (event/event-deleted? prec-eternal)
                                     (variable/unify imp-precon (:term prec-eternal)))
                      subs-event (when-not (event/event-deleted? prec-event)
                                   (variable/unify imp-precon (:term prec-event)))
                      successes (+ (if (:success subs-eternal) 1 0)
                                   (if (:success subs-event) 1 0))
                      state (perf-add state :decl-unification-successes successes)]
                  (if-not (or (:success subs-eternal) (:success subs-event))
                    state
                    (let [;; Derive conclusions
                          result-eternal (when (and (:success subs-eternal)
                                                     (not (event/event-deleted? prec-eternal)))
                                           (inference/belief-deduction prec-eternal imp))
                          result-event (when (and (:success subs-event)
                                                   (not (event/event-deleted? prec-event)))
                                         (inference/belief-deduction prec-event imp))
                          ;; Compute substitutions from concept term
                          sub-prec-eternal (when (:success subs-eternal)
                                             (variable/unify (:term concept) (:term prec-eternal)))
                          sub-prec-event (when (:success subs-event)
                                           (variable/unify (:term concept) (:term prec-event)))
                          ;; Update eternal belief
                          state (if (and result-eternal
                                         (not (zero? (term/term-root (:term result-eternal)))))
                                  (update-eternal-from-declarative
                                    state result-eternal sub-prec-eternal subs-eternal
                                    current-time config)
                                  state)
                          ;; Update belief spike for ==> implications
                          state (if (and result-event
                                         (not (zero? (term/term-root (:term result-event)))))
                                  (update-spike-from-declarative
                                    state result-event sub-prec-event subs-event
                                    current-time config)
                                  state)]
                      state))))))))
      state
      candidates)))

(defn- declarative-anticipate
  "Process declarative implications each cycle.
   Matches ONA Cycle.c:1110-1114 calling Decision_Anticipate(0, _, true, currentTime).
   Iterates all concepts with implication-links, processing up to
   TOP_K_DECLARATIVE_IMPLICATIONS ==> implications per concept."
  [state]
  (let [config (:config state)]
    (if-not (:declarative-implications-cycle-process config true)
      state
      (let [current-time (:current-time state)]
        (reduce
          (fn [state [_postc-term postc]]
            (let [imp-links (:implication-links postc)
                  items (when imp-links (:items imp-links))]
              (if (or (nil? items) (empty? items))
                state
                ;; Process implications, limiting declarative ones to top-k
                (loop [remaining (seq items)
                       state state
                       decl-count 0]
                  (if-not remaining
                    state
                    (let [imp (first remaining)
                          is-decl? (== (term/term-root (:term imp)) term/implication)
                          state (-> state
                                    (perf-inc :decl-implications-scanned)
                                    (cond-> is-decl? (perf-inc :decl-implications-declarative)))]
                      (if (and is-decl? (>= decl-count top-k-declarative-implications))
                        (recur (next remaining)
                               (perf-inc state :decl-implications-skipped-topk)
                               decl-count)
                        (recur (next remaining)
                               (-> state
                                   (perf-inc :decl-implications-processed)
                                   (process-declarative-implication imp current-time config))
                               (if is-decl? (inc decl-count) decl-count)))))))))
          state
          (:concepts state))))))

;; -- Main Cycle --

(defn perf-summary
  "Return a lightweight summary of cycle instrumentation data."
  [state]
  (let [phase-ns (get-in state [:perf :phase-times-ns] {})
        phase-seconds (into {}
                        (map (fn [[k v]] [k (/ (double v) 1000000000.0)]))
                        phase-ns)]
    {:enabled? (perf-enabled? state)
     :phase-seconds phase-seconds
     :phase-counts (get-in state [:perf :phase-counts] {})
     :counters (get-in state [:perf :counters] {})}))

(defn cycle-perform
  "Perform one inference cycle. Pure function: state -> state.
   Time increments AFTER processing, matching ONA's flow:
   event at currentTime -> Cycle_Perform(currentTime) -> currentTime++"
  [state]
  (let [state (timed-phase state :process-pending-events process-pending-events)
        state (timed-phase state :select-belief-events select-belief-events)
        state (timed-phase state :process-belief-events process-belief-events)
        state (timed-phase state :declarative-anticipate declarative-anticipate)
        state (timed-phase state :process-goal-events process-goal-events)
        state (timed-phase state :apply-forgetting apply-forgetting)]
    (-> state
        (dissoc :selected-beliefs)
        (update :current-time inc))))
