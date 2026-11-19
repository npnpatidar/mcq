# Round-2 full app review — consolidated findings

**Branch:** `feature/review-round-2` · **Base:** `main` `fbc8e15` (release `0.0.61`) · **Date:** 2026-11-18
**Method:** 13 independent read-only reviewers, one per screen/feature area plus three cross-cutting lenses. Every old report in `review-reports/` was treated as a claim to re-verify, never as truth. Algorithm claims were checked against upstream `fsrs-rs` v7.0.0 / `ts-fsrs` and platform claims against official Android/Compose docs, with URLs in the individual reports. No product code was changed by this branch.

**Reports:** `01-library-navigation.md` … `13-hidden-security-perf.md`.
**Raw findings:** 85 — 6 High (5 distinct), 33 Medium, 44 Low, 2 informational. Cross-cutting reports deliberately re-state area findings to identify root causes, so the deduplicated total is lower (~65 distinct issues).

> **Outcome (2026-11-19): closed.** Every finding in this set — including the
> High items, the ~65 deduplicated issues and every open §6 decision — was
> fixed or applied by PR #95 (`feature/round2-high-fixes`), verified by four
> area reviewers plus an integration reviewer and green CI (357 classes /
> 2,728 tests), and shipped as release **0.0.62**. Read the area reports as
> the *evidence* for those fixes, not as live defects: none of the findings
> below exist in 0.0.62.

---

## 1. Priority fixes (High)

| ID | Finding | Impact | Fix shape |
|---|---|---|---|
| **L1** | `MainActivity.onCreate` re-handles every SEND intent (`MainActivity.kt:75,89-100`), so Activity recreation replays the same file into `SharedImport` and pushes `import/direct` again. | Rotate mid-import → typed input lost and duplicate import screen; rotate during Study/Test → learner yanked to Import; failed copy produces a snackbar on every rotation. | Gate on `savedInstanceState == null` or mark the intent consumed; `onNewIntent` remains the only in-task entry. |
| **S1** | `StudyViewModel.restart()` clears `stopped`/CAS while a grade write is still in flight (`StudyViewModel.kt:1437-1522`); the late continuation calls `pullCard()` on the new sitting. | Persisted review with no `study_sessions` row (old M3 defect, partially fixed), grade attributed to the wrong sitting, two concurrent pulls, late error on a fresh card. | Join the in-flight grade `Job` in `restart()`, or tag sittings with a monotonic id used by continuations. |
| **SE1** | Backup/restore/share state (`busy`, errors, result) lives in `rememberSaveable` composable state while the work runs in the ViewModel (`SettingsScreen.kt:82-87`). A locale change or rotation recreates the composition with `busy=true` and orphans the callbacks. | All Backup & data controls stay disabled and no result dialog appears after a language change or rotation mid-operation — old H4 reintroduced through a new path. | Move operation state and callbacks to the ViewModel (survives recreation), keep only a one-shot completion signal in the UI. |
| **E3 = A4** | Any question containing a `MathElement` is rendered by `MixedContentView` as one merged TalkBack node with the fixed description "Rendered question content with formulas" (`ContentElements.kt:49-51,540-542`). | A screen-reader user cannot read the question, table cells or math at all. Adding a label answered the old "WebView unlabelled" claim but hid the content. | Provide a real accessible text fallback / non-merging node, or let TalkBack traverse the WebView's own accessibility tree. |
| **I1** | Duplicate-answer refresh reinserts incoming correct-option IDs while stored option rows keep their old IDs (`Importer.kt:427-455`, `StreamingImportSession.kt:489-511`). | A regenerated bank with corrected key and re-minted option ids imports cleanly but never grades — the key points at non-existent options. | Remap incoming correct ids onto stored option ids by position; add a regression test with re-minted ids. |

---

## 2. Cross-cutting themes (deduped)

1. **Untranslated literals outside the resource system** — root cause **A1**, ~70 user-facing strings in enum/data holders and formatter parameters. Area evidence: L5, L6, L8, S2, T5, R2, R3, B5, ST2, E8, I6, SE2, SE3 (+A2, A3). Resource EN↔HI name parity is actually complete (1,291 EN / 1,271 HI names; all 20 EN-only are `translatable="false"`), so the gap is invisible to the locale files *and* to the string guard. Fix: migrate literals to resources and extend `StringResourceUsageTest` beyond its four syntactic forms (A7).
2. **Composition-scoped coroutines for critical operations** — L3 (blueprint save), L2 (test-start tap), R1 and B3 (share/export), SE5 (share double-tap). Rotation or Back cancels the job and/or strands the chooser; several prior "share cancelled" fixes only moved file writing, not coroutine ownership. Fix: own the work in the ViewModel/activity scope with a one-shot UI signal.
3. **Accessibility state and announcements** — E3/A4 (High, above), A5/T2 (flag/bookmark and checkbox state not spoken), A6/T5 (timer warnings English, unannounced, urgency colour-only), S3 (verdict never announced), S2 (option states English), R5, B6, ST6, L9. Round 1's announcer and four-state verdicts are real, but several controls never use them.
4. **Rotation/process-death state loss** — E1 (passage Cancel destroys the edited form), E2/R8 (open dialogs and typed drafts lost), L2, B7 (filters lost on process death), R9, T6 (missing question leaves a frozen spinner).
5. **Backup/export semantics** — H1 (backup rules exclude `file`/DataStore, so no settings survive cloud/D2D restore, contradicting the XML comment), H2 (per-paper JSON/ZIP export drops card schedules), H4 (Export Logs shares paper titles and raw stack traces without warning), SE3 (partial-restore report appends raw `e.message`).
6. **Statistics/calibration correctness** — ST1 (BCa bootstrap resamples Easy/Good independently rather than by card cluster, so it does not preserve the dependence it claims), ST4 (`t=1.70` under-covers near its floor), ST3 (stability bins mislabelled as durations), ST5 (unknown grade renders as Easy).

