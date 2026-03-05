(ns fNARS.parser
  "Narsese parser matching ONA's Narsese.c parsing pipeline.
   Three stages:
   1. Replace multi-char copulas with canonical keywords
   2. Tokenize and convert infix to prefix
   3. Build binary heap term from prefix tokens"
  (:require [fNARS.term :as term]
            [fNARS.variable :as variable]
            [clojure.string :as str]))

;; -- Copula replacement map --
(def copula-replacements
  {"-->" term/INHERITANCE
   "<->" term/SIMILARITY
   "==>" term/IMPLICATION
   "=/>" term/TEMPORAL-IMPLICATION
   "<=>" term/EQUIVALENCE
   "&/"  term/SEQUENCE
   "&&"  term/CONJUNCTION
   "||"  term/DISJUNCTION
   "--"  term/NEGATION
   "|->" term/HAS-CONTINUOUS-PROPERTY
   "/1"  term/EXT-IMAGE1
   "/2"  term/EXT-IMAGE2
   "\\1" term/INT-IMAGE1
   "\\2" term/INT-IMAGE2})

(defn- tokenize-narsese
  "Tokenize a Narsese string into a sequence of tokens.
   Handles: <...>, {..}, [...], (,..), copulas, punctuation, truth values."
  [input]
  (let [s (str/trim input)]
    ;; Use a simple state machine to tokenize
    (loop [i 0
           tokens []
           current ""]
      (if (>= i (count s))
        (let [tokens (if (seq current) (conj tokens current) tokens)]
          tokens)
        (let [c (nth s i)]
          (case c
            \space
            (recur (inc i)
                   (if (seq current) (conj tokens current) tokens)
                   "")

            \(
            (recur (inc i)
                   (-> (if (seq current) (conj tokens current) tokens)
                       (conj "("))
                   "")

            \)
            (recur (inc i)
                   (-> (if (seq current) (conj tokens current) tokens)
                       (conj ")"))
                   "")

            \<
            ;; Check for copulas: <->, <=>, <
            (cond
              (and (< (+ i 2) (count s))
                   (= (subs s i (+ i 3)) "<->"))
              (recur (+ i 3)
                     (-> (if (seq current) (conj tokens current) tokens)
                         (conj "<->"))
                     "")

              (and (< (+ i 2) (count s))
                   (= (subs s i (+ i 3)) "<=>"))
              (recur (+ i 3)
                     (-> (if (seq current) (conj tokens current) tokens)
                         (conj "<=>"))
                     "")

              :else
              (recur (inc i)
                     (-> (if (seq current) (conj tokens current) tokens)
                         (conj "<"))
                     ""))

            \>
            (recur (inc i)
                   (-> (if (seq current) (conj tokens current) tokens)
                       (conj ">"))
                   "")

            \{
            (recur (inc i)
                   (-> (if (seq current) (conj tokens current) tokens)
                       (conj "{"))
                   "")

            \}
            (recur (inc i)
                   (-> (if (seq current) (conj tokens current) tokens)
                       (conj "}"))
                   "")

            \[
            (recur (inc i)
                   (-> (if (seq current) (conj tokens current) tokens)
                       (conj "["))
                   "")

            \]
            (recur (inc i)
                   (-> (if (seq current) (conj tokens current) tokens)
                       (conj "]"))
                   "")

            \,
            (recur (inc i)
                   (if (seq current) (conj tokens current) tokens)
                   "")

            ;; Multi-char copulas
            \-
            (cond
              (and (< (+ i 2) (count s))
                   (= (subs s i (+ i 3)) "-->"))
              (recur (+ i 3)
                     (-> (if (seq current) (conj tokens current) tokens)
                         (conj "-->"))
                     "")

              (and (< (inc i) (count s))
                   (= (subs s i (+ i 2)) "--"))
              (recur (+ i 2)
                     (-> (if (seq current) (conj tokens current) tokens)
                         (conj "--"))
                     "")

              :else
              (recur (inc i) tokens (str current c)))

            \=
            (cond
              (and (< (+ i 2) (count s))
                   (= (subs s i (+ i 3)) "=/>"))
              (recur (+ i 3)
                     (-> (if (seq current) (conj tokens current) tokens)
                         (conj "=/>"))
                     "")

              (and (< (+ i 2) (count s))
                   (= (subs s i (+ i 3)) "==>"))
              (recur (+ i 3)
                     (-> (if (seq current) (conj tokens current) tokens)
                         (conj "==>"))
                     "")

              :else
              (recur (inc i) tokens (str current c)))

            \&
            (cond
              (and (< (inc i) (count s))
                   (= (subs s i (+ i 2)) "&/"))
              (recur (+ i 2)
                     (-> (if (seq current) (conj tokens current) tokens)
                         (conj "&/"))
                     "")

              (and (< (inc i) (count s))
                   (= (subs s i (+ i 2)) "&&"))
              (recur (+ i 2)
                     (-> (if (seq current) (conj tokens current) tokens)
                         (conj "&&"))
                     "")

              :else
              (recur (inc i) tokens (str current c)))

            \|
            (cond
              (and (< (+ i 2) (count s))
                   (= (subs s i (+ i 3)) "|->"))
              (recur (+ i 3)
                     (-> (if (seq current) (conj tokens current) tokens)
                         (conj "|->"))
                     "")

              (and (< (inc i) (count s))
                   (= (subs s i (+ i 2)) "||"))
              (recur (+ i 2)
                     (-> (if (seq current) (conj tokens current) tokens)
                         (conj "||"))
                     "")

              :else
              (recur (inc i) tokens (str current c)))

            \*
            (recur (inc i)
                   (-> (if (seq current) (conj tokens current) tokens)
                       (conj "*"))
                   "")

            ;; Default: accumulate into current token
            (recur (inc i) tokens (str current c))))))))

