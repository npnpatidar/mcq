# Full App Review — consolidated findings

**Branch:** `feature/full-app-audit-plan` · **Base:** `main` `ba79e24` (release `0.0.53`) · **Date:** 2026-11-17
**Method:** 5 parallel inventory agents (Phase A), 3 independent reviewers per major screen (B1–B4), 1 full-lens reviewer per remaining screen (B5–B14), 5 deep-domain reviewers (C–G). Every finding cites file:line; platform/algorithm claims cite official upstream sources in the individual reports. No product code was changed by this branch.

**Reports:** `review-reports/00-baseline.md` … `19-g-tests-ci.md` (20 files). **Ledger:** `REVIEW_LEDGER.md`.

---

## 1. Severity-ranked backlog

### High — fix before further feature work

| # | Finding | Report | Fix shape |
|---|---|---|---|
| H1 | Ending a Study sitting after a persisted grade whose next-card load failed writes the review **twice** (dup `review_log`, doubled exposure/FSRS update). | 02 | Guard `stop()` with `gradedAndStuck`; regression test. |
| H2 | Simplified (default) Study mode has **no Retry** for that state, so the only exit triggers H1. | 02 | Add Retry; emulator test. |
| H3 | Test-session top-bar Back cancels an in-flight attempt save (transaction rolled back, screen vanishes). | 03 | Block navigation while `saving`; VM-level test. |
| H4 | Failed Settings restore leaves `busy=true` forever, disabling every Backup & data control. | 07 | Reset in `finally`; test both catch paths. |
| H5 | Formula edits after block reorder land in the wrong block (positional callback captured once by `AndroidView.factory`). | 08 | Stable blockId resolved at callback time; emulator test. |
| H6 | Stale passage member count can suppress the mandatory cross-paper warning before a group move. | 09 | Re-read member count at save decision time. |
| H7 | Import suggestion fields use `PrimaryNotEditable` on an editable field — typing novel names loses focus. | 10 | `PrimaryEditable`; emulator test. |
| H8 | `.apkg` imports bypass preview and silently drop blank-stem notes / convert no-option notes without disclosure. | 10 | Surface dropped/recall counts. **Owner.** |
| H9 | Skipped answers rendered with red wrong-answer container; option states unlabelled for screen readers. | 04, 17 | Four-state Verdict enum + merged semantics. **Owner: colours/copy.** |
| H10 | Light verdict palette ignores custom light themes; white launch window flashes before forced-dark composition. | 17 | Derive from scheme roles; fix window background. |
| H11 | No announcement mechanism anywhere (0 `liveRegion`/`announceForAccessibility`); errors and counts are silent to TalkBack. | 17, 19 | App-level message/announcer host. **Owner: scope.** |
| H12 | Library rotation loses paper/category/test-setup state and silently turns blueprint edit into create. | 01 | Saveable state / hoist ids to VM. |

### Medium — next batch

| Area | Findings (condensed) | Report |
|---|---|---|
| Study | change-grade during in-flight save; grade write cancelled on teardown; sitting with persisted review may have no session row; colour-only dwell band; undiscoverable pause; hardcoded copy/errors. | 02 |
| Test | countdown runs behind dialogs; palette Back exits test; duration wrong after failed save; dwell pause not persisted; question_stats standing divergence; cross-paper test_progress orphans. | 03 |
| Results | CSV mislabels ungraded; cross-paper moved question treated as resolved; load error no Retry; stale bookmark-filter race; Study/Test actions drop scope; PDF denominator mismatch; export double-tap. | 04 |
| Library | destructive copy understates history/stats; category edit non-atomic; stale selection after delete; dialog stacking; double-taps; partial import; truncated export; blueprint label wrong. | 01 |
| Settings | storage "Measuring…" forever; per-keystroke cuts mint grading versions; hardcoded/raw error copy; share `busy` leak; dangling theme key. | 07 |
| Editor | non-atomic session snapshot; schedule-reset undisclosed; WebViews unlabelled; half-typed tag crosses questions. | 08 |
| Passages | Cancel discards form edits; rotation loses dialogs; whitespace no-op destroys rich content; pager error shown as empty. | 09 |
| Import | non-single-shot import; partial commits not disclosed upfront; completion omits restored schedules; warnings classifier is a substring. | 10 |
| Stats/Browse/History/Sessions/Bookmarks/Search | epoch resets hidden; sheet error vanishes; TESTS range ignored; charts unlabelled; row delete unconfirmed; query/filter lost on process death; trends mix scopes; range chips show stale data; share render cancelled; scope-chip debounce mismatch. | 05, 06, 11–14 |
| Domain | unknown Kind discards imported stability; DST drift; Anki leech threshold hardcoded; clip infinity mismatch; rebuild CAS skip. | 15 |
| Data | source-agnostic cross-paper re-key; attempt-delete re-points stats paper; stale-hash cache on restore. | 16 |
| Design/strings | non-plural counts, 25 unused resources, stale `sm_2_*` names, no localisation, 117 format-arg calls invisible to guard. | 17 |
| Hidden behaviour | correctness-only default, schedule-reset on edit, global "enable new cards", per-paper JSON progress gap, Anki FSRS ignored. | 18 |

