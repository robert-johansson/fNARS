# Chapter 6 Replication: Operant Conditioning with ONA

This document describes the replication of all three experiments from Chapter 6 of *Empirical Studies in Machine Psychology* (Johansson, 2024), using the MPRF framework and a fresh build of OpenNARS for Applications (ONA).

## ONA Configuration

ONA was compiled with two critical parameters changed from their defaults in `src/Config.h`:

```c
#define SEMANTIC_INFERENCE_NAL_LEVEL 0
#define ALLOW_VAR_INTRO false
```

**`SEMANTIC_INFERENCE_NAL_LEVEL=0`** disables all declarative inference rules (NAL levels 1-6). This means ONA cannot perform syllogistic reasoning, set operations, higher-order inference, or any of the classical NAL deduction/induction/abduction rules on declarative knowledge. Only the sensorimotor inference engine remains active — temporal induction and procedural (goal-driven) deduction operate on event sequences observed in real time.

**`ALLOW_VAR_INTRO=false`** disables variable introduction, the abstraction mechanism. ONA cannot generalize across specific terms by replacing concrete atoms with variables (e.g., replacing `green` with `#1`). Every hypothesis it forms must contain the exact concrete terms it observed. This is deliberate for Chapter 6: operant conditioning requires only stimulus-specific learning, not generalization.

Together, these settings restrict ONA to **NAL layers 7-8 only** — temporal reasoning (layer 7) and procedural reasoning (layer 8). This is the minimal configuration needed to demonstrate operant conditioning.

## Available Mechanisms

With this configuration, exactly five ONA mechanisms are active and relevant:

### 1. Temporal Induction

When ONA observes a sequence of events ending in a consequence, it derives a temporal implication using the `=/>`(predictive implication) copula combined with the `&/` (sequential conjunction) connector. For example, after observing green on the left, selecting left, and receiving reinforcement:

```
// Observed events:
<(left * green) --> (loc * color)>. :|:     // green is at the left
<({SELF} * left) --> ^select>. :|:          // system selects left
G. :|:                                       // reinforcement

// Derived via temporal induction:
<(<(left * green) --> (loc * color)> &/ <({SELF} * left) --> ^select>) =/> G>. {1.00, 0.19}
```

The truth value `{1.00, 0.19}` means frequency 1.0 (the relation held in all observations so far) and confidence 0.19 (only one observation, so limited evidence). The `&/` connector captures that the precondition events occurred in sequence, and `=/>` captures that they predict the consequence. With repeated observations, confidence grows through evidence accumulation.

ONA also derives simpler (shorter) hypotheses from the same event sequence. For instance, from a trial with three input events (sample, left comparison, right comparison) followed by an action and outcome, ONA may derive both a 3-precondition hypothesis and multiple 2-precondition hypotheses. These simpler hypotheses initially have higher confidence (based on fewer required conditions), but they may accumulate negative evidence from trials where they make incorrect predictions.

### 2. Goal Deduction (Procedural Reasoning)

When a goal is active (`G! :|:`) and ONA has a learned temporal implication whose consequence matches that goal, it performs backward chaining: it checks whether the precondition is currently satisfied, and if so, executes the operation specified in the implication.

```
// Knowledge (learned previously):
<(<(left * green) --> (loc * color)> &/ <({SELF} * left) --> ^select>) =/> G>. {0.98, 0.41}

// Current perception:
<(left * green) --> (loc * color)>. :|:

// Goal:
G! :|:

// Decision: execute ^select with argument left
// (because the precondition matches and the implication predicts G)
```

The decision is made based on the expectation value of the implication's truth, computed as `expectation = (confidence * (frequency - 0.5) + 0.5)`. If this exceeds the decision threshold (0.501 by default), the operation fires. If not, motor babbling may occur instead.

### 3. Motor Babbling

Motor babbling is random exploration: ONA executes one of its registered operations with a randomly chosen argument, independent of any learned knowledge. This is the mechanism that enables discovery of correct responses before any learning has occurred. In our experiments, ONA was configured with one operation (`^select` or `^match`) with two possible arguments, giving a 50% chance of randomly choosing the correct response on any given babbling trial.

The motor babbling probability is set at initialization (e.g., `*motorbabbling=0.9` means 90% chance of babbling when no confident decision can be made). As ONA learns strong hypotheses, goal deduction takes over and babbling becomes less frequent — the system transitions from random exploration to goal-directed behavior.

### 4. Anticipation

