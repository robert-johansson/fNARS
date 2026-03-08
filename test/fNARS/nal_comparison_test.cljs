(ns fNARS.nal-comparison-test
  "Systematic NAL 1-6 tests comparing fNARS derivations against expected ONA results."
  (:require [fNARS.nar :as nar]
            [fNARS.nar-config :as nar-config]
            [fNARS.term :as term]
            [fNARS.event :as event]
            [fNARS.truth :as truth]
            [fNARS.parser :as parser]
            [fNARS.variable :as variable]))

;; -- Helpers --

(defn make-state [nal-level]
  (let [config (assoc nar-config/default-config
                 :semantic-inference-nal-level nal-level
                 :motor-babbling-chance 0.0)]
    (nar/nar-init config)))

(defn add-belief
  "Add a belief string (Narsese) to the NAR state."
  [state narsese-str]
  (let [parsed (parser/parse-narsese narsese-str)]
    (when-not parsed
      (throw (js/Error. (str "Failed to parse: " narsese-str))))
    (nar/nar-add-input state (:term parsed) event/event-type-belief
      (or (:truth parsed) truth/default-truth)
      {:eternal? (= (:tense parsed) :eternal)})))

(defn ask
  "Ask a question and return {:answer {:truth ...} :state ...}."
  [state narsese-str]
  (let [parsed (parser/parse-narsese narsese-str)
        tense (or (:tense parsed) :eternal)]
    (nar/nar-answer-question state (:term parsed) tense)))

