(ns fNARS.shell
  "Interactive shell / REPL for the NAR."
  (:require [fNARS.nar :as nar]
            [fNARS.parser :as parser]
            [fNARS.truth :as truth]
            [fNARS.term :as term]
            [fNARS.event :as event]
            [fNARS.concept :as concept]
            [fNARS.atom-registry :as ar]
            [fNARS.rule-table :as rule-table]
            [fNARS.platform :as p]
            [clojure.string :as str]))

(defn- format-truth
  "Format a truth value for display."
  [{:keys [frequency confidence]}]
  (str "{" (p/format-decimal frequency 6) " " (p/format-decimal confidence 6) "}"))

(defn format-term
  "Format a term for display (simplified Narsese output)."
  [t]
  (let [root (term/term-root t)]
    (cond
      (zero? root) "@"

      (== root term/inheritance)
      (str "<" (format-term (term/extract-subterm t 1))
           " --> " (format-term (term/extract-subterm t 2)) ">")

      (== root term/similarity)
      (str "<" (format-term (term/extract-subterm t 1))
           " <-> " (format-term (term/extract-subterm t 2)) ">")

      (== root term/temporal-implication)
      (str "<" (format-term (term/extract-subterm t 1))
           " =/> " (format-term (term/extract-subterm t 2)) ">")

      (== root term/implication)
      (str "<" (format-term (term/extract-subterm t 1))
           " ==> " (format-term (term/extract-subterm t 2)) ">")

      (== root term/equivalence)
      (str "<" (format-term (term/extract-subterm t 1))
           " <=> " (format-term (term/extract-subterm t 2)) ">")

      (== root term/sequence*)
      (str "(&/ " (format-term (term/extract-subterm t 1))
           " " (format-term (term/extract-subterm t 2)) ")")

      (== root term/conjunction)
      (str "(&& " (format-term (term/extract-subterm t 1))
           " " (format-term (term/extract-subterm t 2)) ")")

      (== root term/product)
      (str "(* " (format-term (term/extract-subterm t 1))
           " " (format-term (term/extract-subterm t 2)) ")")

      (== root term/negation)
      (str "(-- " (format-term (term/extract-subterm t 1)) ")")

      (== root term/ext-set)
      (str "{" (format-term (term/extract-subterm t 1)) "}")

      (== root term/int-set)
      (str "[" (format-term (term/extract-subterm t 1)) "]")

      :else
      (if-let [kw (ar/resolve-atom root)]
        (name kw)
        (str root)))))

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
    (let [base (str "EXE: " (:operation entry) " executed with args")]
      (if-let [arg (:arguments entry)]
        (str base " {" (format-term arg) "}")
        base))

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
                       " priority=" (p/format-decimal (concept/effective-priority c
                                               (:decay-epoch state 0)
                                               (:concept-durability (:config state) 0.9)) 4)
                       " usefulness=" (p/format-decimal (concept/usage-usefulness (:usage c) (:current-time state)) 4)
                       (when-not (event/event-deleted? (:belief c))
                         (str " belief=" (format-truth (:truth (:belief c)))))
                       (when-not (event/event-deleted? (:belief-spike c))
                         (str " spike=" (format-truth (:truth (:belief-spike c)))))))
                (take 20 sorted))))))

