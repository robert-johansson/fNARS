# Chapter 7 Replication: Generalized Identity Matching with ONA

This document describes the replication of the experiments from Chapter 7 of *Empirical Studies in Machine Psychology* (Johansson, 2024), using the MPRF framework and OpenNARS for Applications (ONA). The chapter investigates two distinct mechanisms for achieving generalized identity matching: **variable introduction** (Experiment 1) and **comparative reasoning** (Experiment 2).

---

## Part 1: Variable Introduction

## What Changed from Chapter 6

Chapter 6 demonstrated operant conditioning using only temporal and procedural reasoning (NAL layers 7-8). Chapter 7 adds exactly one mechanism:

```c
#define ALLOW_VAR_INTRO true    // was false in Chapter 6
```

Everything else remains the same — `SEMANTIC_INFERENCE_NAL_LEVEL` stays at `0`, so no declarative inference rules are available. The system still operates purely at the sensorimotor level, but now with the ability to **abstract** by introducing variable terms.

| Parameter | Chapter 6 | Chapter 7 |
|-----------|-----------|-----------|
| `SEMANTIC_INFERENCE_NAL_LEVEL` | 0 | 0 |
| `ALLOW_VAR_INTRO` | false | **true** |
| Available NAL layers | 7-8 | **6**, 7-8 |

## The New Mechanism: Variable Introduction (Abstraction)

Variable introduction is the process by which ONA replaces concrete terms with variable terms when deriving temporal implications. When ONA observes a reinforced event sequence and the same term appears in multiple positions within the precondition, it can abstract over that term by replacing all occurrences with a dependent variable (`#n`).

### How It Works: A Concrete Example

Consider a trial where the sample is blue, blue is on the right, green is on the left, and the system matches sample to right (correct):

```
// Observed events:
<(sample * blue) --> (loc * color)>. :|:
<(left * green) --> (loc * color)>. :|:
<(right * blue) --> (loc * color)>. :|:
G! :|:

// Motor babbling executes: ^match with (sample * right)
// Reinforcement: G. :|:
```

Without variable introduction (Chapter 6 configuration), ONA derives only specific hypotheses:

```
// Specific hypothesis (3-precondition):
<((<(sample * blue) --> (loc * color)> &/
  <(right * blue) --> (loc * color)>) &/
  <({SELF} * (sample * right)) --> ^match>) =/> G>. {1.00, 0.15}

// Simpler specific hypotheses (2-precondition):
<(<(right * blue) --> (loc * color)> &/
  <({SELF} * (sample * right)) --> ^match>) =/> G>. {1.00, 0.21}

<(<(sample * blue) --> (loc * color)> &/
  <({SELF} * (sample * right)) --> ^match>) =/> G>. {1.00, 0.16}
```

With variable introduction enabled (Chapter 7 configuration), ONA additionally derives a **general hypothesis** by noticing that the term `blue` appears in both `(sample * blue)` and `(right * blue)`, and that `sample` and `right` appear in the operation argument `(sample * right)`. It replaces these repeated concrete terms with dependent variables:

```
// General hypothesis (derived via variable introduction):
<((<(#1 * #2) --> (loc * color)> &/
  <(#3 * #2) --> (loc * color)>) &/
  <({SELF} * (#1 * #3)) --> ^match>) =/> G>. {1.00, 0.15}
```

Here:
- `#2` replaces `blue` — the shared color property between sample and comparison
- `#1` replaces `sample` — the location of the sample
- `#3` replaces `right` — the location of the matching comparison

The general hypothesis captures the abstract identity concept: "if the stimulus at location `#1` has property `#2`, and the stimulus at location `#3` also has property `#2`, then matching `#1` to `#3` achieves G." This is identity matching expressed as a sensorimotor contingency — match any stimulus to the comparison that shares its property.

### Why the General Hypothesis Accumulates More Evidence

A critical observation from the results is that the general hypothesis reaches much higher confidence (~0.91) than the specific hypotheses (~0.37). This follows directly from the logic of evidence accumulation in NARS:

- Each **specific** hypothesis (e.g., the one involving `blue`) only receives evidence from trials where that exact color appears. With two training colors (green and blue), each specific hypothesis gets evidence from roughly half the trials.
- The **general** hypothesis, because it uses variables, unifies with *every* correctly reinforced trial regardless of color. It receives evidence from 100% of successful trials.