---

## 3. Other findings by area

| Area | Medium | Low / nit |
|---|---|---|
| Library/nav (01) | L2 start tap lost; L3 cancellable blueprint save; L4 mislabeled fill preview; L5/L6 English dialog/error copy; L7 failed delete still removes row | L8 card summary untranslated; L9 dialog/a11y leftovers |
| Study (02) | S2 English grade labels; S3 verdict unannounced | S4 no `grading` guard in VM |
| Test (03) | T1 top-bar Back exits with palette open; T2 toggle state unspoken; T5 English/unsoken timer warning | T3 sub-48dp rows; T4 colour-only timer; T6 frozen spinner; T7 validation bypass on selector path |
| Results/History (04) | R1 share/export cancelled; R2/R3 English chips/dialog; R4 relaunch drops minutes/breadth | R5 row/trend a11y; R6 silent failures; R7 count mismatch; R8 dialog state; R9 filter lost; R10 raw category ids |
| Browse/Search (05) | B1 single delete has no confirm/undo; B2 collision history-drop undisclosed; B3 share aborted on rotation | B4 stale hits; B5 literals; B6 a11y; B7 filters lost |
| Stats (06) | ST1 bootstrap; ST2 chart dates English | ST3 bins; ST4 t-value; ST5 unknown grade; ST6 stacked-chart a11y |
| Settings (07) | SE1 (High above); SE2 log copy; SE3 raw/partial restore report | SE4 dangling theme key; SE5 double share; SE6 blocking DataStore read pre-33 |
| Editor/Passages (08) | E1 Cancel loses form; E2 dialogs/drafts lost; E3 (High above); E4 dirty guard bypass | E5 torn snapshot; E6 error as empty; E7 unsavable passage; E8 difficulty English; E9 Main-thread hashing |
| Import (09) | I1 (High above); I2 all-duplicate stream disables button; I3 "streaming" holds O(cards) | I4 empty-shell count; I5 substring warning severity; I6 data-layer literals |
| Domain/Data (10) | — | D1 Anki leech threshold hardcoded; D2 Anki day DST asymmetry; D3 clip divergence; D4–D6 latent/corruption-only |
| Strings/A11y (11) | A1 root cause; A2 states; A3 dates; A5 toggles; A6 timers | A7 guard coverage; A4 is the High above |
| CI (12) | — | C1 release notes advisory; C2 `--refresh-dependencies` on 2 of 4 jobs; C3/C4 informational |
| Hidden (13) | H1 backup rules | H2 schedules dropped; H3 global new-card switch; H4 log privacy |

---

## 4. Verified genuinely fixed since 0.0.53

Round 1 High items H1–H12 are confirmed fixed in code by at least one independent reviewer, with the qualifications above: H1/H2 (double review, Retry), H3 (Back during save), H4 (restore busy wedge, but see SE1 for a new path), H5 (formula block ids), H6 (passage member count), H7 (suggestion field), H8 (apkg disclosure), H9/H10 (verdict colours/theme), H11 (announcer infrastructure), H12 (rotation state). FSRS-7 maths, weights, clip bounds, curve, solver and SM-2 bridge still match upstream `fsrs-rs` v7.0.0 / `ts-fsrs`; calendar-day DST boundaries and Anki export day counts are correct; migration chain 3→28, CAS rebuild, SAF temp files, atomic review writes, import rollback, and cross-paper calibration invalidation all checked clean. No new High defect in the core scheduling/data engine.

## 5. New this round (missed by round 1)

L1 (SEND replay on every recreation), S1 (restart during grade), SE1 (locale/rotation orphans operation callbacks), E3/A4 (formula content unreadable to TalkBack), I1 (re-minted option ids ungrade questions), I2/I3 (streamed import UX/memory), ST1 (bootstrap resampling), H1 (backup rules exclude settings), plus the ~70-literal i18n root cause that resource-parity checks cannot see.

## 6. Owner decisions still needed

- i18n scope: migrate all ~70 literals now, or only high-visibility surfaces?
- Which High items to fix first, and whether L1 needs a `launchMode`/singleTop change.
- Collision history-drop disclosure (B2), schedule-reset wording, global new-card switch wording (H3).
- Anki leech threshold and day-model semantics (D1/D2); clip divergence (D3).
- Backup: include DataStore in cloud/D2D rules (H1) and/or correct the XML comment; add schedules to per-paper export (H2) or disclose "content only".
- Log export privacy warning vs redaction (H4); release-notes publish gate (C1).

---

No release is cut from this branch. User-visible fixes follow the normal PR → merge → release process with plain-language notes.
