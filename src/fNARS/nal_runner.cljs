(ns fNARS.nal-runner
  "Runs ONA .nal files through fNARS and checks expected results.
   Usage: bunx --bun nbb -cp src:lib/instaparse/src -m fNARS.nal-runner <file.nal>"
  (:require [fNARS.nar :as nar]
            [fNARS.nar-config :as nar-config]
            [fNARS.parser :as parser]
            [fNARS.event :as event]
            [fNARS.truth :as truth]
            [fNARS.narsese :as narsese]
            [fNARS.shell :as shell]
            [fNARS.variable :as variable]
            [fNARS.term :as term]
            [clojure.string :as str]
            ["fs" :as fs]))

;; -- Line classification --

(defn- blank-or-comment? [line]
  (let [trimmed (str/trim line)]
    (or (empty? trimmed)
        (str/starts-with? trimmed "//"))))

(defn- cycle-count? [line]
  (re-matches #"\d+" (str/trim line)))

(defn- config-command? [line]
  (str/starts-with? (str/trim line) "*"))

(defn- expected-line? [line]
  (let [trimmed (str/trim line)]
    (or (str/starts-with? trimmed "//expected:")
        (str/starts-with? trimmed "//--expected:"))))

(defn- extract-expected [line]
  "Extract expected result from comment line.
   Returns {:type :answer/:execution, :term-str, :truth {:frequency f :confidence c}} or nil."
  (let [trimmed (str/trim line)
        text (cond
               (str/starts-with? trimmed "//--expected: ") (subs trimmed (count "//--expected: "))
               (str/starts-with? trimmed "//expected: ") (subs trimmed (count "//expected: "))
               (str/starts-with? trimmed "//--expected:") (subs trimmed (count "//--expected:"))
               (str/starts-with? trimmed "//expected:") (subs trimmed (count "//expected:"))
               :else nil)]
    (when text
      (let [text (str/trim text)]
        (cond
          ;; Answer: <term>. Truth: frequency=F, confidence=C
          (str/starts-with? text "Answer:")
          (let [answer-text (str/trim (subs text 7))
                ;; Split on ". Truth:" or " Truth:"
                [term-part truth-part] (str/split answer-text #"\.\s*Truth:\s*" 2)]
            (when truth-part
              (let [freq-match (re-find #"frequency=([0-9.]+)" truth-part)
                    conf-match (re-find #"confidence=([0-9.]+)" truth-part)]
                {:type :answer
                 :term-str (str/trim term-part)
                 :truth {:frequency (js/parseFloat (second freq-match))
                         :confidence (js/parseFloat (second conf-match))}})))

          ;; ^op executed with args
          (str/includes? text "executed with args")
          (let [[op-part args-part] (str/split text #"\s+executed with args\s*" 2)]
            {:type :execution
             :operation (str/trim op-part)
             :args (when args-part (str/trim args-part))})

          :else nil)))))

;; -- Config handling --

(defn- apply-config [state line]
  (let [trimmed (str/trim line)]
    (cond
      (or (= trimmed "*motorbabbling=false")
          (= trimmed "*motorbabbling=0"))
      (update state :config assoc :motor-babbling-chance 0.0)

      (str/starts-with? trimmed "*volume=")
      state ;; volume is display-only

      (str/starts-with? trimmed "*setopname")
      (let [[_ id-str name-str] (re-find #"\*setopname\s+(\d+)\s+(\S+)" trimmed)]
        (when (and id-str name-str)
          (nar/nar-add-operation state name-str (fn [s _] s))))

      (= trimmed "*reset")
      (nar/nar-init (:config state))

      (str/starts-with? trimmed "*anticipationconfidence=")
      (let [val (js/parseFloat (subs trimmed (count "*anticipationconfidence=")))]
        (update state :config assoc :anticipation-confidence val))

      :else state)))

;; -- Answer checking --

(defn- approx= [a b tolerance]
  (< (js/Math.abs (- a b)) tolerance))

(defn- check-answer [state expected tolerance]
  "Check if the NAR can answer a question matching the expected result."
  (let [parsed (parser/parse-narsese (str (:term-str expected) "?"))
        _ (when-not parsed
            (println "  WARNING: Cannot parse expected term:" (:term-str expected)))
        {:keys [answer]} (when parsed
                           (nar/nar-answer-question state (:term parsed) :eternal))]
    (if-not answer
      {:pass false :reason "No answer found"}
      (let [ef (:frequency (:truth expected))
            ec (:confidence (:truth expected))
            af (:frequency (:truth answer))
            ac (:confidence (:truth answer))]
        ;; ONA evaluation checks confidence >= expected (not exact match)
        ;; and frequency approximately equal
        (if (and (approx= af ef tolerance)
                 (>= ac (- ec tolerance)))
          {:pass true}
          {:pass false
           :reason (str "Expected f=" ef " c>=" ec
                        ", got f=" af " c=" ac)})))))

(defn- check-execution [output expected]
  "Check if the expected operation was executed in the output."
  (let [op-kw (keyword (subs (:operation expected) 1)) ;; ^left -> :left... no, ^left -> :^left
        op-name (:operation expected)]
    (if (some (fn [o]
                (and (= (:type o) :execution)
                     (str/includes? (str (:operation o)) op-name)))
              output)
      {:pass true}
      {:pass false
       :reason (str "Expected " op-name " execution not found")})))

;; -- Main runner --

(defn run-nal-file [filepath & [{:keys [tolerance verbose nal-level]
                                  :or {tolerance 0.05 verbose false nal-level 6}}]]
  (let [content (str (.readFileSync fs filepath "utf8"))
        lines (str/split-lines content)
        config (assoc nar-config/default-config
                 :semantic-inference-nal-level nal-level
                 :motor-babbling-chance 0.0)]
    (println (str "=== Running: " filepath " ===\n"))
    (loop [state (nar/nar-init config)
           remaining lines
           results []
           pending-expected []]
      (if (empty? remaining)
        ;; Done — report results
        (let [checks (filter some? results)
              passed (filter :pass checks)
              failed (remove :pass checks)]
          (println)
          (when (seq checks)
            (doseq [r checks]
              (println (if (:pass r) "  PASS" "  FAIL") (:desc r))
              (when-not (:pass r)
                (println "       " (:reason r)))))
          (println (str "\n" (count passed) "/" (count checks) " checks passed"))
          {:passed (count passed) :total (count checks) :failed (count failed)})

        (let [line (first remaining)
              rest-lines (rest remaining)
              trimmed (str/trim line)]
          (cond
            ;; Skip blanks and non-expected comments
            (and (blank-or-comment? line) (not (expected-line? line)))
            (recur state rest-lines results pending-expected)

            ;; Expected result comment
            (expected-line? line)
            (let [expected (extract-expected trimmed)]
              (if expected
                (let [result (case (:type expected)
                               :answer
                               (let [r (check-answer state expected tolerance)]
                                 (assoc r :desc (str "Answer: " (:term-str expected)
                                                     " f=" (:frequency (:truth expected))
                                                     " c=" (:confidence (:truth expected)))))
                               :execution
                               (let [{:keys [output]} (nar/nar-get-output state)
                                     r (check-execution output expected)]
                                 (assoc r :desc (str "Execution: " (:operation expected))))
                               nil)]
                  (recur state rest-lines (if result (conj results result) results) []))
                (recur state rest-lines results pending-expected)))

            ;; Config command
            (config-command? line)
            (let [new-state (apply-config state trimmed)]
              (recur (or new-state state) rest-lines results pending-expected))

            ;; Cycle count
            (cycle-count? line)
            (let [n (js/parseInt trimmed)]
              (when verbose (println (str "  [" n " cycles]")))
              (recur (nar/nar-cycles state n) rest-lines results pending-expected))

            ;; Narsese input
            :else
            (let [parsed (parser/parse-narsese trimmed)]
              (if-not parsed
                (do
                  (when verbose (println (str "  SKIP (unparseable): " trimmed)))
                  (recur state rest-lines results pending-expected))
                (let [ev-type (case (:type parsed)
                                :belief event/event-type-belief
                                :goal event/event-type-goal
                                :question nil
                                event/event-type-belief)
                      tense (:tense parsed)]
                  (if (= (:type parsed) :question)
                    ;; Questions — just run through, answer checked by //expected
                    (let [{:keys [state answer]} (nar/nar-answer-question
                                                   state (:term parsed)
                                                   (or tense :eternal))]
                      (when verbose
                        (println (str "  Q: " trimmed
                                     (if answer
                                       (str " → f=" (:frequency (:truth answer))
                                            " c=" (:confidence (:truth answer)))
                                       " → None"))))
                      (recur state rest-lines results pending-expected))
                    ;; Belief or goal
                    (let [eternal? (= tense :eternal)
                          state (nar/nar-add-input state (:term parsed) ev-type
                                  (or (:truth parsed) truth/default-truth)
                                  {:eternal? eternal?
                                   :occurrence-time-offset (:occurrence-time-offset parsed 0.0)})]
                      (when verbose
                        (println (str "  " (if (= ev-type event/event-type-goal) "!" ".") " " trimmed)))
                      (recur state rest-lines results pending-expected))))))))))))

;; -- Entry point --

(defn -main [& args]
  (if (empty? args)
    (println "Usage: bunx --bun nbb -cp src:lib/instaparse/src -m fNARS.nal-runner <file.nal> [--verbose] [--tolerance 0.05]")
    (let [filepath (first args)
          verbose (some #(= % "--verbose") args)
          tol-idx (some (fn [i] (when (= (nth args i) "--tolerance") (inc i)))
                        (range (count args)))
          tolerance (if tol-idx (js/parseFloat (nth args tol-idx)) 0.05)]
      (run-nal-file filepath {:verbose verbose :tolerance tolerance}))))
