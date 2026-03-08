(ns fNARS.event
  "Event data structure matching ONA's Event.h/Event.c."
  (:require [fNARS.truth :as truth]
            [fNARS.term :as term]
            [fNARS.stamp :as stamp]))

(def ^:const event-type-deleted 0)
(def ^:const event-type-goal 1)
(def ^:const event-type-belief 2)

(defn make-event
  "Create an event."
  [{:keys [term type truth stamp occurrence-time creation-time input?
           occurrence-time-offset processed?]
    :or {type event-type-belief
         truth truth/default-truth
         stamp []
         occurrence-time 0
         creation-time 0
         input? false
         occurrence-time-offset 0.0
         processed? false}}]
  {:term term
   :type type
   :truth truth
   :stamp stamp
   :occurrence-time occurrence-time
   :occurrence-time-offset occurrence-time-offset
   :processed? processed?
   :creation-time creation-time
   :input? input?})

(def deleted-event
  "A deleted/empty event."
  {:term term/empty-term
   :type event-type-deleted
   :truth {:frequency 0.0 :confidence 0.0}
   :stamp []
   :occurrence-time truth/occurrence-eternal
   :occurrence-time-offset 0.0
   :processed? false
   :creation-time 0
   :input? false})

(defn make-input-event
  "Create an input event with a fresh stamp ID.
   Returns [new-event new-stamp-counter]."
  [term type tv current-time stamp-counter & [{:keys [occurrence-time-offset]
                                                :or {occurrence-time-offset 0.0}}]]
  [(make-event {:term term
                :type type
                :truth tv
                :stamp (stamp/make-input-stamp stamp-counter)
                :occurrence-time current-time
                :occurrence-time-offset occurrence-time-offset
                :creation-time current-time
                :input? true})
   (inc stamp-counter)])

(defn event-deleted?
  "Check if an event is deleted/empty."
  [event]
  (= (:type event) event-type-deleted))

(defn event-equal
  "Check if two events are equal (truth, time, term, stamp)."
  [a b]
  (and (truth/truth-equal (:truth a) (:truth b))
       (== (:occurrence-time a) (:occurrence-time b))
       (term/term-equal (:term a) (:term b))
       (stamp/stamp-equal (:stamp a) (:stamp b))))

(defn event-eternalized
  "Create an eternalized version of an event."
  [event horizon]
  (if (== (:occurrence-time event) truth/occurrence-eternal)
    event
    (assoc event
      :occurrence-time truth/occurrence-eternal
      :truth (truth/truth-eternalize (:truth event) horizon))))
