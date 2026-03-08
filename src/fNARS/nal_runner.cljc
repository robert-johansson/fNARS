(ns fNARS.nal-runner
  "Runs ONA .nal files through fNARS and checks expected results.
   Usage: bunx --bun nbb -cp src:lib/instaparse/src -m fNARS.nal-runner <file.nal>"
  (:require [fNARS.nar :as nar]
            [fNARS.nar-config :as nar-config]
            [fNARS.parser :as parser]
            [fNARS.cycle :as cycle]
            [fNARS.shell :as shell]
            [fNARS.platform :as p]
            [clojure.string :as str]))

;; -- Line classification --

(defn- blank-or-comment? [line]
  (let [trimmed (str/trim line)]
    (or (empty? trimmed)
        (str/starts-with? trimmed "//"))))

(defn- cycle-count? [line]
  (re-matches #"\d+" (str/trim line)))

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
          ;; no execution
          (re-find #"(?i)\bno execution\b" text)
          {:type :no-execution}

          ;; Answer: <term>. :|: occurrenceTime=N Truth: frequency=F, confidence=C
          (str/starts-with? text "Answer:")
          (when-let [[_ term-str occurrence-time-str truth-part]
                     (re-find #"(?is)^Answer:\s*(.+?)\.\s*(?::\|:\s*occurrenceTime=([-]?\d+)\s*)?(?:creationTime=[-]?\d+\s*)?Truth:\s*(.+)$"
                       text)]
            (let [freq-match (re-find #"frequency=([0-9.]+)" truth-part)
                  conf-match (re-find #"confidence=([0-9.]+)" truth-part)]
              {:type :answer
               :term-str (str/trim term-str)
               :occurrence-time (when occurrence-time-str (p/parse-int occurrence-time-str))
               :truth {:frequency (p/parse-float (second freq-match))
                       :confidence (p/parse-float (second conf-match))}}))

          ;; <term>. Truth: frequency=F, confidence=C (used in some files without "Answer:")
          (and (str/includes? text "Truth:")
               (re-find #"^<.+>\." text))
          (when-let [[_ term-str _occurrence-time-str truth-part]
                     (re-find #"(?is)^(.+?)\.\s*(?::\|:\s*occurrenceTime=([-]?\d+)\s*)?(?:creationTime=[-]?\d+\s*)?Truth:\s*(.+)$"
                       text)]
            (let [freq-match (re-find #"frequency=([0-9.]+)" truth-part)
                  conf-match (re-find #"confidence=([0-9.]+)" truth-part)]
              {:type :answer
               :term-str (str/trim term-str)
               :truth {:frequency (p/parse-float (second freq-match))
                       :confidence (p/parse-float (second conf-match))}}))

          ;; ^op executed with args
          (str/includes? text "executed with args")
          (let [[op-part args-part] (str/split text #"\s+executed with args\s*" 2)]
            {:type :execution
             :operation (str/trim op-part)
             :args (when args-part (str/trim args-part))})

          :else nil)))))

;; -- Answer checking --

(defn- approx= [a b tolerance]
  (< (p/abs (- a b)) tolerance))

(defn- check-answer [state expected tolerance]
  "Check if the NAR can answer a question matching the expected result."
  (let [parsed (parser/parse-narsese (str (:term-str expected) "?"))
        _ (when-not parsed
            (println "  WARNING: Cannot parse expected term:" (:term-str expected)))
        query-tense (if (some? (:occurrence-time expected)) :present :eternal)
        {:keys [answer]} (when parsed
                           (nar/nar-answer-question state (:term parsed) query-tense))]
    (if-not answer
      {:pass false :reason "No answer found"}
      (let [ef (:frequency (:truth expected))
            ec (:confidence (:truth expected))
            af (:frequency (:truth answer))
            ac (:confidence (:truth answer))
            expected-occurrence (:occurrence-time expected)
            actual-occurrence (:occurrence-time answer)
            occurrence-match? (or (nil? expected-occurrence)
                                  (= expected-occurrence actual-occurrence))]
        ;; ONA evaluation checks confidence >= expected (not exact match)
        ;; and frequency approximately equal
        (if (and (approx= af ef tolerance)
                 (>= ac (- ec tolerance))
                 occurrence-match?)
          {:pass true}
          {:pass false
           :reason (str "Expected f=" ef " c>=" ec
                        (when expected-occurrence
                          (str " occurrenceTime=" expected-occurrence))
                        ", got f=" af " c=" ac
                        (when (some? actual-occurrence)
                          (str " occurrenceTime=" actual-occurrence)))})))))

(defn- check-execution [output expected]
  "Check if the expected operation was executed in the output."
  (let [op-name (:operation expected)]
    (if (some (fn [o]
                (and (= (:type o) :execution)
                     (str/includes? (str (:operation o)) op-name)))
              output)
      {:pass true}
      {:pass false
       :reason (str "Expected " op-name " execution not found")})))

(defn- check-no-execution [output]
  "Check that no execution happened in the buffered output."
  (if (some #(= (:type %) :execution) output)
    {:pass false :reason "Found execution but expected none"}
    {:pass true}))

;; -- Main runner --

(defn run-nal-file [filepath & [{:keys [tolerance verbose nal-level perf?]
                                  :or {tolerance 0.05 verbose false nal-level 6 perf? false}}]]
  (let [content (p/slurp-file filepath)
        lines (str/split-lines content)
        config (assoc nar-config/default-config
                 :semantic-inference-nal-level nal-level
                 :motor-babbling-chance 0.0
                 :perf-instrumentation perf?)]
    (println (str "=== Running: " filepath " ===\n"))
    (loop [state (nar/nar-init config)
           remaining lines
           results []
           pending-output []]
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
          (when perf?
            (println "\nPerf summary:")
            (println (pr-str (cycle/perf-summary state))))
          (println (str "\n" (count passed) "/" (count checks) " checks passed"))
          {:passed (count passed) :total (count checks) :failed (count failed)})

        (let [line (first remaining)
              rest-lines (rest remaining)
              trimmed (str/trim line)]
          (cond
            ;; Skip blanks and non-expected comments
            (and (blank-or-comment? line) (not (expected-line? line)))
            (recur state rest-lines results pending-output)

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
                               (let [r (check-execution pending-output expected)]
                                 (assoc r :desc (str "Execution: " (:operation expected))))
                               :no-execution
                               (let [r (check-no-execution pending-output)]
                                 (assoc r :desc "No execution"))
                               nil)]
                  (recur state rest-lines (if result (conj results result) results) []))
                (recur state rest-lines results pending-output)))

            ;; Everything else is executed by the shell line handler for parity.
            :else
            (let [{:keys [state entries messages]} (shell/execute-line state trimmed)]
              (when verbose
                (when (cycle-count? trimmed)
                  (println (str "  [" trimmed " cycles]")))
                (when (seq entries)
                  (doseq [entry entries]
                    (println (str "  OUT " entry))))
                (when (seq messages)
                  (doseq [message messages]
                    (println (str "  MSG " message)))))
              (recur state rest-lines results (into pending-output entries)))))))))

;; -- Entry point --

(defn -main [& args]
  (if (empty? args)
    (println "Usage: bunx --bun nbb -cp src:lib/instaparse/src -m fNARS.nal-runner <file.nal> [--verbose] [--perf] [--nal-level 6] [--tolerance 0.05]")
    (let [filepath (first args)
          verbose (some #(= % "--verbose") args)
          perf? (some #(= % "--perf") args)
          level-idx (some (fn [i] (when (= (nth args i) "--nal-level") (inc i)))
                          (range (count args)))
          nal-level (if level-idx (p/parse-int (nth args level-idx)) 6)
          tol-idx (some (fn [i] (when (= (nth args i) "--tolerance") (inc i)))
                        (range (count args)))
          tolerance (if tol-idx (p/parse-float (nth args tol-idx)) 0.05)]
      (run-nal-file filepath {:verbose verbose
                              :perf? perf?
                              :nal-level nal-level
                              :tolerance tolerance}))))
