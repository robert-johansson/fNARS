(ns fNARS.shell
  "Interactive shell / REPL for the NAR.
   Process input lines (Narsese statements, cycle counts, commands)."
  (:require [fNARS.nar :as nar]
            [fNARS.parser :as parser]
            [fNARS.truth :as truth]
            [fNARS.term :as term]
            [fNARS.event :as event]
            [fNARS.concept :as concept]
            [clojure.string :as str]))

(defn- format-truth
  "Format a truth value for display."
  [{:keys [frequency confidence]}]
  (str "{" (.toFixed frequency 6) " " (.toFixed confidence 6) "}"))

(defn- format-term
  "Format a term for display (simplified Narsese output)."
  [t]
  (let [root (term/term-root t)]
    (cond
      (nil? root) "@"

      ;; Inheritance: <S --> P>
      (term/copula? root term/INHERITANCE)
      (str "<" (format-term (term/extract-subterm t 1))
           " --> " (format-term (term/extract-subterm t 2)) ">")

      ;; Similarity: <S <-> P>
      (term/copula? root term/SIMILARITY)
      (str "<" (format-term (term/extract-subterm t 1))
           " <-> " (format-term (term/extract-subterm t 2)) ">")

      ;; Temporal implication: <S =/> P>
      (term/copula? root term/TEMPORAL-IMPLICATION)
      (str "<" (format-term (term/extract-subterm t 1))
           " =/> " (format-term (term/extract-subterm t 2)) ">")

      ;; Implication: <S ==> P>
      (term/copula? root term/IMPLICATION)
      (str "<" (format-term (term/extract-subterm t 1))
           " ==> " (format-term (term/extract-subterm t 2)) ">")

      ;; Equivalence: <S <=> P>
      (term/copula? root term/EQUIVALENCE)
      (str "<" (format-term (term/extract-subterm t 1))
           " <=> " (format-term (term/extract-subterm t 2)) ">")

      ;; Sequence: (&/ S P)
      (term/copula? root term/SEQUENCE)
      (str "(&/ " (format-term (term/extract-subterm t 1))
           " " (format-term (term/extract-subterm t 2)) ")")

      ;; Conjunction: (&& S P)
      (term/copula? root term/CONJUNCTION)
      (str "(&& " (format-term (term/extract-subterm t 1))
           " " (format-term (term/extract-subterm t 2)) ")")

      ;; Product: (* S P)
      (term/copula? root term/PRODUCT)
      (str "(* " (format-term (term/extract-subterm t 1))
           " " (format-term (term/extract-subterm t 2)) ")")

      ;; Negation: (-- S)
      (term/copula? root term/NEGATION)
      (str "(-- " (format-term (term/extract-subterm t 1)) ")")

      ;; Ext set: {x}
      (term/copula? root term/EXT-SET)
      (str "{" (format-term (term/extract-subterm t 1)) "}")

      ;; Int set: [x]
      (term/copula? root term/INT-SET)
      (str "[" (format-term (term/extract-subterm t 1)) "]")

      ;; Atomic term
      (keyword? root)
      (name root)

      :else (str root))))

(defn- format-output-entry
  "Format a single output entry for display."
  [entry]
  (case (:type entry)
    :input
    (str "Input: " (format-term (:term entry))
         (if (= (:event-type entry) event/event-type-belief) ". " "! ")
         (when (not= (:occurrence-time entry) truth/occurrence-eternal)
           (str ":|: occurrenceTime=" (:occurrence-time entry) " "))
         "Truth: " (format-truth (:truth entry)))

    :execution
    (str "EXE: " (:operation entry)
         " executed with args")

    :derived
    (str "Derived: " (format-term (:term entry))
         (if (= (:event-type entry) event/event-type-belief) ". " "! ")
         (format-truth (:truth entry)))

    (str entry)))

(defn- concepts-report
  "Generate a concepts report."
  [state]
  (let [concepts (:concepts state)
        sorted (sort-by (fn [[k c]] (- (concept/usage-usefulness (:usage c) (:current-time state))))
                        concepts)]
    (str "Concepts (" (count concepts) "):\n"
         (str/join "\n"
           (map (fn [[k c]]
                  (str "  " (format-term k)
                       " priority=" (.toFixed (:priority c) 4)
                       " usefulness=" (.toFixed (concept/usage-usefulness (:usage c) (:current-time state)) 4)
                       (when-not (event/event-deleted? (:belief c))
                         (str " belief=" (format-truth (:truth (:belief c)))))
                       (when-not (event/event-deleted? (:belief-spike c))
                         (str " spike=" (format-truth (:truth (:belief-spike c)))))))
                (take 20 sorted))))))

(defn process-input
  "Process a single input line. Returns {:state new-state :output string}."
  [state line]
  (let [line (str/trim line)]
    (cond
      ;; Empty line: run 1 cycle
      (empty? line)
      (let [state (nar/nar-cycles state 1)
            {:keys [output state]} (nar/nar-get-output state)]
        {:state state
         :output (str/join "\n" (map format-output-entry output))})

      ;; Numeric: run N cycles
      (re-matches #"\d+" line)
      (let [n (js/parseInt line)
            state (nar/nar-cycles state n)
            {:keys [output state]} (nar/nar-get-output state)]
        {:state state
         :output (str/join "\n" (map format-output-entry output))})

      ;; Commands
      (= line "*reset")
      {:state (nar/nar-init (:config state))
       :output "Reset."}

      (= line "*concepts")
      {:state state
       :output (concepts-report state)}

      (str/starts-with? line "*volume=")
      (let [vol (js/parseInt (subs line 8))]
        {:state (assoc-in state [:config :volume] vol)
         :output (str "Volume set to " vol)})

      (str/starts-with? line "*motorbabbling=")
      (let [val (js/parseFloat (subs line 15))]
        {:state (assoc-in state [:config :motor-babbling-chance] val)
         :output (str "Motor babbling chance set to " val)})

      (str/starts-with? line "*decisionthreshold=")
      (let [val (js/parseFloat (subs line 19))]
        {:state (assoc-in state [:config :decision-threshold] val)
         :output (str "Decision threshold set to " val)})

      (str/starts-with? line "//")
      {:state state :output ""}

      ;; Narsese input
      :else
      (if-let [parsed (parser/parse-narsese line)]
        (let [{:keys [term type truth tense occurrence-time-offset]} parsed
              state (case type
                      :belief (nar/nar-add-input state term event/event-type-belief truth
                                [{:eternal? (= tense :eternal)
                                  :occurrence-time-offset occurrence-time-offset}])
                      :goal   (nar/nar-add-input state term event/event-type-goal truth)
                      :question state
                      state)
              ;; Handle question answering
              [state question-output]
              (if (= type :question)
                (let [{:keys [state answer]} (nar/nar-answer-question state term tense)]
                  (if answer
                    [state (str "Answer: " (format-term (:term answer))
                                (if (= (:occurrence-time answer) -1)
                                  ". "
                                  (str ". :|: occurrenceTime=" (:occurrence-time answer) " "))
                                "Truth: " (format-truth (:truth answer)))]
                    [state "//No answer found."]))
                [state nil])
              {:keys [output state]} (nar/nar-get-output state)]
          {:state state
           :output (str/join "\n" (filter seq
                     (concat (map format-output-entry output)
                             (when question-output [question-output]))))})
        {:state state
         :output (str "//Failed to parse: " line)}))))
