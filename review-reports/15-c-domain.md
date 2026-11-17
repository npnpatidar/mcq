# Phase C report — domain, scheduling and feature semantics

**Reviewer:** deep-domain audit; algorithm claims verified against upstream `fsrs-rs` v7.0.0 sources (URLs below). **Read-only.**

## Upstream sources used

[model_v7.rs](https://raw.githubusercontent.com/open-spaced-repetition/fsrs-rs/v7.0.0/src/model_v7.rs) ·
[inference.rs](https://raw.githubusercontent.com/open-spaced-repetition/fsrs-rs/v7.0.0/src/inference.rs) ·
[parameter_clipper_v7.rs](https://raw.githubusercontent.com/open-spaced-repetition/fsrs-rs/v7.0.0/src/parameter_clipper_v7.rs) ·
[parameter_initialization_fsrs7.rs](https://raw.githubusercontent.com/open-spaced-repetition/fsrs-rs/v7.0.0/src/parameter_initialization_fsrs7.rs) ·
[Anki FSRS deck options](https://docs.ankiweb.net/deck-options.html#fsrs) · [new-day start](https://docs.ankiweb.net/deck-options.html#new-day-start)

## Findings

| Sev | Finding | Evidence / fix |
|---|---|---|
| Medium | Unknown/corrupt card-state string plus `stability > 0` makes `next()` call `initialMemory`, discarding imported memory. Upstream `next_state` has no Kind input — memory should be authoritative. | `Fsrs.kt:132`, `StreamingImportSession.kt:667-669`. Gate `hadMemory` on `stability > 0` alone. |
| Medium | DST drift: `dueAfter` anchors the whole interval to the offset at grading time; due instants can land at 03:00/05:00 local or in the previous study day. | `DayBoundary.kt:49-56`, `Fsrs.kt:184`, `DayBoundaryTest:89` covers only no-DST zones. Resolve at target day or store a day index. **Owner: accept ±1 h?** |
| Low | Anki import hardcodes leech threshold 8, ignoring configured `leechThreshold`; imported leech badge can flip on first study. | `AnkiScheduling.kt:174` vs `Fsrs.kt:193`. Pass config into `fromAnki`. |
| Low | `clipInPlace` maps non-finite weights to the upper bound; upstream maps to the lower bound. Only hand-edited backups can reach it. | `Fsrs.kt:690-697`, upstream `clamp_safe`. Align and add clip test. |
| Low | Post-restore rebuild CAS can permanently skip a card whose stored `lastReviewedAt` disagrees with its last log row. | `Fsrs7Upgrade.kt:97-104`, `Daos.kt:1649-1654`. Revisit no-op CAS rows with `stabilityFast == 0`. **Owner: harden inconsistent backups?** |

## Checked against upstream — no defect

Forgetting curve (both traces, mixture, epsilon, derivative); update formulas and fast-lapse cap; default 34 weights and clip bounds (upstream `next_states` vector reproduced); interval solver (7 Newton steps, log-time, bisection fallback); SM-2 bridge at fixed `HISTORICAL_RETENTION = 0.9` with `D=5, Sf=0.8·S`; `Sf=0` sentinel across scheduler/replay/rebuild/optimizer; exposure cap parity across live/replay/rebuild/preview/DAO; grading-version epochs and calibration gate; 21-weight fallback; trainset/optimizer parity and non-storable fits; backup/JSON key resolution through one helper.

## Test gaps / owner decisions

Add DST-transition tests, an upstream golden vector for `stateForInterval`, carry-memory-with-unknown-Kind, inconsistent-backup rebuild, infinity weights, and configured leech threshold on Anki import. Decisions: accept DST drift; harden restores against inconsistent backups.
