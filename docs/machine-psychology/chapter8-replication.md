# Chapter 8 Replication: Functional Equivalence with ONA

This document describes the replication of the functional equivalence experiment from Chapter 8 of *Empirical Studies in Machine Psychology* (Johansson, 2024), using the MPRF framework and OpenNARS for Applications (ONA). The chapter investigates how ONA can group dissimilar stimuli into equivalence classes based on their shared functional roles, and then transfer newly learned responses across members of the same class.

---

## What Changed from Chapter 7

Chapter 7 demonstrated generalized identity matching through variable introduction (Experiment 1) and comparative reasoning (Experiment 2). Chapter 8 adds a new mechanism:

```c
#define FUNCTIONAL_EQUIVALENCE true           // was false in Chapters 6-7
#define FUNCTIONAL_EQUIVALENCE_SPECIFIC true   // derive specific (non-variable) equivalences too
```

Variable introduction also remains enabled (`ALLOW_VAR_INTRO=true`), as it was in Chapter 7 Experiment 1. However, it is important to note that variable introduction is **not required for the implications mechanism itself**. The implications mechanism can derive specific two-way implications (e.g., `<(s1 * A1) ==> (s1 * B1)>`) without any variables. Variable introduction is needed only for **generalization** — abstracting the derived implications so they apply at new locations (e.g., `<<($1 * A1) ==> ($1 * B1)>>`). In this experiment, generalization across locations is what enables transfer during testing, so both mechanisms are needed.

| Parameter | Chapters 6 | Chapter 7 (Exp. 1) | Chapter 8 |
|-----------|-----------|-----------|-----------|
| `SEMANTIC_INFERENCE_NAL_LEVEL` | 0 | 0 | 0 |
| `ALLOW_VAR_INTRO` | false | true | true |
| `FUNCTIONAL_EQUIVALENCE` | false | false | **true** |
| Available mechanisms | 5 | 6 | **7** |

## The New Mechanism: Implications (Reasoning on Statements as Terms)

It is important to distinguish between the **mechanism** and the **demonstrated effect**. The mechanism added in Chapter 8 is **implications** — the ability to reason on statements as terms, corresponding to NAL level 5 (Wang, 2013). The demonstrated psychological effect is **functional equivalence** — the grouping of dissimilar stimuli into equivalence classes based on shared functional roles.

The ONA config parameter is named `FUNCTIONAL_EQUIVALENCE`, which can cause confusion. The parameter enables the *implications mechanism*: when two learned contingencies share the same operation and consequence, ONA treats those contingencies as terms and derives two-way implications (`==>`) between their preconditions. Two-way implications between stimuli *is* functional equivalence at the behavioral level — but the underlying mechanism is simply implication derivation through higher-order reasoning.

### How It Works: A Simple Example

Suppose ONA has learned two three-term contingencies where different stimuli (A and B) both lead to the same operation (`^op1`) achieving the same goal (G):

```
// Contingency 1: A leads to ^op1 for G
A. :|:
^op1. :|:
G. :|:
// Derived: <(A &/ ^op1) =/> G>.

100

// Contingency 2: B leads to ^op1 for G
B. :|:
^op1. :|:
G. :|:
// Derived: <(B &/ ^op1) =/> G>.
```

Both contingencies are acted on — when A is present and G is desired, ONA executes `^op1`; when B is present and G is desired, ONA also executes `^op1`. At the moment of execution, ONA's implications mechanism compares the contingency being used with other contingencies sharing the same operation and consequence. Since both `<(A &/ ^op1) =/> G>` and `<(B &/ ^op1) =/> G>` have the same operation (`^op1`) and consequence (G), ONA reasons on these contingencies as terms and derives two-way implications:

```
// Derived: <A ==> B>.
// Derived: <B ==> A>.
// Together these form the equivalence A <=> B
// (ONA represents equivalences as two implications)
```

This is the key insight: **A and B have never been directly associated with each other**. They are linked only through their common functional role — both are conditions for the same action achieving the same goal. The equivalence is *derived*, not trained.

### How It Works: The Full Experimental Encoding

In the functional equivalence experiment, the encoding is more complex. Stimuli are presented as `ocr` events at specific locations, and the operation `^press` takes one of four arguments (R1, R2, R3, R4). Consider the training phase where A1 and B1 both lead to pressing R1:

