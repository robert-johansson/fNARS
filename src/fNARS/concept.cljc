(ns fNARS.concept
  "Concept data structure matching ONA's Concept.h."
  (:require [fNARS.event :as event]
            [fNARS.table :as table]
            [fNARS.platform :as p]))

(defn make-concept
  "Create a new concept."
  [id term current-time]
  {:id id
   :term term
   :usage {:use-count 1 :last-used current-time}
   :belief event/deleted-event           ;; eternal belief
   :belief-spike event/deleted-event     ;; most recent temporal belief
   :predicted-belief event/deleted-event ;; predicted future belief
   :goal-spike event/deleted-event       ;; most recent goal
   :precondition-beliefs {}              ;; op-id -> table of implications
   :implication-links (table/table-init) ;; temporal implication table
   :priority 0.0
   :priority-epoch 0                     ;; epoch when priority was last set
   :last-selection-time 0
   :process-id 0
   :process-id2 0
   :process-id3 0})

(defn effective-priority
  "Compute the effective priority of a concept, applying lazy decay.
   effective = raw-priority * (concept-durability ^ (current-epoch - stored-epoch))"
  [concept decay-epoch concept-dur]
  (let [age (- decay-epoch (:priority-epoch concept 0))]
    (if (zero? age)
      (:priority concept)
      (* (:priority concept) (p/pow concept-dur age)))))

(defn priority-set-max
  "Set concept priority to max of current effective priority and new-val.
   Materializes the decayed priority and resets epoch."
  [concept new-val decay-epoch concept-dur]
  (let [eff (effective-priority concept decay-epoch concept-dur)]
    (if (>= new-val eff)
      (assoc concept :priority new-val :priority-epoch decay-epoch)
      (assoc concept :priority eff :priority-epoch decay-epoch))))

(defn usage-usefulness
  "Compute usefulness score for concept eviction.
   Matches ONA's Usage_usefulness."
  [{:keys [use-count last-used]} current-time]
  (let [recency (/ 1.0 (max 1 (- current-time last-used)))]
    (* use-count recency)))

(defn usage-use
  "Update usage when concept is used."
  [usage current-time eternal?]
  (-> usage
      (update :use-count + (if eternal? 1000000 1))
      (assoc :last-used current-time)))
