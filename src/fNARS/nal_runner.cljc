(ns fNARS.nal-runner
  "Runs ONA .nal files through fNARS and checks expected results.
   Usage: bunx --bun nbb -cp src:lib/instaparse/src -m fNARS.nal-runner <file.nal>"
  (:require [fNARS.nar :as nar]
            [fNARS.nar-config :as nar-config]
            [fNARS.parser :as parser]
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
                 :truth {:frequency (p/parse-float (second freq-match))
                         :confidence (p/parse-float (second conf-match))}})))

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
  (let [op-name (:operation expected)]
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
  (let [content (p/slurp-file filepath)
        lines (str/split-lines content)
        config (assoc nar-config/default-config
                 :semantic-inference-nal-level nal-level
                 :motor-babbling-chance 0.0)]
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
    (println "Usage: bunx --bun nbb -cp src:lib/instaparse/src -m fNARS.nal-runner <file.nal> [--verbose] [--tolerance 0.05]")
    (let [filepath (first args)
          verbose (some #(= % "--verbose") args)
          tol-idx (some (fn [i] (when (= (nth args i) "--tolerance") (inc i)))
                        (range (count args)))
          tolerance (if tol-idx (p/parse-float (nth args tol-idx)) 0.05)]
      (run-nal-file filepath {:verbose verbose :tolerance tolerance}))))