When ONA has a learned implication and observes its precondition being satisfied (including the operation being executed), it *anticipates* the consequence. If the anticipated consequence does not occur, ONA derives negative evidence for that implication. For example:

```
// Knowledge:
<(<(right * green) --> (loc * color)> &/ <({SELF} * (sample * right)) --> ^match>) =/> G>. {1.00, 0.21}

// Observed: precondition satisfied but G does not occur (punishment instead)
// Derived via anticipation failure:
<(<(right * green) --> (loc * color)> &/ <({SELF} * (sample * right)) --> ^match>) =/> G>. {0.00, 0.19}
```

This negative evidence statement has frequency 0.0 (the relation did not hold) and is then combined with prior evidence through revision.

### 5. Revision

Revision is the mechanism for combining evidence from multiple observations of the same statement. When ONA has two truth values for the same hypothesis (from different evidential bases), it computes a revised truth value that reflects the total evidence:

```
// Positive evidence (from reinforced trials):
<(<(left * green) --> (loc * color)> &/ <({SELF} * left) --> ^select>) =/> G>. {0.98, 0.41}

// Negative evidence (from one punished trial after contingency reversal):
<(<(left * green) --> (loc * color)> &/ <({SELF} * left) --> ^select>) =/> G>. {0.00, 0.19}

// After revision:
<(<(left * green) --> (loc * color)> &/ <({SELF} * left) --> ^select>) =/> G>. {0.74, 0.48}
```

After revision, the frequency drops from 0.98 to 0.74 (incorporating the negative instance), while the confidence rises from 0.41 to 0.48 (more total evidence). The revision function in NAL uses the formula: `f_revised = (f1*c1 + f2*c2) / (c1 + c2)` for frequency and `c_revised = (c1 + c2) / (c1 + c2 + c1*c2)` for confidence (simplified).

## Mechanisms NOT Used

Because of the Config.h settings, several ONA mechanisms were explicitly excluded:

- **Variable introduction** (`ALLOW_VAR_INTRO=false`): No abstraction. ONA cannot derive general hypotheses like `<(<(#1 * #2) --> (loc * color)> &/ ...) =/> G>` where `#1` and `#2` are variables. Every hypothesis is concrete — it refers to specific stimuli like `green`, `blue`, `left`, `right`.
- **NAL 1-6 inference** (`SEMANTIC_INFERENCE_NAL_LEVEL=0`): No inheritance deduction/induction/abduction, no set operations, no higher-order reasoning, no implication-level inference, no similarity. ONA operates purely at the sensorimotor level.
- **Functional equivalence**: Requires implications (NAL-5), which are disabled.
- **Acquired relations**: Requires NAL-4 set operations, which are disabled.

## Experiment 1: Simple Discrimination

**Initialization:**
```
*babblingops=1
*motorbabbling=0.2
*setopname 1 ^select
*setoparg 1 1 left
*setoparg 1 2 right
```

One operation `^select` with two arguments: `left` and `right`.

**Task:** On each trial, green appears at one location and blue at the other. The correct response is always to select the location where green is. Two trial types, each repeated 3 times per block (6 trials per block).

**Phases:** Baseline (3 blocks, no feedback) -> Training (3 blocks, with feedback) -> Testing (3 blocks, no feedback)

**Example trial sequence during training:**

```
// Perception: green left, blue right
<(left * green) --> (loc * color)>. :|:
<(right * blue) --> (loc * color)>. :|:
G! :|:

// ONA executes via motor babbling (early training):
// ^select executed with args ({SELF} * left)

// Correct! Reinforcement:
G. :|:

// ONA derives via temporal induction:
// <(<(left * green) --> (loc * color)> &/ <({SELF} * left) --> ^select>) =/> G>. {1.00, 0.19}
// Also derives simpler hypothesis:
// <<(left * green) --> (loc * color)> =/> G>. {1.00, 0.25}

100    // 100 inference cycles between trials
```

**Target hypotheses (measured after each block):**

```
<(<(left * green) --> (loc * color)> &/ <({SELF} * left) --> ^select>) =/> G>
<(<(right * green) --> (loc * color)> &/ <({SELF} * right) --> ^select>) =/> G>
```

These express: "if green is at location X and I select X, then G follows."

**Results:**

| Phase | Block | Accuracy | Avg Hypothesis Confidence |
|-------|-------|----------|--------------------------|
| Baseline | 1 | 17% | 0.00 |
| Baseline | 2 | 33% | 0.00 |
| Baseline | 3 | 17% | 0.00 |
| Training | 1 | 0% | 0.00 |
| Training | 2 | 0% | 0.00 |
| Training | 3 | 67% | 0.31 |
| Testing | 1 | **100%** | 0.32 |
| Testing | 2 | 83% | 0.33 |
| Testing | 3 | **100%** | 0.34 |