In NAL terms, the general hypothesis has a broader evidential base. After 6 blocks of 12 trials each, it has accumulated evidence from dozens of successful matchings across both colors, while each specific hypothesis has seen only its share.

## Experiment: Generalized Identity Matching-to-Sample

### Initialization

```
*babblingops=1
*motorbabbling=0.9
*setopname 1 ^match
*setoparg 1 1 (sample * left)
*setoparg 1 2 (sample * right)
*volume=0
```

One operation `^match` with two compound arguments: `(sample * left)` and `(sample * right)`.

### Task

Identity matching-to-sample: a sample stimulus is presented alongside two comparisons, one identical to the sample and one different. The correct response is to match the sample to the identical comparison.

Four trial types per block (12 trials per block with BLOCK_MULTIPLIER=3):

| Sample | Left | Right | Correct Response |
|--------|------|-------|-----------------|
| green | green | blue | match sample to left |
| green | blue | green | match sample to right |
| blue | green | blue | match sample to right |
| blue | blue | green | match sample to left |

### Phases

1. **Baseline** (2 blocks, no feedback): Establish chance-level responding
2. **Training** (6 blocks, with feedback): Train identity matching with green and blue
3. **Identity test** (2 blocks, no feedback): Test learned identity matching with trained colors (green/blue)
4. **Generalized identity test** (2 blocks, no feedback): Test with **novel** colors (red/yellow) never seen during training

The critical test is Phase 4. If ONA can correctly match red-to-red and yellow-to-yellow without ever having been trained on these colors, it demonstrates generalized identity matching — the abstract concept of identity has transferred to novel stimuli.

### Hypotheses Tracked

**Specific hypotheses** (4 total, one per trained trial type):
```
<((<(sample * green) --> (loc * color)> &/ <(left * green) --> (loc * color)>) &/
  <({SELF} * (sample * left)) --> ^match>) =/> G>

<((<(sample * green) --> (loc * color)> &/ <(right * green) --> (loc * color)>) &/
  <({SELF} * (sample * right)) --> ^match>) =/> G>

<((<(sample * red) --> (loc * color)> &/ <(left * red) --> (loc * color)>) &/
  <({SELF} * (sample * left)) --> ^match>) =/> G>

<((<(sample * red) --> (loc * color)> &/ <(right * red) --> (loc * color)>) &/
  <({SELF} * (sample * right)) --> ^match>) =/> G>
```

Note: the specific hypotheses include red variants even though red only appears in Phase 4. During training (green/blue only), the red-specific hypotheses remain at confidence 0. They only gain evidence in the generalized identity test phase if the system happens to respond correctly (which it does, driven by the general hypothesis).

**General hypothesis** (1):
```
<((<(#1 * #2) --> (loc * color)> &/ <(#3 * #2) --> (loc * color)>) &/
  <({SELF} * (#1 * #3)) --> ^match>) =/> G>
```

### What Happens During Training

**Early training** — ONA relies on motor babbling. When a correct match occurs by chance and is reinforced, temporal induction derives both a specific and a general hypothesis simultaneously:

```
// Trial: sample=green, left=green, right=blue
// Motor babbling: ^match with (sample * left) → correct → G. :|:

// Specific hypothesis derived:
<((<(sample * green) --> (loc * color)> &/ <(left * green) --> (loc * color)>) &/
  <({SELF} * (sample * left)) --> ^match>) =/> G>. {1.00, 0.15}

// General hypothesis derived (via variable introduction):
<((<(#1 * #2) --> (loc * color)> &/ <(#3 * #2) --> (loc * color)>) &/
  <({SELF} * (#1 * #3)) --> ^match>) =/> G>. {1.00, 0.15}

// Simpler hypotheses also derived:
<(<(left * green) --> (loc * color)> &/
  <({SELF} * (sample * left)) --> ^match>) =/> G>. {1.00, 0.21}
```

**Mid training** — simpler hypotheses are initially preferred (higher confidence), but they accumulate negative evidence from trials where the comparison positions swap. For example, the simple hypothesis "if green is on the left, match to left" fails when green is the non-matching comparison on the left. Through anticipation and revision, these simpler hypotheses lose reliability.