```
// Trial with A1 at location s1:
<(s1 * A1) --> (loc * ocr)>. :|:
G! :|:
// Executed: <({SELF} * R1) --> ^press>. :|:
G. :|:

// Derived contingency:
<(<(s1 * A1) --> (loc * ocr)> &/ <({SELF} * R1) --> ^press>) =/> G>.

100

// Trial with B1 at location s1:
<(s1 * B1) --> (loc * ocr)>. :|:
G! :|:
// Executed: <({SELF} * R1) --> ^press>. :|:
G. :|:

// Derived contingency:
<(<(s1 * B1) --> (loc * ocr)> &/ <({SELF} * R1) --> ^press>) =/> G>.
```

Both contingencies share the same operation (`^press` with argument R1) and consequence (G). When they are both acted upon, ONA's implications mechanism fires — it reasons on the two contingencies as terms and derives implications between their differing preconditions:

```
// Specific two-way implications (at location s1):
<(s1 * A1) --> (loc * ocr)> ==> <(s1 * B1) --> (loc * ocr)>.
<(s1 * B1) --> (loc * ocr)> ==> <(s1 * A1) --> (loc * ocr)>.
```

These specific implications are functional equivalence: A1 and B1 are interchangeable at location s1. **No variable introduction is needed for this step** — the implications mechanism alone produces them.

However, these specific implications are bound to `s1`. If B1 later appears at a different location `s2`, the specific implication `<(s1 * A1) ==> (s1 * B1)>` does not match — `s1 ≠ s2`. For the equivalence to *generalize* across locations, variable introduction is needed. With `ALLOW_VAR_INTRO=true`, ONA additionally derives:

```
// Generalized implications (for any location $1):
<<($1 * A1) --> (loc * ocr)> ==> <($1 * B1) --> (loc * ocr)>>.
<<($1 * B1) --> (loc * ocr)> ==> <($1 * A1) --> (loc * ocr)>>.
```

The variable `$1` replaces the concrete location `s1`. Now the equivalence applies at *any* location, not just where the original training occurred. This generalization is what enables transfer in the experiment, where retraining and testing happen at a new location `s2`.

### Why Two-Way Implications Enable Transfer

The power of derived implications becomes apparent when the experimental setting changes. The two-way implications `<A1 ==> B1>` and `<B1 ==> A1>` together constitute functional equivalence: A1 and B1 are interchangeable. Suppose ONA learns this equivalence during training at location s1. Then during retraining at a *new* location s2, only A1 is retrained with a new response:

```
// Retraining: A1 at location s2 now leads to R3
<(s2 * A1) --> (loc * ocr)>. :|:
G! :|:
// Executed: <({SELF} * R3) --> ^press>. :|:
G. :|:

// Derived contingency:
<(<(s2 * A1) --> (loc * ocr)> &/ <({SELF} * R3) --> ^press>) =/> G>.
```

Now when B1 is presented at location s2 — a combination never seen before — ONA can use the general implication to transfer knowledge:

```
// B1 presented at s2:
<(s2 * B1) --> (loc * ocr)>. :|:
G! :|:

// ONA has the general equivalence:
<<($1 * B1) --> (loc * ocr)> ==> <($1 * A1) --> (loc * ocr)>>.

// Substituting $1 = s2:
// B1 at s2 implies A1 at s2

// ONA has the retrained contingency:
<(<(s2 * A1) --> (loc * ocr)> &/ <({SELF} * R3) --> ^press>) =/> G>.

// Therefore: press R3 when B1 is at s2
// This response was NEVER directly trained for B1!
```

The system derives `B1 → R3` without ever having been reinforced for pressing R3 when B1 is present. The transfer happens through the chain: B1 ≡ A1 (from training) + A1 → R3 (from retraining) = B1 → R3 (derived).

## Experiment: Functional Equivalence Task

The experiment is inspired by Smeets et al. (1997), who investigated functional equivalence with preschool children. Children were trained on specific stimulus-response relations, then some stimuli were reassigned new responses. The researchers tested whether the children would apply the new responses to other members of the same equivalence class.

### Initialization

```
*babblingops=1
*motorbabbling=0.9
*setopname 1 ^press
*setoparg 1 1 R1
*setoparg 1 2 R2
*setoparg 1 3 R3
*setoparg 1 4 R4
*volume=0
```

One operation `^press` with four possible arguments: R1, R2, R3, and R4. These represent four response buttons. Motor babbling starts at 90%.

### Task

