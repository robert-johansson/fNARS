(ns fNARS.nal
  (:require [fNARS.truth-values :as t]))

(defn revision
  [[statement [f1 c1]] [_ [f2 c2]]]
  [statement (t/f-rev f1 c1 f2 c2)])

(defn choice
  [[statement1 [f1 c1]] [statement2 [f2 c2]]]
  (let [e1 (t/f-exp f1 c1)
        e2 (t/f-exp f2 c2)]
    (if (= statement1 statement2)
      (if (>= c1 c2)
        [statement1 [f1 c1]]    ;; choice([S, [F1, C1]], [S, [_F2, C2]], [S, [F1, C1]]) :- C1 >= C2, !
        [statement1 [f2 c2]])   ;; choice([S, [_F1, C1]], [S, [F2, C2]], [S, [F2, C2]]) :- C1 < C2, !.
      (if (>= e1 e2)
        [statement1 [f1 c1]]
        [statement2 [f2 c2]]))))

(defn rule-type
  [[[_ S1 P1] _] [[_ S2 P2] _]]
  (cond (and (= S1 S2) (= P1 P2)) :revision
        (= S1 P2) :deduction
        (= P1 P2) :abduction
        (= S1 S2) :induction
        (= P1 S2) :exemplification
        :else :undefined))

;; R2 ((S --> M), (M --> P), |-, (S --> P), Truth_Deduction)
;; R2 ((A --> B), (A --> C), |-, (C --> B), Truth_Induction)
;; R2 ((A --> C), (B --> C), |-, (B --> A), Truth_Abduction)
;; R2 ((A --> B), (B --> C), |-, (C --> A), Truth_Exemplification)

;; NAL-1

;; R2( (S --> M), (M --> P), |-, (S --> P), Truth_Deduction )
(defn infer-ded
  [[statement1 [f1 c1]] [statement2 [f2 c2]]]
  (let [copula (first statement1)
        P (nth statement1 2)
        S (nth statement2 1)]
    [(list copula S P) (t/f-ded f1 c1 f2 c2)]))

;; R2( (A --> B), (A --> C), |-, (C --> B), Truth_Abduction )
(defn infer-abd
  [[statement1 [f1 c1]] [statement2 [f2 c2]]]
  (let [copula (first statement1)
        P (nth statement1 1)
        S (nth statement2 1)]
    [(list copula S P) (t/f-abd f1 c1 f2 c2)]))

;; R2( (A --> C), (B --> C), |-, (B --> A), Truth_Induction )
(defn infer-ind
  [[statement1 [f1 c1]] [statement2 [f2 c2]]]
  (let [copula (first statement1)
        P (nth statement1 2)
        S (nth statement2 2)]
    [(list copula S P) (t/f-ind f1 c1 f2 c2)]))

;; R2( (A --> B), (B --> C), |-, (C --> A), Truth_Exemplification )
(defn infer-exe
  [[statement1 [f1 c1]] [statement2 [f2 c2]]]
  (let [copula (first statement1)
        P (nth statement1 1)
        S (nth statement2 2)]
    [(list copula S P) (t/f-exe f1 c1 f2 c2)]))

;; immediate inference

;; inference ([inheritance (S, P), T1], [inheritance (P, S), T]) :-
;;   f_cnv (T1, T) .

(defn infer-cnv
  [[statement [f1 c1]]]
  (let [copula (first statement)
        P (nth statement 1)
        S (nth statement 2)]
    [(list copula S P) (t/f-cnv f1 c1)]))

(defn inference
  ([sentence]
   (infer-cnv sentence))
  ([sentence1 sentence2]
   (case (rule-type sentence1 sentence2)
     :revision (revision sentence1 sentence2)
     :deduction (infer-ded sentence1 sentence2)
     :abduction (infer-abd sentence1 sentence2)
     :induction (infer-ind sentence1 sentence2)
     :exemplification (infer-exe sentence1 sentence2))))
