(ns fNARS.term
  "Term representation using vectors of integer atom IDs.
   Binary heap encoding: root at index 0, left child at 2i+1, right child at 2i+2.
   Atoms are integer IDs managed by atom-registry. 0 = empty slot."
  (:require [fNARS.atom-registry :as ar]))

(def ^:const compound-term-size-max 64)

;; -- Copula Constants (integer IDs) --
(def inheritance ar/id-inheritance)
(def similarity ar/id-similarity)
(def implication ar/id-implication)
(def temporal-implication ar/id-temporal-implication)
(def equivalence ar/id-equivalence)
(def conjunction ar/id-conjunction)
(def sequence* ar/id-sequence)
(def negation ar/id-negation)
(def product ar/id-product)
(def ext-set ar/id-ext-set)
(def int-set ar/id-int-set)
(def set-terminator ar/id-set-terminator)
(def disjunction ar/id-disjunction)
(def ext-image1 ar/id-ext-image1)
(def ext-image2 ar/id-ext-image2)
(def int-image1 ar/id-int-image1)
(def int-image2 ar/id-int-image2)
(def set-element ar/id-set-element)
(def has-continuous-property ar/id-has-continuous-property)
(def ext-difference ar/id-ext-difference)
(def int-difference ar/id-int-difference)
(def ext-intersection ar/id-ext-intersection)
(def int-intersection ar/id-int-intersection)

;; -- Core Term Functions --

(def empty-term
  "An empty term (all zero slots)."
  (vec (repeat compound-term-size-max 0)))

(defn atomic-term
  "Create an atomic term. Accepts a keyword (auto-interned) or integer ID."
  [atom-or-kw]
  (let [id (if (keyword? atom-or-kw)
             (ar/intern-atom atom-or-kw)
             (int atom-or-kw))]
    (assoc empty-term 0 id)))

(defn term-root
  "Get the root atom ID of a term."
  [term]
  (get term 0))

(defn term-get
  "Get the atom ID at position i in a term."
  [term i]
  (get term (int i)))

(defn term-assoc
  "Create a new term with atom ID v at position i."
  [term i v]
  (assoc term (int i) (int v)))

(defn left-child-idx [i] (+ (* 2 i) 1))
(defn right-child-idx [i] (+ (* 2 i) 2))

(defn override-subterm
  "Override the subtree at position i in term with subterm (rooted at 0).
   Returns new term."
  [term i subterm]
  (letfn [(copy [t dst src]
            (if (or (>= dst compound-term-size-max) (>= src compound-term-size-max))
              t
              (let [v (get subterm src)]
                (if (zero? v)
                  (assoc t dst 0)
                  (let [t (assoc t dst v)
                        left-src (left-child-idx src)
                        right-src (right-child-idx src)
                        left-dst (left-child-idx dst)
                        right-dst (right-child-idx dst)
                        t (if (and (< left-src compound-term-size-max)
                                   (pos? (get subterm left-src)))
                            (copy t left-dst left-src)
                            t)
                        t (if (and (< right-src compound-term-size-max)
                                   (pos? (get subterm right-src)))
                            (copy t right-dst right-src)
                            t)]
                    t)))))]
    (copy term i 0)))

(defn extract-subterm
  "Extract subtree at position j from term, re-rooting it at 0."
  [term j]
  (override-subterm empty-term 0
    ;; Create a "view" that maps 0->j, etc.
    ;; Actually we can just use override-subterm with swapped args
    term j))

;; Override extract-subterm to properly re-root
(defn extract-subterm
  "Extract subtree at position j from term, re-rooting it at 0."
  [term j]
  (letfn [(copy [t dst src]
            (if (or (>= dst compound-term-size-max) (>= src compound-term-size-max))
              t
              (let [v (get term src)]
                (if (zero? v)
                  t
                  (let [t (assoc t dst v)
                        left-src (left-child-idx src)
                        right-src (right-child-idx src)
                        left-dst (left-child-idx dst)
                        right-dst (right-child-idx dst)
                        t (if (and (< left-src compound-term-size-max)
                                   (pos? (get term left-src)))
                            (copy t left-dst left-src)
                            t)
                        t (if (and (< right-src compound-term-size-max)
                                   (pos? (get term right-src)))
                            (copy t right-dst right-src)
                            t)]
                    t)))))]
    (copy empty-term 0 j)))

(defn term-complexity
  "Count non-zero atoms in the term."
  [term]
  (reduce (fn [c v] (if (pos? v) (inc c) c)) 0 term))

(defn term-equal
  "Check if two terms are equal."
  [a b]
  (= a b))

(defn has-atom
  "Check if term contains a specific atom ID anywhere."
  [term atom-id]
  (let [id (if (keyword? atom-id) (ar/intern-atom atom-id) (int atom-id))]
    (some #(== % id) term)))

(defn term-atoms
  "Get all non-zero atom IDs in a term."
  [term]
  (filterv pos? term))

(def copula-set
  "Set of all copula integer IDs."
  (set (range ar/copula-min (inc ar/copula-max))))

(defn is-copula?
  "Check if an atom ID is any copula."
  [atom-id]
  (ar/copula-id? atom-id))

(defn simple-atom?
  "Check if an atom ID is a 'simple' atom (not copula, not zero, not variable)."
  [atom-id]
  (ar/simple-atom-id? atom-id))

(defn is-operator?
  "Check if an atom ID is an operator (e.g. ^pick)."
  [atom-id]
  (ar/operator-id? atom-id))
