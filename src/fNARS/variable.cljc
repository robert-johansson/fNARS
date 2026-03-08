(ns fNARS.variable
  "Variable handling matching ONA's Variable.c.
   Variable atoms: $1-$9 (independent), #1-#9 (dependent), ?1-?9 (query).
   Unification, substitution, variable introduction, and normalization."
  (:require [fNARS.term :as term]
            [fNARS.truth :as truth]
            [fNARS.atom-registry :as ar]
            [fNARS.platform :as p]))

;; Note: p is still required for p/abs in unify-with-analogy

(defn independent-var?
  "Check if atom ID is an independent variable ($1-$9)."
  [atom-id]
  (ar/indep-var-id? atom-id))

(defn dependent-var?
  "Check if atom ID is a dependent variable (#1-#9)."
  [atom-id]
  (ar/dep-var-id? atom-id))

(defn query-var?
  "Check if atom ID is a query variable (?1-?9)."
  [atom-id]
  (ar/query-var-id? atom-id))

(defn variable?
  "Check if atom ID is any kind of variable."
  [atom-id]
  (ar/variable-id? atom-id))

(defn has-variable?
  "Check if term contains any variables of the specified types."
  ([t] (has-variable? t true true true))
  ([t independent? dependent? query?]
   (loop [i 0]
     (if (>= i term/compound-term-size-max)
       false
       (let [id (get t i)]
         (if (or (and independent? (independent-var? id))
                 (and dependent? (dependent-var? id))
                 (and query? (query-var? id)))
           true
           (recur (inc i))))))))

(defn make-var
  "Create a variable keyword. type is \\$ \\# or \\?, id is 1-9."
  [type id]
  (keyword (str type id)))

(defn make-var-id
  "Create a variable integer ID. type is \\$ \\# or \\?, num is 1-9."
  [type num]
  (ar/make-var-id type num))

;; -- Unification --

(defn unify2
  "Unify a general term (may contain variables) with a specific term.
   When query-var-only? is true, only query variables (?1-?9) can bind.
   Returns {:success true :substitution {var-id -> subterm}} or {:success false}."
  [general specific query-var-only?]
  (loop [i 0
         substitution {}]
    (if (>= i term/compound-term-size-max)
      {:success true :substitution substitution}
      (let [g-id (get general i)]
        (if (zero? g-id)
          (recur (inc i) substitution)
            (let [is-allowed-var? (if query-var-only?
                                    (query-var? g-id)
                                    (variable? g-id))]
              (if is-allowed-var?
                ;; Variable in general: extract corresponding subtree from specific
                (let [subtree (term/extract-subterm specific i)]
                  (cond
                    ;; Query var can't match a variable
                    (and (query-var? g-id) (variable? (term/term-root subtree)))
                    {:success false :substitution {}}

                    ;; Set terminator not allowed
                    (== (term/term-root subtree) term/set-terminator)
                    {:success false :substitution {}}

                    ;; Check consistency with existing binding
                    (and (contains? substitution g-id)
                         (not (term/term-equal (get substitution g-id) subtree)))
                    {:success false :substitution {}}

                    :else
                    (recur (inc i) (assoc substitution g-id subtree))))
                ;; Non-variable: must match exactly
                (let [s-id (term/term-get specific i)]
                  (if (not= g-id s-id)
                    {:success false :substitution {}}
                    (recur (inc i) substitution))))))))))

(defn unify
  "Unify a general term (may contain variables) with a specific term.
   Returns {:success true :substitution {var-id -> subterm}} or {:success false}."
  [general specific]
  (unify2 general specific false))

(defn unify-query
  "Unify allowing only query variables (?1-?9) to bind.
   Used for question answering."
  [general specific]
  (unify2 general specific true))

