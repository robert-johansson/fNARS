(ns fNARS.mprf
  "Machine Psychology Research Framework for fNARS.
   Runs behavioral experiments (operant conditioning, matching-to-sample)
   defined as Clojure data structures."
  (:require [fNARS.nar :as nar]
            [fNARS.term :as term]
            [fNARS.event :as event]
            [fNARS.truth :as truth]
            [fNARS.narsese :as narsese]
            [fNARS.parser :as parser]
            [fNARS.shell :as shell]
            [clojure.string :as str]))

;; ============================================================
;; Experiment Definitions
;; ============================================================

(def op1
  {:name "Op1: Simple Discrimination"
   :description "Green-left / blue-right. Baseline -> training -> testing."
   :operation "^select"
   :args ["left" "right"]
   :motor-babbling 0.2
   :goal "G"
   :time-between-trials 100
   :block-multiplier 3
   :hypotheses
   {"h1" ["<(<(left * green) --> (loc * color)> &/ <({SELF} * left) --> ^select>) =/> G>"
          "<(<(right * green) --> (loc * color)> &/ <({SELF} * right) --> ^select>) =/> G>"]}
   :phases
   [{:name "baseline" :blocks 3 :feedback? false
     :event-type "color"
     :conditions [{"left" "green" "right" "blue" :correct "left"}
                  {"left" "blue" "right" "green" :correct "right"}]}
    {:name "training" :blocks 3 :feedback? true
     :event-type "color"
     :conditions [{"left" "green" "right" "blue" :correct "left"}
                  {"left" "blue" "right" "green" :correct "right"}]}
    {:name "testing" :blocks 3 :feedback? false
     :event-type "color"
     :conditions [{"left" "green" "right" "blue" :correct "left"}
                  {"left" "blue" "right" "green" :correct "right"}]}]})

(def op2
  {:name "Op2: Reversal Learning"
   :description "Train green=left, then reverse to green=right."
   :operation "^select"
   :args ["left" "right"]
   :motor-babbling 0.9
   :goal "G"
   :time-between-trials 100
   :block-multiplier 3
   :hypotheses
   {"h1" ["<(<(left * green) --> (loc * color)> &/ <({SELF} * left) --> ^select>) =/> G>"
          "<(<(right * green) --> (loc * color)> &/ <({SELF} * right) --> ^select>) =/> G>"]
    "h2" ["<(<(left * blue) --> (loc * color)> &/ <({SELF} * left) --> ^select>) =/> G>"
          "<(<(right * blue) --> (loc * color)> &/ <({SELF} * right) --> ^select>) =/> G>"]}
   :phases
   [{:name "baseline" :blocks 2 :feedback? false
     :event-type "color"
     :conditions [{"left" "green" "right" "blue" :correct "left"}
                  {"left" "blue" "right" "green" :correct "right"}]}
    {:name "training" :blocks 4 :feedback? true
     :event-type "color"
     :conditions [{"left" "green" "right" "blue" :correct "left"}
                  {"left" "blue" "right" "green" :correct "right"}]}
    {:name "testing" :blocks 2 :feedback? false
     :event-type "color"
     :conditions [{"left" "green" "right" "blue" :correct "left"}
                  {"left" "blue" "right" "green" :correct "right"}]}
    {:name "training2" :blocks 4 :feedback? true
     :event-type "color"
     :conditions [{"left" "green" "right" "blue" :correct "right"}
                  {"left" "blue" "right" "green" :correct "left"}]}
    {:name "testing2" :blocks 2 :feedback? false
     :event-type "color"
     :conditions [{"left" "green" "right" "blue" :correct "right"}
                  {"left" "blue" "right" "green" :correct "left"}]}]})

