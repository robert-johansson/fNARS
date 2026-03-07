# fNARS

Functional ClojureScript port of the sensorimotor subset of OpenNARS for Applications (ONA).

## Quick Start

```bash
# Run tests
bunx --bun nbb -cp src:lib/instaparse/src:test -m fNARS.test-runner

# Run Chapter 6 operant conditioning tests
bunx --bun nbb -cp src:lib/instaparse/src:test test/fNARS/ch6_test.cljs

# Run interactive shell
bunx --bun nbb -cp src:lib/instaparse/src -m fNARS.core
```

## Runtime

nbb on bun. No JVM. All commands use `bunx --bun nbb`.

### Dependencies

- `lib/instaparse/` — [instaparse](https://github.com/robert-johansson/instaparse/tree/bb-nbb-support) (nbb-compatible fork, git submodule)

## Architecture

The entire NAR state is a single immutable Clojure map. No globals, no atoms (except for one `atom` in `suggest-decision` for local accumulation). Every function takes state and returns new state.

### Term Encoding

Terms are flat vectors of length 64 (binary heap encoding). Root at index 0, left child at `2i+1`, right child at `2i+2`. Atoms are keywords.

- Copulas: `:cop-inheritance`, `:cop-sequence`, `:cop-temporal-implication`, etc.
- Operators: `(keyword "^pick")` -- the `:^pick` syntax is **invalid in CLJS** due to `^` being metadata syntax.
- Variables: `:$1`-`:$9` (independent), `:#1`-`:#9` (dependent), `:?1`-`:?9` (query)

### Key Data Flow

1. Input events enter via `nar/nar-add-input` -> added to cycling-belief-events or cycling-goal-events
2. Each cycle: select events -> process beliefs (mine temporal correlations, reinforce links) -> process goals (suggest decisions, execute operations, propagate subgoals) -> forgetting
3. Temporal mining (cycle.cljs) has two phases:
   - **Phase 1**: Find `<(precondition &/ ^operation) =/> postcondition>` triples
   - **Phase 2**: Same-type non-op pairs create `<A =/> B>` implications and `(A &/ B)` sequences
4. Implications are stored on the **postcondition concept**, keyed by operation ID
5. Decision making checks the goal concept's implication tables for registered operations

## C Reference

The `OpenNARS-for-Applications/` submodule contains the C reference implementation. Key source files to compare against:
- `src/Cycle.c` - Main inference cycle (most complex, ~850 lines)
- `src/Decision.c` - Decision making
- `src/Inference.c` - Truth-based inference rules
- `src/Variable.c` - Unification and variable introduction
- `src/Memory.c` - Concept storage and event management
- `src/Config.h` - All tunable parameters

To rebuild ONA after changing Config.h:
```bash
cd OpenNARS-for-Applications && make -j
```

## ONA MCP Server

Configured in `.mcp.json`. Provides 6 tools for interacting with the C ONA binary:
- `mcp__ona__send_narsese` - Send Narsese input
- `mcp__ona__get_concepts` - Dump concept memory (`*concepts`)
- `mcp__ona__get_cycling_events` - Show cycling belief/goal events
- `mcp__ona__reset` - Reset ONA state
- `mcp__ona__run_cycles` - Run N inference cycles
- `mcp__ona__configure` - Set config params (e.g. `*motorbabbling=0.9`)

Use the MCP server to run the same scenario through C ONA and compare stamps, truth values, and decisions against fNARS.

## CLJS/nbb Gotchas

- No `Character/isLetter` or Java interop -- use JS char comparisons
- Don't shadow `count` in `let` bindings (causes "F.call is not a function")
- `:^pick` is invalid CLJS -- use `(keyword "^pick")`
- Copula keywords must be valid CLJS names (`:cop-sequence` not `:&/`)
- nbb `-m` flag requires a `-main` function; use plain file execution for scripts without one

## Testing Workflow

When verifying fNARS behavior against ONA:
1. Run the scenario through ONA MCP to get reference output (stamps, truth values, decisions)
2. Run the same scenario through fNARS shell or programmatically
3. Compare: implication tables, truth confidence, operation execution, source-concept-keys

Key things to check:
- Implications stored on the correct concept under the correct op-id
- Source concept keys match the precondition (not a nested sequence)
- Time index stays clean (no exponential sequence growth)
- Decision expectation exceeds threshold and selects the right operation

## File Structure

```
src/fNARS/
  truth.cljs          # Truth functions (revision, deduction, induction, etc.)
  term.cljs           # Binary heap term vectors
  stamp.cljs          # Evidential base (zip-merge, overlap)
  event.cljs          # Event structure (belief/goal/deleted)
  implication.cljs    # Implication structure
  nar_config.cljs     # All tunable parameters
  priority_queue.cljs # Bounded sorted priority queue
  table.cljs          # Implication tables (sorted by expectation)
  concept.cljs        # Concept (belief, spike, goal, tables, usage)
  memory.cljs         # Concept store, inverted atom index, time index
  inference.cljs      # Temporal/procedural inference rules
  narsese.cljs        # Term construction utilities
  variable.cljs       # Unification, substitution, variable introduction
  decision.cljs       # Decision making, motor babbling
  cycle.cljs          # Main inference cycle (Phase 1 & 2 mining)
  nar.cljs            # Top-level NAR API
  parser.cljs         # Narsese parser (instaparse grammar)
  shell.cljs          # Interactive shell (commands, formatting)
  core.cljs           # REPL entry point

test/fNARS/
  nar_test.cljs       # Unit tests
  snapshot_test.cljs  # Snapshot tests
  ch6_test.cljs       # Chapter 6 operant conditioning tests (4 tests)
  ch7_test.cljs       # Chapter 7 identity matching test

lib/instaparse/       # instaparse git submodule (bb-nbb-support branch)

tools/ona-mcp/
  index.js            # MCP server for C ONA binary
  package.json        # Dependencies (@modelcontextprotocol/sdk)
```
