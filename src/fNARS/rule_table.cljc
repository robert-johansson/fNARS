(ns fNARS.rule-table
  "Data-driven NAL 1-5 rule table engine.
   Rules are data vectors: pattern matching replaces ONA's generated C code."
  (:require [fNARS.term :as term]
            [fNARS.truth :as truth]
            [fNARS.variable :as variable]
            [fNARS.narsese :as narsese]
            [fNARS.atom-registry :as ar]))

;; -- Pattern Language --
;; Patterns are nested vectors describing term structure:
;;   [:inh :S :M]        = (S --> M)
;;   [:S]                = bare term S
;;   etc.

(def ^:private copula-map
  "Map from pattern keyword to term copula integer ID."
  {:inh      term/inheritance
   :sim      term/similarity
   :impl     term/implication
   :equiv    term/equivalence
   :timp     term/temporal-implication
   :neg      term/negation
   :conj     term/conjunction
   :disj     term/disjunction
   :prod     term/product
   :ext-set  term/ext-set
   :int-set  term/int-set
   :ext-int  term/ext-intersection
   :int-int  term/int-intersection
   :ext-diff term/ext-difference
   :int-diff term/int-difference
   :ext-img1 term/ext-image1
   :ext-img2 term/ext-image2
   :int-img1 term/int-image1
   :int-img2 term/int-image2
   :set-el   term/set-element
   :set-term term/set-terminator
   :hcp      term/has-continuous-property})