(def op3
  {:name "Op3: Matching-to-Sample"
   :description "Match sample color to comparison. Green->blue, red->yellow."
   :operation "^match"
   :args ["(* sample left)" "(* sample right)"]
   :motor-babbling 0.9
   :goal "G"
   :time-between-trials 100
   :block-multiplier 3
   :hypotheses
   {"h1" ["<(<(sample * green) --> (loc * color)> &/ <({SELF} * (sample * left)) --> ^match>) =/> G>"
          "<(<(sample * green) --> (loc * color)> &/ <({SELF} * (sample * right)) --> ^match>) =/> G>"
          "<(<(sample * red) --> (loc * color)> &/ <({SELF} * (sample * left)) --> ^match>) =/> G>"
          "<(<(sample * red) --> (loc * color)> &/ <({SELF} * (sample * right)) --> ^match>) =/> G>"]}
   :phases
   [{:name "baseline" :blocks 3 :feedback? false
     :event-type "color"
     :conditions [{"sample" "green" "left" "blue" "right" "yellow" :correct "(* sample left)"}
                  {"sample" "green" "left" "yellow" "right" "blue" :correct "(* sample right)"}
                  {"sample" "red" "left" "blue" "right" "yellow" :correct "(* sample right)"}
                  {"sample" "red" "left" "yellow" "right" "blue" :correct "(* sample left)"}]}
    {:name "training" :blocks 6 :feedback? true
     :event-type "color"
     :conditions [{"sample" "green" "left" "blue" "right" "yellow" :correct "(* sample left)"}
                  {"sample" "green" "left" "yellow" "right" "blue" :correct "(* sample right)"}
                  {"sample" "red" "left" "blue" "right" "yellow" :correct "(* sample right)"}
                  {"sample" "red" "left" "yellow" "right" "blue" :correct "(* sample left)"}]}
    {:name "testing" :blocks 3 :feedback? false
     :event-type "color"
     :conditions [{"sample" "green" "left" "blue" "right" "yellow" :correct "(* sample left)"}
                  {"sample" "green" "left" "yellow" "right" "blue" :correct "(* sample right)"}
                  {"sample" "red" "left" "blue" "right" "yellow" :correct "(* sample right)"}
                  {"sample" "red" "left" "yellow" "right" "blue" :correct "(* sample left)"}]}]})

;; ============================================================
;; Helpers
;; ============================================================