(defn get-derivations
  "Get all derived output events from state."
  [state]
  (let [{:keys [output]} (nar/nar-get-output state)]
    (filter #(= (:type %) :derived) output)))

(defn find-derivation
  "Find a derivation matching the given Narsese term string."
  [state narsese-str]
  (let [parsed (parser/parse-narsese narsese-str)
        target-term (:term parsed)
        {:keys [output state]} (nar/nar-get-output state)]
    (first (filter #(and (= (:type %) :derived)
                         (or (= (:term %) target-term)
                             ;; Also check with variable normalization
                             (= (variable/normalize-variables (:term %))
                                (variable/normalize-variables target-term))))
                   output))))

(defn approx=
  "Check if two numbers are approximately equal."
  [a b & [tolerance]]
  (< (js/Math.abs (- a b)) (or tolerance 0.01)))

(defn check-answer
  "Check that answering question-str gives expected frequency and confidence."
  [state question-str expected-freq expected-conf & [tolerance]]
  (let [{:keys [answer state]} (ask state question-str)
        tol (or tolerance 0.01)]
    (if-not answer
      {:pass false :reason (str "No answer for: " question-str)}
      (let [f (:frequency (:truth answer))
            c (:confidence (:truth answer))]
        (if (and (approx= f expected-freq tol)
                 (approx= c expected-conf tol))
          {:pass true :freq f :conf c}
          {:pass false :reason (str "Expected f=" expected-freq " c=" expected-conf
                                   " got f=" f " c=" c)
           :freq f :conf c})))))

;; -- Test Scenarios --
;; Each returns {:name :pass :reason? :details?}

(defn test-nal1-deduction []
  (let [state (make-state 6)
        state (add-belief state "<cat --> animal>.")
        state (add-belief state "<animal --> living>.")
        state (nar/nar-cycles state 3)
        result (check-answer state "<cat --> living>?" 1.0 0.81)]
    (assoc result :name "NAL1 Deduction: <cat-->animal>, <animal-->living> |- <cat-->living>")))

(defn test-nal1-induction []
  ;; <animal --> living>, <animal --> being> |- <living --> being> (induction)
  (let [state (make-state 6)
        state (add-belief state "<animal --> living>.")
        state (add-belief state "<animal --> being>.")
        state (nar/nar-cycles state 3)
        ;; Induction: f=1.0, c = f*c1*c2/(f*c1*c2+k) = 1*0.9*0.9/(1*0.9*0.9+1) = 0.81/1.81
        result (check-answer state "<living --> being>?" 1.0 (/ 0.81 1.81))]
    (assoc result :name "NAL1 Induction: <animal-->living>, <animal-->being> |- <living-->being>")))

(defn test-nal1-abduction []
  ;; <cat --> animal>, <dog --> animal> |- <cat --> dog> (abduction)
  (let [state (make-state 6)
        state (add-belief state "<cat --> animal>.")
        state (add-belief state "<dog --> animal>.")
        state (nar/nar-cycles state 3)
        result (check-answer state "<cat --> dog>?" 1.0 (/ 0.81 1.81))]
    (assoc result :name "NAL1 Abduction: <cat-->animal>, <dog-->animal> |- <cat-->dog>")))

(defn test-nal1-exemplification []
  ;; <cat --> animal>, <animal --> living> |- <living --> cat> (exemplification)
  (let [state (make-state 6)
        state (add-belief state "<cat --> animal>.")
        state (add-belief state "<animal --> living>.")
        state (nar/nar-cycles state 3)
        ;; Exemplification: f=1.0, c = f1*f2*c1*c2/(f1*f2*c1*c2+k)
        ;; = 1*1*0.9*0.9/(1*1*0.9*0.9+1) = 0.81/1.81
        result (check-answer state "<living --> cat>?" 1.0 (/ 0.81 1.81))]
    (assoc result :name "NAL1 Exemplification: <cat-->animal>, <animal-->living> |- <living-->cat>")))

(defn test-nal1-revision []
  ;; Two beliefs about same thing should revise
  (let [state (make-state 6)
        state (add-belief state "<cat --> animal>. {1.0 0.5}")
        state (add-belief state "<cat --> animal>. {0.0 0.5}")
        state (nar/nar-cycles state 3)
        ;; Revision: f = (f1*c1 + f2*c2)/(c1+c2) = (0.5+0)/(1.0) = 0.5
        ;; c = (c1+c2)/(c1+c2+k) = 1.0/2.0 = 0.5 (but this is c1+c2 not divided...)
        ;; Actually revision: f=(f1*c1*(1-c2) + f2*c2*(1-c1))/(c1*(1-c2)+c2*(1-c1))
        ;;                      = (1*0.5*0.5 + 0*0.5*0.5)/(0.5*0.5+0.5*0.5) = 0.25/0.50 = 0.5
        ;; c = (c1*(1-c2) + c2*(1-c1)) / (c1*(1-c2)+c2*(1-c1)+k) = 0.5/1.5 = 0.333
        ;; Wait, ONA revision: w1=c1/(1-c1), w2=c2/(1-c2), w=w1+w2, c=w/(w+k)
        ;; w1=1, w2=1, w=2, c=2/3=0.667
        ;; f = (f1*w1+f2*w2)/w = (1+0)/2 = 0.5
        result (check-answer state "<cat --> animal>?" 0.5 0.667 0.02)]
    (assoc result :name "NAL1 Revision: two conflicting beliefs")))

(defn test-nal2-comparison []
  ;; <cat --> animal>, <dog --> animal> |- <cat <-> dog> (comparison)
  (let [state (make-state 6)
        state (add-belief state "<cat --> animal>.")
        state (add-belief state "<dog --> animal>.")
        state (nar/nar-cycles state 3)
        ;; Comparison: f=f1*f2, c=f1*f2*c1*c2/(f1*f2*c1*c2+k)
        ;; = 1*1*0.9*0.9/(1+1) ... actually ONA Truth_Comparison
        ;; f=1.0, c = c1*c2*f1*f2 / (c1*c2*f1*f2 + k) = 0.81/1.81
        result (check-answer state "<cat <-> dog>?" 1.0 (/ 0.81 1.81))]
    (assoc result :name "NAL2 Comparison: <cat-->animal>, <dog-->animal> |- <cat<->dog>")))

(defn test-nal2-analogy []
  ;; <cat --> animal>, <cat <-> dog> |- <dog --> animal> (analogy)
  (let [state (make-state 6)
        state (add-belief state "<cat --> animal>.")
        state (add-belief state "<cat <-> dog>.")
        state (nar/nar-cycles state 3)
        ;; Analogy requires 2 derivation steps: similarity->similarity, then analogy
        ;; ONA gives c=0.729 (0.9^3) because similarity goes through structural-deduction first
        result (check-answer state "<dog --> animal>?" 1.0 0.729)]
    (assoc result :name "NAL2 Analogy: <cat-->animal>, <cat<->dog> |- <dog-->animal>")))

(defn test-nal5-deduction []
  ;; <cat --> animal>, <<$1 --> animal> ==> <$1 --> living>> |- <cat --> living>
  (let [state (make-state 6)
        state (add-belief state "<cat --> animal>.")
        state (add-belief state "<<$1 --> animal> ==> <$1 --> living>>.")
        state (nar/nar-cycles state 5)
        result (check-answer state "<cat --> living>?" 1.0 0.81)]
    (assoc result :name "NAL5/6 Modus ponens: <cat-->animal>, <<$1-->animal>==><$1-->living>> |- <cat-->living>")))

(defn test-nal5-abduction []
  ;; <cat --> living>, <<$1 --> animal> ==> <$1 --> living>> |- <cat --> animal>
  (let [state (make-state 6)
        state (add-belief state "<cat --> living>.")
        state (add-belief state "<<$1 --> animal> ==> <$1 --> living>>.")
        state (nar/nar-cycles state 5)
        ;; Abduction: f=1.0, c = c1*c2*f2 / (c1*c2*f2 + k)
        ;; In ONA this gives c ≈ 0.4475
        result (check-answer state "<cat --> animal>?" 1.0 (/ 0.81 1.81))]
    (assoc result :name "NAL5/6 Abduction: <cat-->living>, <<$1-->animal>==><$1-->living>> |- <cat-->animal>")))

(defn test-nal5-equivalence-analogy []
  ;; <cat --> animal>, <<$1 --> animal> <=> <$1 --> pet>> |- <cat --> pet>
  (let [state (make-state 6)
        state (add-belief state "<cat --> animal>.")
        state (add-belief state "<<$1 --> animal> <=> <$1 --> pet>>.")
        state (nar/nar-cycles state 5)
        result (check-answer state "<cat --> pet>?" 1.0 0.81)]
    (assoc result :name "NAL5/6 Equivalence analogy: <cat-->animal>, <<$1-->animal><=><$1-->pet>> |- <cat-->pet>")))

(defn test-nal5-implication-deduction []
  ;; Chained implications
  ;; <<$1 --> a> ==> <$1 --> b>>, <<$1 --> b> ==> <$1 --> c>> |- <<$1 --> a> ==> <$1 --> c>>
  (let [state (make-state 6)
        state (add-belief state "<<$1 --> a> ==> <$1 --> b>>.")
        state (add-belief state "<<$1 --> b> ==> <$1 --> c>>.")
        state (nar/nar-cycles state 5)
        result (check-answer state "<<$1 --> a> ==> <$1 --> c>>?" 1.0 0.81)]
    (assoc result :name "NAL5 Implication chain: <<a==>b>>, <<b==>c>> |- <<a==>c>>")))

(defn test-nal3-decomposition []
  ;; <robin --> (bird & animal)> |- <robin --> bird>, <robin --> animal>
  ;; ONA: c=0.81 (structural deduction)
  (let [state (make-state 6)
        state (add-belief state "<robin --> (bird & animal)>.")
        state (nar/nar-cycles state 5)
        r1 (check-answer state "<robin --> bird>?" 1.0 0.81)
        r2 (check-answer state "<robin --> animal>?" 1.0 0.81)]
    {:name "NAL3 Decomposition: <robin-->(bird & animal)> |- <robin-->bird>, <robin-->animal>"
     :pass (and (:pass r1) (:pass r2))
     :reason (when-not (and (:pass r1) (:pass r2))
               (str "bird: " (or (:reason r1) "ok") " animal: " (or (:reason r2) "ok")))}))

(defn test-nal4-product-to-image []
  ;; <(cat * food) --> eat> |- <cat --> (eat /1 food)>, <food --> (eat /2 cat)>
  ;; ONA: c=0.81 (structural deduction)
  (let [state (make-state 6)
        state (add-belief state "<(cat * food) --> eat>.")
        state (nar/nar-cycles state 5)
        r1 (check-answer state "<cat --> (eat /1 food)>?" 1.0 0.81)
        r2 (check-answer state "<food --> (eat /2 cat)>?" 1.0 0.81)]
    {:name "NAL4 Product->Image: <(cat*food)-->eat> |- <cat-->(eat/1 food)>, <food-->(eat/2 cat)>"
     :pass (and (:pass r1) (:pass r2))
     :reason (when-not (and (:pass r1) (:pass r2))
               (str "/1: " (or (:reason r1) "ok") " /2: " (or (:reason r2) "ok")))}))

(defn test-nal6-variable-introduction []
  ;; <cat --> animal>, <cat --> pet> |- <<$1 --> animal> ==> <$1 --> pet>>
  ;; ONA: c=0.4475 (induction with variable introduction)
  (let [state (make-state 6)
        state (add-belief state "<cat --> animal>.")
        state (add-belief state "<cat --> pet>.")
        state (nar/nar-cycles state 5)
        result (check-answer state "<<$1 --> animal> ==> <$1 --> pet>>?" 1.0 (/ 0.81 1.81))]
    (assoc result :name "NAL6 Variable introduction: <cat-->animal>, <cat-->pet> |- <<$1-->animal>==><$1-->pet>>")))

(defn test-nal2-resemblance []
  ;; <cat <-> animal>, <animal <-> pet> |- <cat <-> pet>
  ;; Resemblance: f=f1*f2, c=f1*f2*c1*c2
  (let [state (make-state 6)
        state (add-belief state "<cat <-> animal>.")
        state (add-belief state "<animal <-> pet>.")
        state (nar/nar-cycles state 3)
        ;; resemblance: c = f1*f2*c1*c2 = 1*1*0.9*0.9 = 0.81
        result (check-answer state "<cat <-> pet>?" 1.0 0.81)]
    (assoc result :name "NAL2 Resemblance: <cat<->animal>, <animal<->pet> |- <cat<->pet>")))

;; -- Runner --

(defn run-all-tests []
  (let [tests [(test-nal1-deduction)
               (test-nal1-induction)
               (test-nal1-abduction)
               (test-nal1-exemplification)
               (test-nal1-revision)
               (test-nal2-comparison)
               (test-nal2-analogy)
               (test-nal2-resemblance)
               (test-nal3-decomposition)
               (test-nal4-product-to-image)
               (test-nal5-deduction)
               (test-nal5-abduction)
               (test-nal5-equivalence-analogy)
               (test-nal5-implication-deduction)
               (test-nal6-variable-introduction)]
        passed (filter :pass tests)
        failed (remove :pass tests)]
    (println "=== NAL Comparison Tests ===\n")
    (doseq [t tests]
      (println (if (:pass t) "  PASS" "  FAIL") (:name t))
      (when-not (:pass t)
        (println "       " (:reason t))))
    (println (str "\n" (count passed) "/" (count tests) " passed"))
    (when (seq failed)
      (println (str (count failed) " FAILED")))
    (println)))

(run-all-tests)
