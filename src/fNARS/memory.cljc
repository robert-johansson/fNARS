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
  [atom-index term-key term]
  (loop [i 0 idx atom-index]
    (if (>= i term/compound-term-size-max)
      idx
      (let [id (get term i)]
        (recur (inc i)
               (if (and (pos? id) (not (ar/copula-id? id)))
                 (update idx id (fnil conj #{}) term-key)
                 idx))))))

(defn- remove-from-atom-index
  "Remove a term's atoms from the inverted atom index."
  [atom-index term]
  (loop [i 0 idx atom-index]
    (if (>= i term/compound-term-size-max)
      idx
      (let [id (get term i)]
        (recur (inc i)
               (if (and (pos? id) (not (ar/copula-id? id)))
                 (update idx id disj term)
                 idx))))))

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
                  atom-index (remove-from-atom-index (:atom-index state) (:term worst-concept))
                  state (-> (assoc state :atom-index atom-index)
                            (perf-inc :concepts-evicted))
                  concepts (dissoc concepts worst-key)]
              [state concepts])
            [state concepts])
          new-concept (concept/make-concept concept-id term current-time)
          concepts (assoc concepts term new-concept)
          atom-index (add-to-atom-index (:atom-index state) term term)
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
        exact (when-let [c (get concepts term)] [c])
        ;; Collect atom IDs from first `depth` non-zero positions
        atoms-to-check (loop [i 0 n 0 result []]
                         (if (or (>= i term/compound-term-size-max) (>= n depth))
                           result
                           (let [id (get term i)]
                             (if (pos? id)
                               (recur (inc i) (inc n) (conj result id))
                               (recur (inc i) n result)))))
        related-keys (into #{}
                       (mapcat #(get atom-index %))
                       atoms-to-check)
        related (keep #(get concepts %) related-keys)]
    (distinct (concat exact related))))

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
