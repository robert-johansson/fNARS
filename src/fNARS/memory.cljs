(ns fNARS.memory
  "NAR memory management matching ONA's Memory.c.
   Concept store with inverted atom index and occurrence time index.
   All state is immutable - returned as new state maps."
  (:require [fNARS.term :as term]
            [fNARS.concept :as concept]
            [fNARS.event :as event]
            [fNARS.truth :as truth]
            [fNARS.stamp :as stamp]
            [fNARS.priority-queue :as pq]
            [fNARS.table :as table]))

;; -- Inverted Atom Index --
;; Maps atom -> set of term-keys that contain that atom

(defn- add-to-atom-index
  "Add a term's atoms to the inverted atom index."
  [atom-index term-key term]
  (reduce
    (fn [idx atom]
      (if (and (some? atom) (not (term/is-copula? atom)))
        (update idx atom (fnil conj #{}) term-key)
        idx))
    atom-index
    term))

(defn- remove-from-atom-index
  "Remove a term's atoms from the inverted atom index."
  [atom-index term]
  (reduce
    (fn [idx atom]
      (if (and (some? atom) (not (term/is-copula? atom)))
        (update idx atom disj term)
        idx))
    atom-index
    term))

;; -- Occurrence Time Index --
;; Circular buffer of concept term-keys for temporal correlation

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

(defn conceptualize
  "Find or create a concept for a term.
   Returns [new-state concept]."
  [state term current-time]
  (if-let [existing (find-concept state term)]
    [state existing]
    ;; Create new concept
    (let [config (:config state)
          concepts (:concepts state)
          concept-id (:next-concept-id state 0)
          ;; Check capacity - evict if needed
          [state concepts]
          (if (>= (count concepts) (:concepts-max config))
            ;; Evict lowest-usefulness concept
            (let [worst (apply min-key
                               (fn [[k c]]
                                 (concept/usage-usefulness (:usage c) current-time))
                               concepts)
                  worst-key (first worst)
                  worst-concept (second worst)
                  ;; Remove from atom index
                  atom-index (remove-from-atom-index (:atom-index state) (:term worst-concept))
                  state (assoc state :atom-index atom-index)
                  concepts (dissoc concepts worst-key)]
              [state concepts])
            [state concepts])
          ;; Create the concept
          new-concept (concept/make-concept concept-id term current-time)
          ;; Add to concepts map
          concepts (assoc concepts term new-concept)
          ;; Add to atom index
          atom-index (add-to-atom-index (:atom-index state) term term)
          state (assoc state
                  :concepts concepts
                  :atom-index atom-index
                  :next-concept-id (inc concept-id))]
      [state new-concept])))

(defn update-concept
  "Update a concept in the state."
  [state term update-fn]
  (if (get-in state [:concepts term])
    (update-in state [:concepts term] update-fn)
    state))

(defn related-concepts
  "Get concepts related to a term via the inverted atom index.
   Includes exact match + all concepts sharing any atom.
   Matches C's RELATED_CONCEPTS_FOREACH."
  [state term depth]
  (let [atom-index (:atom-index state)
        concepts (:concepts state)
        ;; Exact match
        exact (when-let [c (get concepts term)] [c])
        ;; Atoms in the term (up to unification-depth positions)
        atoms-to-check (take depth (filter some? term))
        ;; Lookup via inverted atom index
        related-keys (into #{}
                       (mapcat #(get atom-index %))
                       atoms-to-check)
        related (keep #(get concepts %) related-keys)]
    (distinct (concat exact related))))

(defn get-operation-id
  "Get the operation ID for an operation term. Returns 0 if not found."
  [state term]
  (let [op-atom (cond
                  (term/is-operator? (term/term-root term))
                  (term/term-root term)

                  (and (= (term/term-root term) term/inheritance)
                       (= (get term 1) term/product)
                       (term/is-operator? (get term 2)))
                  (get term 2)

                  :else nil)]
    (if op-atom
      (or (first (keep (fn [[k v]]
                         (when (= (:atom v) op-atom) k))
                       (:operations state)))
          0)
      0)))