The task examines whether stimulus-response relations can transfer between members of an equivalence class. Four arbitrary stimuli (A1, B1, A2, B2) are used. The system is trained that A1 and B1 both require pressing R1, and A2 and B2 both require pressing R2 — making A1 functionally equivalent to B1, and A2 functionally equivalent to B2. Then A1 and A2 are retrained with new responses (R3 and R4). The test is whether B1 and B2 spontaneously adopt the new responses.

### Stimulus Encoding

Each trial presents a single stimulus at a location. During training, stimuli appear at location `s1`; during retraining and testing, stimuli appear at location `s2`. The change of location prevents direct interference between old and new contingencies.

```
// Training trial: A1 at location s1
<(s1 * A1) --> (loc * ocr)>. :|:
G! :|:

// Retraining trial: A1 at location s2
<(s2 * A1) --> (loc * ocr)>. :|:
G! :|:
```

### Phases

1. **Training** (6 blocks of 12 trials, with feedback): Train four stimulus-response relations at location s1:

   | Stimulus | Correct Response | Equivalence Class |
   |----------|-----------------|-------------------|
   | A1 | R1 | Class 1 |
   | B1 | R1 | Class 1 |
   | A2 | R2 | Class 2 |
   | B2 | R2 | Class 2 |

2. **Retraining** (6 blocks of 12 trials, with feedback): Retrain only the A stimuli with new responses at location s2:

   | Stimulus | Correct Response |
   |----------|-----------------|
   | A1 | R3 |
   | A2 | R4 |

3. **Testing** (6 blocks of 12 trials, no feedback): Test whether B stimuli adopt the new responses at location s2:

   | Stimulus | Expected Response | Derivation |
   |----------|------------------|------------|
   | B1 | R3 | B1 ≡ A1 (from training) + A1 → R3 (from retraining) |
   | B2 | R4 | B2 ≡ A2 (from training) + A2 → R4 (from retraining) |

### Hypotheses Tracked

Three sets of hypotheses are tracked throughout the experiment:

**Specific hypotheses — training contingencies** (4 total, at location s1):
```
<(<(s1 * A1) --> (loc * ocr)> &/ <({SELF} * R1) --> ^press>) =/> G>
<(<(s1 * B1) --> (loc * ocr)> &/ <({SELF} * R1) --> ^press>) =/> G>
<(<(s1 * A2) --> (loc * ocr)> &/ <({SELF} * R2) --> ^press>) =/> G>
<(<(s1 * B2) --> (loc * ocr)> &/ <({SELF} * R2) --> ^press>) =/> G>
```

These express the learned relations from the training phase: "if stimulus X is at location s1 and I press R1/R2, then G follows."

**Specific hypotheses — retraining contingencies** (2 total, at location s2):
```
<(<(s2 * A1) --> (loc * ocr)> &/ <({SELF} * R3) --> ^press>) =/> G>
<(<(s2 * A2) --> (loc * ocr)> &/ <({SELF} * R4) --> ^press>) =/> G>
```

These express the retrained relations: "if A1 is at s2 and I press R3, then G follows."

**General hypotheses — derived implications** (4 total):
```
<<($1 * A1) --> (loc * ocr)> ==> <($1 * B1) --> (loc * ocr)>>
<<($1 * B1) --> (loc * ocr)> ==> <($1 * A1) --> (loc * ocr)>>
<<($1 * A2) --> (loc * ocr)> ==> <($1 * B2) --> (loc * ocr)>>
<<($1 * B2) --> (loc * ocr)> ==> <($1 * A2) --> (loc * ocr)>>
```

These are the two-way implications derived through the implications mechanism. They express: "for any location $1, if A1 is at $1, then B1 is at $1 (and vice versa)." These are *not* trained directly — they emerge from ONA reasoning on pairs of contingencies that share the same operation and consequence. Together, the two implications `<A1 ==> B1>` and `<B1 ==> A1>` constitute the functional equivalence A1 ≡ B1.

### What Happens During Training

**Early training** — motor babbling drives exploration across the four possible responses (R1-R4). With a 25% chance of randomly pressing the correct button, learning is slower than in two-choice tasks. When a correct press occurs by chance and is reinforced, temporal induction derives the corresponding contingency:

```
// Trial: A1 at s1, motor babbling selects R1 (correct)
<(s1 * A1) --> (loc * ocr)>. :|:
G! :|:
// Executed: <({SELF} * R1) --> ^press>. :|:
G. :|:                                        // reinforcement

// Derived via temporal induction:
<(<(s1 * A1) --> (loc * ocr)> &/ <({SELF} * R1) --> ^press>) =/> G>. {1.00, 0.31}
```