(defn execute-line
  "Execute a single shell line and return structured output.
   Returns {:state new-state :entries [...] :messages [...]}."
  [state line]
  (let [line (str/trim line)]
    (cond
      (empty? line)
      (let [state (nar/nar-cycles state 1)
            {:keys [output state]} (nar/nar-get-output state)]
        {:state state
         :entries output
         :messages []})

      (re-matches #"\d+" line)
      (let [n (p/parse-int line)
            state (nar/nar-cycles state n)
            {:keys [output state]} (nar/nar-get-output state)]
        {:state state
         :entries output
         :messages []})

      (= line "*reset")
      {:state (nar/nar-init (:config state))
       :entries []
       :messages ["Reset."]}

      (= line "*concepts")
      {:state state
       :entries []
       :messages [(concepts-report state)]}

      (str/starts-with? line "*volume=")
      (let [vol (p/parse-int (subs line 8))]
        {:state (assoc-in state [:config :volume] vol)
         :entries []
         :messages [(str "Volume set to " vol)]})

      (str/starts-with? line "*motorbabbling=")
      (let [raw (str/lower-case (str/trim (subs line 15)))
            val (case raw
                  "false" 0.0
                  "true" 1.0
                  (p/parse-float raw))]
        {:state (assoc-in state [:config :motor-babbling-chance] val)
         :entries []
         :messages [(str "Motor babbling chance set to " val)]})

      (str/starts-with? line "*decisionthreshold=")
      (let [val (p/parse-float (subs line 19))]
        {:state (assoc-in state [:config :decision-threshold] val)
         :entries []
         :messages [(str "Decision threshold set to " val)]})

      (str/starts-with? line "*setopname ")
      (let [parts (str/split (str/trim line) #"\s+")
            op-idx (p/parse-int (nth parts 1))
            op-name (nth parts 2)
            max-ops (get-in state [:config :operations-max] 10)]
        (cond
          (not (empty? (:concepts state)))
          {:state state
           :entries []
           :messages ["//Operators can only be registered right after initialization / reset"]}

          (or (nil? op-idx) (< op-idx 1) (> op-idx max-ops))
          {:state state
           :entries []
           :messages [(str "//Operator index out of bounds (1.." max-ops ")")]}

          :else
          (let [op-atom (keyword op-name)
                duplicate-slot (first
                                 (for [i (sort (keys (:operations state)))
                                       :let [entry (get-in state [:operations i])]
                                       :when (= (:atom entry) op-atom)]
                                   i))
                op-entry-before (get-in state [:operations op-idx])
                state (if duplicate-slot
                        (update state :operations
                          (fn [ops]
                            (reduce (fn [acc k] (if (>= k duplicate-slot) (dissoc acc k) acc))
                              ops
                              (keys ops))))
                        state)
                op-entry (merge {:name op-name
                                 :atom op-atom
                                 :action (fn [s _] s)}
                           (select-keys op-entry-before [:action :args])
                           {:name op-name :atom op-atom})]
            {:state (assoc-in state [:operations op-idx] op-entry)
             :entries []
             :messages [(str "Set operation " op-idx " to " op-name)]})))

      (str/starts-with? line "*setoparg ")
      (let [parts (str/split (str/trim line) #"\s+" 4)
            op-idx (p/parse-int (nth parts 1))
            arg-idx (p/parse-int (nth parts 2))
            arg-str (str/trim (nth parts 3))
            arg-term (if (str/starts-with? arg-str "(")
                       (:term (parser/parse-narsese (str arg-str ". :|:")))
                       (term/atomic-term (keyword arg-str)))]
        {:state (update-in state [:operations op-idx :args]
                  (fn [args]
                    (let [args (or args [])
                          needed (max (count args) arg-idx)
                          args (vec (take needed (concat args (repeat nil))))]
                      (assoc args (dec arg-idx) arg-term))))
         :entries []
         :messages [(str "Set operation " op-idx " arg " arg-idx " to " arg-str)]})

      (str/starts-with? line "*babblingops=")
      (let [val (p/parse-int (subs line 13))]
        {:state (assoc-in state [:config :babbling-ops] val)
         :entries []
         :messages [(str "Babbling ops set to " val)]})

      (str/starts-with? line "*setsemanticinferencenallevel=")
      (let [val (p/parse-int (subs line 30))
            rules (rule-table/rules-for-level val)
            index (rule-table/build-rule-index rules)]
        {:state (-> state
                    (assoc-in [:config :semantic-inference-nal-level] val)
                    (assoc :nal-rules rules :nal-rule-index index))
         :entries []
         :messages [(str "Semantic inference NAL level set to " val)]})

      (str/starts-with? line "*anticipationconfidence=")
      (let [val (p/parse-float (subs line 23))]
        {:state (assoc-in state [:config :anticipation-confidence] val)
         :entries []
         :messages [(str "Anticipation confidence set to " val)]})

      (str/starts-with? line "*setopstdin ")
      ;; ONA shell supports this for channel integration. fNARS has no stdin op channel,
      ;; so we accept it as a compatibility no-op for .nal replay parity.
      {:state state
       :entries []
       :messages ["Set op stdin (no-op in fNARS)"]}

      (= line "*concurrent")
      ;; ONA shell semantics: decrement currentTime by 1.
      {:state (update state :current-time dec)
       :entries []
       :messages []}

      (str/starts-with? line "//")
      {:state state :entries [] :messages []}

      :else
      (if-let [parsed (parser/parse-narsese line)]
        (let [{:keys [term type truth tense occurrence-time-offset]} parsed
              state (case type
                      :belief (nar/nar-add-input state term event/event-type-belief truth
                                {:eternal? (= tense :eternal)
                                 :occurrence-time-offset occurrence-time-offset})
                      :goal   (nar/nar-add-input state term event/event-type-goal truth)
                      :question state
                      state)
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
           :entries output
           :messages (vec (filter seq (when question-output [question-output])))})
        {:state state
         :entries []
         :messages [(str "//Failed to parse: " line)]}))))

(defn process-input
  "Process a single input line. Returns {:state new-state :output string}."
  [state line]
  (let [{:keys [state entries messages]} (execute-line state line)
        rendered (concat (map format-output-entry entries) messages)]
    {:state state
     :output (str/join "\n" (filter seq rendered))}))
