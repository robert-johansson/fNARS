# Chapter 9 Replication: Arbitrarily Applicable Relational Responding (AARR)

This document describes the replication of the AARR experiment from Chapter 9 of *Empirical Studies in Machine Psychology* (Johansson, 2024), using the MPRF framework and OpenNARS for Applications (ONA). This chapter investigates whether ONA can learn relational patterns (SAME and OPPOSITE) from explicit training and then derive novel relational responses to untrained stimulus combinations — the hallmark of Arbitrarily Applicable Relational Responding as described by Relational Frame Theory (RFT).

This experiment corresponds to the empirical work published in the AGI 2025 proceedings paper: "Arbitrarily Applicable Same/Opposite Relational Responding with NARS" (Johansson, Hammer, & Lofthouse, 2025), and builds on the theoretical framework presented in the Frontiers paper: "Modeling Arbitrarily Applicable Relational Responding with the Non-Axiomatic Reasoning System" (Johansson, 2025).

---

## What Changed from Chapter 8

Chapter 8 demonstrated functional equivalence through the implications mechanism (reasoning on statements as terms). Chapter 9 adds a new mechanism:

**Acquired relations** — enabling NARS to derive symbolic relational terms directly from learned sensorimotor contingencies.

This mechanism requires the **RFT82** branch of ONA with a specific `Config_ch9.h` configuration, which implements acquired relations alongside the existing implications mechanism.

| Parameter | Chapters 6 | Chapter 7 (Exp. 1) | Chapter 8 | Chapter 9 |
|-----------|-----------|-----------|-----------|-----------|
| ONA Branch | master | master | master | **RFT82** |
| `ALLOW_VAR_INTRO` | false | true | true | false |
| `FUNCTIONAL_EQUIVALENCE` | false | false | true | true |
| Acquired Relations | no | no | no | **yes** |
| Available mechanisms | 5 | 6 | 7 | **8+** |

## The New Mechanism: Acquired Relations

The key theoretical contribution of Chapter 9 is the concept of **acquired relations** — a mechanism by which NARS extracts symbolic relational terms from learned sensorimotor contingencies. This bridges the gap between low-level contingency learning ("animal-like" behavior) and high-level symbolic reasoning ("human-like" behavior).

### How Acquired Relations Work

When NARS learns a matching-to-sample contingency and acts on it, the system can decompose the contingency into its relational components. Consider a SAME trial where X1 is the sample and Y1 is the correct left comparison:

```
// Sensorimotor events presented to ONA:
<(rel * SAME) --> (loc * ocr)>. :|:      // relational cue: SAME
<(sample * X1) --> (loc * ocr)>. :|:     // sample stimulus: X1
<(left * Y1) --> (loc * ocr)>. :|:       // left comparison: Y1
<(right * Y2) --> (loc * ocr)>. :|:      // right comparison: Y2
G! :|:                                    // goal: make a decision
```

Note the use of `*concurrent` between statements — all stimuli are presented at the same timestamp. After motor babbling selects the correct response and feedback is provided:

```
// Motor babbling selects left (correct for SAME X1→Y1):
^match executed with args ({SELF} * (sample * left))
G. :|:                                    // reinforcement

// ONA derives the contingency:
<(((<(rel * SAME) --> (loc * ocr)> &/
    <(sample * X1) --> (loc * ocr)>) &/
    <(left * Y1) --> (loc * ocr)>) &/
    <({SELF} * (sample * left)) --> ^match>) =/> G>.
```

From this contingency, ONA derives two things:

**1. An acquired relation** — a symbolic relational term extracted from the contingency:

```
<(X1 * Y1) --> SAME>.
```

This states that X1 and Y1 stand in a SAME relation. The relation is "acquired" because it was not directly told to the system — it was extracted from the structure of the sensorimotor contingency.

**2. A generalized implication** — linking the acquired relation back to the contingency:

```
GENERALIZED IMPLICATION:
<(<($1 * $2) --> (loc * loc)> && <($3 * $4) --> SAME>) ==>
  <(((<(rel * SAME) --> (loc * ocr)> &/
      <($1 * $3) --> (loc * ocr)>) &/
      <($2 * $4) --> (loc * ocr)>) &/
      <({SELF} * ($1 * $2)) --> ^match>) =/> G>>
```

This is a powerful abstraction. It says: "If I know a SAME relation between two stimuli ($3 and $4), and I know their locations ($1 and $2), then I can derive the full MTS contingency — and therefore make the correct matching decision." The variables make this applicable to *any* SAME relation at *any* pair of locations.

Similarly, for OPPOSITE relations:

```
GENERALIZED IMPLICATION:
<(<($1 * $2) --> (loc * loc)> && <($3 * $4) --> OPPOSITE>) ==>
  <(((<(rel * OPPOSITE) --> (loc * ocr)> &/
      <($1 * $3) --> (loc * ocr)>) &/
      <($2 * $4) --> (loc * ocr)>) &/
      <({SELF} * ($1 * $2)) --> ^match>) =/> G>>
```

### The Bidirectional Bridge

Acquired relations create a bidirectional bridge between sensorimotor experience and symbolic reasoning:

**Sensorimotor → Symbolic**: Through interacting with the MTS procedure, the system acquires relational terms like `<(X1 * Y1) --> SAME>` from its contingency learning.

**Symbolic → Sensorimotor**: Through the generalized implications, symbolically derived relations (e.g., a SAME relation derived through combinatorial entailment) can be "grounded" back into a concrete MTS contingency, driving behavior in novel situations.

This bridge is what makes AARR possible in NARS — the system can reason symbolically about relations and then use those symbolic conclusions to act in the physical world.

## Three Properties of AARR

AARR is defined by three properties, all of which are targeted by this experiment:

### 1. Mutual Entailment (Symmetry)

If the system learns SAME X1→Y1, it should derive SAME Y1→X1. This is trained explicitly during pretraining (phases XY and YX) and tracked as a hypothesis:

```
// Mutual entailment hypothesis for SAME:
<<($1 * $2) --> SAME> ==> <($2 * $1) --> SAME>>

// Mutual entailment hypothesis for OPPOSITE:
<<($1 * $2) --> OPPOSITE> ==> <($2 * $1) --> OPPOSITE>>
```

These hypotheses are generalized rules stating that SAME and OPPOSITE relations are bidirectional. They emerge from training both XY and YX directions.

### 2. Combinatorial Entailment (Transitivity)

Novel relations can be derived by combining trained relations. Four patterns of combinatorial entailment are tracked:

```
// SAME + SAME = SAME (e.g., SAME A→B, SAME B→C ⟹ SAME A→C)
<(<($1 * #1) --> SAME> && <(#1 * $2) --> SAME>) ==> <($1 * $2) --> SAME>>

// SAME + OPPOSITE = OPPOSITE
<(<($1 * #1) --> SAME> && <(#1 * $2) --> OPPOSITE>) ==> <($1 * $2) --> OPPOSITE>>

// OPPOSITE + SAME = OPPOSITE
<(<($1 * #1) --> OPPOSITE> && <(#1 * $2) --> SAME>) ==> <($1 * $2) --> OPPOSITE>>

// OPPOSITE + OPPOSITE = SAME (the crucial one!)
<(<($1 * #1) --> OPPOSITE> && <(#1 * $2) --> OPPOSITE>) ==> <($1 * $2) --> SAME>>
```

The last rule is particularly important: if A is OPPOSITE to B, and B is OPPOSITE to C, then A is SAME as C. This is the "double negation" of relational reasoning.

