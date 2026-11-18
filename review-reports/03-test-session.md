# Phase B report — Test session screen

**Batch:** B3 · **Reviewers:** behaviour/actions, UI/accessibility, data integrity (independent, parallel).
**Scope:** `ui/test/TestSessionScreen.kt`, `TestViewModel`, `TestSelector`/`TestPlan`, `Submission`, `TestProgressStore`, resume/snapshot paths and tests. Read-only.

## Severity summary

| Severity | Count | Themes |
|---|---:|---|
| High | 1 | Back during save cancels the attempt write |
| Medium | 9 | countdown behind dialogs, palette Back, scope/duration integrity, a11y semantics, hardcoded copy |
| Low/Nit | 12 | control gating, layout, formatting, store hygiene |

## Findings

### High

**H1 — Back arrow cancels an in-flight attempt save.** `TestSessionScreen.kt:305-307,318-328`: once `submit()` sets `submitted=true, saving=true` (`TestViewModel.kt:1080-1086`), `BackHandler` is disabled and the arrow pops the destination. Popping clears the screen-scoped ViewModel and cancels `viewModelScope.launch` (`:1086-1135`) mid-`withTransaction` (`HistoryStore.kt:371`), rolling back the attempt. The screen vanishes with no result; recovery only via resume snapshot. Keep a `BackHandler(enabled = saving)` that blocks navigation, disable the arrow, and don't navigate until `attemptId` fires. **Owner decision: block vs explicit cancel.**

### Medium

- **M1 — Countdown runs behind blocking dialogs/overlays.** `TestViewModel.kt:558` starts the timer only checking `resumeOffer == null`; the ungraded-note dialog (`TestSessionScreen.kt:259-284`) makes the question invisible (`:286-295`) but the timer keeps running and can auto-submit at zero (`:738-741`). Same for palette, submit-confirm and answer-review overlays. Pause/withhold the timer whenever `questionVisible` is false. **Owner: exam behaviour.**
- **M2 — System Back exits the test when the palette is open.** The palette is an inline `Card` (`TestSessionScreen.kt:434-443`), not a dialog; `questionVisible=false` disables the submit-confirm handler (`:305-307`) and nothing handles Back. Add `BackHandler(enabled = showPalette) { showPalette = false }`.
- **M3 — Timer wording and errors are hardcoded.** `domain/TestTiming.kt:17-21` messages ("5 minutes left", …), `TestViewModel.kt:563,1122` raw exception text; resources `load_failed`/`save_failed` exist but are unused (`strings.xml:1036-1037`). Move to resources; **owner: copy**.
- **M4 — Flag/bookmark toggle state invisible to TalkBack.** `TestSessionScreen.kt:362-389`: IconButtons with static descriptions; state only by tint/filled icon. Add `stateDescription` or `IconToggleButton`.
- **M5 — Palette status precedence hides "flagged".** `TestSessionScreen.kt:1205-1235` orders current > answered > flagged; a flagged-and-answered question is announced/coloured only as answered, defeating post-test review flagging. Combine states and add a non-colour marker.
- **M6 — Answer-review rows are sub-48dp and unlabelled.** `TestSessionScreen.kt:937-945` `.clickable{}.padding(6.dp)` with two Texts; no contentDescription. Use `heightIn(48.dp)` and a "Go to question N" label.
- **M7 — Timer readout is two unlinked semantics nodes; urgency colour-only** (`:340-354`). Merge into one labelled node; announce the 60-second threshold once.
- **M8 — Fixed header/palette/review heights clip at large font scale** (`:1021-1036`, `:1198`, `:931`); use `heightIn(min=…)` and test at 1.3/landscape.
- **M9 — `question_stats` last-standing pair diverges from recompute.** `Daos.kt:2946-2953` advances the standing on skips/ungraded (`isCorrect=false`), while delete-recompute picks the latest graded+answered row (`:3146-3158`). `QuestionStatsRecomputeTest:312-330` only checks after the heal; `:247-258` confirms the bad incremental value. Columns currently have no production reader, so latent. Pass an explicit last-graded standing.

### Low / Nit

- **L1 — Controls active during/after submit:** Show answer (`TestSessionScreen.kt:804-825`) and Submit buttons (`:788-793,826-834`) lack `saving/submitted` guards; taps change displayed verdicts or open dialogs whose confirm no-ops. Add `enabled` + early returns.
- **L2 — Missing question leaves a permanent spinner** (`TestSessionScreen.kt:530-547`, `TestViewModel.kt:996-1007`); skip unresolved ids or offer "question unavailable — Skip".
- **L3 — Failed save distorts duration on retry** (`TestViewModel.kt:1075-1079,1116-1133`); clocks restarted without rebasing anchors.
- **L4 — Deliberate dwell pause persists only in memory** (`:830-869`); a kill restores older dwell. Persist after user pause.
- **L5 — Bookmark toggle unguarded and failure logged only** (`TestSessionScreen.kt:376-389`, `TestViewModel.kt:931-940`).
- **L6 — Resume and ungraded dialogs can stack** (`TestSessionScreen.kt:177-284`); suppress one.
- **L7 — Cross-paper takeover leaves orphan `test_progress` rows** (`TestProgressStore.kt:24-29,89`; `TestViewModel.kt:1145-1155` clears the new paper only). Capture outgoing paper id before overwriting the header.
- **L8 — `adopt()`/`discardStale()` bypass `writeLock`** (`TestProgressStore.kt:99-104,127-129`); wrap in lock.
- **L9 — D8 stale-snapshot defense untested end-to-end** (`TestViewModel.kt:1206-1211`); add a test with a snapshot plus newer finished attempt.
- **L10 — No test for save-fails→retry not double-counting.**
- **L11 — Locale-insensitive time/marks formatting** (`TestTiming.kt:57-61`, `TestSessionScreen.kt:985-990`).
- **N1 — Position chip reads "3 slash 10"** (`:773-778`), unlabelled progress spinner (`:543`).

## Checked with no defect

Submit-exactly-once (guard `:1065`, single-shot confirm, retry resets guard, D8 stale-snapshot defence); save-error retry retains the card and snapshot; legacy/short resume warnings and identity checks; dwell pausing via `questionVisible` and `SessionClock`; option selection, flag, Prev/Next/palette jumps and their guards; strict/practice gating via `ExamMode`/`Feedback`; answer-review paging keys and open/dismiss; rotation (`rememberSaveable`) and lifecycle observer removal; stable palette/review keys; empty-scope refusal; calibrated cache untouched by test submits (correct).

## Owner decisions

- Block navigation during save vs offer explicit "Cancel save" (H1).
- Timer handling while dialogs/overlays are open (M1).
- Flagged-and-answered palette semantics (M5).
- Error copy and whether raw exception messages may be shown (M3).
