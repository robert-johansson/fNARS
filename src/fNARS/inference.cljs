(ns fNARS.inference
  "Temporal and procedural inference rules matching ONA's Inference.c.
   All pure functions: take events/implications, return new events/implications."
  (:require [fNARS.truth :as truth]
            [fNARS.term :as term]
            [fNARS.stamp :as stamp]
            [fNARS.event :as event]
            [fNARS.implication :as implication]
            [fNARS.narsese :as narsese]))

(defn- derivation-stamp-and-time
  "Compute conclusion stamp, creation time, conclusion time, and projected truths.
   Matches C's DERIVATION_STAMP_AND_TIME macro."
  [a b config]
  (let [conclusion-stamp (stamp/stamp-make (:stamp a) (:stamp b))
        creation-time (max (:creation-time a) (:creation-time b))
        conclusion-time (:occurrence-time b)
        truth-a (truth/truth-projection (:truth a) (:occurrence-time a) conclusion-time
                                        (:projection-decay config))
        truth-b (:truth b)]
    {:stamp conclusion-stamp
     :creation-time creation-time
     :conclusion-time conclusion-time
     :truth-a truth-a
     :truth-b truth-b}))

(defn- derivation-stamp
  "Compute conclusion stamp and creation time only.
   Matches C's DERIVATION_STAMP macro."
  [a b]
  {:stamp (stamp/stamp-make (:stamp a) (:stamp b))
   :creation-time (max (:creation-time a) (:creation-time b))})

;; {Event a., Event b.} |- Event (&/,a,b). Truth_Intersection
(defn belief-intersection
  "Create a temporal sequence from two belief events.
   b must occur after or at the same time as a."
  [a b config]
  (let [{:keys [stamp creation-time conclusion-time truth-a truth-b]}
        (derivation-stamp-and-time a b config)
        {:keys [term success?]} (narsese/make-sequence (:term a) (:term b))]
    (when success?
      (event/make-event
        {:term term
         :type event/event-type-belief
         :truth (truth/truth-intersection truth-a truth-b)
         :stamp stamp
         :occurrence-time conclusion-time
         :creation-time creation-time}))))

;; {Event a., Event b., after(b,a)} |- Implication <a =/> b>. Truth_Eternalize(Truth_Induction)
(defn belief-induction
  "Create a temporal implication from two belief events.
   b must occur after or at the same time as a."
  [a b config]
  (let [{:keys [stamp creation-time conclusion-time truth-a truth-b]}
        (derivation-stamp-and-time a b config)
        imp-term (narsese/make-implication-term (:term a) (:term b))]
    (implication/make-implication
      {:term imp-term
       :truth (truth/truth-eternalize
                (truth/truth-induction truth-b truth-a (:horizon config))
                (:horizon config))
       :stamp stamp
       :occurrence-time-offset (- (:occurrence-time b) (:occurrence-time a))
       :creation-time creation-time})))

;; {Event a., Event a.} |- Event a. (revision)
(defn event-revision
  "Revise two events with the same term."
  [a b config]
  (let [{:keys [stamp creation-time conclusion-time truth-a truth-b]}
        (derivation-stamp-and-time a b config)]
    (event/make-event
      {:term (:term a)
       :type (:type a)
       :truth (truth/truth-revision truth-a truth-b config)
       :stamp stamp
       :occurrence-time conclusion-time
       :creation-time creation-time})))

;; {Implication <a =/> b>., <a =/> b>.} |- Implication <a =/> b>. (revision)
(defn implication-revision
  "Revise two implications. Uses choice if stamps overlap."
  [a b config]
  (let [{:keys [stamp creation-time]} (derivation-stamp a b)
        overlap? (stamp/stamp-overlap? (:stamp a) (:stamp b))
        ;; For temporal implications, always do full revision
        ;; For non-temporal, check overlap
        is-non-temporal? (term/copula? (term/term-root (:term a)) term/IMPLICATION)
        [t s] (if overlap?
                ;; Choice: keep higher confidence
                (if (> (:confidence (:truth a)) (:confidence (:truth b)))
                  [(:truth a) (:stamp a)]
                  [(:truth b) (:stamp b)])
                ;; Revision
                [(truth/truth-revision (:truth a) (:truth b) config)
                 stamp])
        ;; For non-temporal implications, use choice/revision based on overlap
        ;; For temporal, always revise
        [final-truth final-stamp]
        (if is-non-temporal?
          [t s]
          [(truth/truth-revision (:truth a) (:truth b) config) stamp])
        ;; Weighted average of occurrence time offsets
        w1 (truth/c2w (:confidence (:truth a)) (:horizon config))
        w2 (truth/c2w (:confidence (:truth b)) (:horizon config))
        offset-avg (/ (+ (* (:occurrence-time-offset a) w1)
                         (* (:occurrence-time-offset b) w2))
                      (+ w1 w2))]
    (implication/make-implication
      {:term (:term a)
       :truth final-truth
       :stamp final-stamp
       :occurrence-time-offset offset-avg
       :source-concept-key (:source-concept-key a)
       :source-concept-id (:source-concept-id a)
       :creation-time creation-time})))