(def ^:private pattern-vars #{:S :M :P :A :B :C :R})

(defn- pattern-var? [x] (contains? pattern-vars x))

;; -- Pattern Matching --

(declare match-subpattern)

(defn- match-pattern
  "Match a pattern against a term, extending bindings."
  [pattern term bindings]
  (cond
    ;; Bare variable pattern like [:S]
    (and (= 1 (count pattern)) (pattern-var? (first pattern)))
    (let [v (first pattern)]
      (if-let [existing (get bindings v)]
        (when (term/term-equal existing term) bindings)
        (assoc bindings v term)))

    ;; Compound pattern like [:inh :S :M]
    :else
    (let [[cop-key & args] pattern
          expected-cop (get copula-map cop-key)]
      (when (and expected-cop (== (term/term-root term) expected-cop))
        (case (count args)
          1 (let [child (term/extract-subterm term 1)]
              (match-subpattern bindings (first args) child))
          2 (let [left (term/extract-subterm term 1)
                  right (term/extract-subterm term 2)]
              (some-> bindings
                      (match-subpattern (first args) left)
                      (match-subpattern (second args) right)))
          nil)))))

(defn- match-subpattern
  "Match a sub-pattern element against a subterm."
  [bindings element subterm]
  (cond
    (pattern-var? element)
    (if-let [existing (get bindings element)]
      (when (term/term-equal existing subterm) bindings)
      (assoc bindings element subterm))

    (vector? element)
    (match-pattern element subterm bindings)

    (keyword? element)
    (when-let [cop (get copula-map element)]
      (when (== (term/term-root subterm) cop)
        bindings))

    :else nil))

;; -- Conclusion Building --

(declare build-subterm)

(defn- build-term
  "Build a term from a pattern and bindings map."
  [pattern bindings]
  (cond
    (and (= 1 (count pattern)) (pattern-var? (first pattern)))
    (get bindings (first pattern))

    :else
    (let [[cop-key & args] pattern
          cop (get copula-map cop-key)]
      (case (count args)
        1 (let [child (build-subterm (first args) bindings)]
            (when child
              (-> (term/atomic-term cop)
                  (term/override-subterm 1 child))))
        2 (let [left (build-subterm (first args) bindings)
                right (build-subterm (second args) bindings)]
            (when (and left right)
              (-> (term/atomic-term cop)
                  (term/override-subterm 1 left)
                  (term/override-subterm 2 right))))
        nil))))

(defn- build-subterm
  "Build a sub-element: variable lookup or nested pattern."
  [element bindings]
  (cond
    (pattern-var? element)
    (get bindings element)

    (vector? element)
    (build-term element bindings)

    (keyword? element)
    (when-let [cop (get copula-map element)]
      (term/atomic-term cop))

    :else nil))

;; -- Truth Function Wrappers --

(defn- wrap-truth-2 [f] (fn [t1 t2 _config] (f t1 t2)))
(defn- wrap-truth-horizon [f] (fn [t1 t2 config] (f t1 t2 (:horizon config))))
(defn- wrap-truth-structural [f] (fn [t1 _t2 config] (f t1 config)))
(defn- wrap-truth-1 [f] (fn [t1 _t2 _config] (f t1)))

(def ^:private truth-fns
  {:deduction              (wrap-truth-2 truth/truth-deduction)
   :induction              (wrap-truth-horizon truth/truth-induction)
   :abduction              (wrap-truth-horizon truth/truth-abduction)
   :exemplification        (wrap-truth-horizon truth/truth-exemplification)
   :comparison             (wrap-truth-horizon truth/truth-comparison)
   :analogy                (wrap-truth-2 truth/truth-analogy)
   :resemblance            (wrap-truth-2 truth/truth-resemblance)
   :intersection           (wrap-truth-2 truth/truth-intersection)
   :union                  (wrap-truth-2 truth/truth-union)
   :difference             (wrap-truth-2 truth/truth-difference)
   :negation               (wrap-truth-1 truth/truth-negation)
   :conversion             (wrap-truth-horizon truth/truth-conversion)
   :structural-deduction   (wrap-truth-structural truth/truth-structural-deduction)
   :structural-intersection (wrap-truth-structural truth/truth-structural-intersection)
   :structural-deduction-negated (wrap-truth-structural truth/truth-structural-deduction-negated)
   :decompose-pnn          (wrap-truth-2 truth/truth-decompose-pnn)
   :decompose-npp          (wrap-truth-2 truth/truth-decompose-npp)
   :decompose-pnp          (wrap-truth-2 truth/truth-decompose-pnp)
   :decompose-ppp          (wrap-truth-2 truth/truth-decompose-ppp)
   :decompose-nnn          (wrap-truth-2 truth/truth-decompose-nnn)
   :anonymous-analogy      (wrap-truth-horizon truth/truth-anonymous-analogy)
   :frequency-greater      (wrap-truth-2 truth/truth-frequency-greater)
   :frequency-equal        (wrap-truth-2 truth/truth-frequency-equal)})

;; -- Rule Definitions --

(defn- r2 [p1 p2 concl truth-key]
  [{:p1 p1 :p2 p2 :concl concl :truth truth-key :double? true :swap-truth? false}
   {:p1 p2 :p2 p1 :concl concl :truth truth-key :double? true :swap-truth? true}])

(defn- r1 [p1 concl truth-key]
  [{:p1 p1 :p2 nil :concl concl :truth truth-key :double? false :swap-truth? false}])

(defn- r1-bidi [p1 p2 truth-key]
  [{:p1 p1 :p2 nil :concl p2 :truth truth-key :double? false :swap-truth? false}
   {:p1 p2 :p2 nil :concl p1 :truth truth-key :double? false :swap-truth? false}])

(defn- r2-var-intro [p1 p2 concl truth-key]
  [{:p1 p1 :p2 p2 :concl concl :truth truth-key :double? true :swap-truth? false :var-intro? true}
   {:p1 p2 :p2 p1 :concl concl :truth truth-key :double? true :swap-truth? true :var-intro? true}])

(def nal-1-rules
  (vec (concat
    (r2 [:inh :S :M] [:inh :M :P] [:inh :S :P] :deduction)
    (r2 [:inh :A :B] [:inh :A :C] [:inh :C :B] :induction)
    (r2 [:inh :A :C] [:inh :B :C] [:inh :B :A] :abduction)
    (r2 [:inh :A :B] [:inh :B :C] [:inh :C :A] :exemplification))))

(def nal-2-rules
  (vec (concat
    (r1 [:sim :S :P] [:sim :P :S] :structural-intersection)
    (r2 [:sim :M :P] [:sim :S :M] [:sim :S :P] :resemblance)
    (r2 [:inh :P :M] [:inh :S :M] [:sim :S :P] :comparison)
    (r2 [:inh :M :P] [:inh :M :S] [:sim :S :P] :comparison)
    (r2 [:inh :M :P] [:sim :S :M] [:inh :S :P] :analogy)
    (r2 [:inh :P :M] [:sim :S :M] [:inh :P :S] :analogy)
    (r2 [:S] [:sim :S :P] [:P] :analogy)
    (r2 [:S] [:sim :P :S] [:P] :analogy)
    (r1 [:inh :S [:ext-set :P]] [:sim :S [:ext-set :P]] :structural-intersection)
    (r1 [:inh [:int-set :S] :P] [:sim [:int-set :S] :P] :structural-intersection)
    (r2 [:inh [:ext-set :M] :P] [:sim :S :M] [:inh [:ext-set :S] :P] :analogy)
    (r2 [:inh :P [:int-set :M]] [:sim :S :M] [:inh :P [:int-set :S]] :analogy)
    (r1 [:sim [:ext-set :A] [:ext-set :B]] [:sim :A :B] :structural-intersection)
    (r1 [:sim [:int-set :A] [:int-set :B]] [:sim :A :B] :structural-intersection))))

(def nal-3-rules
  (vec (concat
    (r1 [:inh [:ext-set :A :B] :M] [:inh [:ext-set :A :set-term] :M] :structural-deduction)
    (r1 [:inh [:ext-set :A :B] :M] [:inh [:ext-set :B :set-term] :M] :structural-deduction)
    (r1 [:inh :M [:int-set :A :B]] [:inh :M [:int-set :A :set-term]] :structural-deduction)
    (r1 [:inh :M [:int-set :A :B]] [:inh :M [:int-set :B :set-term]] :structural-deduction)
    (r1 [:inh [:int-int :S :P] :M] [:inh :S :M] :structural-deduction)
    (r1 [:inh :M [:ext-int :S :P]] [:inh :M :S] :structural-deduction)
    (r1 [:inh [:int-int :S :P] :M] [:inh :P :M] :structural-deduction)
    (r1 [:inh :M [:ext-int :S :P]] [:inh :M :P] :structural-deduction)
    (r1 [:inh [:int-diff :A :S] :M] [:inh :A :M] :structural-deduction)
    (r1 [:inh :M [:ext-diff :B :S]] [:inh :M :B] :structural-deduction)
    (r1 [:inh [:int-diff :A :S] :M] [:inh :S :M] :structural-deduction-negated)
    (r1 [:inh :M [:ext-diff :B :S]] [:inh :M :S] :structural-deduction-negated)
    (r2 [:inh :P :M] [:inh :S :M] [:inh [:int-int :P :S] :M] :intersection)
    (r2 [:inh :P :M] [:inh :S :M] [:inh [:ext-int :P :S] :M] :union)
    (r2 [:inh :P :M] [:inh :S :M] [:inh [:int-diff :P :S] :M] :difference)
    (r2 [:inh :M :P] [:inh :M :S] [:inh :M [:ext-int :P :S]] :intersection)
    (r2 [:inh :M :P] [:inh :M :S] [:inh :M [:int-int :P :S]] :union)
    (r2 [:inh :M :P] [:inh :M :S] [:inh :M [:ext-diff :P :S]] :difference)
    (r2 [:inh :S :M] [:inh [:int-int :S :P] :M] [:inh :P :M] :decompose-pnn)
    (r2 [:inh :P :M] [:inh [:int-int :S :P] :M] [:inh :S :M] :decompose-pnn)
    (r2 [:inh :S :M] [:inh [:ext-int :S :P] :M] [:inh :P :M] :decompose-npp)
    (r2 [:inh :P :M] [:inh [:ext-int :S :P] :M] [:inh :S :M] :decompose-npp)
    (r2 [:inh :S :M] [:inh [:int-diff :S :P] :M] [:inh :P :M] :decompose-pnp)
    (r2 [:inh :S :M] [:inh [:int-diff :P :S] :M] [:inh :P :M] :decompose-nnn)
    (r2 [:inh :M :S] [:inh :M [:ext-int :S :P]] [:inh :M :P] :decompose-pnn)
    (r2 [:inh :M :P] [:inh :M [:ext-int :S :P]] [:inh :M :S] :decompose-pnn)
    (r2 [:inh :M :S] [:inh :M [:int-int :S :P]] [:inh :M :P] :decompose-npp)
    (r2 [:inh :M :P] [:inh :M [:int-int :S :P]] [:inh :M :S] :decompose-npp)
    (r2 [:inh :M :S] [:inh :M [:ext-diff :S :P]] [:inh :M :P] :decompose-pnp)
    (r2 [:inh :M :S] [:inh :M [:ext-diff :P :S]] [:inh :M :P] :decompose-nnn))))

(def nal-4-rules
  (vec (concat
    (r1 [:inh [:prod :A :B] [:prod :S :M]] [:inh :A :S] :structural-deduction)
    (r1 [:inh [:prod :A :B] [:prod :S :M]] [:inh :B :M] :structural-deduction)
    (r1-bidi [:inh [:prod :A :B] :R] [:inh :A [:ext-img1 :R :B]] :structural-intersection)
    (r1-bidi [:inh [:prod :A :B] :R] [:inh :B [:ext-img2 :R :A]] :structural-intersection)
    (r1-bidi [:inh :R [:prod :A :B]] [:inh [:int-img1 :R :B] :A] :structural-intersection)
    (r1-bidi [:inh :R [:prod :A :B]] [:inh [:int-img2 :R :A] :B] :structural-intersection)
    (r2 [:hcp [:ext-set :R] [:int-set :P]] [:hcp [:ext-set :S] [:int-set :P]]
        [:inh [:prod [:ext-set :R] [:ext-set :S]] [:inh :+ :P]] :frequency-greater)
    (r2 [:inh [:prod :A :B] [:inh :+ :P]] [:inh [:prod :B :C] [:inh :+ :P]]
        [:inh [:prod :A :C] [:inh :+ :P]] :deduction)
    (r2 [:hcp [:ext-set :R] [:int-set :P]] [:hcp [:ext-set :S] [:int-set :P]]
        [:inh [:prod [:ext-set :R] [:ext-set :S]] [:inh := :P]] :frequency-equal)
    (r2 [:inh [:prod :A :B] [:inh := :P]] [:inh [:prod :B :C] [:inh := :P]]
        [:inh [:prod :A :C] [:inh := :P]] :deduction)
    (r1 [:inh [:prod :A :B] [:inh := :P]] [:inh [:prod :B :A] [:inh := :P]] :structural-intersection)
    (r2 [:inh [:prod :A :B] :R] [:inh [:prod :C :B] :R] [:inh :C :A] :abduction)
    (r2 [:inh [:prod :A :B] :R] [:inh [:prod :A :C] :R] [:inh :C :B] :abduction)
    (r2 [:inh :R [:prod :A :B]] [:inh :R [:prod :C :B]] [:inh :C :A] :induction)
    (r2 [:inh :R [:prod :A :B]] [:inh :R [:prod :A :C]] [:inh :C :B] :induction)
    (r2 [:inh [:prod :A :B] :R] [:inh :C :A] [:inh [:prod :C :B] :R] :deduction)
    (r2 [:inh [:prod :A :B] :R] [:inh :A :C] [:inh [:prod :C :B] :R] :induction)
    (r2 [:inh [:prod :A :B] :R] [:sim :C :A] [:inh [:prod :C :B] :R] :analogy)
    (r2 [:inh [:prod :A :B] :R] [:inh :C :B] [:inh [:prod :A :C] :R] :deduction)
    (r2 [:inh [:prod :A :B] :R] [:inh :B :C] [:inh [:prod :A :C] :R] :induction)
    (r2 [:inh [:prod :A :B] :R] [:sim :C :B] [:inh [:prod :A :C] :R] :analogy)
    (r2 [:inh :R [:prod :A :B]] [:inh :A :C] [:inh :R [:prod :C :B]] :deduction)
    (r2 [:inh :R [:prod :A :B]] [:inh :C :A] [:inh :R [:prod :C :B]] :abduction)
    (r2 [:inh :R [:prod :A :B]] [:sim :C :A] [:inh :R [:prod :C :B]] :analogy)
    (r2 [:inh :R [:prod :A :B]] [:inh :B :C] [:inh :R [:prod :A :C]] :deduction)
    (r2 [:inh :R [:prod :A :B]] [:inh :C :B] [:inh :R [:prod :A :C]] :abduction)
    (r2 [:inh :R [:prod :A :B]] [:sim :C :B] [:inh :R [:prod :A :C]] :analogy)
    (r2 [:inh [:prod :A :B] :R] [:inh [:prod :C :B] :R] [:sim :A :C] :comparison)
    (r2 [:inh [:prod :A :B] :R] [:inh [:prod :A :C] :R] [:sim :B :C] :comparison)
    (r2 [:inh :R [:prod :A :B]] [:inh :R [:prod :C :B]] [:sim :A :C] :comparison)
    (r2 [:inh :R [:prod :A :B]] [:inh :R [:prod :A :C]] [:sim :B :C] :comparison))))

(def nal-5-rules
  (vec (concat
    (r1 [:neg :A] [:A] :negation)
    (r1 [:conj :A :B] [:A] :structural-deduction)
    (r1 [:conj :A :B] [:B] :structural-deduction)
    (r1 [:conj :A :B] [:conj :B :A] :structural-intersection)
    (r2 [:S] [:conj :S :A] [:A] :decompose-pnn)
    (r2 [:S] [:disj :S :A] [:A] :decompose-npp)
    (r2 [:S] [:conj [:neg :S] :A] [:A] :decompose-nnn)
    (r2 [:S] [:disj [:neg :S] :A] [:A] :decompose-ppp)
    (r2 [:impl :S :M] [:impl :M :P] [:impl :S :P] :deduction)
    (r2 [:impl :A :B] [:impl :A :C] [:impl :C :B] :induction)
    (r2 [:impl :A :C] [:impl :B :C] [:impl :B :A] :abduction)
    (r2 [:impl :A :B] [:impl :B :C] [:impl :C :A] :exemplification)
    (r2 [:impl :A :C] [:impl :B :C] [:impl [:conj :A :B] :C] :union)
    (r2 [:impl :A :C] [:impl :B :C] [:impl [:disj :A :B] :C] :intersection)
    (r2 [:impl :C :A] [:impl :C :B] [:impl :C [:conj :A :B]] :intersection)
    (r2 [:impl :C :A] [:impl :C :B] [:impl :C [:disj :A :B]] :union)
    (r2 [:impl [:conj :S :P] :M] [:impl :S :M] [:P] :abduction)
    (r2 [:impl [:conj :C :M] :P] [:impl :S :M] [:impl [:conj :C :S] :P] :deduction)
    (r2 [:impl [:conj :C :P] :M] [:impl [:conj :C :S] :M] [:impl :S :P] :abduction)
    (r2 [:impl [:conj :C :M] :P] [:impl :M :S] [:impl [:conj :C :S] :P] :induction)
    (r1 [:equiv :S :P] [:equiv :P :S] :structural-intersection)
    (r2 [:impl :S :P] [:impl :P :S] [:equiv :S :P] :intersection)
    (r2 [:impl :P :M] [:impl :S :M] [:equiv :S :P] :comparison)
    (r2 [:impl :M :P] [:impl :M :S] [:equiv :S :P] :comparison)
    (r2 [:impl :M :P] [:equiv :S :M] [:impl :S :P] :analogy)
    (r2 [:impl :P :M] [:equiv :S :M] [:impl :P :S] :analogy)
    (r2 [:equiv :M :P] [:equiv :S :M] [:equiv :S :P] :resemblance)
    (r1 [:equiv :M :P] [:impl :M :P] :structural-deduction)
    (r1 [:equiv :M :P] [:impl :P :M] :structural-deduction))))

(def nal-5-only-rules
  (vec (concat
    (r2 [:A] [:impl :A :B] [:B] :deduction)
    (r2 [:A] [:impl [:conj :A :B] :C] [:impl :B :C] :deduction)
    (r2 [:B] [:impl :A :B] [:A] :abduction)
    (r2 [:A] [:equiv :A :B] [:B] :analogy))))

(def nal-6-rules
  (vec (concat
    (r2-var-intro [:inh :C :A] [:inh :C :B] [:impl [:inh :C :B] [:inh :C :A]] :induction)
    (r2-var-intro [:inh :A :C] [:inh :B :C] [:impl [:inh :B :C] [:inh :A :C]] :induction)
    (r2-var-intro [:inh :C :A] [:inh :C :B] [:equiv [:inh :C :B] [:inh :C :A]] :comparison)
    (r2-var-intro [:inh :A :C] [:inh :B :C] [:equiv [:inh :B :C] [:inh :A :C]] :comparison)
    (r2-var-intro [:neg [:inh :C :A]] [:inh :C :B] [:impl [:inh :C :B] [:neg [:inh :C :A]]] :induction)
    (r2-var-intro [:neg [:inh :A :C]] [:inh :B :C] [:impl [:inh :B :C] [:neg [:inh :A :C]]] :induction)
    (r2-var-intro [:neg [:inh :C :A]] [:inh :C :B] [:equiv [:inh :C :B] [:neg [:inh :C :A]]] :comparison)
    (r2-var-intro [:neg [:inh :A :C]] [:inh :B :C] [:equiv [:inh :B :C] [:neg [:inh :A :C]]] :comparison)
    (r2-var-intro [:inh :C :A] [:neg [:inh :C :B]] [:impl [:neg [:inh :C :B]] [:inh :C :A]] :induction)
    (r2-var-intro [:inh :A :C] [:neg [:inh :B :C]] [:impl [:neg [:inh :B :C]] [:inh :A :C]] :induction)
    (r2-var-intro [:inh :C :A] [:neg [:inh :C :B]] [:equiv [:neg [:inh :C :B]] [:inh :C :A]] :comparison)
    (r2-var-intro [:inh :A :C] [:neg [:inh :B :C]] [:equiv [:neg [:inh :B :C]] [:inh :A :C]] :comparison)
    (r2-var-intro [:inh :C :A] [:inh :C :B] [:conj [:inh :C :B] [:inh :C :A]] :intersection)
    (r2-var-intro [:inh :A :C] [:inh :B :C] [:conj [:inh :B :C] [:inh :A :C]] :intersection)
    (r2-var-intro [:neg [:inh :C :A]] [:inh :C :B] [:conj [:inh :C :B] [:neg [:inh :C :A]]] :intersection)
    (r2-var-intro [:neg [:inh :A :C]] [:inh :B :C] [:conj [:inh :B :C] [:neg [:inh :A :C]]] :intersection)
    (r2-var-intro [:inh :C :A] [:neg [:inh :C :B]] [:conj [:neg [:inh :C :B]] [:inh :C :A]] :intersection)
    (r2-var-intro [:inh :A :C] [:neg [:inh :B :C]] [:conj [:neg [:inh :B :C]] [:inh :A :C]] :intersection)
    (r2-var-intro [:inh [:prod :A :B] :R] [:inh [:prod :B :A] :S]
                  [:impl [:inh [:prod :B :A] :S] [:inh [:prod :A :B] :R]] :induction)
    (r2-var-intro [:neg [:inh [:prod :B :A] :R]] [:inh [:prod :A :B] :S]
                  [:impl [:inh [:prod :A :B] :S] [:neg [:inh [:prod :B :A] :R]]] :induction)
    (r2-var-intro [:inh [:prod :B :A] :R] [:neg [:inh [:prod :A :B] :S]]
                  [:impl [:neg [:inh [:prod :A :B] :S]] [:inh [:prod :B :A] :R]] :induction)
    (r2 [:A] [:B] [:conj :A :B] :intersection)
    (r2-var-intro [:inh [:prod :A :C] :M] [:conj [:inh [:prod :A :B] :R] [:inh [:prod :B :C] :S]]
                  [:impl [:conj [:inh [:prod :A :B] :R] [:inh [:prod :B :C] :S]] [:inh [:prod :A :C] :M]] :induction))))

(defn rules-for-level
  "Get all rules up to the given NAL level."
  [level]
  (cond-> []
    (>= level 1) (into nal-1-rules)
    (>= level 2) (into nal-2-rules)
    (>= level 3) (into nal-3-rules)
    (>= level 4) (into nal-4-rules)
    (>= level 5) (into nal-5-rules)
    (== level 5) (into nal-5-only-rules)
    (>= level 6) (into nal-6-rules)))

;; -- Rule Application --

(defn apply-rule
  "Try to apply a single rule to two terms."
  [rule term1 term2 truth1 truth2 config]
  (let [bindings (match-pattern (:p1 rule) term1 {})]
    (when bindings
      (let [bindings (if (:double? rule)
                       (match-pattern (:p2 rule) term2 bindings)
                       bindings)]
        (when bindings
          (let [conclusion-term (build-term (:concl rule) bindings)]
            (when conclusion-term
              (let [truth-fn (get truth-fns (:truth rule))
                    [t1 t2] (if (:swap-truth? rule)
                              [truth2 truth1]
                              [truth1 truth2])
                    conclusion-truth (truth-fn t1 t2 config)]
                {:term conclusion-term
                 :truth conclusion-truth
                 :var-intro? (:var-intro? rule false)}))))))))

(defn apply-rules
  "Apply all matching rules to two terms."
  [rules term1 term2 truth1 truth2 config double-premise?]
  (into []
    (keep (fn [rule]
            (when (= (:double? rule) double-premise?)
              (apply-rule rule term1 term2 truth1 truth2 config))))
    rules))

;; -- Copula-Based Rule Indexing --

(def ^:private reverse-copula-map
  "Map from term copula integer ID to pattern keyword."
  (into {} (map (fn [[k v]] [v k])) copula-map))

(defn- pattern-copula-key
  "Extract the root copula key from a pattern."
  [pattern]
  (if (and (= 1 (count pattern)) (pattern-var? (first pattern)))
    :*
    (let [first-el (first pattern)]
      (if (contains? copula-map first-el)
        first-el
        :*))))

(defn- term-copula-key
  "Extract the copula key from a runtime term for index lookup."
  [t]
  (get reverse-copula-map (term/term-root t) :unknown))

(defn build-rule-index
  "Build a copula-indexed rule lookup structure."
  [rules]
  (reduce
    (fn [{:keys [single double]} rule]
      (let [p1-key (pattern-copula-key (:p1 rule))]
        (if (:double? rule)
          (let [p2-key (pattern-copula-key (:p2 rule))
                k [p1-key p2-key]]
            {:single single
             :double (update double k (fnil conj []) rule)})
          {:single (update single p1-key (fnil conj []) rule)
           :double double})))
    {:single {} :double {}}
    rules))

(defn indexed-apply-rules
  "Apply matching rules using copula index."
  [index term1 term2 truth1 truth2 config double-premise?]
  (if double-premise?
    (let [cop1 (term-copula-key term1)
          cop2 (term-copula-key term2)
          dbl (:double index)
          rules (concat (get dbl [cop1 cop2])
                        (get dbl [:* cop2])
                        (get dbl [cop1 :*])
                        (get dbl [:* :*]))]
      (into []
        (keep (fn [rule] (apply-rule rule term1 term2 truth1 truth2 config)))
        rules))
    (let [cop1 (term-copula-key term1)
          sgl (:single index)
          rules (concat (get sgl cop1) (get sgl :*))]
      (into []
        (keep (fn [rule] (apply-rule rule term1 term2 truth1 truth2 config)))
        rules))))

;; -- Term Reductions --

(defn- try-reduce-subterm
  "Try to match pattern against a subterm at position idx in term."
  [term idx pattern replacement]
  (let [sub (term/extract-subterm term idx)
        bindings (match-pattern pattern sub {})]
    (when bindings
      (when-let [new-sub (build-term replacement bindings)]
        (term/override-subterm term idx new-sub)))))

(defn- try-reduce-statement
  "Try to match pattern against the whole term."
  [term pattern replacement]
  (let [bindings (match-pattern pattern term {})]
    (when bindings
      (build-term replacement bindings))))

(def ^:private term-reductions
  [[[:ext-int :A :A] [:A]]
   [[:int-int :A :A] [:A]]
   [[:int-int [:ext-set :A :set-term] [:ext-set :B :set-term]] [:ext-set :A :B]]
   [[:int-int [:ext-set :A :B] [:ext-set :C :set-term]] [:ext-set [:set-el :A :B] :C]]
   [[:int-int [:ext-set :C :set-term] [:ext-set :A :B]] [:ext-set :C [:set-el :A :B]]]
   [[:ext-int [:int-set :A :set-term] [:int-set :B :set-term]] [:int-set :A :B]]
   [[:ext-int [:int-set :A :B] [:int-set :C :set-term]] [:int-set [:set-el :A :B] :C]]
   [[:ext-int [:int-set :A :set-term] [:int-set :B :C]] [:int-set :A [:set-el :B :C]]]
   [[:ext-set [:set-el :A :B] :set-term] [:ext-set :A :B]]
   [[:int-set [:set-el :A :B] :set-term] [:int-set :A :B]]])

(def ^:private statement-reductions
  [[[:conj :A :A] [:A]]
   [[:disj :A :A] [:A]]])

(defn reduce-conclusion
  "Apply all term reductions to a conclusion term."
  [conclusion]
  (let [result (reduce
                 (fn [t [pattern replacement]]
                   (or (try-reduce-statement t pattern replacement) t))
                 conclusion
                 statement-reductions)
        root (term/term-root result)]
    (if (or (== root term/inheritance) (== root term/similarity)
            (== root term/implication) (== root term/equivalence)
            (== root term/temporal-implication))
      (reduce
        (fn [t [pattern replacement]]
          (let [t (or (try-reduce-subterm t 1 pattern replacement) t)
                t (or (try-reduce-subterm t 2 pattern replacement) t)]
            t))
        result
        term-reductions)
      result)))

;; -- Derived Event Filters --

(defn- atom-appears-twice?
  [conclusion]
  (let [root (term/term-root conclusion)]
    (or
      (and (or (== root term/implication) (== root term/equivalence))
           (let [t1 (term/extract-subterm conclusion 1)
                 t2 (term/extract-subterm conclusion 2)]
             (term/term-equal t1 t2)))
      (and (or (== root term/inheritance) (== root term/similarity))
           (not (and (== (term/term-get conclusion 1) term/product)
                     (term/simple-atom? (term/term-get conclusion 2))))
           (let [atoms (term/term-atoms conclusion)
                 simple (filter term/simple-atom? atoms)]
             (not= (count simple) (count (distinct simple))))))))

(defn- nested-hol-statement?
  [conclusion]
  (let [atoms (term/term-atoms conclusion)
        counts (reduce
                 (fn [{:keys [ie te] :as acc} atom-id]
                   (cond
                     (or (== atom-id term/implication) (== atom-id term/equivalence))
                     (update acc :ie inc)
                     (== atom-id term/temporal-implication)
                     (update acc :te inc)
                     :else acc))
                 {:ie 0 :te 0}
                 atoms)]
    (or (>= (:ie counts) 2) (> (:te counts) 2))))

(defn- inh-or-sim-has-dep-var?
  [conclusion]
  (let [root (term/term-root conclusion)]
    (and (or (== root term/inheritance) (== root term/similarity))
         (boolean (variable/has-variable? conclusion false true false)))))

(defn- junction-not-right-nested?
  [conclusion]
  (loop [i 0]
    (if (>= i term/compound-term-size-max)
      false
      (let [atom-id (get conclusion i)]
        (if (or (== atom-id term/conjunction) (== atom-id term/disjunction))
          (let [right-idx (term/right-child-idx i)]
            (if (< right-idx term/compound-term-size-max)
              (let [right-id (get conclusion right-idx)]
                (if (or (== right-id term/conjunction) (== right-id term/disjunction))
                  true
                  (recur (inc i))))
              (recur (inc i))))
          (recur (inc i)))))))

(defn- invalid-set-op?
  [conclusion]
  (and (== (term/term-root conclusion) term/inheritance)
       (or
         (and (== (term/term-get conclusion 1) term/ext-intersection)
              (== (term/term-get conclusion 3) term/ext-set)
              (== (term/term-get conclusion 4) term/ext-set))
         (and (== (term/term-get conclusion 2) term/int-intersection)
              (== (term/term-get conclusion 5) term/int-set)
              (== (term/term-get conclusion 6) term/int-set)))))

(defn- indep-or-dep-variable-appears-once?
  [conclusion]
  (let [var-ids (loop [i 0 result []]
                  (if (>= i term/compound-term-size-max)
                    result
                    (let [id (get conclusion i)]
                      (recur (inc i)
                             (if (or (variable/independent-var? id)
                                     (variable/dependent-var? id))
                               (conj result id)
                               result)))))]
    (when (seq var-ids)
      (let [freqs (frequencies var-ids)]
        (boolean (some #(= (val %) 1) freqs))))))

(defn- declarative-implication-without-indep-var?
  [conclusion]
  (let [root (term/term-root conclusion)]
    (and (or (== root term/implication) (== root term/equivalence))
         (not (variable/has-variable? conclusion true false false)))))

(defn- declarative-impl-with-lh-conj-op?
  ([conclusion] (declarative-impl-with-lh-conj-op? conclusion false))
  ([conclusion recurse?]
   (let [root (term/term-root conclusion)]
     (or
       (and (not recurse?)
            (or (== root term/implication) (== root term/equivalence))
            (== (term/term-get conclusion 1) term/conjunction)
            (let [lh (term/extract-subterm conclusion 1)]
              (declarative-impl-with-lh-conj-op? lh true)))
       (and recurse?
            (== root term/conjunction)
            (let [left (term/extract-subterm conclusion 1)]
              (or (narsese/is-operation? left)
                  (and (== (term/term-root left) term/conjunction)
                       (declarative-impl-with-lh-conj-op? left true)))))))))

(defn valid-conclusion?
  "Check if a derived conclusion passes all NAL_DerivedEvent filters."
  [conclusion nal-level]
  (not (or (atom-appears-twice? conclusion)
           (nested-hol-statement? conclusion)
           (inh-or-sim-has-dep-var? conclusion)
           (junction-not-right-nested? conclusion)
           (invalid-set-op? conclusion)
           (indep-or-dep-variable-appears-once? conclusion)
           (and (>= nal-level 6) (declarative-implication-without-indep-var? conclusion))
           (declarative-impl-with-lh-conj-op? conclusion))))