**Late training** — the general hypothesis has accumulated evidence from many successful trials across both colors. Its confidence exceeds the decision threshold, and goal deduction based on the general hypothesis drives correct behavior consistently.

### What Happens During the Generalized Identity Test

When novel stimuli (red, yellow) are presented:

```
// Novel trial: sample=red, left=red, right=yellow
<(sample * red) --> (loc * color)>. :|:
<(left * red) --> (loc * color)>. :|:
<(right * yellow) --> (loc * color)>. :|:
G! :|:
```

No specific hypothesis mentions `red` or `yellow` — they were never seen during training. But the general hypothesis unifies with this situation:

```
// General hypothesis:
<((<(#1 * #2) --> (loc * color)> &/ <(#3 * #2) --> (loc * color)>) &/
  <({SELF} * (#1 * #3)) --> ^match>) =/> G>. {1.00, 0.91}

// Substitution: #1=sample, #2=red, #3=left
// Precondition matches: (sample * red) and (left * red) share property red
// Goal deduction: execute ^match with (sample * left)
```

The variable `#2` binds to `red` (the shared property), `#1` binds to `sample`, and `#3` binds to `left` (where the matching comparison is). ONA correctly matches sample to left — without any training on these colors.

### Results

| Phase | Block | Accuracy | Specific Conf. | General Conf. |
|-------|-------|----------|---------------|--------------|
| Baseline | 1 | 50% | 0.00 | 0.00 |
| Baseline | 2 | 25% | 0.00 | 0.00 |
| Training | 1 | 67% | 0.15 | 0.59 |
| Training | 2 | 67% | 0.22 | 0.74 |
| Training | 3 | 100% | 0.29 | 0.83 |
| Training | 4 | 83% | 0.33 | 0.87 |
| Training | 5 | 83% | 0.35 | 0.90 |
| Training | 6 | 92% | 0.37 | 0.91 |
| Identity test | 1 | **100%** | 0.37 | 0.91 |
| Identity test | 2 | **100%** | 0.37 | 0.91 |
| Gen. identity test | 1 | **100%** | 0.38 | 0.92 |
| Gen. identity test | 2 | **100%** | 0.39 | 0.92 |

The results confirm:

1. **Baseline** is at chance (25-50%)
2. **Training** shows rapid learning — 100% accuracy by block 3, with the general hypothesis confidence climbing from 0.59 to 0.91
3. **Identity test** maintains 100% accuracy without feedback on trained colors
4. **Generalized identity test** achieves **100% accuracy on novel colors** (red/yellow) — the abstract identity concept transfers to never-seen stimuli

The general hypothesis (confidence ~0.91) substantially outperforms the specific hypotheses (confidence ~0.37), consistent with it receiving evidence from every successful trial rather than just color-specific trials.

## All Six Mechanisms

Chapter 7 uses all five mechanisms from Chapter 6 plus one new one:

| # | Mechanism | Role | NAL Layer |
|---|-----------|------|-----------|
| 1 | Temporal induction | Derive stimulus-action-outcome hypotheses from observed sequences | 7 |
| 2 | Goal deduction | Use learned hypotheses to drive goal-directed behavior | 8 |
| 3 | Motor babbling | Random exploration to discover correct responses | 8 |
| 4 | Anticipation | Derive negative evidence when expected consequences don't occur | 7 |
| 5 | Revision | Combine positive and negative evidence for a statement | 7 |
| 6 | **Variable introduction** | **Abstract over concrete terms by replacing repeated atoms with variables** | **6** |

The addition of a single mechanism — variable introduction — transforms operant conditioning (Chapter 6) into generalized concept learning (Chapter 7). With only stimulus-specific hypotheses, the system can learn but cannot generalize beyond its training stimuli. With variable introduction, it derives abstract hypotheses that transfer to any novel stimuli sharing the same relational structure.

---

## Part 2: Comparative Reasoning

The second experiment in Chapter 7 demonstrates an alternative path to generalized identity matching: instead of abstracting over concrete terms with variables, ONA can derive **comparative relations** between stimuli based on their quantifiable attributes. This mechanism, described in Hammer et al. (2023), enables the system to reason about equality and inequality of perceivable properties like brightness or size.

### What Changed from Experiment 1

