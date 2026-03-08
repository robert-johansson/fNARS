# NAL Compatibility Plan

## Goal

Increase fNARS compatibility with ONA for `.nal` example files in
`OpenNARS-for-Applications/examples/nal/`, excluding files that depend on
`*setvalue` or `*space` for now.

This plan focuses on:

- shell-command compatibility
- `.nal` replay compatibility
- parser compatibility
- behavior compatibility for the example families that remain in scope

This plan does not yet focus on:

- `*setvalue`
- `*space`
- English example files

## Current Baseline

Known examples that already work through `bb nal`:

- `plandeep.nal`
- `pickbrighter.nal`

Known examples that fail today for reasons already identified:

- `value.nal`
- `vector.nal`

These are excluded for now because they rely on `*space`.

Known false lead:

- `door.nal` fails in fNARS, but ONA also returns `None`, so it is not a
  useful compatibility target.

## Progress Checkpoint (2026-03-08)

Completed:

- Workstream 1: compatibility harness (`bb nal:compat`)
- Workstream 3 (partial): `*concurrent` and `*setopstdin` support in shell path
- Workstream 2: shared line execution path (`shell/execute-line`) now used by
  `.nal` replay runner

Next:

- Workstream 4: align `*setopname` operator-slot semantics with ONA

Update:

- Added ONA-like `*setopname` constraints/behavior in shell path:
  - only allowed right after init/reset
  - slot bounds checking by `operations-max`
  - duplicate target op clears that slot and following slots
- Expanded `.nal` expectation handling in runner:
  - `//--expected: no execution`
  - `Answer: ... :|: occurrenceTime=... Truth: ...`
  - `<term>. Truth: ...` lines without `Answer:`

## Strategy

Do not start by rewriting the inference engine.

First make fNARS replay `.nal` files the same way ONA's shell does. After that,
close the remaining gaps file family by file family with targeted tests.

## Workstreams

### 1. Build a Compatibility Harness

Create a repeatable corpus runner over
`OpenNARS-for-Applications/examples/nal/`.

Tasks:

- enumerate `.nal` files
- exclude files containing `*setvalue` or `*space`
- run each file through ONA
- run each file through fNARS
- record pass/fail by expected answers and expected executions
- produce a stable summary report

Deliverable:

- a script or task in-repo that generates a compatibility report

Definition of done:

- one command produces a list of in-scope example files and their current status

### 2. Unify Shell and `.nal` Replay Semantics

Right now `nal_runner.cljc` partially reimplements shell behavior.

That should be replaced with one shared line executor used by:

- `shell.cljc`
- `nal_runner.cljc`

Tasks:

- extract a shared `execute-line` path
- route `.nal` replay through the same command handling as the interactive shell
- remove duplicated command logic where practical

Target files:

- `src/fNARS/shell.cljc`
- `src/fNARS/nal_runner.cljc`

Definition of done:

- a command accepted in the interactive shell behaves the same when replayed from
  a `.nal` file

### 3. Add Missing Shell Command Parity

Implement the high-value ONA shell commands that appear in the in-scope corpus.

Priority order:

1. `*concurrent`
2. `*setopstdin`
3. any remaining in-scope commands discovered by the harness

Concrete notes:

- In ONA, `*concurrent` effectively does `currentTime -= 1`.
- `*setopstdin` can initially be a compatibility no-op if no fNARS path uses it.

Target files:

- `src/fNARS/shell.cljc`
- `src/fNARS/nal_runner.cljc` if any compatibility glue remains there

Definition of done:

- all in-scope `.nal` commands seen in the corpus are either implemented or
  intentionally supported as no-op compatibility stubs

### 4. Match ONA Operator Slot Semantics

`*setopname` behavior must match ONA more closely.

Current risk:

- fNARS shell and `.nal` runner do not fully mirror ONA's indexed operator-slot
  behavior
- sparse indices and overwrites may behave differently

Tasks:

- make operator registration slot-based
- make overwriting semantics match ONA
- ensure `.nal` replay and interactive shell share the same operator-slot logic

Target files:

- `src/fNARS/shell.cljc`
- `src/fNARS/nar.cljc`
- possibly a small helper module if slot logic should be centralized

Definition of done:

- examples using `*setopname` and sparse operator IDs behave the same in fNARS
  and ONA

### 5. Expand `.nal` Expectation Support

The runner should understand the expectation styles used in ONA example files.

Minimum support to add:

- `//--expected: no execution`
- optional occurrence-time checking for answers
- more faithful execution matching for `executed with args`

Tasks:

- extend expectation parser
- extend execution checker
- extend answer checker for temporal answers when required