**Mid training** — as contingencies accumulate evidence, goal deduction begins driving behavior. When ONA executes ^press based on a learned contingency and another contingency with the same operation and goal exists, the implications mechanism fires — reasoning on the two contingencies as terms:

```
// ONA executes ^press R1 for A1 based on contingency 1:
<(<(s1 * A1) --> (loc * ocr)> &/ <({SELF} * R1) --> ^press>) =/> G>.

// ONA also has learned contingency 2:
<(<(s1 * B1) --> (loc * ocr)> &/ <({SELF} * R1) --> ^press>) =/> G>.

// Same operation (^press R1), same consequence (G)
// → reason on these contingencies as terms
// → derive two-way implications between their preconditions:
<(s1 * A1) --> (loc * ocr)> ==> <(s1 * B1) --> (loc * ocr)>.  // specific
<<($1 * A1) --> (loc * ocr)> ==> <($1 * B1) --> (loc * ocr)>>. // general (via var intro)
```

**Late training** — all four training contingencies are established and equivalence classes are formed. A1 ≡ B1 and A2 ≡ B2, with generalized equivalences that apply at any location.

### What Happens During Retraining

When the setting changes to s2 and only A1 and A2 are retrained:

```
// A1 at s2, correct response is now R3
<(s2 * A1) --> (loc * ocr)>. :|:
G! :|:
```

At first, ONA does not know the correct response for A1 at s2 — the training contingencies were at s1, and there are no s2 contingencies yet. Motor babbling explores until R3 is discovered:

```
// Motor babbling: ^press R3 (correct)
G. :|:                                        // reinforcement

// New contingency derived:
<(<(s2 * A1) --> (loc * ocr)> &/ <({SELF} * R3) --> ^press>) =/> G>. {1.00, 0.31}
```

Retraining is relatively fast because there are only two stimulus-response relations to learn (A1→R3, A2→R4), and the system quickly builds confidence.

### What Happens During Testing

The critical test: B1 is presented at s2, a combination never directly reinforced. ONA has never received feedback for pressing any button when B1 is at s2.

```
// B1 at location s2 — never seen before:
<(s2 * B1) --> (loc * ocr)>. :|:
G! :|:
```

ONA has the general implication derived during training:
```
<<($1 * B1) --> (loc * ocr)> ==> <($1 * A1) --> (loc * ocr)>>.
```

Substituting `$1 = s2`, this means B1 at s2 implies A1 at s2. ONA also has the retrained contingency:
```
<(<(s2 * A1) --> (loc * ocr)> &/ <({SELF} * R3) --> ^press>) =/> G>.
```

Through this chain of reasoning — B1 at s2 → A1 at s2 (via equivalence) → press R3 (via retrained contingency) → G — ONA correctly executes `^press R3`. The response transfers without any direct training.

### Results

| Phase | Block | Accuracy | Training Conf. | Retraining Conf. | Equivalence Conf. |
|-------|-------|----------|---------------|-----------------|-------------------|
| Training | 1 | 50% | 0.23 | 0.00 | 0.00 |
| Training | 2 | 50% | 0.31 | 0.00 | 0.00 |
| Training | 3 | 100% | 0.58 | 0.00 | 0.17 |
| Training | 4 | 100% | 0.70 | 0.00 | 0.24 |
| Training | 5 | 100% | 0.76 | 0.00 | 0.26 |
| Training | 6 | 100% | 0.80 | 0.00 | 0.28 |
| Retraining | 1 | 50% | 0.80 | 0.31 | 0.28 |
| Retraining | 2 | 100% | 0.80 | 0.70 | 0.28 |
| Retraining | 3 | 100% | 0.80 | 0.80 | 0.28 |
| Retraining | 4 | 100% | 0.80 | 0.85 | 0.28 |
| Retraining | 5 | 100% | 0.80 | 0.88 | 0.28 |
| Retraining | 6 | 100% | 0.80 | 0.90 | 0.28 |
| Testing | 1 | **100%** | 0.80 | 0.90 | 0.28 |
| Testing | 2 | **100%** | 0.80 | 0.90 | 0.28 |
| Testing | 3 | **100%** | 0.80 | 0.90 | 0.28 |
| Testing | 4 | **100%** | 0.80 | 0.90 | 0.28 |
| Testing | 5 | **100%** | 0.80 | 0.90 | 0.28 |
| Testing | 6 | **100%** | 0.80 | 0.90 | 0.28 |

