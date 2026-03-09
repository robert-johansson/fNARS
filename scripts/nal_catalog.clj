#!/usr/bin/env bb

(ns nal-catalog
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(def examples-dir "OpenNARS-for-Applications/examples/nal")

(defn usage []
  (println "Usage: bb scripts/nal_catalog.clj [--format summary|table|csv] [--dir PATH]"))

(defn parse-args [args]
  (loop [args args
         opts {:format :summary
               :dir examples-dir}]
    (if (empty? args)
      opts
      (let [a (first args)
            more (rest args)]
        (case a
          "--format" (if-let [fmt (first more)]
                       (recur (rest more)
                         (assoc opts :format
                           (case fmt
                             "summary" :summary
                             "table" :table
                             "csv" :csv
                             (do
                               (println "Unknown format:" fmt)
                               (usage)
                               (System/exit 1)))))
                       (do
                         (println "Missing value for --format")
                         (System/exit 1)))
          "--dir" (if-let [d (first more)]
                    (recur (rest more) (assoc opts :dir d))
                    (do
                      (println "Missing value for --dir")
                      (System/exit 1)))
          "--help" (do
                     (usage)
                     (System/exit 0))
          (do
            (println "Unknown argument:" a)
            (usage)
            (System/exit 1)))))))

(defn line-command [line]
  (let [trimmed (str/trim line)]
    (when (str/starts-with? trimmed "*")
      (let [token (first (str/split trimmed #"\s+"))
            eq-pos (.indexOf token "=")]
        (if (neg? eq-pos)
          token
          (subs token 0 eq-pos))))))

(defn numeric-line? [line]
  (boolean (re-matches #"\d+" (str/trim line))))

(defn expectation-line? [line]
  (let [trimmed (str/trim line)]
    (or (str/starts-with? trimmed "//expected:")
        (str/starts-with? trimmed "//--expected:"))))

(defn goal-line? [line]
  (let [trimmed (str/trim line)]
    (and (not (str/starts-with? trimmed "//"))
         (re-find #"![\s.]|!:\|:|!\s*:|\>!" trimmed))))

(defn question-line? [line]
  (let [trimmed (str/trim line)]
    (and (not (str/starts-with? trimmed "//"))
         (str/includes? trimmed "?"))))

(defn classify-profile
  [{:keys [setvalue? space? operations? temporal? higher-order? variables? goals? questions?]
    :as profile}]
  (cond
    (or setvalue? space?) :blocked
    ;; heavy mixed declarative + procedural profile
    (and operations? temporal? higher-order? variables? goals? questions?) :hard
    ;; large declarative profile
    (and higher-order? variables? questions?) :hard
    ;; procedural-heavy profile
    (and operations? temporal? goals?) :hard
    ;; moderate mixed profile
    (or (and operations? temporal?)
        (and higher-order? questions?)
        (and variables? questions?)) :medium
    :else :easy))

(defn complexity-score
  [{:keys [line-count command-count expected-count cycle-lines cycle-sum setvalue? space? operations? temporal?
           higher-order? variables? goals? questions? parallel? concurrent?]}]
  (+ (if setvalue? 100 0)
     (if space? 100 0)
     (if operations? 18 0)
     (if temporal? 16 0)
     (if higher-order? 12 0)
     (if variables? 12 0)
     (if goals? 8 0)
     (if questions? 8 0)
     (if parallel? 8 0)
     (if concurrent? 4 0)
     (* 2 expected-count)
     (* 2 command-count)
     (if (> cycle-lines 10) 4 0)
     (if (> cycle-sum 500) 6 0)
     (if (> line-count 300) 8 0)))

(defn nal-profile [file]
  (let [content (slurp (str file))
        lines (str/split-lines content)
        commands (->> lines
                      (keep line-command)
                      vec)
        command-set (set commands)
        cycle-values (->> lines
                          (filter numeric-line?)
                          (map parse-long)
                          (remove nil?)
                          vec)
        expected-count (count (filter expectation-line? lines))
        setvalue? (str/includes? content "*setvalue")
        space? (str/includes? content "*space")
        operations? (or (str/includes? content "^")
                        (contains? command-set "*setopname")
                        (contains? command-set "*setopstdin"))
        temporal? (or (str/includes? content "=/>")
                      (str/includes? content "&/"))
        higher-order? (or (str/includes? content "==>")
                          (str/includes? content "<=>"))
        variables? (boolean (or (re-find #"\{[\?\$#]" content)
                                (re-find #"\$[0-9]+" content)
                                (re-find #"\#[0-9]+" content)
                                (re-find #"\?[0-9]+" content)))
        goals? (boolean (some goal-line? lines))
        questions? (boolean (some question-line? lines))
        parallel? (str/includes? content "&|")
        concurrent? (contains? command-set "*concurrent")
        profile {:file (str file)
                 :name (fs/file-name file)
                 :line-count (count lines)
                 :command-count (count commands)
                 :commands (vec (sort command-set))
                 :expected-count expected-count
                 :cycle-lines (count cycle-values)
                 :cycle-sum (reduce + 0 cycle-values)
                 :setvalue? setvalue?
                 :space? space?
                 :operations? operations?
                 :temporal? temporal?
                 :higher-order? higher-order?
                 :variables? variables?
                 :goals? goals?
                 :questions? questions?
                 :parallel? parallel?
                 :concurrent? concurrent?}
        profile (assoc profile
                  :profile (classify-profile profile)
                  :complexity (complexity-score profile))]
    profile))

(defn list-nal-files [dir]
  (->> (fs/glob dir "*.nal")
       (map str)
       sort
       (map fs/file)
       vec))

(defn risk-tier [{:keys [profile complexity]}]
  (if (= profile :blocked)
    :blocked
    (cond
      (>= complexity 65) :hard
      (>= complexity 35) :medium
      :else :easy)))

(defn family
  [{:keys [operations? temporal? higher-order? variables?]}]
  (cond
    (and operations? temporal? (or higher-order? variables?)) "mixed (sensorimotor + declarative)"
    (and operations? temporal?) "sensorimotor/procedural (NAL 7-8 style)"
    (or higher-order? variables?) "declarative (NAL 5-6 style)"
    :else "basic declarative (NAL 1-4 style)"))

(defn profile->row [p]
  (assoc p
    :tier (risk-tier p)
    :family (family p)))

(defn print-summary [rows]
  (let [grouped (group-by :tier rows)
        blocked (sort (map :name (get grouped :blocked [])))
        hard (sort (map :name (get grouped :hard [])))
        medium (sort (map :name (get grouped :medium [])))
        easy (sort (map :name (get grouped :easy [])))]
    (println "NAL Catalog")
    (println "Files:" (count rows))
    (println)
    (println "Blocked (*space/*setvalue):" (count blocked))
    (doseq [n blocked] (println " -" n))
    (println)
    (println "Hard (likely expensive/high-risk):" (count hard))
    (doseq [n hard] (println " -" n))
    (println)
    (println "Medium (mixed features):" (count medium))
    (doseq [n medium] (println " -" n))
    (println)
    (println "Easy (basic profile):" (count easy))
    (doseq [n easy] (println " -" n))))

(defn print-table [rows]
  (println "| File | Tier | Family | Complexity | Expected | Cycles | Commands |")
  (println "| --- | :---: | --- | ---: | ---: | ---: | ---: |")
  (doseq [{fname :name :keys [tier family complexity expected-count cycle-sum command-count]} rows]
    (println (str "| " fname
                  " | " (clojure.core/name tier)
                  " | " family
                  " | " complexity
                  " | " expected-count
                  " | " cycle-sum
                  " | " command-count
                  " |"))))

(defn print-csv [rows]
  (println "file,tier,family,complexity,expected_count,cycle_sum,cycle_lines,command_count,commands")
  (doseq [{fname :name :keys [tier family complexity expected-count cycle-sum cycle-lines command-count commands]} rows]
    (println
      (str fname ","
           (clojure.core/name tier) ","
           "\"" family "\"" ","
           complexity ","
           expected-count ","
           cycle-sum ","
           cycle-lines ","
           command-count ","
           "\"" (str/join " " commands) "\""))))

(defn -main [& args]
  (let [{:keys [format dir]} (parse-args args)
        files (list-nal-files dir)
        rows (->> files
                  (map nal-profile)
                  (map profile->row)
                  (sort-by (juxt :tier (comp - :complexity) :name)))]
    (case format
      :summary (print-summary rows)
      :table (print-table rows)
      :csv (print-csv rows))))

(apply -main *command-line-args*)
