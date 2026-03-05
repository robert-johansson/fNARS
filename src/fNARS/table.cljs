(ns fNARS.table
  "Implication table matching ONA's Table.c.
   Sorted vector of implications ordered by truth expectation.
   Fixed max size (TABLE_SIZE)."
  (:require [fNARS.truth :as truth]
            [fNARS.term :as term]))

(defn table-init
  "Create an empty table."
  []
  {:items []
   :max-size 120})

(defn table-add
  "Add an implication to the table, maintaining sort order by truth expectation.
   Replaces lowest-expectation item if full and new item has higher expectation.
   If same term exists with lower confidence, replaces it.
   Returns {:table new-table :added implication-or-nil}."
  [table implication]
  (let [{:keys [items max-size]} table
        imp-exp (truth/truth-expectation (:truth implication))
        count (count items)]
    ;; Find insertion point
    (loop [i 0]
      (if (>= i (min count max-size))
        ;; Reached the end or max size
        (if (< count max-size)
          ;; Still room, append
          {:table (assoc table :items (conj items implication))
           :added implication}
          ;; Full, can't add (all existing have higher expectation)
          {:table table :added nil})
        (let [existing (nth items i)
              existing-exp (truth/truth-expectation (:truth existing))
              same-term? (term/term-equal (:term existing) (:term implication))]
          (if (or (and same-term? (> (:confidence (:truth implication))
                                     (:confidence (:truth existing))))
                  (and (not same-term?) (> imp-exp existing-exp))
                  (== i count))
            ;; Insert here, shift rest down, evict last if at max
            (let [before (subvec items 0 i)
                  after (subvec items i (min count (dec max-size)))
                  new-items (vec (concat before [implication] after))]
              {:table (assoc table :items new-items)
               :added implication})
            (recur (inc i))))))))

(defn table-remove
  "Remove implication at index. Shifts remaining up."
  [table index]
  (let [items (:items table)
        new-items (vec (concat (subvec items 0 index)
                               (subvec items (inc index))))]
    (assoc table :items new-items)))

(defn table-add-and-revise
  "Add implication to table. If same term exists, revise with it first.
   revision-fn takes two implications and returns the revised implication.
   Returns {:table new-table :added implication-or-nil}."
  [table implication revision-fn]
  (let [items (:items table)
        same-idx (first (keep-indexed
                          (fn [i imp]
                            (when (term/term-equal (:term imp) (:term implication))
                              i))
                          items))]
    (if same-idx
      ;; Found same term - revise
      (let [old-imp (nth items same-idx)
            revised (revision-fn old-imp implication)
            ;; Remove old, add revised
            table-without (table-remove table same-idx)
            result (table-add table-without revised)]
        result)
      ;; No match - just add
      (table-add table implication))))
