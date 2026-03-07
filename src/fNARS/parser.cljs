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
   <term> = statement | sequence | conjunction | product
          | negation | ext-set | int-set | operation | atom
   statement = <'<'> <ws>? term <ws> copula <ws> term <ws>? <'>'>
   copula = '-->' | '<->' | '=/>' | '==>' | '<=>'
   sequence = <'(&/'> (<ws> term)+ <ws>? <')'>
   conjunction = <'(&&'> (<ws> term)+ <ws>? <')'>
   product = <'(*'> <ws> term <ws> term <ws>? <')'>
   negation = <'(--'> <ws> term <ws>? <')'>
   ext-set = <'{'> term <'}'>
   int-set = <'['> term <']'>
   operation = <'('> operator <')'>
   operator = #'\\^[a-zA-Z_][a-zA-Z0-9_]*'
   atom = variable | word | operator
   variable = #'[\\$#\\?][a-zA-Z0-9]+'
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
               "-->" term/INHERITANCE
               "<->" term/SIMILARITY
               "=/>" term/TEMPORAL-IMPLICATION
               "==>" term/IMPLICATION
               "<=>" term/EQUIVALENCE))
   :sequence (fn [& children]
               (build-binary-compound term/SEQUENCE children))
   :conjunction (fn [& children]
                  (build-binary-compound term/CONJUNCTION children))
   :product (fn [left right]
              (-> (term/atomic-term term/PRODUCT)
                  (term/override-subterm 1 left)
                  (term/override-subterm 2 right)))
   :negation (fn [child]
               (-> (term/atomic-term term/NEGATION)
                   (term/override-subterm 1 child)))
   :ext-set (fn [child]
              (-> (term/atomic-term term/EXT-SET)
                  (term/override-subterm 1 child)
                  (assoc 2 term/SET-TERMINATOR)))
   :int-set (fn [child]
              (-> (term/atomic-term term/INT-SET)
                  (term/override-subterm 1 child)
                  (assoc 2 term/SET-TERMINATOR)))
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
