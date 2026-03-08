(ns fNARS.narsese
  "Narsese term construction and inspection utilities.
   Port of Narsese.c utility functions (NOT the parser - that's in parser.cljs)."
  (:require [fNARS.term :as term]))

(defn make-sequence
  "Create a sequence term (&/ a b). Returns {:term t :success? bool}."
  [a b]
  (let [base (term/atomic-term term/sequence*)
        t (-> base
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
      (assoc 2 term/set-terminator)))

(defn is-operation?
  "Check if term is an operation: ^op or <(*,{SELF},x) --> ^op>."
  [t]
  (or (term/is-operator? (term/term-root t))
      (and (= (term/term-root t) term/inheritance)
           (= (get t 1) term/product)
           (term/is-operator? (get t 2))
           (= (get t 3) term/ext-set))))

(defn is-executable-operation?
  "Check if term is an executable operation (has SELF or variable in agent slot)."
  [t]
  (and (is-operation? t)
       (or (term/is-operator? (term/term-root t))
           (= (get t 7) :SELF)
           (and (keyword? (get t 7))
                (let [n (name (get t 7))]
                  (and (pos? (count n))
                       (contains? #{\$ \# \?} (first n))))))))

(defn get-operation-atom
  "Extract the ^op atom from any operation form.
   Handles sequences: (a &/ ^op) -> ^op."
  [t]
  (cond
    ;; Sequence: check right child
    (= (term/term-root t) term/sequence*)
    (let [right (term/extract-subterm t 2)]
      (when-not (= (term/term-root right) term/sequence*)
        (get-operation-atom right)))

    ;; Atomic operator
    (term/is-operator? (term/term-root t))
    (term/term-root t)

    ;; Full operation <(* ...) --> ^op>
    (is-operation? t)
    (get t 2)

    :else nil))

(defn get-precondition-without-op
  "Remove operation from sequence tail.
   (&/ precondition ^op) -> precondition (recursively)."
  [precondition]
  (if (= (term/term-root precondition) term/sequence*)
    (let [right (term/extract-subterm precondition 2)]
      (if (is-operation? right)
        (let [left (term/extract-subterm precondition 1)]
          (get-precondition-without-op left))
        precondition))
    precondition))

(defn sequence-length
  "Count the number of components in a sequence term."
  [t]
  (if (= (term/term-root t) term/sequence*)
    (+ (sequence-length (term/extract-subterm t 1))
       (sequence-length (term/extract-subterm t 2)))
    1))

(defn has-operation?
  "Check if term contains any operation."
  [t]
  (boolean (get-operation-atom t)))

(defn make-compound-op-term
  "Build compound operation term: <(* {SELF} arg) --> ^op>."
  [op-atom arg-term]
  (make-inheritance
    (make-product
      (make-ext-set (term/atomic-term :SELF))
      arg-term)
    (term/atomic-term op-atom)))

(defn extract-op-arg
  "Extract the argument term from a compound operation <(* {SELF} arg) --> ^op>.
   Returns nil for bare operators."
  [op-term]
  (when (and (= (term/term-root op-term) term/inheritance)
             (= (get op-term 1) term/product))
    (term/extract-subterm op-term 4)))

(defn get-operation-term-from-subject
  "Extract the full operation term from an implication's subject.
   For (A &/ ^op) returns ^op or <({SELF}*arg) --> ^op>.
   For bare operations, returns the operation itself.
   Matches ONA Narsese_getOperationTerm for MAX_COMPOUND_OP_LEN=1."
  [imp-subject]
  (cond
    (= (term/term-root imp-subject) term/sequence*)
    (let [right (term/extract-subterm imp-subject 2)]
      (when (is-operation? right) right))
    (is-operation? imp-subject) imp-subject
    :else nil))