During baseline, accuracy is at or below chance — motor babbling produces random responses with no learning. During training, ONA initially still explores (0% in early blocks as motor babbling produces wrong responses) but by block 3, learned hypotheses drive decisions and accuracy rises to 67%. In testing, accuracy reaches 100% without feedback, demonstrating that learned operant behavior persists.

## Experiment 2: Changing Contingencies (Reversal Learning)

**Initialization:** Same as Experiment 1, but with `*motorbabbling=0.9`.

**Task:** Same stimulus arrangement (green vs blue), but the contingencies reverse midway. In Training 1, selecting green's location is correct. In Training 2, selecting blue's location is correct.

**Phases:** Baseline (2 blocks) -> Training 1 (4 blocks) -> Testing 1 (2 blocks) -> Training 2 (4 blocks, reversed) -> Testing 2 (2 blocks)

**Two hypothesis sets are tracked:**

H1 (original contingency — select green):
```
<(<(left * green) --> (loc * color)> &/ <({SELF} * left) --> ^select>) =/> G>
<(<(right * green) --> (loc * color)> &/ <({SELF} * right) --> ^select>) =/> G>
```

H2 (reversed contingency — select blue):
```
<(<(left * blue) --> (loc * color)> &/ <({SELF} * left) --> ^select>) =/> G>
<(<(right * blue) --> (loc * color)> &/ <({SELF} * right) --> ^select>) =/> G>
```

**What happens during reversal (Training 2):**

When contingencies reverse, ONA still acts on its old H1 hypothesis. But now selecting green's location leads to punishment:

```
// ONA decides based on H1: select left (where green is)
// Feedback: G. :|: {0.0 0.9}    (punishment)

// Negative evidence derived:
<(<(left * green) --> (loc * color)> &/ <({SELF} * left) --> ^select>) =/> G>. {0.00, 0.19}

// Revised with prior positive evidence:
// frequency drops from ~0.98 to ~0.77, confidence increases
```

As H1's frequency drops below the decision threshold, ONA falls back to motor babbling. Eventually it randomly selects blue's location, receives reinforcement, and begins building H2:

```
// Motor babbling leads to selecting right (where blue is)
// Feedback: G. :|:    (reinforcement)

// New hypothesis via temporal induction:
<(<(right * blue) --> (loc * color)> &/ <({SELF} * right) --> ^select>) =/> G>. {1.00, 0.19}
```

**Results:**

| Phase | Block | Accuracy | H1 Frequency | H2 Frequency |
|-------|-------|----------|-------------|-------------|
| Baseline | 1 | 67% | 0.00 | 0.00 |
| Baseline | 2 | 17% | 0.00 | 0.00 |
| Training 1 | 1 | 83% | 0.99 | 0.00 |
| Training 1 | 2 | 100% | 0.98 | 0.00 |
| Training 1 | 3 | 100% | 0.98 | 0.00 |
| Training 1 | 4 | 100% | 0.98 | 0.00 |
| Testing 1 | 1 | 100% | 0.97 | 0.00 |
| Testing 1 | 2 | 100% | 0.97 | 0.00 |
| Training 2 | 1 | 0% | 0.77 | 0.00 |
| Training 2 | 2 | 0% | 0.64 | 0.00 |
| Training 2 | 3 | 100% | 0.64 | 0.82 |
| Training 2 | 4 | 67% | 0.61 | 0.85 |
| Testing 2 | 1 | 83% | 0.60 | 0.84 |
| Testing 2 | 2 | 83% | 0.60 | 0.83 |

The frequency values tell the story: H1 starts near 1.0 and decays during Training 2 as negative evidence accumulates through revision. H2 rises from 0.0 to ~0.85 as new positive evidence builds. The crossover between H1 and H2 frequency corresponds to the shift in behavioral accuracy.

## Experiment 3: Conditional Discriminations (Matching-to-Sample)

**Initialization:**
```
*babblingops=1
*motorbabbling=0.9
*setopname 1 ^match
*setoparg 1 1 (sample * left)
*setoparg 1 2 (sample * right)
```

One operation `^match` with two compound arguments: `(sample * left)` and `(sample * right)`.

**Task:** A sample stimulus is presented alongside two comparison stimuli. The rule is: green goes with blue, red goes with yellow. Four trial types (12 trials per block with BLOCK_MULTIPLIER=3):