Target file:

- `src/fNARS/nal_runner.cljc`

Definition of done:

- the runner can correctly score the expectation forms used by the in-scope
  corpus

### 6. Stop Collapsing `&|` Into Normal Conjunction

The parser currently maps parallel conjunction `&|` to ordinary conjunction.

That is likely acceptable for some files but will blur ONA behavior in:

- `avoid2.nal`
- other concurrent-perception examples

Tasks:

- add an explicit internal representation for parallel conjunction
- parse `&|` distinctly
- audit downstream code that assumes conjunction and parallel conjunction are the
  same

Target files:

- `src/fNARS/parser.cljc`
- `src/fNARS/term.cljc`
- any inference code that should treat `&|` differently

Definition of done:

- files using `&|` can be run without semantic collapse to ordinary conjunction

### 7. Fix Behavior by Example Family

After shell and parser parity, attack the remaining behavior gaps by example
family.

Recommended order:

1. `learnwords.nal`
2. `avoid2.nal` and `avoid3.nal`
3. `map.nal` and `map2.nal`
4. `propertymatching.nal`
5. `categoryformationanddetection.nal`
6. `example1.nal`
7. `smokes.nal`
8. `reasoningbench.nal`
9. `relationalnetwork.nal`

Why this order:

- early items are most likely blocked by shell and parser semantics
- later items stress deeper declarative reasoning and query behavior

Definition of done:

- each family has a tracked status and at least one minimized regression test

### 8. Add Regression Tests Before Engine Changes

For every behavior mismatch that requires engine work:

- write a minimized test first
- confirm ONA behavior with the MCP server
- only then change fNARS

Preferred test shape:

- small scenario tests in `test/fNARS/`
- use `.nal` files only for corpus-level compatibility checks

Use ONA MCP as the oracle for:

- answers
- truth values
- execution choice
- occurrence times
- sometimes stamps when needed

Definition of done:

- every nontrivial compatibility fix is guarded by a local regression test

## Execution Order

Follow this sequence:

1. Add corpus compatibility harness.
2. Unify shell and `.nal` replay execution.
3. Implement `*concurrent`.
4. Add `*setopstdin` compatibility behavior.
5. Fix `*setopname` slot semantics.
6. Expand `.nal` expectation parsing and checking.
7. Add explicit `&|` support.
8. Work through failing example families one by one.
9. Add minimized regression tests for every engine-level fix.

## Acceptance Criteria by Phase

### Phase 1: Replay Parity

Success criteria:

- in-scope example files no longer fail due to missing shell commands
- interactive shell and `.nal` replay use the same line semantics

### Phase 2: Syntax and Expectation Parity

Success criteria:

- in-scope parser syntax matches ONA for the remaining corpus
- runner can score ONA-style expectations accurately

### Phase 3: Behavioral Parity

Success criteria:

- remaining failing files are actual reasoning mismatches, not shell or parser
  mismatches
- each reasoning mismatch is represented by a targeted test

## Tracking Table

Use this table as the working checklist.

| Item | Status | Notes |
| --- | --- | --- |
| Corpus compatibility harness | In progress | `bb nal:compat` added (`scripts/nal_compat.clj`) |
| Shared shell and `.nal` line execution | Not started | Remove drift between shell and runner |
| `*concurrent` support | Done | Implemented in `shell.cljc` and `nal_runner.cljc` |
| `*setopstdin` compatibility | Done | Added as compatibility no-op |
| `*setopname` slot parity | Not started | Match indexed overwrite behavior |
| Expectation support: `no execution` | Not started | Needed for `avoid3.nal` |
| Expectation support: temporal answer checks | Not started | Needed for temporal corpus checks |
| Distinct `&|` support | Not started | Parser and downstream semantics |
| `learnwords.nal` family | Not started | Likely shell/time semantics first |
| `avoid2.nal` and `avoid3.nal` family | Not started | Needs `&|` and no-execution support |
| `map.nal` and `map2.nal` family | Not started | Needs command/operator parity |
| `propertymatching.nal` family | Not started | Query and temporal-answer behavior |
| `categoryformationanddetection.nal` family | Not started | Large pattern/query benchmark |
| `example1.nal` family | Not started | Similar to category formation benchmark |
| `smokes.nal` family | Not started | Declarative higher-order reasoning |
| `reasoningbench.nal` family | Not started | Broad declarative benchmark |
| `relationalnetwork.nal` family | Not started | Product/image/equivalence reasoning |

## Immediate Next Step

Start with the compatibility harness.

That gives a real baseline and prevents guessing which failures are engine bugs
versus shell-command drift.
