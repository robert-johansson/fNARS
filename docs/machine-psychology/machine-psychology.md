# Machine Psychology

Machine Psychology is an interdisciplinary framework that integrates principles from learning psychology with the Non-Axiomatic Reasoning System (NARS) to advance Artificial General Intelligence (AGI) research. It was developed by Robert Johansson and presented as a PhD thesis at Linköping University in 2024, titled *Empirical Studies in Machine Psychology*.

## Subject Matter

The subject matter of Machine Psychology is **adaptation with insufficient knowledge and resources**. This definition synergistically combines the subject matters of the two disciplines it integrates:

- **Learning psychology**: studies *ontogenetic adaptation* — the ever-evolving interaction between organism and environment
- **NARS**: concerns *information-processing systems that operate with insufficient knowledge and resources*

This combined subject matter also aligns with Pei Wang's working definition of intelligence that has guided the development of NARS.

An analogy: Machine Psychology is to computer science (NARS) what **Psychobiology** is to biology. Psychobiology integrates psychological and biological perspectives; Machine Psychology integrates psychological and computational perspectives.

## Core Principles

- **Functional approach**: Machine Psychology studies functional relations between (a) changes in the environment and changes in behavior, and (b) mechanisms and changes in behavior. Both the system's experience and its mechanisms can be manipulated as independent variables.
- **Natural intelligence**: At the functional level of analysis, the behavior that occurs with NARS is argued to be neither "artificial" nor "simulated" — the learning processes (e.g., operant conditioning) are the same as those observed in biological organisms when studied functionally.
- **Theory-driven research**: The framework uses Relational Frame Theory (RFT) as a roadmap for increasingly complex cognitive tasks, addressing the AGI field's repeated calls for theory-driven research.

## The Non-Axiomatic Reasoning System (NARS)

NARS is an AI reasoning architecture designed to operate under the real-world constraints of insufficient knowledge and resources (AIKR). Key properties:

- Uses **Non-Axiomatic Logic (NAL)** — an adaptive logic that learns from experience and revises beliefs
- Knowledge represented in **Narsese**, an internal language where statements carry truth values with **frequency** (how often a relation holds) and **confidence** (amount of evidence)
- Does not assume a predefined, complete set of axioms — all knowledge is learned from experience
- Uses a priority mechanism for attention and memory under resource constraints

The implementation used throughout the research is **OpenNARS for Applications (ONA)**, a sensorimotor variant tailored for practical applications. In all experiments, ONA was compiled with `SEMANTIC_INFERENCE_NAL_LEVEL=0`, meaning only sensorimotor reasoning was used — no declarative inference rules.

## Sensory Encoding

Interactions with the environment are represented as temporal Narsese statements using location and property sensors. For example, perceiving a green object to the left and a blue object to the right:

```
<(left * green) --> (loc * color)>. :|:
<(right * blue) --> (loc * color)>. :|:
```

The `:|:` marker indicates temporal statements. The encoding `<(left * green) --> (loc * color)>` is a composition of `<left --> loc>` and `<green --> color>`. This scheme generalizes to any sensor modality — color, shape, OCR text, object classifiers, brightness, etc.

The **Matching-to-Sample (MTS)** procedure is the primary experimental paradigm used throughout the research. A sample stimulus is presented, along with comparison stimuli at different locations, and the system must learn to select the correct comparison.

## Measuring Behavior

Two methods are used to measure operant behavior in NARS:

1. **State transitions**: Observing transitions from motor babbling to goal-directed responding
2. **Truth value transitions**: Tracking the frequency and confidence of specific hypotheses over time, providing quantitative measures of learning

## Layered Mechanism Progression

Each empirical study builds upon the previous one by adding new NARS mechanisms and demonstrating new psychological capabilities. The layers correspond to NAL levels from Wang (2013):

| Study | Psychological Process | NARS Mechanism Added | NAL Layers |
|-------|----------------------|---------------------|------------|
| Chapter 6 | Operant Conditioning | Temporal + Procedural Reasoning | 7–8 |
| Chapter 7 | Generalized Identity Matching | + Abstraction (variable introduction) | + 6 |
| Chapter 8 | Functional Equivalence | + Implications (reasoning on statements as terms) | + 5 |
| Chapter 9 / Frontiers paper | Arbitrarily Applicable Relational Responding | + Acquired Relations | + 4 |

Each row adds a new mechanism to those already available from previous rows. The distinction between mechanism (NARS capability) and psychological process (behavioral capability it enables) is important — the mechanism is the independent variable, the psychological process is the dependent variable.