| Sample | Left | Right | Correct Response |
|--------|------|-------|-----------------|
| green | blue | yellow | match sample to left |
| green | yellow | blue | match sample to right |
| red | blue | yellow | match sample to right |
| red | yellow | blue | match sample to left |

**Phases:** Baseline (3 blocks) -> Training (6 blocks) -> Testing (3 blocks)

**Example trial and derivation:**

```
// Perception: sample is green, blue is left, yellow is right
<(sample * green) --> (loc * color)>. :|:
<(left * blue) --> (loc * color)>. :|:
<(right * yellow) --> (loc * color)>. :|:
G! :|:

// Motor babbling: ^match with (sample * left)
// Correct! Reinforcement: G. :|:

// ONA derives (among others):
<((<(sample * green) --> (loc * color)> &/ <(left * blue) --> (loc * color)>) &/
  <({SELF} * (sample * left)) --> ^match>) =/> G>. {1.00, 0.15}
```

Note the nested `&/` — this is a 3-element sequential conjunction. ONA learns that the full context (sample identity AND comparison location) together with the action predicts reinforcement.

**Importantly**, ONA also derives simpler 2-precondition hypotheses:

```
// Simpler hypothesis (ignoring sample):
<(<(left * blue) --> (loc * color)> &/ <({SELF} * (sample * left)) --> ^match>) =/> G>. {1.00, 0.21}

// Simpler hypothesis (ignoring comparison):
<(<(sample * green) --> (loc * color)> &/ <({SELF} * (sample * left)) --> ^match>) =/> G>. {1.00, 0.16}
```

These simpler hypotheses initially have higher confidence and drive early decisions. But they generate incorrect predictions on trials where the comparison positions swap, accumulating negative evidence through anticipation and revision. Over training, the full conditional hypotheses — which account for both sample and comparison — become the most reliable and come to control behavior.

**Target hypotheses:**
```
<((<(sample * green) --> (loc * color)> &/ <(left * blue) --> (loc * color)>) &/
  <({SELF} * (sample * left)) --> ^match>) =/> G>

<((<(sample * green) --> (loc * color)> &/ <(right * blue) --> (loc * color)>) &/
  <({SELF} * (sample * right)) --> ^match>) =/> G>

<((<(sample * red) --> (loc * color)> &/ <(left * yellow) --> (loc * color)>) &/
  <({SELF} * (sample * left)) --> ^match>) =/> G>

<((<(sample * red) --> (loc * color)> &/ <(right * yellow) --> (loc * color)>) &/
  <({SELF} * (sample * right)) --> ^match>) =/> G>
```

**Results:**

| Phase | Block | Accuracy | Avg Hypothesis Confidence |
|-------|-------|----------|--------------------------|
| Baseline | 1 | 50% | 0.00 |
| Baseline | 2 | 17% | 0.00 |
| Baseline | 3 | 42% | 0.00 |
| Training | 1 | 58% | 0.23 |
| Training | 2 | 58% | 0.37 |
| Training | 3 | 100% | 0.53 |
| Training | 4 | 100% | 0.63 |
| Training | 5 | 75% | 0.68 |
| Training | 6 | 92% | 0.72 |
| Testing | 1 | **100%** | 0.72 |
| Testing | 2 | **100%** | 0.72 |
| Testing | 3 | **100%** | 0.73 |

Baseline accuracy hovers around chance (25-50% with 4 trial types). During training, accuracy climbs as ONA accumulates evidence for the correct conditional hypotheses and negative evidence eliminates simpler competing hypotheses. Testing reaches 100% accuracy across all three blocks, with hypothesis confidence stable at ~0.73.

## Summary

All three experiments successfully replicate the Chapter 6 findings using only temporal and procedural reasoning (NAL layers 7-8). The five active mechanisms — temporal induction, goal deduction, motor babbling, anticipation, and revision — together produce the operant conditioning learning pattern observed in biological organisms:

1. **Exploration** (motor babbling) enables discovery of correct responses
2. **Learning** (temporal induction) forms stimulus-action-outcome hypotheses from reinforced sequences
3. **Exploitation** (goal deduction) uses learned hypotheses to drive goal-directed behavior
4. **Error correction** (anticipation + revision) accumulates negative evidence when predictions fail
5. **Adaptation** (revision) integrates positive and negative evidence, enabling contingency reversal

No abstraction, no declarative reasoning, no higher-order inference — just sensorimotor learning from experience and its consequences.
