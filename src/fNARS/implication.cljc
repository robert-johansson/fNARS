(ns fNARS.implication
  "Implication data structure matching ONA's Implication.h.")

(defn make-implication
  "Create an implication."
  [{:keys [term truth stamp occurrence-time-offset
           source-concept-key source-concept-id creation-time]
    :or {truth {:frequency 1.0 :confidence 0.5}
         stamp []
         occurrence-time-offset 0.0
         source-concept-key nil
         source-concept-id -1
         creation-time 0}}]
  {:term term
   :truth truth
   :stamp stamp
   :occurrence-time-offset occurrence-time-offset
   :source-concept-key source-concept-key
   :source-concept-id source-concept-id
   :creation-time creation-time})
