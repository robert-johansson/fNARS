(ns fNARS.atom-registry
  "Bidirectional keyword ↔ integer atom registry for typed array terms.
   Copulas and variables have fixed IDs. User atoms get dynamic IDs.

   ID ranges:
     0        = nil/empty slot
     100-122  = copulas
     150-158  = independent variables $1-$9
     159-167  = dependent variables #1-#9
     168-176  = query variables ?1-?9
     210+     = user atoms (dynamically assigned)"
  (:require [clojure.string :as str]))

;; -- Fixed ID Constants --

(def ^:const nil-atom 0)

;; Copulas (100-122)
(def ^:const id-inheritance 100)
(def ^:const id-similarity 101)
(def ^:const id-implication 102)
(def ^:const id-temporal-implication 103)
(def ^:const id-equivalence 104)
(def ^:const id-conjunction 105)
(def ^:const id-sequence 106)
(def ^:const id-negation 107)
(def ^:const id-product 108)
(def ^:const id-ext-set 109)
(def ^:const id-int-set 110)
(def ^:const id-set-terminator 111)
(def ^:const id-disjunction 112)
(def ^:const id-ext-image1 113)
(def ^:const id-ext-image2 114)
(def ^:const id-int-image1 115)
(def ^:const id-int-image2 116)
(def ^:const id-set-element 117)
(def ^:const id-has-continuous-property 118)
(def ^:const id-ext-difference 119)
(def ^:const id-int-difference 120)
(def ^:const id-ext-intersection 121)
(def ^:const id-int-intersection 122)

;; First copula, last copula (for range checks)
(def ^:const copula-min 100)
(def ^:const copula-max 122)

;; Variables (150-176)
;; $1=150, $2=151, ..., $9=158
;; #1=159, #2=160, ..., #9=167
;; ?1=168, ?2=169, ..., ?9=176
(def ^:const indep-var-min 150)
(def ^:const indep-var-max 158)
(def ^:const dep-var-min 159)
(def ^:const dep-var-max 167)
(def ^:const query-var-min 168)
(def ^:const query-var-max 176)

;; First user atom ID
(def ^:const first-user-id 210)

;; -- Static Lookup Tables --

(def ^:private copula-kw->id
  {:cop-inheritance id-inheritance
   :cop-similarity id-similarity
   :cop-implication id-implication
   :cop-temporal-implication id-temporal-implication
   :cop-equivalence id-equivalence
   :cop-conjunction id-conjunction
   :cop-sequence id-sequence
   :cop-negation id-negation
   :cop-product id-product
   :cop-ext-set id-ext-set
   :cop-int-set id-int-set
   :cop-set-terminator id-set-terminator
   :cop-disjunction id-disjunction
   :cop-ext-image1 id-ext-image1
   :cop-ext-image2 id-ext-image2
   :cop-int-image1 id-int-image1
   :cop-int-image2 id-int-image2
   :cop-set-element id-set-element
   :cop-has-continuous-property id-has-continuous-property
   :cop-ext-difference id-ext-difference
   :cop-int-difference id-int-difference
   :cop-ext-intersection id-ext-intersection
   :cop-int-intersection id-int-intersection})

(def ^:private copula-id->kw
  (into {} (map (fn [[k v]] [v k])) copula-kw->id))

(defn- build-var-maps []
  (let [entries
        (concat
          (for [i (range 1 10)]
            [(keyword (str "$" i)) (+ (dec indep-var-min) i)])
          (for [i (range 1 10)]
            [(keyword (str "#" i)) (+ (dec dep-var-min) i)])
          (for [i (range 1 10)]
            [(keyword (str "?" i)) (+ (dec query-var-min) i)]))]
    {:kw->id (into {} entries)
     :id->kw (into {} (map (fn [[k v]] [v k])) entries)}))

(def ^:private var-maps (build-var-maps))
(def ^:private var-kw->id (:kw->id var-maps))
(def ^:private var-id->kw (:id->kw var-maps))

;; Combined static lookup
(def ^:private static-kw->id (merge copula-kw->id var-kw->id))
(def ^:private static-id->kw (merge copula-id->kw var-id->kw))

;; -- Dynamic Registry (global atom) --

(def ^:private registry
  (atom {:kw->id {}
         :id->kw {}
         :operator-ids #{}
         :next-id first-user-id}))

(defn reset-registry!
  "Reset the dynamic registry. For testing."
  []
  (reset! registry {:kw->id {} :id->kw {} :operator-ids #{} :next-id first-user-id}))

(defn- operator-keyword?
  "Check if a keyword represents an operator (name starts with ^)."
  [kw]
  (let [n (name kw)]
    (and (> (count n) 1) (= (first n) \^))))

(defn intern-atom
  "Get or assign an integer ID for a keyword atom.
   Static atoms (copulas, variables) return fixed IDs.
   User atoms get dynamic IDs, assigned on first use."
  [kw]
  (or (get static-kw->id kw)
      (get (:kw->id @registry) kw)
      (do (swap! registry
            (fn [r]
              (if (contains? (:kw->id r) kw)
                r
                (let [id (:next-id r)]
                  (cond-> (-> r
                              (assoc-in [:kw->id kw] id)
                              (assoc-in [:id->kw id] kw)
                              (update :next-id inc))
                    (operator-keyword? kw)
                    (update :operator-ids conj id))))))
          (get (:kw->id @registry) kw))))

(defn resolve-atom
  "Get the keyword for an integer atom ID. Returns nil for unknown IDs."
  [id]
  (or (get static-id->kw id)
      (get (:id->kw @registry) id)))

(defn operator-id?
  "Check if an integer atom ID is an operator."
  [id]
  (contains? (:operator-ids @registry) id))

(defn copula-id?
  "Check if an integer atom ID is a copula."
  [id]
  (and (>= id copula-min) (<= id copula-max)))

(defn indep-var-id?
  "Check if an integer atom ID is an independent variable ($1-$9)."
  [id]
  (and (>= id indep-var-min) (<= id indep-var-max)))

(defn dep-var-id?
  "Check if an integer atom ID is a dependent variable (#1-#9)."
  [id]
  (and (>= id dep-var-min) (<= id dep-var-max)))

(defn query-var-id?
  "Check if an integer atom ID is a query variable (?1-?9)."
  [id]
  (and (>= id query-var-min) (<= id query-var-max)))

(defn variable-id?
  "Check if an integer atom ID is any kind of variable."
  [id]
  (and (>= id indep-var-min) (<= id query-var-max)))

(defn simple-atom-id?
  "Check if an integer atom ID is a simple user atom
   (not nil, not copula, not variable)."
  [id]
  (and (pos? id)
       (not (copula-id? id))
       (not (variable-id? id))))

(defn make-var-id
  "Create a variable integer ID. type is \\$ \\# or \\?, num is 1-9."
  [type num]
  (case type
    \$ (+ (dec indep-var-min) num)
    \# (+ (dec dep-var-min) num)
    \? (+ (dec query-var-min) num)))

(defn var-id-type
  "Get the variable type character for a variable ID."
  [id]
  (cond
    (indep-var-id? id) \$
    (dep-var-id? id)   \#
    (query-var-id? id) \?
    :else nil))

(defn var-id-num
  "Get the variable number (1-9) from a variable ID."
  [id]
  (cond
    (indep-var-id? id) (- id (dec indep-var-min))
    (dep-var-id? id)   (- id (dec dep-var-min))
    (query-var-id? id) (- id (dec query-var-min))
    :else 0))