(defn- token->atom
  "Convert a token string to a term atom (keyword)."
  [token]
  (case token
    "-->"  term/INHERITANCE
    "<->"  term/SIMILARITY
    "==>"  term/IMPLICATION
    "=/>"  term/TEMPORAL-IMPLICATION
    "<=>"  term/EQUIVALENCE
    "&/"   term/SEQUENCE
    "&&"   term/CONJUNCTION
    "||"   term/DISJUNCTION
    "--"   term/NEGATION
    "|->"  term/HAS-CONTINUOUS-PROPERTY
    "*"    term/PRODUCT
    "/1"   term/EXT-IMAGE1
    "/2"   term/EXT-IMAGE2
    "\\1"  term/INT-IMAGE1
    "\\2"  term/INT-IMAGE2
    ;; Default: user atom
    (keyword token)))

(def copula-tokens
  #{"-->" "<->" "==>" "=/>" "<=>" "&/" "&&" "||" "--" "|->" "*"
    "/1" "/2" "\\1" "\\2"})

(defn- copula-token?
  [token]
  (contains? copula-tokens token))

;; -- Recursive descent parser --

(declare parse-compound)

(defn- parse-atom-or-compound
  "Parse a single atom or compound from tokens starting at index i.
   Returns [term next-index]."
  [tokens i]
  (when (< i (count tokens))
    (let [token (nth tokens i)]
      (cond
        ;; Opening bracket - compound
        (or (= token "<") (= token "("))
        (parse-compound tokens i)

        ;; Extensional set
        (= token "{")
        (let [[content-term next-i] (parse-atom-or-compound tokens (inc i))
              ;; Check for more set elements or closing }
              next-i (if (and (< next-i (count tokens))
                              (= (nth tokens next-i) "}"))
                       (inc next-i)
                       next-i)]
          [(-> (term/atomic-term term/EXT-SET)
               (term/override-subterm 1 content-term)
               (assoc 2 term/SET-TERMINATOR))
           next-i])

        ;; Intensional set
        (= token "[")
        (let [[content-term next-i] (parse-atom-or-compound tokens (inc i))
              next-i (if (and (< next-i (count tokens))
                              (= (nth tokens next-i) "]"))
                       (inc next-i)
                       next-i)]
          [(-> (term/atomic-term term/INT-SET)
               (term/override-subterm 1 content-term)
               (assoc 2 term/SET-TERMINATOR))
           next-i])

        ;; Negation prefix
        (= token "--")
        (let [[sub-term next-i] (parse-atom-or-compound tokens (inc i))]
          [(-> (term/atomic-term term/NEGATION)
               (term/override-subterm 1 sub-term))
           next-i])

        ;; Atomic term
        :else
        [(term/atomic-term (token->atom token)) (inc i)]))))

