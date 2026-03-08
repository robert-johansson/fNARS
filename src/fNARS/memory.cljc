(ns fNARS.memory
  "NAR memory management matching ONA's Memory.c."
  (:require [fNARS.term :as term]
            [fNARS.concept :as concept]
            [fNARS.event :as event]
            [fNARS.truth :as truth]
            [fNARS.stamp :as stamp]
            [fNARS.atom-registry :as ar]
            [fNARS.priority-queue :as pq]
            [fNARS.table :as table]))

;; -- Inverted Atom Index --
;; Maps integer atom ID -> set of term-keys (IntTerms) that contain that atom

(defn- add-to-atom-index
  "Add a term's atoms to the inverted atom index."
  [atom-index term-key term max-depth]
  (let [limit (min (max 0 (long max-depth)) term/compound-term-size-max)]
    (loop [i 0 idx atom-index]
      (if (>= i limit)
        idx
        (let [id (get term i)]
          (recur (inc i)
                 (if (and (pos? id) (not (ar/copula-id? id)))
                   ;; Preserve insertion order to mirror ONA's concept chains.
                   (update idx id
                     (fn [entries]
                       (let [entries (or entries [])]
                         (if (some #(= % term-key) entries)
                           entries
                           (conj entries term-key)))))
                   idx)))))))

(defn- remove-from-atom-index
  "Remove a term's atoms from the inverted atom index."
  [atom-index term max-depth]
  (let [limit (min (max 0 (long max-depth)) term/compound-term-size-max)]
    (loop [i 0 idx atom-index]
      (if (>= i limit)
        idx
        (let [id (get term i)]
          (recur (inc i)
                 (if (and (pos? id) (not (ar/copula-id? id)))
                   (update idx id
                     (fn [entries]
                       (if (seq entries)
                         (into [] (remove #(= % term) entries))
                         entries)))
                   idx)))))))

;; -- Occurrence Time Index --

(defn time-index-add
  "Add a concept term-key to the occurrence time index."
  [time-index term-key max-size]
  (let [{:keys [items current-index]} time-index
        items (if (< (count items) max-size)
                (conj items term-key)
                (assoc items current-index term-key))
        next-idx (mod (if (< (count items) max-size)
                        (count items)
                        (inc current-index))
                      max-size)]
    {:items items :current-index next-idx}))

;; -- Concept Memory --

(defn find-concept
  "Find a concept by its term."
  [state term]
  (get-in state [:concepts term]))

(defn- perf-enabled?
  [state]
  (true? (get-in state [:config :perf-instrumentation])))

(defn- perf-inc
  [state counter-key]
  (if (perf-enabled? state)
    (update-in state [:perf :counters counter-key] (fnil inc 0))
    state))

(defn conceptualize
  "Find or create a concept for a term.
   Returns [new-state concept]."
  [state term current-time]
  (if-let [existing (find-concept state term)]
    [state existing]
    (let [config (:config state)
          unification-depth (:unification-depth config)
          concepts (:concepts state)
          concept-id (:next-concept-id state 0)
          [state concepts]
          (if (>= (count concepts) (:concepts-max config))
            (let [worst (apply min-key
                               (fn [[k c]]
                                 (concept/usage-usefulness (:usage c) current-time))
                               concepts)
                  worst-key (first worst)
                  worst-concept (second worst)
                  atom-index (remove-from-atom-index
                               (:atom-index state)
                               (:term worst-concept)
                               unification-depth)
                  state (-> (assoc state :atom-index atom-index)
                            (perf-inc :concepts-evicted))
                  concepts (dissoc concepts worst-key)]
              [state concepts])
            [state concepts])
          new-concept (concept/make-concept concept-id term current-time)
          concepts (assoc concepts term new-concept)
          atom-index (add-to-atom-index
                       (:atom-index state)
                       term
                       term
                       unification-depth)
          state (-> (assoc state
                      :concepts concepts
                      :atom-index atom-index
                      :next-concept-id (inc concept-id))
                    (perf-inc :concepts-created))]
      [state new-concept])))

(defn update-concept
  "Update a concept in the state."
  [state term update-fn]
  (if (get-in state [:concepts term])
    (update-in state [:concepts term] update-fn)
    state))

(defn related-concepts
  "Get concepts related to a term via the inverted atom index."
  [state term depth]
  (let [atom-index (:atom-index state)
        concepts (:concepts state)
        limit (min (max 0 (long depth)) term/compound-term-size-max)
        exact (get concepts term)
        seen0 (if exact #{(:id exact)} #{})
        acc0 (if exact [exact] [])]
    (second
      (loop [i 0 seen seen0 acc acc0]
        (if (>= i limit)
          [seen acc]
          (let [atom-id (get term i)]
            (if (pos? atom-id)
              (let [[seen acc]
                    (reduce
                      (fn [[seen acc] term-key]
                        (if-let [c (get concepts term-key)]
                          (if (contains? seen (:id c))
                            [seen acc]
                            [(conj seen (:id c)) (conj acc c)])
                          [seen acc]))
                      [seen acc]
                      (get atom-index atom-id))]
                (recur (inc i) seen acc))
              (recur (inc i) seen acc))))))))

(defn get-operation-id
  "Get the operation ID for an operation term. Returns 0 if not found."
  [state term]
  (let [root (term/term-root term)
        op-atom-id (cond
                     (term/is-operator? root)
                     root

                     (and (== root term/inheritance)
                          (== (term/term-get term 1) term/product)
                          (term/is-operator? (term/term-get term 2)))
                     (term/term-get term 2)

                     :else nil)]
    (if op-atom-id
      (let [op-kw (ar/resolve-atom op-atom-id)]
        (or (first (keep (fn [[k v]]
                           (when (= (:atom v) op-kw) k))
                         (:operations state)))
            0))
      0)))