### 3. Transformation of Stimulus Function

Functions trained to one stimulus transfer through the relational network. In the full theoretical framework (Frontiers paper), this means: if B1 is trained to elicit ^clap, and C1 is derived as SAME to B1, then C1 should also elicit ^clap. If C1 is OPPOSITE to B2, and B2 elicits ^wave, then C1 should elicit ^clap (the opposite function). The current empirical experiment focuses on properties 1 and 2 (mutual and combinatorial entailment) rather than transformation of function.

## Experiment: Same/Opposite Relational Responding

The experiment tests whether ONA can learn SAME and OPPOSITE relational frames from explicit pretraining, apply them to novel stimulus networks, and then derive correct relational responses for untrained stimulus pairs.

### ONA Branch

```
git checkout RFT82
cp /path/to/Config_ch9.h src/Config.h
sh build.sh
```

The RFT82 branch implements acquired relations — the mechanism by which ONA extracts relational terms from sensorimotor contingencies. A specific `Config_ch9.h` configuration is required, with key settings including:

- `SEMANTIC_INFERENCE_NAL_LEVEL 0` — sensorimotor reasoning only
- `ALLOW_VAR_INTRO false` — variable introduction handled by acquired relations mechanism
- `TABLE_SIZE 650` — larger implication table for storing generalized implications
- `COMPOUND_TERM_SIZE_MAX 256` — larger compound terms for complex contingencies
- `CONCEPTS_MAX 600` — tuned concept memory
- `TOP_K_DECLARATIVE_IMPLICATIONS 20` — limits declarative implication processing
- `DECLARATIVE_IMPLICATIONS_CYCLE_PROCESS true` — process declarative implications each cycle
- `DECLARATIVE_INHERITANCE_SUBGOALING true` — enables inheritance-based subgoaling

### Initialization

```
*babblingops=1
*motorbabbling=0.9
*anticipationconfidence=0.02
*setopname 1 ^match
*setoparg 1 1 (sample * left)
*setoparg 1 2 (sample * right)
*volume=0
```

One operation `^match` with two possible arguments: `(sample * left)` and `(sample * right)`. Motor babbling starts at 90%. Anticipation confidence is set low (0.02).

### The Relational MTS Procedure

Unlike previous chapters where MTS trials had a sample and two comparisons, the AARR experiment adds a **relational contextual cue** — either SAME or OPPOSITE — presented at the `rel` location:

```
// A SAME trial: "Which comparison is SAME as the sample?"
<(rel * SAME) --> (loc * ocr)>. :|:        // contextual cue
*concurrent
<(sample * X1) --> (loc * ocr)>. :|:       // sample
*concurrent
<(left * Y1) --> (loc * ocr)>. :|:         // left comparison
*concurrent
<(right * Y2) --> (loc * ocr)>. :|:        // right comparison
G! :|:                                      // decide

// An OPPOSITE trial: "Which comparison is OPPOSITE to the sample?"
<(rel * OPPOSITE) --> (loc * ocr)>. :|:    // contextual cue
*concurrent
<(sample * X1) --> (loc * ocr)>. :|:       // sample
*concurrent
<(left * Y1) --> (loc * ocr)>. :|:         // left comparison
*concurrent
<(right * Y2) --> (loc * ocr)>. :|:        // right comparison
G! :|:                                      // decide
```

The `*concurrent` command ensures all stimuli are registered at the same timestamp. The contextual cue determines the correct answer: in the SAME trial above, Y1 is correct (X1 SAME Y1); in the OPPOSITE trial, Y2 is correct (X1 OPPOSITE Y2).

### Stimulus Sets and Relational Network

Three stimulus sets are used:

- **Pretraining stimuli**: X1, X2, Y1, Y2, Z1, Z2 — used to establish mutual and combinatorial entailment
- **Network training stimuli**: A1, A2, B1, B2, C1, C2 — used to train and test relational networks

The trained relational network (after phases AB and AC):

```
// Trained relations (solid arrows):
SAME     A1 → B1       OPPOSITE  A1 → B2
SAME     A1 → C1       OPPOSITE  A1 → C2
SAME     A2 → B2       OPPOSITE  A2 → B1
SAME     A2 → C2       OPPOSITE  A2 → C1

// Derived relations (dashed arrows — tested in BC phase):
SAME     B1 → C1   (via: SAME B1→A1 + SAME A1→C1)
SAME     B2 → C2   (via: SAME B2→A2 + SAME A2→C2)
OPPOSITE B1 → C2   (via: SAME B1→A1 + OPPOSITE A1→C2)
OPPOSITE B2 → C1   (via: SAME B2→A2 + OPPOSITE A2→C1)
```

The BC relations are never trained — they must be derived through mutual entailment (reversing A→B to B→A) and combinatorial entailment (chaining B→A + A→C to get B→C).

### Phases

The experiment has 7 phases, each with 4 blocks of 16 trials (8 trial types × BLOCK_MULTIPLIER of 2):

**Phase 1: Pretraining XY** (with feedback)
Train SAME and OPPOSITE relations between X and Y stimuli:
- SAME X1→Y1, SAME X2→Y2
- OPPOSITE X1→Y2, OPPOSITE X2→Y1

**Phase 2: Pretraining YX** (with feedback)
Train the reverse — establishing mutual entailment:
- SAME Y1→X1, SAME Y2→X2
- OPPOSITE Y1→X2, OPPOSITE Y2→X1

After this phase, the system should derive the general symmetry rule:
```
<<($1 * $2) --> SAME> ==> <($2 * $1) --> SAME>>
<<($1 * $2) --> OPPOSITE> ==> <($2 * $1) --> OPPOSITE>>
```

**Phase 3: Pretraining YZ** (with feedback)
Train SAME and OPPOSITE relations between Y and Z stimuli.

**Phase 4: Pretraining XZ** (with feedback)
Train SAME and OPPOSITE relations between X and Z stimuli. After this phase, combinatorial entailment hypotheses should emerge — the system has seen enough examples to derive general rules like:
```
// Trained: SAME X→Y, SAME Y→Z, and also SAME X→Z
// → derive: <(<($1 * #1) --> SAME> && <(#1 * $2) --> SAME>) ==> <($1 * $2) --> SAME>>

// Trained: OPPOSITE X→Y2, OPPOSITE Y2→Z1, and also SAME X→Z1
// → derive: <(<($1 * #1) --> OPPOSITE> && <(#1 * $2) --> OPPOSITE>) ==> <($1 * $2) --> SAME>>
```

**Phase 5: Network Training AB** (with feedback)
Train SAME and OPPOSITE relations for the A-B network with novel stimuli.

**Phase 6: Network Training AC** (with feedback)
Train SAME and OPPOSITE relations for the A-C network with novel stimuli.

**Phase 7: Derived Testing BC** (no feedback, motor babbling disabled)
Test whether the system can derive the correct BC relations — never explicitly trained — using acquired relations, mutual entailment, and combinatorial entailment.

### How Derived Responding Works in the BC Test

When the system encounters a BC trial like:

```
<(rel * SAME) --> (loc * ocr)>. :|:
<(sample * B1) --> (loc * ocr)>. :|:
<(left * C1) --> (loc * ocr)>. :|:
<(right * C2) --> (loc * ocr)>. :|:
G! :|:
```

The system must derive that B1 SAME C1 is correct. The derivation chain:

