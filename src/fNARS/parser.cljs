(ns fNARS.parser
  "Narsese parser using instaparse.
   Parses Narsese sentences into {:term [...] :type :truth :tense} maps."
  (:require [instaparse.core :as insta]
            [fNARS.term :as term]
            [fNARS.variable :as variable]
            [clojure.string :as str]))

;; -- Grammar --
;; Rules prefixed with <> are hidden (suppressed from parse tree).
;; Whitespace is consumed via <ws> so it never appears in the tree.

(def narsese-grammar
  "sentence = dt? <ws>? term <ws>? punctuation <ws>? tense? <ws>? truth?
   dt = <'dt='> number
   punctuation = '.' | '!' | '?'
   tense = ':|:' | ':\\\\:' | ':/:'
   <truth> = brace-truth | pct-truth
   brace-truth = <'{'> <ws>? number <ws> number <ws>? <'}'>
   pct-truth = <'%'> number (<';'> number)? <'%'>
   <term> = statement | sequence | conjunction | disjunction
          | binary-compound
          | negation | ext-set | int-set | operation | atom
   statement = <'<'> <ws>? term <ws> copula <ws> term <ws>? <'>'>
   copula = '-->' | '<->' | '|->'\n       | '=/>' | '==>' | '<=>'
   sequence = <'(&/'> (<ws> term)+ <ws>? <')'>
   conjunction = <'(&&'> (<ws> term)+ <ws>? <')'>
   disjunction = <'(||'> (<ws> term)+ <ws>? <')'>
   binary-compound = <'('> <ws>? term <ws> compound-op <ws> term <ws>? <')'>
                   | <'(*'> <ws> term <ws> term <ws>? <')'>
   <compound-op> = product-op | ext-intersection-op | int-intersection-op
                 | ext-difference-op | int-difference-op
                 | ext-image1-op | ext-image2-op | int-image1-op | int-image2-op
   product-op = '*'
   ext-intersection-op = '&'
   int-intersection-op = '|'
   ext-difference-op = '-'
   int-difference-op = '~'
   ext-image1-op = '/1'
   ext-image2-op = '/2'
   int-image1-op = '\\\\1'
   int-image2-op = '\\\\2'
   negation = <'(--'> <ws> term <ws>? <')'>
   ext-set = <'{'> <ws>? term (<ws> term)* <ws>? <'}'>
   int-set = <'['> <ws>? term (<ws> term)* <ws>? <']'>
   operation = <'('> operator <')'>
   operator = #'\\^[a-zA-Z_][a-zA-Z0-9_]*'
   atom = variable | symbol-atom | word | operator
   variable = #'[\\$#\\?][a-zA-Z0-9]+'
   symbol-atom = '+' | '='
   word = #'[a-zA-Z][a-zA-Z0-9_]*'
   number = #'-?[0-9]+\\.?[0-9]*'
   <ws> = #'\\s+'")

(def parser (insta/parser narsese-grammar))

;; -- Tree Transform --

(defn- build-binary-compound
  "Build a left-nested binary compound from copula keyword and list of children."
  [copula-kw children]
  (reduce
    (fn [left right]
      (-> (term/atomic-term copula-kw)
          (term/override-subterm 1 left)
          (term/override-subterm 2 right)))
    children))

(defn- build-set
  "Build a set term. 1 element: {A @}. 2 elements: {A B}.
   3+ elements: left-nest with set-element (.)."
  [set-copula children]
  (case (count children)
    1 (-> (term/atomic-term set-copula)
          (term/override-subterm 1 (first children))
          (assoc 2 term/set-terminator))
    2 (-> (term/atomic-term set-copula)
          (term/override-subterm 1 (first children))
          (term/override-subterm 2 (second children)))
    ;; 3+: left-nest elements with set-element, rightmost is last child
    (let [nested (reduce
                   (fn [left right]
                     (-> (term/atomic-term term/set-element)
                         (term/override-subterm 1 left)
                         (term/override-subterm 2 right)))
                   (butlast children))]
      (-> (term/atomic-term set-copula)
          (term/override-subterm 1 nested)
          (term/override-subterm 2 (last children))))))

(def transform-rules
  {:sentence (fn [& args]
               (let [m (reduce
                         (fn [acc x]
                           (cond
                             (and (vector? x) (= :dt (first x)))
                             (assoc acc :occurrence-time-offset (second x))

                             (and (vector? x) (= :punctuation (first x)))
                             (assoc acc :type (case (second x)
                                               "." :belief
                                               "!" :goal
                                               "?" :question))

                             (and (vector? x) (= :tense (first x)))
                             (assoc acc :tense (case (second x)
                                                 ":|:" :present
                                                 ":\\:" :past
                                                 ":/:" :future))

                             (and (vector? x) (= :truth (first x)))
                             (assoc acc :truth (second x))

                             (vector? x)
                             (assoc acc :term x)

                             :else acc))
                         {:type :belief
                          :tense :eternal
                          :truth {:frequency 1.0 :confidence 0.9}
                          :occurrence-time-offset 0.0}
                         args)]
                 m))
   :dt (fn [n] [:dt n])
   :punctuation (fn [p] [:punctuation p])
   :tense (fn [t] [:tense t])
   :brace-truth (fn [f c] [:truth {:frequency f :confidence c}])
   :pct-truth (fn
                ([f] [:truth {:frequency f :confidence 0.9}])
                ([f c] [:truth {:frequency f :confidence c}]))
   :number (fn [s] (js/parseFloat s))
   :statement (fn [left copula right]
                (-> (term/atomic-term copula)
                    (term/override-subterm 1 left)
                    (term/override-subterm 2 right)))
   :copula (fn [s]
             (case s
               "-->" term/inheritance
               "<->" term/similarity
               "|->" term/has-continuous-property
               "=/>" term/temporal-implication
               "==>" term/implication
               "<=>" term/equivalence))
   :sequence (fn [& children]
               (build-binary-compound term/sequence* children))
   :conjunction (fn [& children]
                  (build-binary-compound term/conjunction children))
   :disjunction (fn [& children]
                  (build-binary-compound term/disjunction children))
   :binary-compound (fn
                      ([left cop right]
                       (-> (term/atomic-term cop)
                           (term/override-subterm 1 left)
                           (term/override-subterm 2 right)))
                      ([left right] ;; (* a b) prefix form
                       (-> (term/atomic-term term/product)
                           (term/override-subterm 1 left)
                           (term/override-subterm 2 right))))
   :product-op (constantly term/product)
   :ext-intersection-op (constantly term/ext-intersection)
   :int-intersection-op (constantly term/int-intersection)
   :ext-difference-op (constantly term/ext-difference)
   :int-difference-op (constantly term/int-difference)
   :ext-image1-op (constantly term/ext-image1)
   :ext-image2-op (constantly term/ext-image2)
   :int-image1-op (constantly term/int-image1)
   :int-image2-op (constantly term/int-image2)
   :negation (fn [child]
               (-> (term/atomic-term term/negation)
                   (term/override-subterm 1 child)))
   :ext-set (fn [& children]
              (build-set term/ext-set children))
   :int-set (fn [& children]
              (build-set term/int-set children))
   :symbol-atom (fn [s] (term/atomic-term (keyword s)))
   :operation (fn [op-term] op-term)
   :operator (fn [s] (term/atomic-term (keyword s)))
   :atom identity
   :variable (fn [s] (term/atomic-term (keyword s)))
   :word (fn [s] (term/atomic-term (keyword s)))})

;; -- Public API --

(defn parse-narsese
  "Parse a complete Narsese sentence.
   Returns {:term [...] :type :belief/:goal/:question
            :truth {:frequency f :confidence c}
            :tense :eternal/:present/:past/:future
            :occurrence-time-offset n}
   or nil if parsing fails."
  [input]
  (let [result (parser (str/trim input))]
    (when-not (insta/failure? result)
      (let [transformed (insta/transform transform-rules result)]
        (when (map? transformed)
          (update transformed :term variable/normalize-variables))))))