Experiment 2 uses a fundamentally different mechanism from Experiment 1. Where variable introduction replaces repeated concrete terms with dependent variables (`#1`, `#2`, `#3`), comparative reasoning derives new relational events — equality `(=)` and inequality `(+)` — from the numeric attributes of stimuli.

| Parameter | Experiment 1 | Experiment 2 |
|-----------|-------------|-------------|
| `ALLOW_VAR_INTRO` | true | **false** |
| `ATTRIBUTE_TERM_RELATIONS` | true (default) | **true** (actively used) |
| `*space 10 B` | not used | **required** |
| Generalization mechanism | Variable introduction | Comparative reasoning |

The key difference: variable introduction is turned **off**. The system cannot abstract by replacing atoms with variables. Instead, it relies entirely on the comparative reasoning mechanism provided by `ATTRIBUTE_TERM_RELATIONS`.

### The New Mechanism: Attribute Term Relations

When `ATTRIBUTE_TERM_RELATIONS` is enabled and a numeric space is declared with `*space 10 B`, ONA can compare numeric attributes of stimuli. Given two events with values along the same dimension, ONA automatically derives relational statements:

```
// Input: two stimuli with equal brightness (B_0.5)
<(sample * B_0.5) --> bright>. :|:
<(left * B_0.5) --> bright>. :|:

// ONA automatically derives:
<(sample * left) --> (= bright)>. :|:
```

The derived statement `<(sample * left) --> (= bright)>` means "sample and left have equal brightness." This is a new event that enters ONA's cycling events buffer and can participate in temporal conditioning just like any input event.

Three types of comparative relations can be derived:

| Relation | Meaning | Example |
|----------|---------|---------|
| `(= bright)` | Equal brightness | sample has B_0.5, left has B_0.5 |
| `(+ bright)` | Greater brightness (first > second) | sample has B_0.5, right has B_0.3 |
| `(+ bright)` | Greater brightness (reversed pair) | left has B_0.5, right has B_0.3 |

### How Comparative Reasoning Solves Identity Matching

Consider a trial where sample brightness is B_0.5, left brightness is B_0.5, and right brightness is B_0.3:

```
// Input events:
<(sample * B_0.5) --> bright>. :|:
<(left * B_0.5) --> bright>. :|:
<(right * B_0.3) --> bright>. :|:

// ONA derives comparative relations:
<(sample * left) --> (= bright)>. :|:     // equal: both B_0.5
<(sample * right) --> (+ bright)>. :|:    // unequal: B_0.5 vs B_0.3
<(left * right) --> (+ bright)>. :|:      // unequal: B_0.5 vs B_0.3

// Goal triggers execution:
G! :|:
// Motor babbling or decision: ^match with (sample * left) → correct
// Reinforcement: G. :|:
```

Through temporal conditioning, ONA forms an implication pairing the `(= bright)` event with the correct action:

```
<(<(sample * left) --> (= bright)> &/
  <({SELF} * (sample * left)) --> ^match>) =/> G>.
```

This hypothesis reads: "if the sample and left have equal brightness, then matching sample to left achieves G." Unlike the specific hypothesis which mentions `B_0.5` explicitly, this comparative hypothesis is formulated in terms of the **relational property** `(= bright)`. It applies to any pair of stimuli with equal brightness, regardless of the absolute brightness value.

### How This Differs from Variable Introduction

The two mechanisms achieve generalization through fundamentally different representations:

**Variable introduction** (Experiment 1) creates:
```
<((<(#1 * #2) --> (loc * color)> &/ <(#3 * #2) --> (loc * color)>) &/
  <({SELF} * (#1 * #3)) --> ^match>) =/> G>
```
This is a three-precondition hypothesis with dependent variables. The variable `#2` binds to whatever the shared property value is. The precondition explicitly requires observing both input events with the matching property. Variable introduction works by **syntactic abstraction** — replacing atoms that appear multiple times with variables.

**Comparative reasoning** (Experiment 2) creates:
```
<(<(sample * left) --> (= bright)> &/
  <({SELF} * (sample * left)) --> ^match>) =/> G>
```
This is a simpler two-precondition hypothesis. The `(= bright)` relation is a derived event that already encapsulates the comparison — the system does not need to observe both raw brightness values in the precondition. Comparative reasoning works by **semantic derivation** — producing a new relational event from the raw sensory data before temporal conditioning occurs.

