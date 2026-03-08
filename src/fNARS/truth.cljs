(ns fNARS.truth
  "Truth value functions matching C ONA's Truth.c exactly.
   Uses TRUTH_EVIDENTIAL_HORIZON = 1.0 and clamping semantics.")

(def ^:const occurrence-eternal -1)

(defn w2c
  "Convert weight to confidence. w / (w + horizon)"
  [w horizon]
  (/ w (+ w horizon)))

(defn c2w
  "Convert confidence to weight. horizon * c / (1 - c)"
  [c horizon]
  (/ (* horizon c) (- 1.0 c)))

(defn truth-expectation
  "Calculate expectation: c * (f - 0.5) + 0.5"
  [{:keys [frequency confidence]}]
  (+ (* confidence (- frequency 0.5)) 0.5))

(defn truth-revision
  "Revision of two truth values with ONA's clamping.
   Handles zero-confidence edge cases and MAX_CONFIDENCE = 0.99."
  [v1 v2 {:keys [horizon max-confidence]}]
  (cond
    (== (:confidence v1) 0.0) v2
    (== (:confidence v2) 0.0) v1
    (and (== max-confidence 1.0) (== (:confidence v1) 1.0)) v1
    (and (== max-confidence 1.0) (== (:confidence v2) 1.0)) v2
    :else
    (let [f1 (:frequency v1) c1 (:confidence v1)
          f2 (:frequency v2) c2 (:confidence v2)
          w1 (c2w c1 horizon)
          w2 (c2w c2 horizon)
          w  (+ w1 w2)]
      {:frequency  (min 1.0 (/ (+ (* w1 f1) (* w2 f2)) w))
       :confidence (min max-confidence (max (max (w2c w horizon) c1) c2))})))

(defn truth-deduction
  "Deduction: f = f1*f2, c = c1*c2*f"
  [v1 v2]
  (let [f (* (:frequency v1) (:frequency v2))]
    {:frequency f
     :confidence (* (:confidence v1) (:confidence v2) f)}))

(defn truth-structural-deduction
  "Structural deduction: deduction(v, {f=1.0, c=RELIANCE}).
   ONA: Truth_StructuralDeduction(v1, v2) = Truth_Deduction(v1, STRUCTURAL_TRUTH)."
  [v config]
  (truth-deduction v {:frequency 1.0 :confidence (:reliance config)}))

(defn truth-abduction
  "Abduction: f = f2, c = w2c(f1*c1*c2)"
  [v1 v2 horizon]
  {:frequency (:frequency v2)
   :confidence (w2c (* (:frequency v1) (:confidence v1) (:confidence v2)) horizon)})

(defn truth-induction
  "Induction: abduction(v2, v1)"
  [v1 v2 horizon]
  (truth-abduction v2 v1 horizon))

(defn truth-intersection
  "Intersection: f = f1*f2, c = c1*c2"
  [v1 v2]
  {:frequency (* (:frequency v1) (:frequency v2))
   :confidence (* (:confidence v1) (:confidence v2))})

(defn truth-eternalize
  "Eternalize: f stays, c = w2c(c)"
  [v horizon]
  {:frequency (:frequency v)
   :confidence (w2c (:confidence v) horizon)})

(defn truth-projection
  "Project truth to target time using decay.
   Eternal truths are returned unchanged."
  [v original-time target-time decay]
  (if (== original-time occurrence-eternal)
    v
    {:frequency (:frequency v)
     :confidence (* (:confidence v)
                    (js/Math.pow decay (js/Math.abs (- target-time original-time))))}))

(defn truth-negation
  "Negation: f = 1-f, c = c"
  [v]
  {:frequency (- 1.0 (:frequency v))
   :confidence (:confidence v)})

(defn truth-goal-deduction
  "Goal deduction with closed-world assumption.
   Returns max confidence of deduction(v1,v2) and negation(deduction(negation(v1),v2))."
  [v1 v2]
  (let [res1 (truth-deduction v1 v2)
        res2 (truth-negation (truth-deduction (truth-negation v1) v2))]
    (if (>= (:confidence res1) (:confidence res2))
      res1
      res2)))

(defn truth-analogy
  "Analogy: f = f1*f2, c = c1*c2*f2"
  [v1 v2]
  {:frequency (* (:frequency v1) (:frequency v2))
   :confidence (* (:confidence v1) (:confidence v2) (:frequency v2))})

(defn truth-comparison
  "Comparison: f = f1*f2 / or(f1,f2), c = w2c(or(f1,f2)*c1*c2)"
  [v1 v2 horizon]
  (let [f1 (:frequency v1) f2 (:frequency v2)
        f0 (- 1.0 (* (- 1.0 f1) (- 1.0 f2)))] ; truth-or
    {:frequency (if (== f0 0.0) 0.0 (/ (* f1 f2) f0))
     :confidence (w2c (* f0 (:confidence v1) (:confidence v2)) horizon)}))

(defn truth-resemblance
  "Resemblance: f = f1*f2, c = c1*c2*or(f1,f2)"
  [v1 v2]
  (let [f1 (:frequency v1) f2 (:frequency v2)
        f-or (- 1.0 (* (- 1.0 f1) (- 1.0 f2)))]
    {:frequency (* f1 f2)
     :confidence (* (:confidence v1) (:confidence v2) f-or)}))

(defn truth-exemplification
  "Exemplification: f = 1.0, c = w2c(f1*f2*c1*c2)"
  [v1 v2 horizon]
  {:frequency 1.0
   :confidence (w2c (* (:frequency v1) (:frequency v2) (:confidence v1) (:confidence v2)) horizon)})

(defn truth-conversion
  "Conversion: f = 1.0, c = w2c(f*c)"
  [v horizon]
  {:frequency 1.0
   :confidence (w2c (* (:frequency v) (:confidence v)) horizon)})

;; --- NAL 1-5 truth functions (uniform 2-arity for rule table) ---

(defn truth-structural-intersection
  "StructuralIntersection: Intersection(v1, STRUCTURAL_TRUTH).
   ONA: Truth_StructuralIntersection(v1, v2) = Truth_Intersection(v1, STRUCTURAL_TRUTH)."
  [v config]
  (truth-intersection v {:frequency 1.0 :confidence (:reliance config)}))

(defn truth-structural-deduction-negated
  "StructuralDeductionNegated: Negation(Deduction(v1, STRUCTURAL_TRUTH)).
   ONA: Truth_Negation(Truth_Deduction(v1, STRUCTURAL_TRUTH), v2)."
  [v config]
  (truth-negation (truth-structural-deduction v config)))

(defn truth-union
  "Union: f = or(f1,f2), c = c1*c2"
  [v1 v2]
  (let [f1 (:frequency v1) f2 (:frequency v2)]
    {:frequency (- 1.0 (* (- 1.0 f1) (- 1.0 f2)))
     :confidence (* (:confidence v1) (:confidence v2))}))

(defn truth-difference
  "Difference: f = f1*(1-f2), c = c1*c2"
  [v1 v2]
  {:frequency (* (:frequency v1) (- 1.0 (:frequency v2)))
   :confidence (* (:confidence v1) (:confidence v2))})

(defn truth-decompose-pnn
  "DecomposePNN: fn = f1*(1-f2), result = {1-fn, fn*c1*c2}"
  [v1 v2]
  (let [fn' (* (:frequency v1) (- 1.0 (:frequency v2)))]
    {:frequency (- 1.0 fn')
     :confidence (* fn' (:confidence v1) (:confidence v2))}))

(defn truth-decompose-npp
  "DecomposeNPP: f = (1-f1)*f2, result = {f, f*c1*c2}"
  [v1 v2]
  (let [f (* (- 1.0 (:frequency v1)) (:frequency v2))]
    {:frequency f
     :confidence (* f (:confidence v1) (:confidence v2))}))

(defn truth-decompose-pnp
  "DecomposePNP: f = f1*(1-f2), result = {f, f*c1*c2}"
  [v1 v2]
  (let [f (* (:frequency v1) (- 1.0 (:frequency v2)))]
    {:frequency f
     :confidence (* f (:confidence v1) (:confidence v2))}))

(defn truth-decompose-ppp
  "DecomposePPP: DecomposeNPP(Negation(v1), v2)"
  [v1 v2]
  (truth-decompose-npp (truth-negation v1) v2))

(defn truth-decompose-nnn
  "DecomposeNNN: fn = (1-f1)*(1-f2), result = {1-fn, fn*c1*c2}"
  [v1 v2]
  (let [fn' (* (- 1.0 (:frequency v1)) (- 1.0 (:frequency v2)))]
    {:frequency (- 1.0 fn')
     :confidence (* fn' (:confidence v1) (:confidence v2))}))

(defn truth-anonymous-analogy
  "AnonymousAnalogy: Analogy(v1, {1.0, w2c(f2*c2)}).
   Page 125 in NAL book."
  [v1 v2 horizon]
  (let [v3 {:frequency 1.0 :confidence (w2c (* (:frequency v2) (:confidence v2)) horizon)}]
    (truth-analogy v1 v3)))

(defn truth-frequency-greater
  "FrequencyGreater: if f1>f2 then {1.0, c1*c2} else {0.0, 0.0}"
  [v1 v2]
  (let [condition (> (:frequency v1) (:frequency v2))]
    {:frequency (if condition 1.0 0.0)
     :confidence (if condition (* (:confidence v1) (:confidence v2)) 0.0)}))

(defn truth-frequency-equal
  "FrequencyEqual: if f1==f2 then {1.0, c1*c2} else {0.0, 0.0}"
  [v1 v2]
  (let [condition (== (:frequency v1) (:frequency v2))]
    {:frequency (if condition 1.0 0.0)
     :confidence (if condition (* (:confidence v1) (:confidence v2)) 0.0)}))

(defn truth-equal
  "Check if two truth values are equal."
  [v1 v2]
  (and (== (:frequency v1) (:frequency v2))
       (== (:confidence v1) (:confidence v2))))

(def default-truth
  "Default truth value for input events."
  {:frequency 1.0 :confidence 0.9})
