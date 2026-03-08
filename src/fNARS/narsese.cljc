(ns fNARS.narsese
  "Narsese term construction and inspection utilities."
  (:require [fNARS.term :as term]
            [fNARS.atom-registry :as ar]))

(defn make-sequence
  "Create a sequence term (&/ a b). Returns {:term t :success? bool}."
  [a b]
  (let [t (-> (term/atomic-term term/sequence*)
              (term/override-subterm 1 a)
              (term/override-subterm 2 b))]
    {:term t :success? true}))

(defn make-implication-term
  "Create a temporal implication term (=/> a b)."
  [a b]
  (-> (term/atomic-term term/temporal-implication)
      (term/override-subterm 1 a)
      (term/override-subterm 2 b)))

(defn make-inheritance
  "Create an inheritance term (--> a b)."
  [a b]
  (-> (term/atomic-term term/inheritance)
      (term/override-subterm 1 a)
      (term/override-subterm 2 b)))

(defn make-product
  "Create a product term (* a b)."
  [a b]
  (-> (term/atomic-term term/product)
      (term/override-subterm 1 a)
      (term/override-subterm 2 b)))

(defn make-ext-set
  "Create an extensional set term {a}."
  [a]
  (-> (term/atomic-term term/ext-set)
      (term/override-subterm 1 a)
      (term/term-assoc 2 term/set-terminator)))

(defn is-operation?
  "Check if term is an operation: ^op or <(*,{SELF},x) --> ^op>."
  [t]
  (let [root (term/term-root t)]
    (or (term/is-operator? root)
        (and (== root term/inheritance)
             (== (term/term-get t 1) term/product)
             (term/is-operator? (term/term-get t 2))
             (== (term/term-get t 3) term/ext-set)))))

(defn is-executable-operation?
  "Check if term is an executable operation (has SELF or variable in agent slot)."
  [t]
  (and (is-operation? t)
       (let [root (term/term-root t)]
         (or (term/is-operator? root)
             (let [slot7 (term/term-get t 7)]
               (or (== slot7 (ar/intern-atom :SELF))
                   (ar/variable-id? slot7)))))))

(defn get-operation-atom
  "Extract the ^op atom ID from any operation form."
  [t]
  (let [root (term/term-root t)]
    (cond
      (== root term/sequence*)
      (let [right (term/extract-subterm t 2)]
        (when-not (== (term/term-root right) term/sequence*)
          (get-operation-atom right)))

      (term/is-operator? root)
      root

      (is-operation? t)
      (term/term-get t 2)

      :else nil)))

(defn get-precondition-without-op
  "Remove operation from sequence tail."
  [precondition]
  (if (== (term/term-root precondition) term/sequence*)
    (let [right (term/extract-subterm precondition 2)]
      (if (is-operation? right)
        (let [left (term/extract-subterm precondition 1)]
          (get-precondition-without-op left))
        precondition))
    precondition))

(defn sequence-length
  "Count the number of components in a sequence term."
  [t]
  (if (== (term/term-root t) term/sequence*)
    (+ (sequence-length (term/extract-subterm t 1))
       (sequence-length (term/extract-subterm t 2)))
    1))

(defn has-operation?
  "Check if term contains any operation."
  [t]
  (boolean (get-operation-atom t)))

(defn make-compound-op-term
  "Build compound operation term: <(* {SELF} arg) --> ^op>."
  [op-atom-id arg-term]
  (make-inheritance
    (make-product
      (make-ext-set (term/atomic-term :SELF))
      arg-term)
    (term/atomic-term op-atom-id)))

(defn extract-op-arg
  "Extract the argument term from a compound operation."
  [op-term]
  (when (and (== (term/term-root op-term) term/inheritance)
             (== (term/term-get op-term 1) term/product))
    (term/extract-subterm op-term 4)))

(defn get-operation-term-from-subject
  "Extract the full operation term from an implication's subject."
  [imp-subject]
  (cond
    (== (term/term-root imp-subject) term/sequence*)
    (let [right (term/extract-subterm imp-subject 2)]
      (when (is-operation? right) right))
    (is-operation? imp-subject) imp-subject
    :else nil))