The comparative hypothesis is more compact: it has one precondition event plus the action, rather than two precondition events plus the action. The comparison has already been performed at the perceptual level, resulting in the derived `(= bright)` event.

### Experiment: Comparative Reasoning with Brightness

#### Initialization

```
*babblingops=1
*motorbabbling=0.9
*setopname 1 ^match
*setoparg 1 1 (sample * left)
*setoparg 1 2 (sample * right)
*volume=0
*space 10 B
```

The `*space 10 B` command declares a numeric space with granularity 10 for atoms starting with `B`. This enables ONA to interpret `B_0.5` and `B_0.3` as numeric values along a continuum and compute their comparative relation.

#### Stimulus Encoding

Instead of categorical colors (`green`, `blue`), stimuli are encoded as brightness values:

```
<(sample * B_0.5) --> bright>. :|:
<(left * B_0.3) --> bright>. :|:
<(right * B_0.5) --> bright>. :|:
```

The predicate is `bright` (not the default `(loc * color)` used in Experiment 1). This was specified in the experiment JSON file using the `"predicate": "bright"` field, which the MPRF framework uses to construct the Narsese statements.

#### Trial Structure

Four trial types per block (12 trials per block with BLOCK_MULTIPLIER=3):

| Sample | Left | Right | Correct Response | Derived Relations |
|--------|------|-------|-----------------|-------------------|
| B_0.5 | B_0.5 | B_0.3 | match sample to left | (sample * left) = bright |
| B_0.5 | B_0.3 | B_0.5 | match sample to right | (sample * right) = bright |
| B_0.3 | B_0.5 | B_0.3 | match sample to right | (sample * right) = bright |
| B_0.3 | B_0.3 | B_0.5 | match sample to left | (sample * left) = bright |

In every trial, the correct response is to match the sample to whichever comparison has equal brightness. The `(= bright)` relation is always derived for the correct pairing.

#### Phases

1. **Baseline** (2 blocks, no feedback): Establish chance-level responding
2. **Training** (6 blocks, with feedback): Train brightness matching with B_0.5 and B_0.3
3. **Testing** (2 blocks, no feedback): Test with trained brightness values
4. **Generalized test** (2 blocks, no feedback): Test with **novel** brightness values B_0.8 and B_0.1

The critical test is Phase 4. The training values B_0.5 and B_0.3 never appear in the generalized test. If ONA correctly matches B_0.8-to-B_0.8 and B_0.1-to-B_0.1, it demonstrates that the comparative hypothesis `(= bright)` generalizes across absolute brightness values.

#### Hypotheses Tracked

**Specific hypotheses** (4 total):
```
<((<(sample * B_0.5) --> bright> &/ <(left * B_0.5) --> bright>) &/
  <({SELF} * (sample * left)) --> ^match>) =/> G>

<((<(sample * B_0.5) --> bright> &/ <(right * B_0.5) --> bright>) &/
  <({SELF} * (sample * right)) --> ^match>) =/> G>

<((<(sample * B_0.3) --> bright> &/ <(left * B_0.3) --> bright>) &/
  <({SELF} * (sample * left)) --> ^match>) =/> G>

<((<(sample * B_0.3) --> bright> &/ <(right * B_0.3) --> bright>) &/
  <({SELF} * (sample * right)) --> ^match>) =/> G>
```

These mention specific brightness values and can only match trials with those exact values.

**Comparative hypotheses** (2 total):
```
<(<(sample * left) --> (= bright)> &/
  <({SELF} * (sample * left)) --> ^match>) =/> G>

<(<(sample * right) --> (= bright)> &/
  <({SELF} * (sample * right)) --> ^match>) =/> G>
```

These use the derived `(= bright)` relation and apply to any trial where sample and a comparison share equal brightness, regardless of the absolute value.

#### What Happens During Training

**Early training** — motor babbling drives exploration. When the system happens to select the correct match and receives reinforcement, temporal conditioning pairs the precondition events with the action and outcome. Crucially, the `(= bright)` comparative event participates in this conditioning:

```
// Trial: sample=B_0.5, left=B_0.5, right=B_0.3
<(sample * B_0.5) --> bright>. :|:       // time T
<(left * B_0.5) --> bright>. :|:         // time T+1
  → derived: <(sample * left) --> (= bright)>. :|:
<(right * B_0.3) --> bright>. :|:        // time T+2
  → derived: <(sample * right) --> (+ bright)>. :|:
G! :|:                                    // time T+3
  → motor babbling: ^match(sample * left) → correct
G. :|:                                    // reinforcement

// Temporal conditioning derives both:
// 1. Specific hypothesis:
<((<(sample * B_0.5) --> bright> &/ <(left * B_0.5) --> bright>) &/
  <({SELF} * (sample * left)) --> ^match>) =/> G>. {1.00, 0.15}

// 2. Comparative hypothesis:
<(<(sample * left) --> (= bright)> &/
  <({SELF} * (sample * left)) --> ^match>) =/> G>. {1.00, 0.15}
```

**Across training** — both specific and comparative hypotheses accumulate evidence with each correctly reinforced trial. The specific hypotheses grow in confidence rapidly because the concrete input events (`B_0.5`, `B_0.3`) are always present and highly prioritized. The comparative hypotheses grow more slowly because the derived `(= bright)` event must compete for attention with the input events in ONA's cycling buffer.

#### What Happens During the Generalized Test

When novel brightness values are presented (B_0.8, B_0.1):

```
// Novel trial: sample=B_0.8, left=B_0.8, right=B_0.1
<(sample * B_0.8) --> bright>. :|:
<(left * B_0.8) --> bright>. :|:
  → derived: <(sample * left) --> (= bright)>. :|:
<(right * B_0.1) --> bright>. :|:
  → derived: <(sample * right) --> (+ bright)>. :|:
G! :|:
```

No specific hypothesis mentions `B_0.8` or `B_0.1` — these values never appeared during training. But the comparative hypothesis applies directly:

```
// Comparative hypothesis:
<(<(sample * left) --> (= bright)> &/
  <({SELF} * (sample * left)) --> ^match>) =/> G>. {0.98, 0.42}

// The derived event <(sample * left) --> (= bright)> matches the precondition
// Goal deduction: execute ^match with (sample * left)
```

The `(= bright)` relation is value-independent — it captures equality regardless of what the actual brightness values are. Whether comparing B_0.5 to B_0.5 or B_0.8 to B_0.8, the result is the same: `(= bright)`.

#### Results

| Phase | Block | Accuracy | Specific Conf. | Comparative Conf. |
|-------|-------|----------|---------------|------------------|
| Baseline | 1 | 33% | 0.00 | 0.00 |
| Baseline | 2 | 67% | 0.00 | 0.00 |
| Training | 1 | 42% | 0.16 | 0.21 |
| Training | 2 | 75% | 0.37 | 0.32 |
| Training | 3 | 92% | 0.52 | 0.36 |
| Training | 4 | 100% | 0.62 | 0.40 |
| Training | 5 | 83% | 0.68 | 0.41 |
| Training | 6 | 100% | 0.73 | 0.42 |
| Testing | 1 | **100%** | 0.73 | 0.42 |
| Testing | 2 | **100%** | 0.73 | 0.42 |
| Gen. test | 1 | **100%** | 0.73 | 0.42 |
| Gen. test | 2 | **100%** | 0.73 | 0.42 |

The results confirm:

1. **Baseline** is at chance (33-67%)
2. **Training** shows learning — accuracy reaches 92-100%, specific hypothesis confidence climbs to 0.73, comparative hypothesis confidence grows to 0.42
3. **Testing** maintains 100% accuracy on trained brightness values
4. **Generalized test** achieves **100% accuracy on novel brightness values** (B_0.8, B_0.1) — the comparative reasoning hypothesis enables generalization

#### Comparing Experiment 1 and Experiment 2