(defn- parse-compound
  "Parse a compound term: < left copula right > or ( left copula right ).
   Returns [term next-index]."
  [tokens i]
  (let [opener (nth tokens i)
        closer (case opener "<" ">" "(" ")" nil)
        ;; Parse left operand
        [left next-i] (parse-atom-or-compound tokens (inc i))]
    (if (and (< next-i (count tokens))
             (copula-token? (nth tokens next-i)))
      ;; Infix: left copula right
      (let [copula (nth tokens next-i)
            [right next-i] (parse-atom-or-compound tokens (inc next-i))
            ;; Skip closer
            next-i (if (and (< next-i (count tokens))
                            (= (nth tokens next-i) closer))
                     (inc next-i)
                     next-i)]
        [(-> (term/atomic-term (token->atom copula))
             (term/override-subterm 1 left)
             (term/override-subterm 2 right))
         next-i])
      ;; Just a parenthesized atom/compound
      (let [next-i (if (and (< next-i (count tokens))
                            (= (nth tokens next-i) closer))
                     (inc next-i)
                     next-i)]
        [left next-i]))))

(defn- parse-truth-value
  "Parse truth value from end of Narsese: {f c} or %f;c%.
   Returns [{:frequency f :confidence c} remaining-string] or [nil string]."
  [s]
  (let [s (str/trim s)]
    (cond
      ;; New format: {f c}
      (str/ends-with? s "}")
      (let [open-idx (str/last-index-of s "{")]
        (when open-idx
          (let [tv-str (subs s (inc open-idx) (dec (count s)))
                parts (str/split (str/trim tv-str) #"\s+")
                remaining (str/trim (subs s 0 open-idx))]
            (when (== (count parts) 2)
              [{:frequency (js/parseFloat (first parts))
                :confidence (js/parseFloat (second parts))}
               remaining]))))

      ;; Old format: %f;c%
      (str/ends-with? s "%")
      (let [open-idx (str/index-of s "%")]
        (when (and open-idx (not= open-idx (dec (count s))))
          (let [tv-str (subs s (inc open-idx) (dec (count s)))
                remaining (str/trim (subs s 0 open-idx))]
            (if (str/includes? tv-str ";")
              (let [parts (str/split tv-str #";")]
                [{:frequency (js/parseFloat (first parts))
                  :confidence (js/parseFloat (second parts))}
                 remaining])
              [{:frequency (js/parseFloat tv-str)
                :confidence 0.9}
               remaining]))))

      :else nil)))

(defn- parse-tense
  "Parse tense marker from end of string.
   Returns [tense-keyword remaining-string]."
  [s]
  (let [s (str/trim s)]
    (cond
      (str/ends-with? s ":|:") [:present (str/trim (subs s 0 (- (count s) 3)))]
      (str/ends-with? s ":\\:") [:past (str/trim (subs s 0 (- (count s) 3)))]
      (str/ends-with? s ":/:") [:future (str/trim (subs s 0 (- (count s) 3)))]
      :else [:eternal s])))

(defn- parse-punctuation
  "Parse punctuation from end of string.
   Returns [type remaining-string]."
  [s]
  (let [s (str/trim s)]
    (cond
      (str/ends-with? s ".") [:belief (str/trim (subs s 0 (dec (count s))))]
      (str/ends-with? s "!") [:goal (str/trim (subs s 0 (dec (count s))))]
      (str/ends-with? s "?") [:question (str/trim (subs s 0 (dec (count s))))]
      :else [nil s])))

(defn parse-narsese
  "Parse a complete Narsese sentence.
   Returns {:term [...] :type :belief/:goal/:question
            :truth {:frequency f :confidence c}
            :tense :eternal/:present/:past/:future}
   or nil if parsing fails."
  [input]
  (let [input (str/trim input)
        ;; Handle dt=N prefix
        [occurrence-time-offset input]
        (if (str/starts-with? input "dt=")
          (let [space-idx (str/index-of input " ")]
            (if space-idx
              [(js/parseFloat (subs input 3 space-idx))
               (str/trim (subs input (inc space-idx)))]
              [0.0 input]))
          [0.0 input])
        ;; Parse truth value (optional, at end)
        [tv remaining] (or (parse-truth-value input)
                           [nil input])
        remaining (or remaining input)
        ;; Parse tense
        [tense remaining] (parse-tense remaining)
        ;; Parse punctuation
        [type remaining] (parse-punctuation remaining)]
    (when type
      (let [;; Tokenize and parse the term
            tokens (tokenize-narsese remaining)
            [parsed-term _] (when (seq tokens)
                              (parse-atom-or-compound tokens 0))
            ;; Normalize variables
            parsed-term (when parsed-term (variable/normalize-variables parsed-term))]
        (when parsed-term
          {:term parsed-term
           :type type
           :truth (or tv {:frequency 1.0 :confidence 0.9})
           :tense tense
           :occurrence-time-offset occurrence-time-offset})))))