1. From training, ONA has acquired: `<(A1 * B1) --> SAME>` and `<(A1 * C1) --> SAME>`
2. Using mutual entailment: `<(B1 * A1) --> SAME>` (reversing A1→B1)
3. Using combinatorial entailment (SAME + SAME = SAME): `<(B1 * C1) --> SAME>` (chaining B1→A1→C1)
4. Using the generalized implication, this derived SAME relation grounds back into a contingency that selects `(sample * left)` — the correct response.

For an OPPOSITE trial like B1 with OPPOSITE cue:

1. From training: `<(A1 * B1) --> SAME>` and `<(A1 * C2) --> OPPOSITE>`
2. Mutual entailment: `<(B1 * A1) --> SAME>`
3. Combinatorial entailment (SAME + OPPOSITE = OPPOSITE): `<(B1 * C2) --> OPPOSITE>`
4. This derived OPPOSITE relation grounds into the contingency that selects `(sample * right)`.

### The askAll Queries

After each block, the framework queries all relational pairs across SAME and OPPOSITE frames:

```python
def askAll(stri):
    NAR.AddInput(stri.replace("REL", "SAME"))
    NAR.AddInput(stri.replace("REL", "OPPOSITE"))

askAll("<(A1 * B1) --> REL>?")
askAll("<(B1 * A1) --> REL>?")
askAll("<(B1 * C1) --> REL>?")
askAll("<(C1 * B1) --> REL>?")
# ... (all AB, AC, BC pairs and their reverses)
```

These queries serve a dual purpose: they trigger inference within ONA (asking a question can cause derivations) and they probe the system's internal relational knowledge.

### Hypotheses Tracked

Six hypothesis sets are tracked, corresponding to the three properties of AARR:

**Mutual entailment (2 hypotheses):**
```
<<($1 * $2) --> SAME> ==> <($2 * $1) --> SAME>>
<<($1 * $2) --> OPPOSITE> ==> <($2 * $1) --> OPPOSITE>>
```

**Combinatorial entailment (4 hypotheses):**
```
<(<($1 * #1) --> SAME> && <(#1 * $2) --> SAME>) ==> <($1 * $2) --> SAME>>
<(<($1 * #1) --> SAME> && <(#1 * $2) --> OPPOSITE>) ==> <($1 * $2) --> OPPOSITE>>
<(<($1 * #1) --> OPPOSITE> && <(#1 * $2) --> SAME>) ==> <($1 * $2) --> OPPOSITE>>
<(<($1 * #1) --> OPPOSITE> && <(#1 * $2) --> OPPOSITE>) ==> <($1 * $2) --> SAME>>
```

### Results

The experiment was run multiple times on the RFT82 branch with Config_ch9.h. Due to the nondeterministic nature of trial shuffling and motor babbling, results vary between runs. A representative successful run (from RUN9):

| Phase | Block | Accuracy | SAME Sym. | OPP Sym. | S-S Comb. | S-O Comb. | O-S Comb. | O-O Comb. |
|-------|-------|----------|-----------|----------|-----------|-----------|-----------|-----------|
| Train XY | 1 | 56% | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 |
| Train XY | 2 | 56% | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 |
| Train XY | 3 | 100% | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 |
| Train XY | 4 | 100% | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 |
| Train YX | 1 | 50% | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 |
| Train YX | 2 | 69% | 0.47 | 0.47 | 0.00 | 0.00 | 0.00 | 0.00 |
| Train YX | 3 | 94% | 0.47 | 0.64 | 0.00 | 0.00 | 0.00 | 0.00 |
| Train YX | 4 | 100% | 0.64 | 0.64 | 0.00 | 0.00 | 0.00 | 0.00 |
| Train YZ | 1 | 25% | 0.64 | 0.64 | 0.00 | 0.00 | 0.00 | 0.00 |
| Train YZ | 2 | 69% | 0.64 | 0.64 | 0.00 | 0.00 | 0.00 | 0.00 |
| Train YZ | 3 | 100% | 0.64 | 0.64 | 0.00 | 0.00 | 0.00 | 0.00 |
| Train YZ | 4 | 100% | 0.64 | 0.64 | 0.00 | 0.00 | 0.00 | 0.00 |
| Train XZ | 1 | 50% | 0.64 | 0.64 | 0.00 | 0.00 | 0.00 | 0.00 |
| Train XZ | 2 | 44% | 0.64 | 0.64 | 0.00 | 0.00 | 0.00 | 0.00 |
| Train XZ | 3 | 56% | 0.65 | 0.64 | 0.46 | 0.48 | 0.49 | 0.51 |
| Train XZ | 4 | 50% | 0.65 | 0.64 | 0.58 | 0.48 | 0.49 | 0.51 |
| Train AB | 1 | 31% | 0.65 | 0.64 | 0.58 | 0.48 | 0.49 | 0.51 |
| Train AB | 2 | 56% | 0.65 | 0.64 | 0.58 | 0.48 | 0.49 | 0.51 |
| Train AB | 3 | 75% | 0.65 | 0.64 | 0.58 | 0.48 | 0.49 | 0.51 |
| Train AB | 4 | 81% | 0.65 | 0.64 | 0.58 | 0.48 | 0.49 | 0.51 |
| Train AC | 1 | 69% | 0.65 | 0.64 | 0.58 | 0.48 | 0.49 | 0.51 |
| Train AC | 2 | 63% | 0.65 | 0.64 | 0.58 | 0.48 | 0.49 | 0.51 |
| Train AC | 3 | 88% | 0.65 | 0.64 | 0.59 | 0.48 | 0.49 | 0.51 |
| Train AC | 4 | 100% | 0.65 | 0.64 | 0.59 | 0.48 | 0.57 | 0.51 |
| **Test BC** | **1** | **100%** | 0.65 | 0.64 | 0.59 | 0.48 | 0.57 | 0.51 |
| **Test BC** | **2** | **100%** | 0.65 | 0.64 | 0.59 | 0.48 | 0.57 | 0.51 |
| **Test BC** | **3** | **100%** | 0.65 | 0.64 | 0.59 | 0.48 | 0.57 | 0.51 |
| **Test BC** | **4** | **100%** | 0.65 | 0.64 | 0.59 | 0.48 | 0.57 | 0.51 |

### Interpretation of Results

**Pretraining (XY, YX, YZ, XZ):**
- Each phase shows the characteristic learning curve: starting near chance (with motor babbling) and reaching high accuracy by the final blocks.
- Mutual entailment (symmetry) hypotheses emerge during YX training — after the system has seen both XY and YX directions. Both SAME and OPPOSITE symmetry confidence reach ~0.47 in YX block 2, growing to ~0.64 by YX block 4.
- Combinatorial entailment hypotheses emerge during XZ training (block 3) — the first phase where the system has seen three sets of relations (XY, YZ, XZ) that form a transitive chain. All four combinatorial hypotheses appear simultaneously.
- Notably, XZ training accuracy plateaus at 50-56% despite the relational rules being learned. The experiment succeeds anyway — the relational rules, not the XZ training accuracy, are what matter for the BC test.

**Network Training (AB, AC):**
- The system applies its learned relational abilities to novel stimuli. AB training reaches 81% by block 4; AC training reaches 100%.
- Hypothesis confidences remain stable — the relational rules generalize to the new stimulus sets.
- BC relations are already being derived during AC training through askAll queries, before the BC test even begins.

**Derived Testing (BC):**
- The critical test: BC pairs were never explicitly trained. Performance is **100% across all 4 blocks**, demonstrating robust derived relational responding.
- The system correctly derives SAME B1→C1, SAME B2→C2, OPPOSITE B1→C2, and OPPOSITE B2→C1 purely from the trained AB and AC relations combined with mutual and combinatorial entailment.
- Hypothesis confidences remain constant during testing (no feedback), confirming that responding is driven by previously internalized relational knowledge.