(defn parse-arg-term
  "Parse an argument string into a term.
   'left' -> (term/atomic-term :left)
   '(* sample left)' -> product term"
  [s]
  (if (str/starts-with? s "(*")
    (let [inner (str/trim (subs s 2 (dec (count s))))
          parts (str/split inner #"\s+")]
      (narsese/make-product (parse-arg-term (first parts))
                            (parse-arg-term (second parts))))
    (term/atomic-term (keyword s))))

(defn- make-stimulus-term
  "Build <(location * value) --> (loc * event-type)>."
  [location value event-type]
  (narsese/make-inheritance
    (narsese/make-product (term/atomic-term (keyword location))
                          (term/atomic-term (keyword value)))
    (narsese/make-product (term/atomic-term :loc)
                          (term/atomic-term (keyword event-type)))))

(defn- shuffle-with-seed
  "Deterministic shuffle using LCG. Pure: swaps via assoc on immutable vector."
  [coll seed]
  (let [v (vec coll)]
    (loop [i (dec (count v))
           v v
           rng seed]
      (if (<= i 0)
        v
        (let [new-rng (bit-and (+ (js/Math.imul rng 1103515245) 12345) 0xFFFFFFFF)
              j (mod new-rng (inc i))]
          (recur (dec i)
                 (assoc v i (v j) j (v i))
                 new-rng))))))

(defn- pad-right [s n]
  (let [s (str s)]
    (if (>= (count s) n) s
      (str s (apply str (repeat (- n (count s)) " "))))))

;; ============================================================
;; Trial Runner
;; ============================================================

(defn- run-trial
  "Run a single trial. Returns {:state state :correct? bool :responded? bool}."
  [state condition event-type goal-term feedback?]
  (let [stimuli (dissoc condition :correct)
        correct-str (:correct condition)
        correct-term (parse-arg-term correct-str)
        ;; Present each stimulus
        state (reduce
                (fn [state [loc val]]
                  (let [stim (make-stimulus-term loc val event-type)]
                    (nar/nar-add-input state stim event/event-type-belief truth/default-truth)))
                state
                stimuli)
        ;; Clear output before goal to only capture this trial's execution
        ;; (stimulus cycles may trigger stale goal events that produce executions)
        state (assoc state :output [])
        ;; Request decision
        state (nar/nar-add-input state goal-term event/event-type-goal truth/default-truth)
        {:keys [output state]} (nar/nar-get-output state)
        ;; Find execution in output
        execution (first (filter #(= (:type %) :execution) output))
        selected-arg (:arguments execution)
        correct? (and (some? selected-arg) (term/term-equal selected-arg correct-term))
        ;; Feedback (only when there was a response)
        state (if (and feedback? (some? selected-arg))
                (if correct?
                  (nar/nar-add-input state goal-term event/event-type-belief truth/default-truth)
                  (nar/nar-add-input state goal-term event/event-type-belief
                                     {:frequency 0.0 :confidence 0.9}))
                state)]
    {:state state :correct? correct? :responded? (some? selected-arg)}))

;; ============================================================
;; Hypothesis Querying
;; ============================================================

(defn- query-hypotheses
  "Query hypothesis truth values. Returns {:state state :results {name {:avg-c :avg-f}}}."
  [state hypotheses]
  (reduce-kv
    (fn [{:keys [state results]} hyp-name hyp-list]
      (let [{:keys [state cs fs]}
            (reduce
              (fn [{:keys [state cs fs]} hyp-str]
                (let [parsed (parser/parse-narsese (str hyp-str "?"))
                      {:keys [state answer]}
                      (if parsed
                        (nar/nar-answer-question state (:term parsed) (:tense parsed))
                        {:state state :answer nil})
                      c (get-in answer [:truth :confidence] 0)
                      f (get-in answer [:truth :frequency] 0)]
                  {:state state :cs (conj cs c) :fs (conj fs f)}))
              {:state state :cs [] :fs []}
              hyp-list)]
        {:state state
         :results (assoc results hyp-name
                    {:avg-c (/ (apply + cs) (count hyp-list))
                     :avg-f (/ (apply + fs) (count hyp-list))})}))
    {:state state :results {}}
    hypotheses))

;; ============================================================
;; Block & Experiment Runner
;; ============================================================

(defn- run-block
  "Run one block of trials. Returns {:state state :result {...}}."
  [state phase block-idx experiment]
  (let [{:keys [conditions event-type feedback?]} phase
        multiplier (:block-multiplier experiment)
        goal-term (term/atomic-term (keyword (:goal experiment)))
        time-between (:time-between-trials experiment)
        ;; Create trials: conditions × multiplier, then shuffle
        trials (vec (apply concat (repeat multiplier conditions)))
        trials (shuffle-with-seed trials (+ (* block-idx 17) (:current-time state)))
        ;; Run trials
        {:keys [state total-correct total-responded trial-count]}
        (reduce
          (fn [{:keys [state total-correct total-responded trial-count]} condition]
            (let [{:keys [state correct? responded?]}
                  (run-trial state condition event-type goal-term feedback?)
                  ;; Inter-trial interval
                  state (nar/nar-cycles state time-between)]
              {:state state
               :total-correct (+ total-correct (if correct? 1 0))
               :total-responded (+ total-responded (if responded? 1 0))
               :trial-count (inc trial-count)}))
          {:state state :total-correct 0 :total-responded 0 :trial-count 0}
          trials)
        ;; Query hypotheses
        {:keys [state results]} (query-hypotheses state (:hypotheses experiment))]
    {:state state
     :result {:block block-idx
              :phase (:name phase)
              :correct total-correct
              :responded total-responded
              :trials trial-count
              :accuracy (if (pos? trial-count)
                          (/ (double total-correct) trial-count)
                          0.0)
              :hypotheses results}}))

(defn run-experiment
  "Run an experiment. Returns {:state state :log [block-results...]}."
  [experiment]
  (let [;; Initialize NAR
        state (nar/nar-init)
        ;; Register operation with args
        arg-terms (mapv parse-arg-term (:args experiment))
        state (nar/nar-add-operation state (:operation experiment) (fn [s _] s)
                {:args arg-terms})
        ;; Set config
        state (assoc-in state [:config :motor-babbling-chance] (:motor-babbling experiment))
        ;; Run phases
        {:keys [state log]}
        (reduce
          (fn [{:keys [state log block-counter]} phase]
            (reduce
              (fn [{:keys [state log block-counter]} _block-num]
                (let [{:keys [state result]} (run-block state phase block-counter experiment)]
                  {:state state
                   :log (conj log result)
                   :block-counter (inc block-counter)}))
              {:state state :log log :block-counter block-counter}
              (range (:blocks phase))))
          {:state state :log [] :block-counter 0}
          (:phases experiment))]
    {:state state :log log}))

;; ============================================================
;; Output Formatting
;; ============================================================

(defn- format-term-brief
  "Short format for a term (just the root or first few atoms)."
  [t]
  (when t (shell/format-term t)))

(defn print-results
  "Print experiment results as a table."
  [experiment log]
  (let [hyp-names (sort (keys (:hypotheses experiment)))]
    (println (str "\n" (:name experiment)))
    (when (:description experiment) (println (:description experiment)))
    (println (apply str (repeat 90 "=")))
    (print (pad-right "Phase" 14))
    (print (pad-right "Block" 6))
    (print (pad-right "Correct" 10))
    (print (pad-right "Accuracy" 10))
    (doseq [h hyp-names]
      (print (pad-right (str h ":c") 10))
      (print (pad-right (str h ":f") 10)))
    (println)
    (println (apply str (repeat 90 "-")))
    (doseq [entry log]
      (print (pad-right (:phase entry) 14))
      (print (pad-right (:block entry) 6))
      (print (pad-right (str (:correct entry) "/" (:trials entry)) 10))
      (print (pad-right (.toFixed (double (:accuracy entry)) 3) 10))
      (doseq [h hyp-names]
        (let [v (get (:hypotheses entry) h)]
          (print (pad-right (.toFixed (double (:avg-c v)) 4) 10))
          (print (pad-right (.toFixed (double (:avg-f v)) 4) 10))))
      (println))
    (println (apply str (repeat 90 "=")))))

(defn format-csv
  "Export experiment results as CSV string."
  [experiment log]
  (let [hyp-names (sort (keys (:hypotheses experiment)))
        header (str/join ","
                 (concat ["phase" "count_block_loop" "block_correct"]
                         (mapcat (fn [h] [(str h "_average_c") (str h "_average_f")])
                                 hyp-names)))]
    (str header "\n"
         (str/join "\n"
           (map (fn [entry]
                  (str/join ","
                    (concat [(:phase entry)
                             (:block entry)
                             (.toFixed (double (:accuracy entry)) 4)]
                            (mapcat (fn [h]
                                      (let [v (get (:hypotheses entry) h)]
                                        [(.toFixed (double (:avg-c v)) 4)
                                         (.toFixed (double (:avg-f v)) 4)]))
                                    hyp-names))))
                log)))))

;; ============================================================
;; Entry Point
;; ============================================================

(def experiments
  {"op1" op1
   "op2" op2
   "op3" op3})

(defn -main [& args]
  (let [exp-name (or (first args) "op1")
        experiment (get experiments exp-name)]
    (if experiment
      (let [{:keys [log]} (run-experiment experiment)]
        (print-results experiment log))
      (do
        (println (str "Unknown experiment: " exp-name))
        (println (str "Available: " (str/join ", " (keys experiments))))))))
