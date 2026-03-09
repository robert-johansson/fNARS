#!/usr/bin/env bb

(ns nal-compat
  (:require [babashka.fs :as fs]
            [babashka.process :as proc]
            [clojure.string :as str]))

(def default-examples-dir "OpenNARS-for-Applications/examples/nal")
(def ona-bin "OpenNARS-for-Applications/NAR")
(def fnars-cmd
  ["bunx" "--bun" "nbb" "-cp" "src:lib/instaparse/src" "-m" "fNARS.nal-runner"])

(defn usage []
  (println "Usage: bb nal:compat [--dir PATH] [--limit N] [--file NAME_OR_PATH (repeatable)] [--include-space] [--timeout-sec N] [--verbose]"))

(defn parse-args [args]
  (loop [args args opts {:dir default-examples-dir :limit nil :files [] :include-space? false :verbose? false :timeout-sec 90}]
    (if (empty? args)
      opts
      (let [a (first args)
            more (rest args)]
        (case a
          "--dir" (if-let [d (first more)]
                    (recur (rest more) (assoc opts :dir d))
                    (do (println "Missing value for --dir") (System/exit 1)))
          "--limit" (if-let [n (first more)]
                      (recur (rest more) (assoc opts :limit (parse-long n)))
                      (do (println "Missing value for --limit") (System/exit 1)))
          "--file" (if-let [f (first more)]
                     (recur (rest more) (update opts :files conj f))
                     (do (println "Missing value for --file") (System/exit 1)))
          "--include-space" (recur more (assoc opts :include-space? true))
          "--timeout-sec" (if-let [n (first more)]
                            (recur (rest more) (assoc opts :timeout-sec (parse-long n)))
                            (do (println "Missing value for --timeout-sec") (System/exit 1)))
          "--verbose" (recur more (assoc opts :verbose? true))
          "--help" (do (usage) (System/exit 0))
          (do
            (println "Unknown argument:" a)
            (usage)
            (System/exit 1)))))))

(defn read-file [f]
  (slurp (str f)))

(defn has-blocked-cmd? [content include-space?]
  (or (str/includes? content "*setvalue")
      (and (not include-space?)
           (str/includes? content "*space"))))

(defn list-nal-files [{:keys [dir files limit include-space?]}]
  (let [all-files (->> (fs/glob dir "*.nal")
                       (map str)
                       sort)
        filtered
        (cond
          (seq files)
          (filter
            (fn [path]
              (some (fn [f]
                      (or (= path f)
                          (= (fs/file-name path) f)
                          (str/includes? path f)))
                    files))
            all-files)

          :else
          (remove (fn [f] (has-blocked-cmd? (read-file f) include-space?)) all-files))
        limited (if limit (take limit filtered) filtered)]
    (vec limited)))

