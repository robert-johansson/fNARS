#!/usr/bin/env bb

(ns nal-sync
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def default-manifest "examples/nal/MANIFEST.edn")

(defn usage []
  (println "Usage: bb nal:sync [--manifest PATH] [--category NAME (repeatable)] [--clean] [--dry-run]"))

(defn ->kw [s]
  (keyword (str/replace s #"^:" "")))

(defn parse-args [args]
  (loop [args args
         opts {:manifest default-manifest
               :categories []
               :clean? false
               :dry-run? false}]
    (if (empty? args)
      opts
      (let [a (first args)
            more (rest args)]
        (case a
          "--manifest" (if-let [m (first more)]
                         (recur (rest more) (assoc opts :manifest m))
                         (do (println "Missing value for --manifest") (System/exit 1)))
          "--category" (if-let [c (first more)]
                         (recur (rest more) (update opts :categories conj (->kw c)))
                         (do (println "Missing value for --category") (System/exit 1)))
          "--clean" (recur more (assoc opts :clean? true))
          "--dry-run" (recur more (assoc opts :dry-run? true))
          "--help" (do (usage) (System/exit 0))
          (do
            (println "Unknown argument:" a)
            (usage)
            (System/exit 1)))))))

(defn load-manifest [path]
  (-> path slurp edn/read-string))

(defn normalize-entry [entry]
  (if (string? entry) {:file entry} entry))

(defn categories-in-manifest [entries]
  (->> entries
       (map :category)
       (remove nil?)
       set))

(defn selected-categories [entries requested]
  (let [available (categories-in-manifest entries)]
    (if (seq requested)
      (do
        (doseq [c requested]
          (when-not (contains? available c)
            (println "Unknown category in manifest:" c)
            (println "Available categories:" (str/join ", " (map name (sort available))))
            (System/exit 1)))
        (vec requested))
      (vec (sort available)))))

(defn source-path [source-dir entry]
  (let [src (:source entry)]
    (if (seq src)
      (if (fs/absolute? (fs/path src))
        src
        (str (fs/path source-dir src)))
      (str (fs/path source-dir (:file entry))))))

(defn validate-manifest! [{:keys [source-dir target-dir files]}]
  (when-not (seq source-dir)
    (println "Manifest is missing :source-dir")
    (System/exit 1))
  (when-not (seq target-dir)
    (println "Manifest is missing :target-dir")
    (System/exit 1))
  (when-not (seq files)
    (println "Manifest has no :files entries")
    (System/exit 1))
  (let [entries (map normalize-entry files)
        missing-file (some #(when-not (seq (:file %)) %) entries)
        duplicate-files (->> entries
                             (group-by :file)
                             (filter (fn [[_ es]] (> (count es) 1)))
                             (map first)
                             sort)]
    (when missing-file
      (println "Manifest contains entry without :file:" missing-file)
      (System/exit 1))
    (when (seq duplicate-files)
      (println "Manifest contains duplicate :file entries:")
      (doseq [f duplicate-files]
        (println " -" f))
      (System/exit 1))))

(defn clean-target-dir! [dir dry-run?]
  (when (fs/exists? dir)
    (doseq [f (fs/glob dir "*.nal")]
      (if dry-run?
        (println "DRY-RUN remove" (str f))
        (fs/delete-if-exists f)))))

(defn sync-entry!
  [{:keys [source-dir target-dir dry-run?]} entry]
  (let [entry (normalize-entry entry)
        file (:file entry)
        source (source-path source-dir entry)
        target (str (fs/path target-dir file))]
    (if-not (fs/exists? source)
      {:file file :ok? false :reason (str "missing source " source)}
      (do
        (when-not dry-run?
          (fs/create-dirs target-dir)
          (fs/copy source target {:replace-existing true}))
        {:file file :ok? true :source source :target target}))))

(defn -main [& args]
  (let [{:keys [manifest categories clean? dry-run?]} (parse-args args)
        m (load-manifest manifest)
        _ (validate-manifest! m)
        source-dir (:source-dir m)
        target-dir (:target-dir m)
        all-entries (mapv normalize-entry (:files m))
        cats (selected-categories all-entries categories)
        selected-set (set cats)
        entries (->> all-entries
                     (filter (fn [{:keys [category]}]
                               (and category (contains? selected-set category))))
                     vec)]
    (println "Manifest:" manifest)
    (println "Source dir:" source-dir)
    (println "Target dir:" target-dir)
    (println "Categories:" (str/join ", " (map name cats)))
    (println "Clean:" clean? "Dry-run:" dry-run?)
    (println)
    (when clean?
      (clean-target-dir! target-dir dry-run?))
    (let [results (mapv (fn [entry]
                          (sync-entry!
                            {:source-dir source-dir
                             :target-dir target-dir
                             :dry-run? dry-run?}
                            entry))
                    entries)
          ok (count (filter :ok? results))
          failed (remove :ok? results)
          by-category (->> entries
                           (group-by :category)
                           (map (fn [[category es]]
                                  [category (count es)]))
                           (sort-by (comp name first)))]
      (println (str ok "/" (count entries) " synced"))
      (doseq [[category count] by-category]
        (println " -" (name category) ":" count "file(s)"))
      (doseq [{:keys [file reason]} failed]
        (println " -" file ":" reason))
      (println))))

(apply -main *command-line-args*)