## Empirical Studies

### Chapter 6: Operant Conditioning

**Mechanisms**: Temporal induction, goal deduction, motor babbling, anticipation, revision

**Setup**: ONA with `ALLOW_VAR_INTRO=false`. Only sensorimotor temporal and procedural inference available.

Three tasks of increasing difficulty:

1. **Simple discrimination**: Learn to select green over blue based on feedback. Three phases: baseline (no feedback), training (with feedback), testing (no feedback). Result: 100% accuracy in testing after rapid learning during training.

2. **Changing contingencies**: Same as above but contingencies reverse midway. Five phases: baseline, training 1, testing 1, training 2 (reversed), testing 2. Result: System adapted to reversed contingencies, achieving 83%+ accuracy in final testing. The NARS revision mechanism enabled combining positive and negative evidence.

3. **Conditional discriminations**: Match sample color to correct comparison (e.g., when red is the sample, select yellow). Three phases: baseline, training (6 blocks of 12 trials), testing. Result: 100% accuracy in testing. NARS learned complex hypotheses involving multiple preconditions.

**Key NARS mechanisms demonstrated**:
- **Temporal induction**: Deriving contingencies from observed event sequences
- **Goal deduction**: Using learned contingencies to make decisions
- **Motor babbling**: Random exploration enabling discovery of correct responses
- **Anticipation**: Deriving negative evidence when expected consequences don't occur
- **Revision**: Combining positive and negative evidence for a statement

### Chapter 7: Generalized Identity Matching

**Mechanisms**: All from Chapter 6 + abstraction via variable introduction (`ALLOW_VAR_INTRO=true`)

**Task**: Identity matching-to-sample. Train the system to match colors (green-to-green, blue-to-blue), then test with novel colors (red, yellow) never seen during training.

Four phases: baseline, training (green/blue), testing identity (green/blue, no feedback), testing generalized identity (red/yellow, no feedback).

**Key result**: 100% accuracy on novel colors. NARS derived a **general hypothesis** with variable terms:

```
<((<(#1 * #2) --> (loc * color)> &/
  <(#3 * #2) --> (loc * color)>) &/
  <({SELF} * (#1 * #3)) --> ^match>) =/> G>
```

This general hypothesis captures the identity concept abstractly — match any stimulus to the comparison sharing the same property — and transfers to novel stimuli automatically.

The chapter also explored **comparative reasoning** (matching by brightness equality), demonstrating an alternative path to generalized identity matching through relational comparison mechanisms.

### Chapter 8: Functional Equivalence

**Mechanisms**: All from Chapter 7 + implications (functional equivalence mechanism; reasoning on statements as terms)

Two tasks:

1. **Functional equivalence task** (inspired by Smeets et al., 1997): Train A1→R1, B1→R1, A2→R2, B2→R2. Retrain A1→R3, A2→R4. Test if B1→R3 and B2→R4 emerge without training. Result: 100% accuracy — NARS derived that A1≡B1 and A2≡B2 because they shared functional roles, then transferred retrained functions through the equivalence.

2. **Symmetry task**: Train XY relations, then YX relations (establishing symmetry pattern). Train AB relations. Test if BA relations emerge. Result: 100% accuracy on derived BA relations. General symmetry hypotheses reached confidence of 0.87:

```
<(<(sample * $1) --> (loc * ocr)> &/
  <(left * $2) --> (loc * ocr)>) ==>
  (<(sample * $2) --> (loc * ocr)> &/
   <(left * $1) --> (loc * ocr)>)>
```

### Chapter 9 / Frontiers Paper: Arbitrarily Applicable Relational Responding (AARR)

**Mechanisms**: All from Chapter 8 + acquired relations

**Status**: Theoretical/conceptual work in the thesis (Chapter 9) and the Frontiers paper. The acquired relations mechanism was not yet implemented in ONA at this stage — the work demonstrates the logical sufficiency of NARS inference rules for AARR.

**Acquired relations** — the key theoretical contribution: When NARS learns a sensorimotor contingency and acts on it, it can extract a relational term from that contingency. For example, from a learned matching-to-sample contingency involving red and blue:

```
// Learned contingency
<(sample * red) --> (loc * color)> &/
  <(left * blue) --> (loc * color)> &/
  <({SELF} * (sample * left)) --> ^match> =/> G>.

// Acquired relations
<(red * blue) --> (color * color)> &&
  <(sample * left) --> (loc * loc)>

// Implication (grounding the relation in the contingency)
<($1 * $2) --> (color * color)> &&
  <($3 * $4) --> (loc * loc)> ==>
  <($3 * $1) --> (loc * color)> &/
   <($4 * $2) --> (loc * color)> &/
   <({SELF} * ($3 * $4)) --> ^match> =/> G>.
```

