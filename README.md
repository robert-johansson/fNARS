# fNARS

Functional Non-Axiomatic Reasoning System

<img src="assets/logo-small.png" alt="fNARS" width="400">

A pure functional Clojure port of [OpenNARS for Applications](https://github.com/opennars/OpenNARS-for-Applications) (ONA). The entire reasoner state is a single immutable map — no globals, no mutation. Supports sensorimotor reasoning (NAL 6-8) and declarative inference (NAL 1-6).

## Quick start

```bash
# Interactive shell
bb repl

# Run a .nal file
bb nal OpenNARS-for-Applications/examples/nal/asthma2.nal

# Run all tests
bb test:all
```

## Platforms

fNARS runs on six targets from the same `.cljc` source:

| Target | Command | Use case |
|--------|---------|----------|
| nbb (bun) | `bb repl` | Development |
| JVM Clojure | `bb repl:jvm` | Max speed |
| GraalVM native | `./target/fnars` | Fast startup binary |
| Babashka | `bb test:bb` | Scripting |
| shadow-cljs | `bb build:shadow` | Node.js bundle |
| Browser | `bb build:browser` | Web UI |

### Building

```bash
bb build:shadow    # target/fnars.js (Node)
bb build:browser   # public/js/fnars-browser.js
bb build:uber      # target/fnars.jar (uberjar)
bb build:native    # target/fnars (GraalVM native binary)
```

## Shell usage

The interactive shell accepts Narsese statements, cycle counts, and `*commands`:

```
>> <cat --> animal>.
Input: <cat --> animal>. Truth: {1.000000 0.900000}
>> <animal --> living>.
Input: <animal --> living>. Truth: {1.000000 0.900000}
>> *nallevel=6
Semantic inference NAL level set to 6
>> 100
>> <cat --> living>?
Answer: <cat --> living>. Truth: {1.000000 0.810000}
```

Sensorimotor reasoning (NAL 6-8) is always on. For declarative inference (NAL 1-6), set the level with `*nallevel=N`.

You can also pipe input:

```bash
echo '*nallevel=6' | cat - file.nal | ./target/fnars
```

## Testing

```bash
bb test:nbb        # Unit + snapshot tests (nbb)
bb test:jvm        # Unit + snapshot tests (JVM)
bb test:bb         # Unit + snapshot tests (babashka)
bb test:shadow     # Unit + snapshot tests (shadow-cljs)
bb test:browser    # Browser smoke tests (Playwright)
bb test:ch6        # Chapter 6 operant conditioning (4 tests)
bb test:ch7        # Chapter 7 identity matching
bb test:nal        # NAL 1-6 comparison tests (15 tests, ONA-verified)
bb test:all        # All of the above
```

## Architecture

- **Immutable state**: The NAR is a single Clojure map threaded through every function
- **Binary heap terms**: Terms are flat vectors of 64 integer atom IDs (root at index 0, left child at 2i+1, right child at 2i+2)
- **Integer atom registry**: Atoms are integer IDs for fast comparison (`==`), mapped to keywords via a shared registry
- **Temporal mining**: Two-phase cycle — Phase 1 mines `<(precondition &/ ^op) =/> postcondition>` triples, Phase 2 derives declarative implications and sequences
- **Decision making**: Goals trigger operation execution by matching implication preconditions against current beliefs

### Source layout

```
src/fNARS/
  truth.cljc           Truth functions (revision, deduction, induction, ...)
  term.cljc            Binary heap term encoding
  stamp.cljc           Evidential base (zip-merge, overlap detection)
  event.cljc           Belief/goal event structures
  implication.cljc     Implication structures
  nar_config.cljc      Tunable parameters
  atom_registry.cljc   Keyword <-> integer atom ID mapping
  priority_queue.cljc  Bounded sorted priority queue
  table.cljc           Implication tables (sorted by expectation)
  concept.cljc         Concept (belief, goal, spike, tables, usage)
  memory.cljc          Concept store, inverted atom index, time index
  inference.cljc       Temporal/procedural inference rules
  rule_table.cljc      NAL 1-6 declarative rule table
  narsese.cljc         Term construction utilities
  variable.cljc        Unification, substitution, variable introduction
  decision.cljc        Decision making, motor babbling
  cycle.cljc           Main inference cycle
  nar.cljc             Top-level NAR API
  parser.cljc          Narsese parser (instaparse grammar)
  shell.cljc           Interactive shell
  nal_runner.cljc      .nal file runner
  mprf.cljc            Machine Psychology Research Framework

test/fNARS/
  nar_test.cljc        Unit tests
  snapshot_test.cljc   Snapshot tests
  ch6_test.cljc        Operant conditioning tests
  ch7_test.cljc        Identity matching test
  nal_comparison_test.cljc  NAL 1-6 ONA-verified comparison tests
```

## Machine Psychology (MPRF)

Replicates experiments from the [machine psychology framework](https://github.com/opennars/OpenNARS-for-Applications):

```bash
bb mprf op1    # Simple operant conditioning
bb mprf op2    # Discriminative operant conditioning
bb mprf op3    # Transitive inference
```

## Dependencies

- [Babashka](https://github.com/babashka/babashka) — task runner
- [bun](https://bun.sh/) + [nbb](https://github.com/babashka/nbb) — ClojureScript runtime
- [instaparse](https://github.com/robert-johansson/instaparse/tree/bb-nbb-support) — Narsese parser (nbb-compatible fork, included as git submodule)
- [GraalVM](https://www.graalvm.org/) — native binary builds (optional)

## Reference

- Hammer, P. (2022). Reasoning-learning systems based on non-axiomatic reasoning system theory. In *International Workshop on Self-Supervised Learning* (pp. 89-107). PMLR.
- Hammer, P., & Lofthouse, T. (2020). ['OpenNARS for Applications': Architecture and Control.](https://cis.temple.edu/~pwang/Publication/OpenNARS_for_Applications.pdf) *AGI 2020*.
- Wang, P. (2013). [Non-Axiomatic Logic: A Model of Intelligent Reasoning.](https://www.worldscientific.com/worldscibooks/10.1142/8665) World Scientific.

## License

MIT