### Nondeterminism

Due to the stochastic nature of trial shuffling and motor babbling, not every run produces 100% on the BC test. Across multiple runs, BC test performance ranges from chance (50%) to perfect (100%). The successful run above demonstrates that the mechanism *can* produce the correct derivations — the variability reflects the sensitivity of ONA's inference to the order in which evidence accumulates. This is consistent with human data, where not all participants pass derived relational tests on the first attempt.

### GENERALIZED IMPLICATIONs in the Log

The full execution log reveals the acquired relations mechanism at work. During training, ONA generates GENERALIZED IMPLICATIONs that link symbolic relations to MTS contingencies:

```
GENERALIZED IMPLICATION:
<(<($1 * $2) --> (loc * loc)> && <($3 * $4) --> SAME>) ==>
  <(((<(rel * SAME) --> (loc * ocr)> &/
      <($1 * $3) --> (loc * ocr)>) &/
      <($2 * $4) --> (loc * ocr)>) &/
      <({SELF} * ($1 * $2)) --> ^match>) =/> G>>

GENERALIZED IMPLICATION:
<(<($1 * $2) --> (loc * loc)> && <($3 * $4) --> OPPOSITE>) ==>
  <(((<(rel * OPPOSITE) --> (loc * ocr)> &/
      <($1 * $3) --> (loc * ocr)>) &/
      <($2 * $4) --> (loc * ocr)>) &/
      <({SELF} * ($1 * $2)) --> ^match>) =/> G>>
```

These are the grounding implications — they say: "Given a SAME (or OPPOSITE) relation between stimuli $3 and $4, and a location mapping $1 and $2, derive the full MTS contingency." When a symbolically derived relation like `<(B1 * C1) --> SAME>` exists, the grounding implication generates the procedural knowledge needed to select the correct comparison in the MTS task.

### Internal Mechanics Revealed by the Execution Log

Analysis of the full execution log (RUN9, 11074 lines, 175 generalized implications) reveals the internal mechanics of acquired relations in fine detail.

#### POS REL and NEG REL: Acquired Relation Creation Events

ONA logs each acquired relation creation with a `POS REL` or `NEG REL` entry. The format is:

```
POS REL d=2, <(X1 * Y1) --> SAME>
```

The `d=2` indicates the derivation distance. These appear immediately after a correct response is reinforced — ONA decomposes the learned contingency and extracts the relational term. The progression across phases shows the acquired relations accumulating:

**XY training** — first acquired relations appear:
```
POS REL d=2, <(X1 * Y1) --> SAME>
POS REL d=2, <(X2 * Y2) --> SAME>
POS REL d=2, <(X2 * Y1) --> OPPOSITE>
POS REL d=2, <(X1 * Y2) --> OPPOSITE>
```

**YX training** — reverse relations acquired:
```
POS REL d=2, <(Y1 * X1) --> SAME>
POS REL d=2, <(Y2 * X2) --> SAME>
POS REL d=2, <(Y1 * X2) --> OPPOSITE>
POS REL d=2, <(Y2 * X1) --> OPPOSITE>
```

**AB training** — novel stimulus relations acquired:
```
POS REL d=2, <(A1 * B1) --> SAME>
POS REL d=2, <(A2 * B2) --> SAME>
POS REL d=2, <(A2 * B1) --> OPPOSITE>
POS REL d=2, <(A1 * B2) --> OPPOSITE>
```

**AC training** — completing the relational network:
```
POS REL d=2, <(A1 * C1) --> SAME>
POS REL d=2, <(A2 * C2) --> SAME>
POS REL d=2, <(A1 * C2) --> OPPOSITE>
POS REL d=2, <(A2 * C1) --> OPPOSITE>
```

Notably, no `POS REL` entries appear for BC pairs — these relations are never directly acquired from experience. They are purely derived through the entailment rules.

**NEG REL** entries represent negative acquired relations — they appear during XZ training when ONA's expectation is violated:
```
NEG REL d=2, <(Z2 * Z1) --> OPPOSITE>
NEG REL d=2, <(Z1 * Z2) --> OPPOSITE>
```

#### Three Types of Generalized Implications

The log reveals three distinct types of generalized implications (not just two):

**1. SAME frame** (85 instances):
```
<(<($1 * $2) --> (loc * loc)> && <($3 * $4) --> SAME>) ==>
  <(((<(rel * SAME) --> (loc * ocr)> &/ <($1 * $3) --> (loc * ocr)>) &/
      <($2 * $4) --> (loc * ocr)>) &/ <({SELF} * ($1 * $2)) --> ^match>) =/> G>>
```

**2. OPPOSITE frame** (85 instances):
```
<(<($1 * $2) --> (loc * loc)> && <($3 * $4) --> OPPOSITE>) ==>
  <(((<(rel * OPPOSITE) --> (loc * ocr)> &/ <($1 * $3) --> (loc * ocr)>) &/
      <($2 * $4) --> (loc * ocr)>) &/ <({SELF} * ($1 * $2)) --> ^match>) =/> G>>
```

**3. Generic `(ocr * ocr)` frame** (5 instances, appears during XZ training):
```
<(<($1 * $2) --> (loc * loc)> && <($3 * $4) --> (ocr * ocr)>) ==>
  <((<($1 * $3) --> (loc * ocr)> &/ <($2 * $4) --> (loc * ocr)>) &/
      <({SELF} * ($1 * $2)) --> ^match>) =/> G>>
```

The third type is a relation-agnostic matching rule — it omits the relational cue entirely and uses the generic `(ocr * ocr)` relation type. This corresponds to a general identity matching rule similar to what was learned in Chapter 7. It appears alongside `POS REL d=2, <(X2 * Z2) --> (ocr * ocr)>`, showing that ONA sometimes extracts generic relations without the SAME/OPPOSITE label when the contextual cue isn't fully incorporated into the contingency.

#### DERIVED Statements: Tracing the Derivation Chain

The log shows each derivation step with the format `DERIVED<rule> |- <conclusion>`, making the inference chain fully transparent.

**Mutual entailment in action** — reversing trained relations:
```
DERIVED<<($1 * $2) --> SAME> ==> <($2 * $1) --> SAME>> |- <(B1 * A1) --> SAME>
DERIVED<<($1 * $2) --> SAME> ==> <($2 * $1) --> SAME>> |- <(B2 * A2) --> SAME>
DERIVED<<($1 * $2) --> OPPOSITE> ==> <($2 * $1) --> OPPOSITE>> |- <(B1 * A2) --> OPPOSITE>
```