;; {Event b!, Implication <a =/> b>.} |- Event a! Truth_GoalDeduction
(defn goal-deduction
  "Deduce a subgoal from a goal and an implication.
   Given goal b! and <a =/> b>, derive a!."
  [goal imp current-time]
  (let [{:keys [stamp creation-time]} (derivation-stamp goal imp)
        precondition (term/extract-subterm (:term imp) 1)
        precondition-without-op (narsese/get-precondition-without-op precondition)]
    (event/make-event
      {:term precondition-without-op
       :type event/event-type-goal
       :truth (truth/truth-goal-deduction (:truth goal) (:truth imp))
       :stamp stamp
       :occurrence-time current-time
       :creation-time creation-time})))

;; {Event a.} |- Event a. (updated to target time)
(defn event-update
  "Project an event's truth to a target time."
  [ev target-time config]
  (-> ev
      (assoc :truth (truth/truth-projection (:truth ev) (:occurrence-time ev) target-time
                                            (:projection-decay config)))
      (assoc :occurrence-time target-time)))

;; {Event (&/,a,b)!, Event a.} |- Event b! Truth_GoalDeduction
(defn goal-sequence-deduction
  "Decompose a sequence goal given a satisfied component.
   Given (a &/ b)! and a., derive b!."
  [compound component current-time config]
  (let [{:keys [stamp creation-time]} (derivation-stamp component compound)
        compound-updated (event-update compound current-time config)
        component-updated (event-update component current-time config)]
    (event/make-event
      {:term (:term compound)
       :type event/event-type-goal
       :truth (truth/truth-goal-deduction (:truth compound-updated) (:truth component-updated))
       :stamp stamp
       :occurrence-time current-time
       :creation-time creation-time})))

;; {Event a!, Event a!} |- Event a! (revision or choice)
(defn revision-and-choice
  "Revise or choose between existing and incoming events.
   Uses revision if no stamp overlap and same term/time.
   Uses choice (keep higher confidence) otherwise."
  [existing incoming current-time config]
  (if (event/event-deleted? existing)
    {:event incoming :revised? false}
    (let [later-time (max (:occurrence-time existing) (:occurrence-time incoming))
          existing-updated (event-update existing later-time config)
          incoming-updated (event-update incoming later-time config)
          overlap? (stamp/stamp-overlap? (:stamp incoming) (:stamp existing))
          ;; Check for dep-var conjunction (simplified check)
          same-term? (term/term-equal (:term existing) (:term incoming))
          same-time? (or (== (:occurrence-time existing) truth/occurrence-eternal)
                         (== (:occurrence-time existing) (:occurrence-time incoming)))
          diff-time? (and (not= (:occurrence-time existing) truth/occurrence-eternal)
                          (not= (:occurrence-time existing) (:occurrence-time incoming)))]
      (if (or overlap? diff-time? (not same-term?))
        ;; Choice: keep higher confidence (projected)
        (if (> (:confidence (:truth incoming-updated))
               (:confidence (:truth existing-updated)))
          {:event incoming :revised? false}
          {:event existing :revised? false})
        ;; Revision
        (let [revised (event-revision existing-updated incoming-updated config)]
          (if (>= (:confidence (:truth revised))
                  (:confidence (:truth existing-updated)))
            {:event revised :revised? true}
            {:event existing :revised? false}))))))

;; {Event a., Implication <a =/> b>.} |- Event b. Truth_Deduction
(defn belief-deduction
  "Deduce a belief from a component event and an implication.
   Given a. and <a =/> b>., derive b."
  [component imp]
  (let [{:keys [stamp creation-time]} (derivation-stamp component imp)
        postcondition (term/extract-subterm (:term imp) 2)]
    (event/make-event
      {:term postcondition
       :type event/event-type-belief
       :truth (truth/truth-deduction (:truth imp) (:truth component))
       :stamp stamp
       :occurrence-time (if (== (:occurrence-time component) truth/occurrence-eternal)
                          truth/occurrence-eternal
                          (+ (:occurrence-time component) (:occurrence-time-offset imp)))
       :creation-time creation-time})))
