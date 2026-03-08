#!/usr/bin/env bb

(ns nal-compat
  (:require [babashka.fs :as fs]
            [clojure.java.shell :as sh]
            [clojure.string :as str]))

(def examples-dir "OpenNARS-for-Applications/examples/nal")
(def ona-bin "OpenNARS-for-Applications/NAR")
(def fnars-cmd
  ["bunx" "--bun" "nbb" "-cp" "src:lib/instaparse/src" "-m" "fNARS.nal-runner"])

(defn usage []
  (println "Usage: bb nal:compat [--limit N] [--file NAME_OR_PATH] [--include-space] [--verbose]"))

(defn parse-args [args]
  (loop [args args opts {:limit nil :file nil :include-space? false :verbose? false}]
    (if (empty? args)
      opts
      (let [a (first args)
            more (rest args)]
        (case a
          "--limit" (if-let [n (first more)]
                      (recur (rest more) (assoc opts :limit (parse-long n)))
                      (do (println "Missing value for --limit") (System/exit 1)))
          "--file" (if-let [f (first more)]
                     (recur (rest more) (assoc opts :file f))
                     (do (println "Missing value for --file") (System/exit 1)))
          "--include-space" (recur more (assoc opts :include-space? true))
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

(defn list-nal-files [{:keys [file limit include-space?]}]
  (let [all-files (->> (fs/glob examples-dir "*.nal")
                       (map str)
                       sort)
        filtered
        (cond
          file
          (filter #(or (= % file)
                       (= (fs/file-name %) file)
                       (str/includes? % file))
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

(defn run-cmd [{:keys [cmd cwd]}]
  (let [{:keys [exit out err]}
        (sh/sh "bash" "-lc" cmd :dir (or cwd "."))]
    {:exit exit
     :out (or out "")
     :err (or err "")}))

(defn run-ona [file]
  (run-cmd {:cmd (str ona-bin " shell < " (sh-quote file))
            :cwd "."}))

(defn run-fnars [file]
  (run-cmd {:cmd (str/join " " (concat fnars-cmd [file]))
            :cwd "."}))

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

(defn summarize-row [{:keys [file items ona fnars]}]
  {:file file
   :checks (count items)
   :ona (if (:pass? ona) "PASS" "FAIL")
   :fnars (if (:pass? fnars) "PASS" "FAIL")})

(defn print-table [rows]
  (println "| File | Checks | ONA | fNARS |")
  (println "| --- | ---: | :---: | :---: |")
  (doseq [{:keys [file checks ona fnars]} rows]
    (println (str "| " (fs/file-name file) " | " checks " | " ona " | " fnars " |"))))

(defn -main [& args]
  (let [opts (parse-args args)
        files (list-nal-files opts)]
    (when (empty? files)
      (println "No matching .nal files.")
      (System/exit 1))
    (println "Running compatibility checks on" (count files) "files")
    (println "Excluded commands: *setvalue" (if (:include-space? opts) "" "and *space"))
    (println)
    (let [results
          (mapv
            (fn [f]
              (let [content (slurp f)
                    items (expected-items content)
                    ona-run (run-ona f)
                    fnars-run (run-fnars f)
                    ona-eval (evaluate (:out ona-run) items)
                    fnars-eval (evaluate-fnars (:out fnars-run) items)]
                (when (:verbose? opts)
                  (println (format "%-36s checks=%-3d ONA=%s fNARS=%s"
                                   (fs/file-name f)
                                   (count items)
                                   (if (:pass? ona-eval) "PASS" "FAIL")
                                   (if (:pass? fnars-eval) "PASS" "FAIL"))))
                {:file f
                 :items items
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