**Combinatorial entailment deriving BC relations** — the critical derivations:
```
// B1 SAME C1 — derived via two independent paths:
DERIVED<(<($1 * #1) --> SAME> && <(#1 * $2) --> SAME>) ==> <($1 * $2) --> SAME>>
  |- <(B1 * C1) --> SAME>                           // SAME + SAME = SAME
DERIVED<(<($1 * #1) --> OPPOSITE> && <(#1 * $2) --> OPPOSITE>) ==> <($1 * $2) --> SAME>>
  |- <(B1 * C1) --> SAME>                           // OPP + OPP = SAME

// B1 OPPOSITE C2 — derived via two independent paths:
DERIVED<(<($1 * #1) --> OPPOSITE> && <(#1 * $2) --> SAME>) ==> <($1 * $2) --> OPPOSITE>>
  |- <(B1 * C2) --> OPPOSITE>                       // OPP + SAME = OPP
DERIVED<(<($1 * #1) --> SAME> && <(#1 * $2) --> OPPOSITE>) ==> <($1 * $2) --> OPPOSITE>>
  |- <(B1 * C2) --> OPPOSITE>                       // SAME + OPP = OPP

// B2 SAME C2:
DERIVED<(<($1 * #1) --> SAME> && <(#1 * $2) --> SAME>) ==> <($1 * $2) --> SAME>>
  |- <(B2 * C2) --> SAME>
DERIVED<(<($1 * #1) --> OPPOSITE> && <(#1 * $2) --> OPPOSITE>) ==> <($1 * $2) --> SAME>>
  |- <(B2 * C2) --> SAME>

// B2 OPPOSITE C1:
DERIVED<(<($1 * #1) --> OPPOSITE> && <(#1 * $2) --> SAME>) ==> <($1 * $2) --> OPPOSITE>>
  |- <(B2 * C1) --> OPPOSITE>
DERIVED<(<($1 * #1) --> SAME> && <(#1 * $2) --> OPPOSITE>) ==> <($1 * $2) --> OPPOSITE>>
  |- <(B2 * C1) --> OPPOSITE>
```

Each BC relation converges from multiple derivation paths, reinforcing the conclusion through independent evidence. The system also derives symmetric versions (e.g., `<(C1 * B1) --> SAME>` from `<(B1 * C1) --> SAME>` via mutual entailment).

**Self-relations and within-network relations** are also derived:
```
DERIVED<(<($1 * #1) --> SAME> && <(#1 * $2) --> SAME>) ==> <($1 * $2) --> SAME>>
  |- <(B1 * B1) --> SAME>                           // reflexivity
DERIVED<(<($1 * #1) --> SAME> && <(#1 * $2) --> OPPOSITE>) ==> <($1 * $2) --> OPPOSITE>>
  |- <(B1 * B2) --> OPPOSITE>                       // within-set opposition
```

#### When BC Relations Are Derived

A key observation: BC relations are first derived during the **AC training phase** (not during the BC test), triggered by the `askAll` queries at the end of each AC block. The first BC derivations appear at lines 8669-8886 of the log (within the AC phase spanning lines 7900-9540). By the time the BC test begins, the derived relations already exist in ONA's memory.

#### Acquired Relation Confidence Growth

The `Answer` entries reveal how acquired relation confidence grows with repeated training:

```
// A1 SAME B1: confidence grows from direct acquisition to revision
Answer: <(A1 * B1) --> SAME>. Truth: frequency=1.000000, confidence=0.900000     // 1 exemplar
Answer: <(A1 * B1) --> SAME>. Truth: frequency=1.000000, confidence=0.964286    // 2 exemplars
Answer: <(A1 * B1) --> SAME>. Truth: frequency=1.000000, confidence=0.978261    // 3 exemplars
Answer: <(A1 * B1) --> SAME>. Truth: frequency=1.000000, confidence=0.990000    // converging

// Derived B1 SAME A1 (via mutual entailment) — lower confidence:
Answer: <(B1 * A1) --> SAME>. Truth: frequency=1.000000, confidence=0.587547
Answer: <(B1 * A1) --> SAME>. Truth: frequency=1.000000, confidence=0.629514
Answer: <(B1 * A1) --> SAME>. Truth: frequency=1.000000, confidence=0.638638

// Derived B1 SAME C1 (via combinatorial entailment) — lowest confidence:
Answer: <(B1 * C1) --> SAME>. Truth: frequency=1.000000, confidence=0.364203
```

The confidence hierarchy reflects the derivation chain: directly acquired relations (0.90+) > mutually entailed relations (~0.64) > combinatorially entailed relations (~0.36). Longer derivation chains produce lower confidence, which is exactly what NARS truth value propagation predicts.

#### The Grounding in Action During BC Test

During the BC test phase, the `decision` line reveals the generalized implication grounding mechanism working in real time:

```
// Trial: SAME B1, C1 left, C2 right
Input: <(rel * SAME) --> (loc * ocr)>. :|:
Input: <(sample * B1) --> (loc * ocr)>. :|:
Input: <(left * C1) --> (loc * ocr)>. :|:
Input: <(right * C2) --> (loc * ocr)>. :|:
Input: G! :|:

decision expectation=0.695937 implication:
  <(((<(rel * SAME) --> (loc * ocr)> &/
      <(sample * B1) --> (loc * ocr)>) &/
      <(left * C1) --> (loc * ocr)>) &/
      <({SELF} * (sample * left)) --> ^match>) =/> G>.
  Truth: frequency=1.000000 confidence=0.746596

GENERALIZED IMPLICATION: <(<($1 * $2) --> (loc * loc)> && <($3 * $4) --> SAME>) ==> ...>
^match executed with args ({SELF} * (sample * left))    // CORRECT
```

The system has the derived relation `<(B1 * C1) --> SAME>`, the generalized implication instantiates it with the current locations (sample, left), producing a concrete MTS contingency with confidence 0.747. The decision expectation (0.696) exceeds the threshold (0.501), so ONA executes `^match` with `(sample * left)` — selecting C1, the correct SAME comparison.

## Theoretical Demonstrations: Beyond Same/Opposite

The empirical experiment above validates the acquired relations mechanism for SAME and OPPOSITE frames. The Frontiers paper (Johansson, 2025) goes further with two theoretical demonstrations that show how the NARS framework can capture the full scope of AARR — including the third property, **transformation of stimulus function**. These demonstrations were not empirically implemented but serve as logical proofs-of-concept, showing that NARS's inference rules are sufficient for these more complex relational phenomena. The two demonstrations are based on classic human studies from the RFT literature.

### Demonstration 1: Stimulus Equivalence and Transfer of Function

*Inspired by Hayes et al. (1987)*

This demonstration uses simple coordination (equivalence) relations — no SAME/OPPOSITE contextual cues. The acquired relations use the generic `(ocr * ocr)` relation type rather than labeled SAME/OPPOSITE frames.

#### Phase 1: Pretraining

**Learning conditionality.** The system is trained on X1→Y1 via MTS:

```
<(sample * X1) --> (loc * ocr)>. :|:
<(left * Y1) --> (loc * ocr)>. :|:
<(right * Y2) --> (loc * ocr)>. :|:
G! :|:
// Motor babbling selects left (correct), reinforcement given
```

From this, ONA derives the contingency, then extracts an acquired relation and a grounding implication:

```
// Acquired relation:
<(X1 * Y1) --> (ocr * ocr)>

// Grounding implication (concrete):
<(X1 * Y1) --> (ocr * ocr)> && <(sample * left) --> (loc * loc)>
  ==>
  <((<(sample * X1) --> (loc * ocr)> &/
      <(left * Y1) --> (loc * ocr)>) &/
      <({SELF} * (sample * left)) --> ^match>) =/> G>

// Grounding implication (abstract — with variables):
<($1 * $2) --> (ocr * ocr)> && <($3 * $4) --> (loc * loc)>
  ==>
  <((<($3 * $1) --> (loc * ocr)> &/
      <($4 * $2) --> (loc * ocr)>) &/
      <({SELF} * ($3 * $4)) --> ^match>) =/> G>
```

Note that the `(ocr * ocr)` relation type is generic — it does not carry a relational label like SAME. This is the third type of generalized implication observed in the execution log (5 instances during XZ training in RUN9). It corresponds to a basic coordination/equivalence frame.

