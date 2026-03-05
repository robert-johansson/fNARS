(ns fNARS.nar-config
  "NAR configuration parameters matching ONA's Config.h.
   Sensorimotor subset with SEMANTIC_INFERENCE_NAL_LEVEL=0, ALLOW_VAR_INTRO=true.")

(def default-config
  {:concepts-max                    4096
   :concepts-hashtable-buckets      4096
   :cycling-belief-events-max       40
   :cycling-goal-events-max         400
   :cycling-goal-events-layers      30
   :operations-max                  10
   :stamp-size                      10
   :table-size                      120
   :compound-term-size-max          64
   :max-sequence-len                3
   :max-compound-op-len             1

   ;; Truth parameters
   :horizon                         1.0
   :projection-decay                0.8
   :max-confidence                  0.99
   :min-confidence                  0.01
   :default-frequency               1.0
   :default-confidence              0.9
   :reliance                        0.9

   ;; Attention parameters
   :belief-event-selections         1
   :goal-event-selections           1
   :event-durability                0.9999
   :concept-durability              0.9
   :min-priority                    0
   :event-belief-distance           20
   :belief-concept-match-target     80
   :concept-threshold-adaptation    0.000001
   :question-priming                0.1

   ;; Decision parameters
   :decision-threshold              0.501
   :condition-threshold             0.501
   :motor-babbling-chance           0.2
   :motor-babbling-suppression      0.55

   ;; Anticipation parameters
   :anticipation-threshold          0.501
   :anticipation-confidence         0.01

   ;; Temporal compounding
   :precondition-consequence-distance 20   ;; same as event-belief-distance
   :correlate-outcome-recency       20
   :max-sequence-timediff           20

   ;; Unification
   :unification-depth               31
   :allow-var-intro                 true
   :similarity-distance             1.0

   ;; Derivation
   :semantic-inference-nal-level    0
   :allow-concurrent-implications   true
   :nop-subgoaling                  true
   :restricted-concept-creation     false

   ;; Occurrence time index
   :occurrence-time-index-size      512

   ;; Babbling ops
   :babbling-ops                    10
   :operations-babble-args-max      10

   ;; Output
   :volume                          100
   :print-derivations               false
   :print-input                     true})
