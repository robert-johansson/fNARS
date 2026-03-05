(ns fNARS.term
  "Term representation using flat vectors matching ONA's binary heap encoding.
   Atoms are keywords instead of unsigned chars.
   Root at index 0, left child at 2i+1, right child at 2i+2.")

(def ^:const compound-term-size-max 64)

;; Copula keywords - use valid ClojureScript keyword syntax
(def INHERITANCE :cop-inheritance)
(def SIMILARITY :cop-similarity)
(def IMPLICATION :cop-implication)
(def TEMPORAL-IMPLICATION :cop-temporal-implication)
(def EQUIVALENCE :cop-equivalence)
(def CONJUNCTION :cop-conjunction)
(def SEQUENCE :cop-sequence)
(def NEGATION :cop-negation)
(def PRODUCT :cop-product)
(def EXT-SET :cop-ext-set)
(def INT-SET :cop-int-set)
(def SET-TERMINATOR :cop-set-terminator)
(def DISJUNCTION :cop-disjunction)
(def EXT-IMAGE1 :cop-ext-image1)
(def EXT-IMAGE2 :cop-ext-image2)
(def INT-IMAGE1 :cop-int-image1)
(def INT-IMAGE2 :cop-int-image2)
(def HAS-CONTINUOUS-PROPERTY :cop-has-continuous-property)
(def EXT-DIFFERENCE :cop-ext-difference)
(def INT-DIFFERENCE :cop-int-difference)
(def EXT-INTERSECTION :cop-ext-intersection)
(def INT-INTERSECTION :cop-int-intersection)

(def copula-set
  "Set of all copula keywords."
  #{INHERITANCE SIMILARITY IMPLICATION TEMPORAL-IMPLICATION EQUIVALENCE
    CONJUNCTION SEQUENCE NEGATION PRODUCT EXT-SET INT-SET SET-TERMINATOR
    DISJUNCTION EXT-IMAGE1 EXT-IMAGE2 INT-IMAGE1 INT-IMAGE2
    HAS-CONTINUOUS-PROPERTY EXT-DIFFERENCE INT-DIFFERENCE
    EXT-INTERSECTION INT-INTERSECTION})

(defn copula?
  "Check if an atom is a specific copula."
  [atom copula]
  (= atom copula))

(defn is-copula?
  "Check if an atom is any copula."
  [atom]
  (contains? copula-set atom))

(def empty-term
  "An empty term (all nil slots)."
  (vec (repeat compound-term-size-max nil)))

(defn make-term
  "Create a term from a sparse map of {index -> atom}.
   Fills remaining slots with nil up to compound-term-size-max."
  [atoms-map]
  (reduce-kv
    (fn [t idx atom] (assoc t idx atom))
    empty-term
    atoms-map))

(defn atomic-term
  "Create an atomic term (just root atom, rest nil)."
  [atom]
  (assoc empty-term 0 atom))

(defn term-root
  "Get the root atom of a term."
  [term]
  (get term 0))

(defn left-child-idx [i] (+ (* 2 i) 1))
(defn right-child-idx [i] (+ (* 2 i) 2))

(defn- override-subterm-recursive
  "Recursively copy subterm rooted at j into term at position i.
   Matches C's Term_RelativeOverride."
  [term i subterm j]
  (if (or (>= i compound-term-size-max) (>= j compound-term-size-max))
    term
    (let [term (assoc term i (get subterm j))
          left-sub (left-child-idx j)
          right-sub (right-child-idx j)
          left-dst (left-child-idx i)
          right-dst (right-child-idx i)
          term (if (and (< left-sub compound-term-size-max)
                        (some? (get subterm left-sub)))
                 (override-subterm-recursive term left-dst subterm left-sub)
                 term)
          term (if (and (< right-sub compound-term-size-max)
                        (some? (get subterm right-sub)))
                 (override-subterm-recursive term right-dst subterm right-sub)
                 term)]
      term)))

(defn override-subterm
  "Override the subtree at position i in term with subterm (rooted at 0).
   Returns new term."
  [term i subterm]
  (override-subterm-recursive term i subterm 0))

(defn extract-subterm
  "Extract subtree at position j from term, re-rooting it at 0.
   Matches C's Term_ExtractSubterm."
  [term j]
  (override-subterm-recursive empty-term 0 term j))

(defn term-complexity
  "Count non-nil atoms in the term."
  [term]
  (count (filter some? term)))

(defn term-equal
  "Check if two terms are equal."
  [a b]
  (= a b))

(defn has-atom
  "Check if term contains a specific atom anywhere."
  [term atom]
  (some #(= % atom) term))

(defn term-atoms
  "Get all non-nil atoms in a term."
  [term]
  (filter some? term))

(defn simple-atom?
  "Check if an atom is a 'simple' atom (not a copula, not nil, not a variable).
   Simple atoms are user-defined keywords like :cat, :green, :SELF."
  [atom]
  (and (keyword? atom)
       (not (is-copula? atom))
       (let [n (name atom)
             c (when (pos? (count n)) (first n))]
         (and c
              (not= c \$)
              (not= c \#)
              (not= c \?)))))

(defn is-operator?
  "Check if an atom is an operator (starts with ^)."
  [atom]
  (and (keyword? atom)
       (let [n (name atom)]
         (and (> (count n) 1)
              (= (first n) \^)))))
