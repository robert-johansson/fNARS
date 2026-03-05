(ns fNARS.variable
  "Variable handling matching ONA's Variable.c.
   Variable atoms: :$1-:$9 (independent), :#1-:#9 (dependent), :?1-:?9 (query).
   Unification, substitution, variable introduction, and normalization."
  (:require [fNARS.term :as term]
            [fNARS.narsese :as narsese]))

(defn independent-var?
  "Check if atom is an independent variable ($1-$9)."
  [atom]
  (and (keyword? atom)
       (let [n (name atom)]
         (and (== (count n) 2)
              (= (first n) \$)
              (>= (second n) \1)
              (<= (second n) \9)))))

(defn dependent-var?
  "Check if atom is a dependent variable (#1-#9)."
  [atom]
  (and (keyword? atom)
       (let [n (name atom)]
         (and (== (count n) 2)
              (= (first n) \#)
              (>= (second n) \1)
              (<= (second n) \9)))))

(defn query-var?
  "Check if atom is a query variable (?1-?9)."
  [atom]
  (and (keyword? atom)
       (let [n (name atom)]
         (and (== (count n) 2)
              (= (first n) \?)
              (>= (second n) \1)
              (<= (second n) \9)))))

(defn variable?
  "Check if atom is any kind of variable."
  [atom]
  (or (independent-var? atom) (dependent-var? atom) (query-var? atom)))

(defn has-variable?
  "Check if term contains any variables of the specified types."
  ([t] (has-variable? t true true true))
  ([t independent? dependent? query?]
   (some (fn [atom]
           (or (and independent? (independent-var? atom))
               (and dependent? (dependent-var? atom))
               (and query? (query-var? atom))))
         t)))

(defn make-var
  "Create a variable keyword. type is \\$ \\# or \\?, id is 1-9."
  [type id]
  (keyword (str type id)))

;; -- Unification --

(defn unify
  "Unify a general term (may contain variables) with a specific term.
   Returns {:success true :substitution {var-atom -> subterm}} or {:success false}."
  [general specific]
  (loop [i 0
         substitution {}]
    (if (>= i term/compound-term-size-max)
      {:success true :substitution substitution}
      (let [g-atom (get general i)]
        (if (nil? g-atom)
          (recur (inc i) substitution)
          (if (variable? g-atom)
            ;; Variable in general: extract corresponding subtree from specific
            (let [subtree (term/extract-subterm specific i)]
              (cond
                ;; Query var can't match a variable
                (and (query-var? g-atom) (variable? (term/term-root subtree)))
                {:success false :substitution {}}

                ;; Set terminator not allowed
                (term/copula? (term/term-root subtree) term/SET-TERMINATOR)
                {:success false :substitution {}}

                ;; Check consistency with existing binding
                (and (contains? substitution g-atom)
                     (not (term/term-equal (get substitution g-atom) subtree)))
                {:success false :substitution {}}

                :else
                (recur (inc i) (assoc substitution g-atom subtree))))
            ;; Non-variable: must match exactly
            (let [s-atom (get specific i)]
              (if (not= g-atom s-atom)
                {:success false :substitution {}}
                (recur (inc i) substitution)))))))))

(defn apply-substitute
  "Apply a substitution to a term, replacing variables with their bindings."
  [t substitution]
  (reduce
    (fn [current-term i]
      (let [atom (get current-term i)]
        (if (and (variable? atom) (contains? substitution atom))
          (term/override-subterm current-term i (get substitution atom))
          current-term)))
    t
    (range term/compound-term-size-max)))

;; -- Variable Introduction --

(defn- count-atoms-in-statements
  "Count occurrences of simple atoms in a term (recursively through HOL structure).
   Returns a frequency map {atom -> count}."
  [t]
  (let [atoms (filter narsese/is-simple-atom? t)]
    (frequencies atoms)))

(defn- new-var-id
  "Find the next available variable ID (1-9) of the given type in the term."
  [t var-type]
  (first
    (for [id (range 1 10)
          :let [v (make-var var-type id)]
          :when (not (term/has-atom t v))]
      id)))

(defn introduce-implication-variables
  "Introduce variables in an implication term.
   Atoms in BOTH sides -> independent variable ($i).
   Atoms in ONE side with count >= 2 -> dependent variable (#i).
   Returns {:term new-term :success? bool}."
  [imp-term]
  (let [left (term/extract-subterm imp-term 1)
        right (term/extract-subterm imp-term 2)
        left-counts (count-atoms-in-statements left)
        right-counts (count-atoms-in-statements right)]
    (if-let [dep-start (new-var-id imp-term \#)]
      (if-let [indep-start (new-var-id imp-term \$)]
        ;; Iterate over all atoms, introduce variables
        (let [original imp-term]
          (loop [i 0
                 result imp-term
                 var-map {}        ;; atom -> variable keyword
                 dep-id dep-start
                 indep-id indep-start]
            (if (>= i term/compound-term-size-max)
              {:term result :success? true}
              (let [atom (get original i)]
                (if (and (some? atom) (narsese/is-simple-atom? atom))
                  (let [in-left (get left-counts atom)
                        in-right (get right-counts atom)
                        needs-var? (or (and in-left (>= in-left 2))
                                       (and in-right (>= in-right 2))
                                       (and in-left in-right))]
                    (if needs-var?
                      (if-let [existing-var (get var-map atom)]
                        ;; Already assigned a variable
                        (recur (inc i)
                               (term/override-subterm result i (term/atomic-term existing-var))
                               var-map dep-id indep-id)
                        ;; Assign new variable
                        (if (and in-left in-right)
                          ;; Independent: appears on both sides
                          (if (<= indep-id 9)
                            (let [v (make-var \$ indep-id)]
                              (recur (inc i)
                                     (term/override-subterm result i (term/atomic-term v))
                                     (assoc var-map atom v)
                                     dep-id (inc indep-id)))
                            ;; Too many vars
                            (recur (inc i) result var-map dep-id indep-id))
                          ;; Dependent: appears 2+ on one side only
                          ;; Skip SELF in op sequences
                          (if (and (= atom :SELF))
                            (recur (inc i) result var-map dep-id indep-id)
                            (if (<= dep-id 9)
                              (let [v (make-var \# dep-id)]
                                (recur (inc i)
                                       (term/override-subterm result i (term/atomic-term v))
                                       (assoc var-map atom v)
                                       (inc dep-id) indep-id))
                              (recur (inc i) result var-map dep-id indep-id)))))
                      (recur (inc i) result var-map dep-id indep-id)))
                  (recur (inc i) result var-map dep-id indep-id))))))
        {:term imp-term :success? false})
      {:term imp-term :success? false})))

(defn normalize-variables
  "Normalize variable numbering in a term.
   First variable seen becomes $1/#1/?1, second $2/#2/?2, etc."
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
      (let [atom (get result i)]
        (if (and (variable? atom) (not (contains? normalized i)))
          (let [var-type (cond (independent-var? atom) \$
                              (dependent-var? atom) \#
                              :else \?)
                var-idx (case var-type
                          \$ indep-i
                          \# dep-i
                          \? query-i)]
            (if (contains? rename-map atom)
              ;; Already renamed
              (let [new-atom (get rename-map atom)]
                (recur (inc i)
                       (assoc result i new-atom)
                       indep-i dep-i query-i
                       (conj normalized i) rename-map))
              ;; New variable, assign sequential ID
              (let [new-atom (make-var var-type var-idx)
                    ;; Replace all occurrences of this atom
                    [result normalized]
                    (loop [j i result result normalized normalized]
                      (if (>= j term/compound-term-size-max)
                        [result normalized]
                        (if (= (get result j) atom)
                          (recur (inc j)
                                 (assoc result j new-atom)
                                 (conj normalized j))
                          (recur (inc j) result normalized))))]
                (recur (inc i) result
                       (if (= var-type \$) (inc indep-i) indep-i)
                       (if (= var-type \#) (inc dep-i) dep-i)
                       (if (= var-type \?) (inc query-i) query-i)
                       normalized
                       (assoc rename-map atom new-atom)))))
          (recur (inc i) result indep-i dep-i query-i normalized rename-map))))))