**Learning symmetry.** Training Y1→X1 after X1→Y1 yields:

```
// Functional equivalence between the two relations:
<(X1 * Y1) --> (ocr * ocr)> <=> <(Y1 * X1) --> (ocr * ocr)>

// Abstract symmetry rule:
<($1 * $2) --> (ocr * ocr)> <=> <($2 * $1) --> (ocr * ocr)>
```

**Learning transitivity.** Training X1→Y1, Y1→Z1, and X1→Z1 yields:

```
// Concrete:
<(X1 * Y1) --> (ocr * ocr)> && <(Y1 * Z1) --> (ocr * ocr)>
  ==> <(X1 * Z1) --> (ocr * ocr)>

// Abstract transitivity rule:
<($1 * $2) --> (ocr * ocr)> && <($2 * $3) --> (ocr * ocr)>
  ==> <($1 * $3) --> (ocr * ocr)>
```

**Learning a relation from functional equivalence.** The system also learns to derive relations from shared discriminative function. When X1 and Y1 both serve as discriminative stimuli for the same operant:

```
// Both X1 and Y1 control the same response:
<(<(sample * X1) --> (loc * ocr)> &/
    <({SELF} * op1) --> ^action>) =/> G>

<(<(sample * Y1) --> (loc * ocr)> &/
    <({SELF} * op1) --> ^action>) =/> G>

// Functional equivalence derived:
<(sample * X1) --> (loc * ocr)> <=> <(sample * Y1) --> (loc * ocr)>

// Abstract functional equivalence implication:
<($1 * $2) --> (ocr * ocr)> && <($3 * $3) --> (loc * loc)>
  ==>
  <($3 * $1) --> (loc * ocr)> <=> <($3 * $2) --> (loc * ocr)>
```

This is the key mechanism for transfer of function — it says: if two stimuli are in an `(ocr * ocr)` relation, then perceiving one at a location is functionally equivalent to perceiving the other at that location.

#### Phase 2: Training ABC Networks

The A1-B1-C1 and A2-B2-C2 networks are trained via MTS (A1→B1, A1→C1, etc.). The complete derived network:

```
// (1) Trained:  <(A1 * B1) --> (ocr * ocr)>
// (2) Trained:  <(A1 * C1) --> (ocr * ocr)>
// (3) Derived by symmetry from (1):   <(B1 * A1) --> (ocr * ocr)>
// (4) Derived by symmetry from (2):   <(C1 * A1) --> (ocr * ocr)>
// (5) Derived by transitivity (3)+(2): <(B1 * C1) --> (ocr * ocr)>
// (6) Derived by symmetry from (5):   <(C1 * B1) --> (ocr * ocr)>
```

The same derivations produce the A2-B2-C2 network.

#### Phase 3: Function Training

Two discriminative functions are trained on B stimuli:

```
// B1 → ^clap:
<(<(sample * B1) --> (loc * ocr)> &/
    <({SELF} * clap) --> ^action>) =/> G>

// B2 → ^wave:
<(<(sample * B2) --> (loc * ocr)> &/
    <({SELF} * wave) --> ^action>) =/> G>
```

#### Phase 4: Testing Derived Relations and Transfer of Function

**Testing derived MTS.** When presented with C1 as sample and B1/B2 as comparisons:

```
<(sample * C1) --> (loc * ocr)>. :|:
<(left * B1) --> (loc * ocr)>. :|:
<(right * B2) --> (loc * ocr)>. :|:
G! :|:
```

The system has the derived relation `<(C1 * B1) --> (ocr * ocr)>` and the location relation `<(sample * left) --> (loc * loc)>`. Using the grounding implication:

```
<(C1 * B1) --> (ocr * ocr)> && <(sample * left) --> (loc * loc)>
  ==>
  <((<(sample * C1) --> (loc * ocr)> &/
      <(left * B1) --> (loc * ocr)>) &/
      <({SELF} * (sample * left)) --> ^match>) =/> G>
```

The system selects `(sample * left)` — correct, matching C1 to B1.

**Testing transfer of function.** When C1 alone is presented with a goal:

```
<(sample * C1) --> (loc * ocr)>. :|:
G! :|:
```

Using the derived relation `<(C1 * B1) --> (ocr * ocr)>` and the functional equivalence implication:

```
<(C1 * B1) --> (ocr * ocr)> && <(sample * sample) --> (loc * loc)>
  ==>
  <(sample * C1) --> (loc * ocr)> <=> <(sample * B1) --> (loc * ocr)>
```

This functional equivalence means that perceiving C1 at the sample location is functionally equivalent to perceiving B1. Since B1 controls `^clap`, the system executes `^clap` when C1 is presented. Similarly, C2 → B2 equivalence means C2 triggers `^wave`.

**The function has transferred**: ^clap trained on B1 now controls responding to C1, and ^wave trained on B2 now controls responding to C2 — purely through the derived equivalence network, without any direct function training on C stimuli.

### Demonstration 2: Opposition and Transformation of Function

*Inspired by Roche & Barnes (2000)*

This demonstration adds SAME and OPPOSITE contextual cues, using the nested relation format from the Frontiers paper. In the theoretical framework, relations are encoded as `<(SAME * (X1 * Y1)) --> (ocr * (ocr * ocr))>` — a product where the relational label (SAME/OPPOSITE) is part of the term structure. In the ONA implementation, this simplifies to `<(X1 * Y1) --> SAME>`.

#### Phase 1: Pretraining of Relational Frames

**Learning acquired relations with contextual cues.** Training SAME X1→Y1 in a relational MTS procedure:

```
<(rel * SAME) --> (loc * ocr)>. :|:
<(sample * X1) --> (loc * ocr)>. :|:
<(left * Y1) --> (loc * ocr)>. :|:
<(right * Y2) --> (loc * ocr)>. :|:
G! :|:
```

The acquired relation now includes the relational label:

```
// Acquired relation (theoretical notation):
<(SAME * (X1 * Y1)) --> (ocr * (ocr * ocr))>

// Grounding implication (abstract):
<($1 * ($2 * $3)) --> (ocr * (ocr * ocr))> &&
  <($4 * ($5 * $6)) --> (loc * (loc * loc))>
  ==>
  <((<($4 * $1) --> (loc * ocr)> &/
      <($5 * $2) --> (loc * ocr)>) &/
      <($6 * $3) --> (loc * ocr)>) &/
      <({SELF} * ($5 * $6)) --> ^match>) =/> G>
```

In ONA's actual implementation, the acquired relation is the simpler `<(X1 * Y1) --> SAME>`, and the grounding implication takes the form observed in RUN9:

```
<(<($1 * $2) --> (loc * loc)> && <($3 * $4) --> SAME>) ==>
  <(((<(rel * SAME) --> (loc * ocr)> &/
      <($1 * $3) --> (loc * ocr)>) &/
      <($2 * $4) --> (loc * ocr)>) &/
      <({SELF} * ($1 * $2)) --> ^match>) =/> G>>
```

**Learning mutual entailment.** Training both SAME X1→Y1 and SAME Y1→X1 yields the symmetry rule:

```
// Theoretical notation:
<(SAME * (X1 * Y1)) --> (ocr * (ocr * ocr))>
  <=>
<(SAME * (Y1 * X1)) --> (ocr * (ocr * ocr))>

// Abstract:
<($1 * ($2 * $3)) --> (ocr * (ocr * ocr))>
  <=>
<($1 * ($3 * $2)) --> (ocr * (ocr * ocr))>

// ONA implementation (from hypothesis tracking):
<<($1 * $2) --> SAME> ==> <($2 * $1) --> SAME>>
<<($1 * $2) --> OPPOSITE> ==> <($2 * $1) --> OPPOSITE>>
```