(defn unify-with-analogy
  "Unify with numeric term similarity. Matches ONA Variable.c:62-121.
   Returns {:success bool :substitution map :truth tv}."
  [initial-truth general specific atom-values config]
  (let [similarity-distance (:similarity-distance config 1.0)]
    (loop [i 0
           substitution {}
           tv initial-truth]
      (if (>= i term/compound-term-size-max)
        {:success true :substitution substitution :truth tv}
        (let [g-id (get general i)]
          (if (zero? g-id)
            (recur (inc i) substitution tv)
            (if (variable? g-id)
              ;; Variable: bind as in normal unify
              (let [subtree (term/extract-subterm specific i)]
                (cond
                  (and (query-var? g-id) (variable? (term/term-root subtree)))
                  {:success false :substitution {} :truth tv}

                  (== (term/term-root subtree) term/set-terminator)
                  {:success false :substitution {} :truth tv}

                  (and (contains? substitution g-id)
                       (not (term/term-equal (get substitution g-id) subtree)))
                  {:success false :substitution {} :truth tv}

                  :else
                  (recur (inc i) (assoc substitution g-id subtree) tv)))
              ;; Non-variable: check exact match or numeric similarity
              (let [s-id (term/term-get specific i)]
                (if (== g-id s-id)
                  (recur (inc i) substitution tv)
                  ;; Atoms differ — try numeric similarity
                  (let [g-kw (ar/resolve-atom g-id)
                        s-kw (ar/resolve-atom s-id)
                        g-info (get atom-values g-kw)
                        s-info (get atom-values s-kw)]
                    (if (and (:numeric-term-similarity config)
                             (> (:confidence tv) 0.0)
                             g-info s-info
                             (= (:measurement g-info) (:measurement s-info)))
                      (let [v1 (:value g-info)
                            v2 (:value s-info)
                            sim-conf (max 0.0 (- 1.0 (/ (p/abs (- v1 v2))
                                                         similarity-distance)))
                            new-tv (truth/truth-analogy tv {:frequency 1.0
                                                            :confidence sim-conf})]
                        (if (== (:confidence new-tv) 0.0)
                          {:success false :substitution {} :truth new-tv}
                          (recur (inc i) substitution new-tv)))
                      ;; Not numeric or different measurement — fail
                      {:success false :substitution {} :truth tv})))))))))))


(defn apply-substitute
  "Apply a substitution to a term, replacing variables with their bindings.
   Substitution maps integer var IDs to IntTerms."
  [t substitution]
  (if (empty? substitution)
    t
    (reduce
      (fn [current-term i]
        (let [atom-id (term/term-get current-term i)]
          (if (and (variable? atom-id) (contains? substitution atom-id))
            (term/override-subterm current-term i (get substitution atom-id))
            current-term)))
      t
      (range term/compound-term-size-max))))

;; -- Variable Introduction --

(defn- count-all-simple-atoms
  "Count all simple atoms in a term's flat array."
  [t]
  (loop [i 0 freqs {}]
    (if (>= i term/compound-term-size-max)
      freqs
      (let [id (get t i)]
        (recur (inc i)
               (if (term/simple-atom? id)
                 (update freqs id (fnil inc 0))
                 freqs))))))

(defn- merge-counts [a b]
  (merge-with + a b))

(defn- count-atoms-in-statements
  "Count occurrences of simple atoms in higher-order statement subterms."
  [t]
  (let [root (term/term-root t)]
    (cond
      (== root term/negation)
      (count-atoms-in-statements (term/extract-subterm t 1))

      (or (== root term/sequence*)
          (== root term/conjunction)
          (== root term/temporal-implication)
          (== root term/implication)
          (== root term/equivalence))
      (merge-counts
        (count-atoms-in-statements (term/extract-subterm t 1))
        (count-atoms-in-statements (term/extract-subterm t 2)))

      (or (== root term/inheritance)
          (== root term/similarity))
      (count-all-simple-atoms t)

      :else {})))

(defn- new-var-id
  "Find the next available variable ID (1-9) of the given type in the term."
  [t var-type]
  (first
    (for [id (range 1 10)
          :let [vid (make-var-id var-type id)]
          :when (not (term/has-atom t vid))]
      id)))