---

## 2. Screen × lens matrix

| Screen | Behaviour | UI/design | A11y | Data integrity | Hidden behaviour | Report |
|---|:--:|:--:|:--:|:--:|:--:|---|
| Library | ✓ | ✓ | ✓ | ✓ | ✓ | 01 |
| Study | ✓ | ✓ | ✓ | ✓ | ✓ | 02 |
| Test session | ✓ | ✓ | ✓ | ✓ | ✓ | 03 |
| Results | ✓ | ✓ | ✓ | ✓ | ✓ | 04 |
| Browse | ✓ | ✓ | ✓ | ✓ | ✓ | 05 |
| Stats | ✓ | ✓ | ✓ | ✓ | ✓ | 06 |
| Settings | ✓ | ✓ | ✓ | ✓ | ✓ | 07 |
| Question editor | ✓ | ✓ | ✓ | ✓ | ✓ | 08 |
| Passages | ✓ | ✓ | ✓ | ✓ | ✓ | 09 |
| Import | ✓ | ✓ | ✓ | ✓ | ✓ | 10 |
| History | ✓ | ✓ | ✓ | ✓ | ✓ | 11 |
| Sessions | ✓ | ✓ | ✓ | ✓ | ✓ | 12 |
| Bookmarks | ✓ | ✓ | ✓ | ✓ | ✓ | 13 |
| Search | ✓ | ✓ | ✓ | ✓ | ✓ | 14 |
| Navigation/bootstrap/backbone | ✓ | ✓ | — | ✓ | ✓ | 00, 17 |

---

## 3. Integrity risk register

1. **Double review** after persisted-grade/pull failure (H1). 2. **Cross-paper re-key** with arbitrary source and merged history (Phase D M1). 3. **Collision history drop** disclosed only partially (F4). 4. **Schedule reset on content edit** undisclosed (F2). 5. **Partial imports** commit per batch (01 M7, 10 Medium). 6. **Truncated export file** at SAF URI (01 M8). 7. **Calibration cache** key excludes row ownership; generation guard closes commit→invalidate race but cross-paper re-key needs explicit invalidation (verified present). 8. **`question_stats` last-standing** divergence between incremental write and recompute (03 M9). 9. **`test_progress` orphans** on cross-paper takeover (03 L7). 10. **Bookmark dangling id** count/list mismatch (13 F4). 11. **FSRS rebuild CAS** skip on inconsistent backup (15 C5). 12. **DST day-bucket drift** in due dates and Sessions display (15 C2, 12 H3).

---

## 4. Checked with no defect (high value)

FSRS-7 port matches upstream `fsrs-rs` v7.0.0 curve/update/defaults/solver/bridge; exposure cap parity across live/replay/rebuild; dual-shape v27 migration; atomic review write; submit-exactly-once; backup wipe-and-replay; import dedupe parity; import fail-closed route; WebView teardown/JS bridge removal; delete cascade sweeps; paging retry components; passphrase-free architecture (no services/workers); calendar/day-boundary logic within fixed-offset design; product premises honoured throughout.

---

## 5. Owner decisions still open

Localisation/RTL commitment; error-copy policy (raw exceptions vs generic); verdict colour semantics; announcement host and guard scope; first-run study hint; schedule-reset disclosure; collision/per-paper-JSON wording; Anki warning vs help; trend grouping and Stats range semantics; attempt-deletion exposure; multi-paper card_state policy; DST drift acceptance; instrumentation scope; SHA pinning/permissions/concurrency; minify in CI release; PDF tests on device; lint baseline; release-notes gate.

No release is cut from this branch. User-visible fixes follow the normal PR → merge → release process with plain-language notes.
