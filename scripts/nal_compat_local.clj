#!/usr/bin/env bb

(ns nal-compat-local
  (:require [babashka.process :as proc]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def default-manifest "examples/nal/MANIFEST.edn")
(def default-category :works-splendidly)

(defn usage []
  (println "Usage: bb nal:compat-local [--manifest PATH] [--category NAME (repeatable)] [--all-categories] [args passed to nal:compat]"))

(defn ->kw [s]
  (keyword (str/replace s #"^:" "")))

(defn parse-args [args]
  (loop [args args
         opts {:manifest default-manifest
               :categories []
               :all-categories? false
               :passthrough []}]
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
          "--all-categories" (recur more (assoc opts :all-categories? true))
          "--help" (do (usage) (System/exit 0))
          (recur more (update opts :passthrough conj a)))))))

(defn load-manifest [path]
  (-> path slurp edn/read-string))

(defn normalize-entry [entry]
  (if (string? entry) {:file entry} entry))

(defn validate-manifest! [{:keys [target-dir files]}]
  (when-not (seq target-dir)
    (println "Manifest is missing :target-dir")
    (System/exit 1))
  (when-not (seq files)
    (println "Manifest has no :files entries")
    (System/exit 1)))

(defn selected-categories [entries {:keys [categories all-categories?]}]
  (let [available (->> entries (map :category) (remove nil?) set)
        chosen (cond
                 all-categories? (sort available)
                 (seq categories) categories
                 :else [default-category])]
    (doseq [c chosen]
      (when-not (contains? available c)
        (println "Unknown category in manifest:" c)
        (println "Available categories:" (str/join ", " (map name (sort available))))
        (System/exit 1)))
    (vec chosen)))

(defn has-user-file-filter? [args]
  (boolean (some #{"--file"} args)))

(defn selected-files [entries categories]
  (let [selected-set (set categories)]
    (->> entries
         (filter (fn [{:keys [category]}]
                   (and category (contains? selected-set category))))
         (map :file)
         vec)))

(defn -main [& args]
  (let [{:keys [manifest categories all-categories? passthrough]} (parse-args args)
        m (load-manifest manifest)
        _ (validate-manifest! m)
        target-dir (:target-dir m)
        entries (mapv normalize-entry (:files m))
        chosen-categories (selected-categories entries {:categories categories
                                                        :all-categories? all-categories?})
        use-manifest-files? (not (has-user-file-filter? passthrough))
        files (if use-manifest-files?
                (selected-files entries chosen-categories)
                [])
        file-args (mapcat (fn [f] ["--file" f]) files)
        cmd (vec (concat ["bb" "scripts/nal_compat.clj" "--dir" target-dir]
                    file-args
                    passthrough))]
    (when use-manifest-files?
      (println "Categories:" (str/join ", " (map name chosen-categories)))
      (println "Selected files:" (count files))
      (when (empty? files)
        (println "No files matched selected categories.")
        (System/exit 1))
      (println))
    (let [result @(proc/process cmd {:out :inherit :err :inherit})]
      (System/exit (:exit result)))))

(apply -main *command-line-args*)