This creates a bidirectional bridge: sensorimotor → symbolic → sensorimotor. Relations derived symbolically can drive behavior in novel contexts.

Two theoretical demonstrations:

1. **Stimulus equivalence and transfer of function** (inspired by Hayes et al., 1987): Pretrain symmetry and transitivity. Train ABC networks. Train discriminative functions (B1→clap, B2→wave). Test derived relations and transfer of functions to C stimuli. Result: NARS logic correctly derives all relations and transfers functions through equivalence networks.

2. **Opposition frames** (inspired by Roche & Barnes, 2000): Pretrain SAME and OPPOSITE mutual/combinatorial entailment. Train SAME/OPPOSITE ABC networks. Train discriminative functions. Test derived relations and transformation of function across oppositional contexts. Result: NARS logic correctly handles OPPOSITE-OPPOSITE→SAME combinatorial entailment and transforms functions accordingly.

These demonstrations show how the three defining properties of AARR emerge in NARS:
- **Mutual entailment**: Bidirectional relations derived from unidirectional training
- **Combinatorial entailment**: Novel relations derived from combinations of trained relations
- **Transformation of stimulus function**: Functions transfer across relational networks, reversing across oppositional frames

### AGI 2025 Paper: Empirical Validation of Acquired Relations

**Status**: Empirical — the acquired relations mechanism was implemented in ONA and demonstrated to work.

This paper closes the gap left by the theoretical Chapter 9 / Frontiers paper by actually implementing and testing acquired relations.

**Task**: Same/opposite relational responding in a contextually controlled MTS procedure.

Three phases:
1. **Pretraining** (Phase 1): Explicitly trained mutual and combinatorial entailment for SAME and OPPOSITE frames using XY, YX, YZ, XZ stimulus sets
2. **Network training** (Phase 2): Trained novel AB and AC relational networks under SAME/OPPOSITE contextual cues
3. **Derived testing** (Phase 3): Tested on never-trained BC stimulus pairs without feedback

**Key results**:
- 100% accuracy across all phases, including the critical derived BC test (chance = 50%)
- Internal confidence for mutual and combinatorial entailment grew steadily during pretraining and remained robust through testing
- The system derived novel relational responses never explicitly trained, purely from acquired relational knowledge
- Derived responding in the BC test inherently combined both mutual and combinatorial entailments (e.g., deriving SAME from multiple trained OPPOSITE relations)

## Publications

1. Johansson, R. (2019). "Arbitrarily applicable relational responding". In: *International Conference on Artificial General Intelligence*. Springer, pp. 101-110.
2. Johansson, R. (2019). "Arbitrarily applicable relational responding in NARS". In: *OpenNARS Workshop at the International Conference on Artificial General Intelligence*.
3. Johansson, R. (2020). "Scientific progress in AGI from the perspective of contemporary behavioral psychology". In: *OpenNARS Workshop at the International Conference on Artificial General Intelligence*.
4. Hammer, P., Isaev, P., Lofthouse, T., and Johansson, R. (2023). "ONA for autonomous ROS-based robots". In: *International Conference on Artificial General Intelligence*. Springer, pp. 231-242.
5. Johansson, R. (2024). "Machine Psychology: integrating operant conditioning with the non-axiomatic reasoning system for advancing artificial general intelligence research". *Frontiers in Robotics and AI, 11, 1440631*.
6. Johansson, R., Lofthouse, T., and Hammer, P. (2023). "Generalized identity matching in NARS". In: *International Conference on Artificial General Intelligence*. Springer, pp. 243-249.
7. Johansson, R., Hammer, P., and Lofthouse, T. (2024). "Functional equivalence with NARS". In: *NARS Workshop at the International Conference on Artificial General Intelligence*.
8. Johansson, R., and Lofthouse, T. (2023). "Stimulus equivalence in NARS". In: *International Conference on Artificial General Intelligence*. Springer.
9. Johansson, R. (2025). "Modeling Arbitrarily Applicable Relational Responding with the Non-Axiomatic Reasoning System: A Machine Psychology Approach". Submitted to *Frontiers in Robotics and AI*.
10. Johansson, R., Hammer, P., and Lofthouse, T. (2025). "Arbitrarily Applicable Same/Opposite Relational Responding with NARS". In: *International Conference on Artificial General Intelligence*.