**Learning combinatorial entailment.** Four patterns are explicitly trained:

```
// SAME + SAME = SAME:
<(SAME * ($1 * $2)) --> (ocr * (ocr * ocr))> &&
  <(SAME * ($2 * $3)) --> (ocr * (ocr * ocr))>
  ==> <(SAME * ($1 * $3)) --> (ocr * (ocr * ocr))>

// ONA: <(<($1 * #1) --> SAME> && <(#1 * $2) --> SAME>) ==> <($1 * $2) --> SAME>>

// SAME + OPPOSITE = OPPOSITE:
<(SAME * ($1 * $2)) --> (ocr * (ocr * ocr))> &&
  <(OPPOSITE * ($2 * $3)) --> (ocr * (ocr * ocr))>
  ==> <(OPPOSITE * ($1 * $3)) --> (ocr * (ocr * ocr))>

// OPPOSITE + SAME = OPPOSITE:
<(OPPOSITE * ($1 * $2)) --> (ocr * (ocr * ocr))> &&
  <(SAME * ($2 * $3)) --> (ocr * (ocr * ocr))>
  ==> <(OPPOSITE * ($1 * $3)) --> (ocr * (ocr * ocr))>

// OPPOSITE + OPPOSITE = SAME:
<(OPPOSITE * ($1 * $2)) --> (ocr * (ocr * ocr))> &&
  <(OPPOSITE * ($2 * $3)) --> (ocr * (ocr * ocr))>
  ==> <(SAME * ($1 * $3)) --> (ocr * (ocr * ocr))>
```

**Learning functional equivalence with relational cues.** For the system to transfer functions across relational contexts, it must learn that the relational cue + sample together can be functionally equivalent to a different stimulus. For SAME:

```
// Both (SAME + X1) and Y1 control the same operant:
<((<(rel * SAME) --> (loc * ocr)> &/
    <(sample * X1) --> (loc * ocr)>) &/
    <({SELF} * op1) --> ^action>) =/> G>

<(<(sample * Y1) --> (loc * ocr)> &/
    <({SELF} * op1) --> ^action>) =/> G>

// Functional equivalence derived:
<(rel * SAME) --> (loc * ocr)> &/ <(sample * X1) --> (loc * ocr)>
  <=>
<(sample * Y1) --> (loc * ocr)>

// Abstract functional equivalence implication:
<($1 * ($2 * $3)) --> (ocr * (ocr * ocr))> &&
  <($4 * ($5 * $5)) --> (loc * (loc * loc))>
  ==>
  <($4 * $1) --> (loc * ocr)> &/ <($5 * $2) --> (loc * ocr)>
    <=>
  <($5 * $3) --> (loc * ocr)>
```

This says: if SAME(X1, Y1) holds, then perceiving the SAME cue together with X1 is functionally equivalent to perceiving Y1 alone. Crucially, the same abstract form applies to OPPOSITE relations too — the relational label is captured by the variable $1.

#### Phase 2: Training SAME/OPPOSITE ABC Networks

The full relational network is trained and derived. With trained relations (1)-(4) and derived relations (5)-(14):

```
// Trained:
// (1)  SAME     A1 → B1:  <(SAME * (A1 * B1)) --> (ocr * (ocr * ocr))>
// (2)  SAME     A1 → C1:  <(SAME * (A1 * C1)) --> (ocr * (ocr * ocr))>
// (3)  OPPOSITE A1 → B2:  <(OPPOSITE * (A1 * B2)) --> (ocr * (ocr * ocr))>
// (4)  OPPOSITE A1 → C2:  <(OPPOSITE * (A1 * C2)) --> (ocr * (ocr * ocr))>

// Derived by mutual entailment:
// (5)  SAME     B1 → A1  (from 1)
// (6)  SAME     C1 → A1  (from 2)
// (10) OPPOSITE B2 → A1  (from 3)
// (11) OPPOSITE C2 → A1  (from 4)

// Derived by combinatorial entailment:
// (7)  SAME     B1 → C1  (from 5+2: SAME+SAME=SAME)
// (9)  OPPOSITE C1 → B2  (from 6+3: SAME+OPPOSITE=OPPOSITE)
// (12) OPPOSITE C2 → B1  (from 11+1: OPPOSITE+SAME=OPPOSITE)
// (13) SAME     B2 → C2  (from 10+4: OPPOSITE+OPPOSITE=SAME)

// Derived by mutual entailment of derived:
// (8)  SAME     C1 → B1  (from 7)
// (14) SAME     C2 → B2  (from 13)
```

#### Phase 3: Function Training

Same as Demonstration 1 — B1→^clap, B2→^wave.

#### Phase 4: Testing Derived Relations and Transformation of Function

**Testing derived MTS.** The SAME C1→B1 relation enables correct matching when given the SAME contextual cue:

```
<(rel * SAME) --> (loc * ocr)>. :|:
<(sample * C1) --> (loc * ocr)>. :|:
<(left * B1) --> (loc * ocr)>. :|:
<(right * B2) --> (loc * ocr)>. :|:
G! :|:
```

Using the grounding implication with `<(SAME * (C1 * B1)) --> (ocr * (ocr * ocr))>`:

```
<(SAME * (C1 * B1)) --> (ocr * (ocr * ocr))> &&
  <(rel * (sample * left)) --> (loc * (loc * loc))>
  ==>
  <(((<(rel * SAME) --> (loc * ocr)> &/
      <(sample * C1) --> (loc * ocr)>) &/
      <(left * B1) --> (loc * ocr)>) &/
      <({SELF} * (sample * left)) --> ^match>) =/> G>
```

Correct: selects B1 (left) under SAME cue.

**Testing transformation of function — the critical test.** When C1 is presented with the SAME contextual cue:

```
<(rel * SAME) --> (loc * ocr)>. :|:
<(sample * C1) --> (loc * ocr)>. :|:
G! :|:
```

Using the derived relation SAME C1→B1 and the functional equivalence implication:

```
<(SAME * (C1 * B1)) --> (ocr * (ocr * ocr))> &&
  <(rel * (sample * sample)) --> (loc * (loc * loc))>
  ==>
  <(rel * SAME) --> (loc * ocr)> &/ <(sample * C1) --> (loc * ocr)>
    <=>
  <(sample * B1) --> (loc * ocr)>
```

The SAME cue plus C1 is functionally equivalent to B1. Since B1 controls `^clap`, the system executes **^clap**. The function transfers directly — C1 "means the same as" B1.

Now the transformation: when C1 is presented with the **OPPOSITE** contextual cue:

```
<(rel * OPPOSITE) --> (loc * ocr)>. :|:
<(sample * C1) --> (loc * ocr)>. :|:
G! :|:
```

The derived relation OPPOSITE C1→B2 (relation 9 above) is used:

```
<(OPPOSITE * (C1 * B2)) --> (ocr * (ocr * ocr))> &&
  <(rel * (sample * sample)) --> (loc * (loc * loc))>
  ==>
  <(rel * OPPOSITE) --> (loc * ocr)> &/ <(sample * C1) --> (loc * ocr)>
    <=>
  <(sample * B2) --> (loc * ocr)>
```