| Aspect | Experiment 1 (Variable Introduction) | Experiment 2 (Comparative Reasoning) |
|--------|--------------------------------------|--------------------------------------|
| Generalization mechanism | Dependent variables (#1, #2, #3) | Derived relation (= bright) |
| Hypothesis confidence | ~0.91 (general) | ~0.42 (comparative) |
| Hypothesis structure | 3 preconditions + action | 1 precondition + action |
| Evidence accumulation | Unifies with every trial | Depends on (= bright) being selected |
| Generalization test | 100% correct | 100% correct |

The variable introduction hypothesis reaches higher confidence (~0.91) because it unifies syntactically with every successful trial — the variables bind to whatever atoms are present. The comparative hypothesis reaches lower confidence (~0.42) because the derived `(= bright)` event must be selected into the cycling events buffer and compete for temporal conditioning with the more numerous and higher-priority input events. Despite this difference in confidence, both mechanisms achieve 100% accuracy on the generalization test.

### The `(= bright)` Derivation in Detail

The comparative reasoning mechanism works at the inference level within ONA. When two events sharing the same predicate and having numeric values along a declared dimension arrive in close temporal proximity, ONA derives their comparative relation.

Given:
```
<(sample * B_0.5) --> bright>. :|:    // at time T
<(left * B_0.5) --> bright>. :|:      // at time T+1
```

ONA performs the following internal steps:

1. The predicate `bright` is shared between both statements
2. Both subjects have the form `(X * B_Y)` where `B_Y` is a value in the declared numeric space
3. ONA extracts the positions (`sample`, `left`) and the numeric values (`B_0.5`, `B_0.5`)
4. Since B_0.5 == B_0.5, ONA derives equality: `<(sample * left) --> (= bright)>. :|:`
5. This new event enters the cycling events buffer with its own priority and can participate in temporal conditioning

If the values differ:
```
<(sample * B_0.5) --> bright>. :|:
<(right * B_0.3) --> bright>. :|:
```

ONA derives inequality: `<(sample * right) --> (+ bright)>. :|:`, indicating that sample is brighter than right.

The `*space 10 B` initialization command is essential — it declares that atoms starting with `B` are numeric values with granularity 10. Without this declaration, ONA would treat `B_0.5` and `B_0.3` as opaque atoms with no comparative relationship.

## All Seven Mechanisms

Across both experiments, Chapter 7 uses all five mechanisms from Chapter 6 plus two new ones:

| # | Mechanism | Role | NAL Layer | Experiment |
|---|-----------|------|-----------|------------|
| 1 | Temporal induction | Derive stimulus-action-outcome hypotheses from observed sequences | 7 | Both |
| 2 | Goal deduction | Use learned hypotheses to drive goal-directed behavior | 8 | Both |
| 3 | Motor babbling | Random exploration to discover correct responses | 8 | Both |
| 4 | Anticipation | Derive negative evidence when expected consequences don't occur | 7 | Both |
| 5 | Revision | Combine positive and negative evidence for a statement | 7 | Both |
| 6 | **Variable introduction** | **Abstract over concrete terms by replacing repeated atoms with variables** | **6** | **Exp. 1 only** |
| 7 | **Comparative reasoning** | **Derive equality/inequality relations from numeric attributes of stimuli** | **Sensorimotor** | **Exp. 2 only** |

The two generalization mechanisms are independent — Experiment 1 uses variable introduction with `ALLOW_VAR_INTRO=true` and does not depend on comparative reasoning. Experiment 2 uses comparative reasoning with `ATTRIBUTE_TERM_RELATIONS=true` and explicitly disables variable introduction (`ALLOW_VAR_INTRO=false`). Both achieve 100% generalization to novel stimuli, demonstrating that NARS has multiple independent paths to relational generalization.

### Implications

The existence of two independent mechanisms for generalized identity matching has implications for AGI research:

1. **Redundancy**: An intelligent system can achieve the same behavioral outcome through different internal mechanisms. This mirrors biological intelligence, where multiple neural pathways can support the same cognitive function.

2. **Complementary strengths**: Variable introduction produces higher-confidence hypotheses through syntactic abstraction. Comparative reasoning produces more compact hypotheses through semantic derivation. A system with both mechanisms enabled could leverage whichever is more appropriate for a given task.

3. **Comparative reasoning enables additional tasks**: While both mechanisms solve identity matching, comparative reasoning also enables transposition — learning to always pick the brighter (or dimmer) stimulus. The hypothesis `<(<(sample * left) --> (+ bright)> &/ <({SELF} * (sample * left)) --> ^match>) =/> G>` would encode "match the brighter one," a capability that variable introduction alone cannot achieve because it requires comparing quantitative magnitudes, not just detecting repeated atoms.
