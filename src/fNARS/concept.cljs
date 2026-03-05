(ns fNARS.concept
  "Concept data structure matching ONA's Concept.h."
  (:require [fNARS.event :as event]
            [fNARS.table :as table]))

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
   :last-selection-time 0
   :process-id 0
   :process-id2 0
   :process-id3 0})

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
