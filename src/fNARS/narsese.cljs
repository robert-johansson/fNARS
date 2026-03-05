(ns fNARS.narsese
  "Narsese term construction and inspection utilities.
   Port of Narsese.c utility functions (NOT the parser - that's in parser.cljs)."
  (:require [fNARS.term :as term]))

(defn make-sequence
  "Create a sequence term (&/ a b). Returns {:term t :success? bool}."
  [a b]
  (let [base (term/atomic-term term/SEQUENCE)
        t (-> base
              (term/override-subterm 1 a)
              (term/override-subterm 2 b))]
    {:term t :success? true}))

(defn make-implication-term
  "Create a temporal implication term (=/> a b)."
  [a b]
  (-> (term/atomic-term term/TEMPORAL-IMPLICATION)
      (term/override-subterm 1 a)
      (term/override-subterm 2 b)))

(defn make-inheritance
  "Create an inheritance term (--> a b)."
  [a b]
  (-> (term/atomic-term term/INHERITANCE)
      (term/override-subterm 1 a)
      (term/override-subterm 2 b)))

(defn make-product
  "Create a product term (* a b)."
  [a b]
  (-> (term/atomic-term term/PRODUCT)
      (term/override-subterm 1 a)
      (term/override-subterm 2 b)))

(defn make-ext-set
  "Create an extensional set term {a}."
  [a]
  (-> (term/atomic-term term/EXT-SET)
      (term/override-subterm 1 a)
      (assoc 2 term/SET-TERMINATOR)))

(defn is-operation?
  "Check if term is an operation: ^op or <(*,{SELF},x) --> ^op>."
  [t]
  (or (term/is-operator? (term/term-root t))
      (and (term/copula? (term/term-root t) term/INHERITANCE)
           (term/copula? (get t 1) term/PRODUCT)
           (term/is-operator? (get t 2))
           (term/copula? (get t 3) term/EXT-SET))))

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
    (term/copula? (term/term-root t) term/SEQUENCE)
    (let [right (term/extract-subterm t 2)]
      (when-not (term/copula? (term/term-root right) term/SEQUENCE)
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
  (if (term/copula? (term/term-root precondition) term/SEQUENCE)
    (let [right (term/extract-subterm precondition 2)]
      (if (is-operation? right)
        (let [left (term/extract-subterm precondition 1)]
          (get-precondition-without-op left))
        precondition))
    precondition))

(defn sequence-length
  "Count the number of components in a sequence term."
  [t]
  (if (term/copula? (term/term-root t) term/SEQUENCE)
    (+ (sequence-length (term/extract-subterm t 1))
       (sequence-length (term/extract-subterm t 2)))
    1))

(defn has-operation?
  "Check if term contains any operation."
  [t]
  (boolean (get-operation-atom t)))

(defn is-simple-atom?
  "Check if an atom is a simple atom (not copula, not nil, not variable prefix).
   Simple atoms are user-defined terms like :green, :left, :^pick."
  [atom]
  (term/simple-atom? atom))