(defn parse-expected [line]
  (let [trimmed (str/trim line)
        text (cond
               (str/starts-with? trimmed "//--expected:") (str/trim (subs trimmed (count "//--expected:")))
               (str/starts-with? trimmed "//expected:") (str/trim (subs trimmed (count "//expected:")))
               :else nil)]
    (when (seq text)
      (cond
        (re-find #"(?i)no execution" text)
        {:type :no-execution}

        (str/starts-with? text "Answer:")
        (let [answer-text (str/trim (subs text (count "Answer:")))
              answer-text (first (str/split answer-text #"\s+Truth:\s*" 2))
              answer-text (str/replace answer-text #"\s+:\|:\s+occurrenceTime=[0-9-]+" "")
              answer-text (str/trim answer-text)
              answer-text (if (str/ends-with? answer-text ".")
                            (subs answer-text 0 (dec (count answer-text)))
                            answer-text)]
          {:type :answer :term answer-text})

        (str/includes? text "executed with args")
        (let [[_ op] (re-find #"(\^[^\s]+)\s+executed with args" text)]
          {:type :execution :operation op})

        :else nil))))

(defn expected-items [content]
  (->> (str/split-lines content)
       (keep parse-expected)
       vec))

(defn sh-quote [s]
  (str "'" (str/replace s "'" "'\"'\"'") "'"))

(defn run-cmd [{:keys [cmd cwd timeout-sec]}]
  (let [started (System/nanoTime)
        p (proc/process ["bash" "-lc" cmd]
            {:dir (or cwd ".")
             :out :string
             :err :string})
        result-fut (future @p)
        timeout-ms (* 1000 (max 1 (or timeout-sec 90)))
        result (deref result-fut timeout-ms ::timeout)
        duration-ms (/ (- (System/nanoTime) started) 1000000.0)]
    (if (= result ::timeout)
      (do
        (try
          (proc/destroy-tree p)
          (catch Throwable _
            (try
              (when-let [proc-handle (:proc p)]
                (.destroy proc-handle))
              (catch Throwable _))))
        {:exit 124
         :timed-out? true
         :out ""
         :err (str "Timed out after " timeout-sec "s")
         :duration-ms duration-ms})
      (assoc result
        :timed-out? false
        :out (or (:out result) "")
        :err (or (:err result) "")
        :duration-ms duration-ms))))

(defn run-ona [file timeout-sec]
  (run-cmd {:cmd (str ona-bin " shell < " (sh-quote file))
            :cwd "."
            :timeout-sec timeout-sec}))

(defn run-fnars [file timeout-sec]
  (run-cmd {:cmd (str/join " " (concat fnars-cmd [file]))
            :cwd "."
            :timeout-sec timeout-sec}))

(defn output-has-execution? [output op]
  (or (str/includes? output (str op " executed with args"))
      (str/includes? output (str "EXE: " op))))

(defn output-has-any-execution? [output]
  (or (str/includes? output " executed with args")
      (str/includes? output "EXE: ")))

(defn output-has-answer-term? [output term]
  (or (str/includes? output (str "Answer: " term))
      ;; fNARS includes "Answer: <term>. ..."
      (str/includes? output (str "Answer: " term "."))))

(defn evaluate [output items]
  (if (empty? items)
    {:pass? true :passed 0 :total 0 :failed []}
    (let [results
          (mapv
            (fn [item]
              (case (:type item)
                :execution
                {:ok? (output-has-execution? output (:operation item))
                 :desc (str "execution " (:operation item))}

                :no-execution
                {:ok? (not (output-has-any-execution? output))
                 :desc "no execution"}

                :answer
                {:ok? (output-has-answer-term? output (:term item))
                 :desc (str "answer " (:term item))}

                {:ok? false :desc (str "unknown expectation " item)}))
            items)
          passed (count (filter :ok? results))
          total (count results)]
      {:pass? (= passed total)
       :passed passed
       :total total
       :failed (mapv :desc (remove :ok? results))})))

(defn evaluate-fnars [output items]
  (if-let [[_ passed total] (re-find #"(?m)(\d+)\/(\d+)\s+checks passed" output)]
    (let [p (parse-long passed)
          t (parse-long total)
          fail-lines (->> (str/split-lines output)
                          (filter #(str/starts-with? (str/trim %) "FAIL"))
                          vec)]
      {:pass? (= p t)
       :passed p
       :total t
       :failed fail-lines})
    ;; Fallback when summary is not present
    (evaluate output items)))

(defn- failed-run-eval [label run items timeout-sec]
  {:pass? false
   :passed 0
   :total (count items)
   :failed [(if (:timed-out? run)
              (str label " timed out after " timeout-sec "s")
              (str label " exited " (:exit run)
                   (when-let [e (seq (str/trim (:err run)))]
                     (str ": " e))))]})

(defn- run->eval [label run items timeout-sec evaluate-fn]
  (if (zero? (:exit run))
    (evaluate-fn (:out run) items)
    (failed-run-eval label run items timeout-sec)))

(defn summarize-row [{:keys [file items ona fnars ona-run fnars-run]}]
  {:file file
   :checks (count items)
   :ona (if (:pass? ona) "PASS" "FAIL")
   :fnars (if (:pass? fnars) "PASS" "FAIL")
   :ona-seconds (/ (:duration-ms ona-run 0.0) 1000.0)
   :fnars-seconds (/ (:duration-ms fnars-run 0.0) 1000.0)})

(defn print-table [rows]
  (println "| File | Checks | ONA | fNARS | ONA s | fNARS s |")
  (println "| --- | ---: | :---: | :---: | ---: | ---: |")
  (doseq [{:keys [file checks ona fnars ona-seconds fnars-seconds]} rows]
    (println (str "| " (fs/file-name file) " | " checks " | " ona " | " fnars
                  " | " (format "%.2f" ona-seconds)
                  " | " (format "%.2f" fnars-seconds) " |"))))

(defn -main [& args]
  (let [opts (parse-args args)
        files (list-nal-files opts)]
    (when (empty? files)
      (println "No matching .nal files.")
      (System/exit 1))
    (println "NAL directory:" (:dir opts))
    (println "Running compatibility checks on" (count files) "files")
    (println "Excluded commands: *setvalue" (if (:include-space? opts) "" "and *space"))
    (println "Timeout per engine run:" (:timeout-sec opts) "seconds")
    (println)
    (let [results
          (mapv
            (fn [f]
              (let [content (slurp f)
                    items (expected-items content)
                    ona-run (run-ona f (:timeout-sec opts))
                    fnars-run (run-fnars f (:timeout-sec opts))
                    ona-eval (run->eval "ONA" ona-run items (:timeout-sec opts) evaluate)
                    fnars-eval (run->eval "fNARS" fnars-run items (:timeout-sec opts) evaluate-fnars)]
                (when (:verbose? opts)
                  (println (format "%-36s checks=%-3d ONA=%s(%.2fs) fNARS=%s(%.2fs)"
                                   (fs/file-name f)
                                   (count items)
                                   (if (:pass? ona-eval) "PASS" "FAIL")
                                   (/ (:duration-ms ona-run 0.0) 1000.0)
                                   (if (:pass? fnars-eval) "PASS" "FAIL")
                                   (/ (:duration-ms fnars-run 0.0) 1000.0))))
                {:file f
                 :items items
                 :ona-run ona-run
                 :fnars-run fnars-run
                 :ona ona-eval
                 :fnars fnars-eval}))
            files)
          rows (mapv summarize-row results)
          total (count rows)
          ona-pass (count (filter #(= "PASS" (:ona %)) rows))
          fnars-pass (count (filter #(= "PASS" (:fnars %)) rows))
          both-pass (count (filter #(and (= "PASS" (:ona %)) (= "PASS" (:fnars %))) rows))]
      (print-table rows)
      (println)
      (println "Summary:")
      (println "  Files:" total)
      (println "  ONA pass:" ona-pass "/" total)
      (println "  fNARS pass:" fnars-pass "/" total)
      (println "  Both pass:" both-pass "/" total)
      (println)
      (println "Mismatches (ONA PASS, fNARS FAIL):")
      (doseq [{:keys [file ona fnars]} results
              :when (and (:pass? ona) (not (:pass? fnars)))]
        (println " -" (fs/file-name file) "::" (str/join ", " (:failed fnars)))))))

(apply -main *command-line-args*)
