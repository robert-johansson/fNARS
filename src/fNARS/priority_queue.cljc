(ns fNARS.priority-queue
  "Functional bounded priority queue matching ONA's PriorityQueue.c.
   Implemented as a sorted vector (ascending by priority) for ONA's small sizes.
   Supports O(n) push with eviction of minimum, O(1) pop-max.")

(defn pq-init
  "Create an empty priority queue with max-size."
  [max-size]
  {:items []
   :max-size max-size})

(defn pq-push
  "Push an item with priority into the queue.
   If full, evicts the minimum priority item if new priority is higher.
   Returns {:pq new-pq :added? bool :evicted nil-or-{:priority :item}}."
  [pq priority item]
  (let [{:keys [items max-size]} pq
        entry {:priority priority :item item}
        cnt (count items)]
    (if (< cnt max-size)
      ;; Not full: insert in sorted position
      (let [idx (count (take-while #(< (:priority %) priority) items))
            new-items (vec (concat (subvec items 0 idx)
                                  [entry]
                                  (subvec items idx)))]
        {:pq (assoc pq :items new-items)
         :added? true
         :evicted nil})
      ;; Full: check if we should evict minimum
      (if (and (pos? cnt) (> priority (:priority (first items))))
        (let [evicted (first items)
              rest-items (subvec items 1)
              idx (count (take-while #(< (:priority %) priority) rest-items))
              new-items (vec (concat (subvec rest-items 0 idx)
                                    [entry]
                                    (subvec rest-items idx)))]
          {:pq (assoc pq :items new-items)
           :added? true
           :evicted evicted})
        ;; New item has lower priority than minimum, don't add
        {:pq pq
         :added? false
         :evicted nil}))))

(defn pq-pop-max
  "Remove and return the maximum priority item.
   Returns {:pq new-pq :item item :priority priority} or nil if empty."
  [pq]
  (let [items (:items pq)]
    (when (seq items)
      (let [max-entry (peek items)]
        {:pq (assoc pq :items (pop items))
         :item (:item max-entry)
         :priority (:priority max-entry)}))))

(defn pq-pop-min
  "Remove and return the minimum priority item.
   Returns {:pq new-pq :item item :priority priority} or nil if empty."
  [pq]
  (let [items (:items pq)]
    (when (seq items)
      (let [min-entry (first items)]
        {:pq (assoc pq :items (vec (rest items)))
         :item (:item min-entry)
         :priority (:priority min-entry)}))))

(defn pq-pop-at
  "Remove item at index i. Returns {:pq new-pq :item item :priority priority} or nil."
  [pq i]
  (let [items (:items pq)]
    (when (and (>= i 0) (< i (count items)))
      (let [entry (nth items i)]
        {:pq (assoc pq :items (vec (concat (subvec items 0 i)
                                           (subvec items (inc i)))))
         :item (:item entry)
         :priority (:priority entry)}))))

(defn pq-rebuild
  "Multiply all priorities by decay-factor and re-sort."
  [pq decay-factor]
  (let [items (:items pq)
        decayed (mapv (fn [e] (update e :priority * decay-factor)) items)]
    ;; Items remain sorted since we multiply by same factor
    (assoc pq :items decayed)))

(defn pq-count
  "Number of items in the queue."
  [pq]
  (count (:items pq)))

(defn pq-items
  "Get all items in the queue."
  [pq]
  (:items pq))

(defn pq-contains?
  "Check if queue contains an item matching predicate."
  [pq pred]
  (some #(pred (:item %)) (:items pq)))

(defn pq-update-item
  "Update the first item matching pred with update-fn. Re-sorts."
  [pq pred update-fn]
  (let [items (:items pq)
        idx (first (keep-indexed (fn [i e] (when (pred (:item e)) i)) items))]
    (if idx
      (let [entry (nth items idx)
            new-entry (update entry :item update-fn)
            without (vec (concat (subvec items 0 idx)
                                 (subvec items (inc idx))))
            new-items (vec (sort-by :priority without))
            ins-idx (count (take-while #(< (:priority %) (:priority new-entry)) new-items))
            final (vec (concat (subvec new-items 0 ins-idx)
                               [new-entry]
                               (subvec new-items ins-idx)))]
        (assoc pq :items final))
      pq)))