(defn introduce-implication-variables
  "Introduce variables in an implication term."
  [imp-term]
  (let [left (term/extract-subterm imp-term 1)
        right (term/extract-subterm imp-term 2)
        left-counts (count-atoms-in-statements left)
        right-counts (count-atoms-in-statements right)]
    (if-let [dep-start (new-var-id imp-term \#)]
      (if-let [indep-start (new-var-id imp-term \$)]
        (let [original imp-term]
          (loop [i 0
                 result imp-term
                 var-map {}
                 dep-id dep-start
                 indep-id indep-start]
            (if (>= i term/compound-term-size-max)
              {:term result :success? true}
              (let [atom-id (get original i)]
                (if (and (pos? atom-id) (term/simple-atom? atom-id))
                  (let [self-id (ar/intern-atom :SELF)
                        in-left (get left-counts atom-id)
                        in-right (get right-counts atom-id)
                        needs-var? (or (and in-left (>= in-left 2))
                                       (and in-right (>= in-right 2))
                                       (and in-left in-right))]
                    (if needs-var?
                      (if-let [existing-var (get var-map atom-id)]
                        ;; Already assigned a variable
                        (recur (inc i)
                               (term/override-subterm result i (term/atomic-term existing-var))
                               var-map dep-id indep-id)
                        ;; Assign new variable
                        (if (and in-left in-right)
                          ;; Independent: appears on both sides
                          (if (<= indep-id 9)
                            (let [vid (make-var-id \$ indep-id)]
                              (recur (inc i)
                                     (term/override-subterm result i (term/atomic-term vid))
                                     (assoc var-map atom-id vid)
                                     dep-id (inc indep-id)))
                            (recur (inc i) result var-map dep-id indep-id))
                          ;; Dependent: appears 2+ on one side only
                          (if (== atom-id self-id)
                            (recur (inc i) result var-map dep-id indep-id)
                            (if (<= dep-id 9)
                              (let [vid (make-var-id \# dep-id)]
                                (recur (inc i)
                                       (term/override-subterm result i (term/atomic-term vid))
                                       (assoc var-map atom-id vid)
                                       (inc dep-id) indep-id))
                              (recur (inc i) result var-map dep-id indep-id)))))
                      (recur (inc i) result var-map dep-id indep-id)))
                  (recur (inc i) result var-map dep-id indep-id))))))
        {:term imp-term :success? false})
      {:term imp-term :success? false})))

(defn normalize-variables
  "Normalize variable numbering in a term."
  [t]
  (loop [i 0
           result t
           indep-i 1
           dep-i 1
           query-i 1
           normalized #{}
           rename-map {}]
      (if (>= i term/compound-term-size-max)
        result
        (let [atom-id (term/term-get result i)]
          (if (and (variable? atom-id) (not (contains? normalized i)))
            (let [var-type (cond (independent-var? atom-id) \$
                                (dependent-var? atom-id) \#
                                :else \?)
                  var-idx (case var-type
                            \$ indep-i
                            \# dep-i
                            \? query-i)]
              (if (contains? rename-map atom-id)
                ;; Already renamed
                (let [new-id (get rename-map atom-id)]
                  (recur (inc i)
                         (term/term-assoc result i new-id)
                         indep-i dep-i query-i
                         (conj normalized i) rename-map))
                ;; New variable, assign sequential ID
                (let [new-id (make-var-id var-type var-idx)
                      ;; Replace all occurrences of this atom
                      [result normalized]
                      (loop [j i result result normalized normalized]
                        (if (>= j term/compound-term-size-max)
                          [result normalized]
                          (if (== (term/term-get result j) atom-id)
                            (recur (inc j)
                                   (term/term-assoc result j new-id)
                                   (conj normalized j))
                            (recur (inc j) result normalized))))]
                  (recur (inc i) result
                         (if (= var-type \$) (inc indep-i) indep-i)
                         (if (= var-type \#) (inc dep-i) dep-i)
                         (if (= var-type \?) (inc query-i) query-i)
                         normalized
                         (assoc rename-map atom-id new-id)))))
            (recur (inc i) result indep-i dep-i query-i normalized rename-map))))))