The results confirm:

1. **Training** shows learning from 50% (chance with 4 buttons) to 100% accuracy by block 3. The derived implications (equivalence hypotheses) begin forming once multiple contingencies with the same operation have been established and acted upon (confidence reaches 0.28 by block 6).

2. **Retraining** shows rapid adaptation to the new responses at location s2. Accuracy drops to 50% in the first block (motor babbling at the new location) but recovers to 100% by block 2. The retraining contingency confidence reaches 0.90 by the end of the phase.

3. **Testing** demonstrates functional equivalence: **100% accuracy on all 6 blocks** for responses that were never directly trained. B1 correctly produces R3, and B2 correctly produces R4, derived entirely from the two-way implications between A1 ≡ B1 and A2 ≡ B2.

The training contingency confidence (0.80) remains stable during retraining and testing — these hypotheses are at location s1 and are not affected by events at s2. The implication confidence (0.28) also stabilizes after training, consistent with the implications being derived during the training phase and not receiving further evidence during retraining or testing.

## All Eight Mechanisms

Chapter 8 uses all six mechanisms from Chapter 7 plus one new one:

| # | Mechanism | Role | NAL Layer | Chapter |
|---|-----------|------|-----------|---------|
| 1 | Temporal induction | Derive stimulus-action-outcome hypotheses from observed sequences | 7 | 6+ |
| 2 | Goal deduction | Use learned hypotheses to drive goal-directed behavior | 8 | 6+ |
| 3 | Motor babbling | Random exploration to discover correct responses | 8 | 6+ |
| 4 | Anticipation | Derive negative evidence when expected consequences don't occur | 7 | 6+ |
| 5 | Revision | Combine positive and negative evidence for a statement | 7 | 6+ |
| 6 | Variable introduction | Abstract over concrete terms by replacing repeated atoms with variables | 6 | 7+ |
| 7 | Comparative reasoning | Derive equality/inequality relations from numeric attributes | Sensorimotor | 7 (Exp. 2) |
| 8 | **Implications** | **Derive two-way implications between preconditions of contingencies that share the same operation and consequence (reasoning on statements as terms)** | **5** | **8** |

The implications mechanism is conceptually different from the other mechanisms. While temporal induction, anticipation, and revision operate on individual contingencies, the implications mechanism operates on *pairs of contingencies* — it is a form of higher-order reasoning where ONA treats its own learned contingencies as terms and derives new knowledge from their structural similarities. When two contingencies share the same operation and consequence but differ in their preconditions, two-way implications are derived between those preconditions. At the behavioral level, two-way implications produce functional equivalence: the precondition stimuli become interchangeable.

### Discussion

1. **Mechanism vs. effect**: The mechanism (implications / reasoning on statements as terms) should be distinguished from the demonstrated psychological effect (functional equivalence). The mechanism derives `<A ==> B>` and `<B ==> A>` — two implications. The behavioral consequence of these two-way implications is that A and B become functionally interchangeable. This distinction matters because the same implications mechanism can produce other effects beyond functional equivalence (e.g., symmetry, as demonstrated in the original thesis).

2. **Emergent categorization**: The equivalence classes (A1 ≡ B1, A2 ≡ B2) are not explicitly taught. They emerge from the structure of the reinforcement contingencies. This mirrors how organisms form categories based on common functional roles rather than perceptual similarity — A1 and B1 may look completely different, but they are treated as equivalent because they function identically.

3. **Transfer of learning**: The derived implications enable knowledge transfer that would be impossible with stimulus-specific learning alone. Retraining only A1 and A2 was sufficient to change the behavior for all four stimuli — a form of efficiency that scales with equivalence class size. If the class contained 10 members, retraining just one would transfer to all others.

4. **Implications vs. variable introduction**: The two mechanisms play distinct roles. The implications mechanism derives the equivalence (A1 ≡ B1); variable introduction generalizes it across locations. The implications mechanism does not require variable introduction — it can derive specific two-way implications like `<(s1 * A1) ==> (s1 * B1)>` on its own. If the experiment had used the same location throughout (training and retraining both at s1), variable introduction would not be needed. The experiment uses different locations (s1 for training, s2 for retraining/testing) precisely to test generalization, which is where variable introduction becomes necessary — lifting the specific implication to `<<($1 * A1) ==> ($1 * B1)>>` so it can unify with the new location `s2`.