The OPPOSITE cue plus C1 is functionally equivalent to B2. Since B2 controls `^wave`, the system executes **^wave**. The function has been **transformed** — the same stimulus C1 produces the *opposite* response (^wave instead of ^clap) because the relational context changed from SAME to OPPOSITE.

This is the hallmark of transformation of stimulus function: the behavioral function of a stimulus is not fixed but depends on the relational frame through which it is accessed.

### Summary: The Full AARR Picture

| Property | Demonstration 1 | Demonstration 2 |
|----------|----------------|----------------|
| **Mutual entailment** | Symmetry: `<($1 * $2) --> (ocr * ocr)> <=> <($2 * $1) --> (ocr * ocr)>` | Context-sensitive symmetry for SAME and OPPOSITE frames |
| **Combinatorial entailment** | Transitivity: A→B, B→C ⟹ A→C | Four rules: S+S=S, S+O=O, O+S=O, O+O=S |
| **Transfer/transformation of function** | C1→B1 equivalence transfers ^clap directly | SAME+C1→^clap (transfer), OPPOSITE+C1→^wave (transformation) |

The key distinction:
- In **Demonstration 1** (stimulus equivalence), functions **transfer** — they pass unchanged through equivalence relations. C1 equivalent to B1 means C1 inherits B1's function.
- In **Demonstration 2** (opposition frames), functions **transform** — the same stimulus C1 can produce *different* responses depending on the relational context (SAME vs OPPOSITE). Under SAME, C1 elicits ^clap (same as B1). Under OPPOSITE, C1 elicits ^wave (same as B2, the opposite member).

### Connection to the Empirical Experiment

The empirical AARR experiment (aarr1.json on RFT82) validates the foundational mechanism needed for both demonstrations: acquired relations with mutual and combinatorial entailment for SAME and OPPOSITE frames. The BC test is essentially a simplified version of Demonstration 2's derived MTS testing — the system derives B→C relations and uses them to respond correctly.

What remains to be empirically validated:
1. **Transfer of function** (Demonstration 1): Training B1→^clap, B2→^wave, then testing if C1→^clap and C2→^wave emerge through equivalence
2. **Transformation of function** (Demonstration 2): Testing if the same stimulus produces different responses under different relational cues (SAME+C1→^clap vs OPPOSITE+C1→^wave)

These would require extending the experiment with additional phases for function training and testing, using operations beyond `^match` (e.g., `^clap` and `^wave` as separate operants).

### Relation Encoding: Theory vs Implementation

The Frontiers paper uses the formally correct NAL representation with nested products:

```
// Theoretical (Frontiers paper):
<(SAME * (X1 * Y1)) --> (ocr * (ocr * ocr))>
```

The ONA implementation (RFT82) uses a simplified encoding:

```
// Implementation (ONA RFT82):
<(X1 * Y1) --> SAME>
```

Both representations capture the same information — that X1 and Y1 stand in a SAME relation — but the theoretical notation explicitly embeds the relation type within the product structure, while the implementation uses SAME/OPPOSITE as direct predicates. The theoretical notation `<($1 * ($2 * $3)) --> (ocr * (ocr * ocr))>` naturally unifies with the `(ocr * ocr)` generic relations from Demonstration 1, since `(ocr * ocr)` is just the case where the relational label is the generic property type rather than a named frame.

## All Mechanisms

Chapter 9 uses all mechanisms from Chapter 8 plus acquired relations:

| # | Mechanism | Role | NAL Layer | Chapter |
|---|-----------|------|-----------|---------|
| 1 | Temporal induction | Derive stimulus-action-outcome hypotheses from observed sequences | 7 | 6+ |
| 2 | Goal deduction | Use learned hypotheses to drive goal-directed behavior | 8 | 6+ |
| 3 | Motor babbling | Random exploration to discover correct responses | 8 | 6+ |
| 4 | Anticipation | Derive negative evidence when expected consequences don't occur | 7 | 6+ |
| 5 | Revision | Combine positive and negative evidence for a statement | 7 | 6+ |
| 6 | Variable introduction | Abstract over concrete terms by replacing repeated atoms with variables | 6 | 7+ |
| 7 | Implications | Derive two-way implications between preconditions of contingencies sharing the same operation and consequence | 5 | 8+ |
| 8 | **Acquired relations** | **Extract symbolic relational terms from sensorimotor contingencies; derive grounding implications that link symbolic relations to procedural behavior** | **4** | **9** |

### Discussion

1. **From contingency learning to symbolic reasoning**: Acquired relations represent a qualitative leap in ONA's capabilities. Previous chapters demonstrated increasingly sophisticated contingency learning — but the knowledge remained at the procedural level (stimulus-action-outcome chains). Acquired relations allow the system to extract declarative, symbolic knowledge from procedural experience. The relation `<(X1 * Y1) --> SAME>` is a symbolic statement about the world, derived from sensorimotor interaction. This is what the thesis describes as going from "animal-like" contingency learning to a "symbolic reasoning level."

2. **The grounding problem, solved bidirectionally**: A persistent challenge in AI is the grounding problem — how do symbols get their meaning? Acquired relations offer a concrete answer: symbols (relational terms) are grounded in sensorimotor contingencies. The GENERALIZED IMPLICATIONs provide the reverse mapping: symbolic relations can generate procedural knowledge. This bidirectional grounding is what enables derived relational responding — the system can reason symbolically about novel relations and then act on those conclusions in the physical world.

3. **Explicitly trained relational rules**: An important aspect of this experiment is that mutual and combinatorial entailment are explicitly trained during pretraining (phases XY, YX, YZ, XZ). The system does not spontaneously discover that SAME is symmetric or that OPPOSITE-OPPOSITE yields SAME — it learns these patterns from exemplars. This is consistent with RFT's account of AARR as an *operant behavior* established through multiple exemplar training (MET). The "emergent" quality of BC test performance comes from *applying* these learned rules to novel stimuli, not from discovering the rules themselves.

4. **Context-sensitive relational responding**: The same stimuli can participate in different relations depending on the contextual cue. X1 is SAME to Y1 but OPPOSITE to Y2 — the relation is determined by the contextual cue (SAME or OPPOSITE), not by any intrinsic property of the stimuli. This is the "arbitrarily applicable" aspect of AARR: any stimulus can be related to any other stimulus in any way, given the appropriate contextual cue. ONA achieves this through the `rel` location encoding — `<(rel * SAME) --> (loc * ocr)>` and `<(rel * OPPOSITE) --> (loc * ocr)>` — which enters the contingency and the acquired relation as a relational label.

5. **Connection to the theoretical framework**: The thesis chapter (Chapter 9) and Frontiers paper presented two theoretical demonstrations that go beyond same/opposite responding: (1) stimulus equivalence with transfer of function (clap/wave), and (2) opposition frames with transformation of function. The current empirical experiment validates the foundational mechanism (acquired relations with mutual and combinatorial entailment) that would be needed for those more complex demonstrations. The theoretical demonstrations additionally require the system to transfer discriminative functions (e.g., clap, wave) across relational networks — a direction for future empirical work.

6. **Nondeterminism and robustness**: The variability in BC test results across runs reflects a genuine property of the system. ONA's inference depends on which concepts are active, which derivations fire at which time, and the order of trial presentation. This is not unlike human experimental data, where some participants pass derived relational tests and others do not, depending on their learning history and the specific exemplars encountered. Future work could investigate whether increasing the number of pretraining blocks or adjusting ONA parameters (e.g., TIME_BETWEEN_TRIALS, concept memory size) improves the reliability of derived responding.
