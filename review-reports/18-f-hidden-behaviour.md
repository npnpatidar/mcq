# Phase F report — what a new user cannot know

**Reviewer:** whole-app disclosure audit. **Read-only.** Product premises applied; a finding survives only if a first-time honest learner would be misled.

## High — must disclose

| # | User assumes | Actually | Evidence / disclosure |
|---|---|---|---|
| F1 | The clock grades speed; Easy/Hard come from fast answers. | Fresh installs seed `correctness_only=true`; correct→Good, wrong→Again; Easy/Hard only by manual edit or timing mode. Dwell still recorded. | `RepositorySettings.kt:420-426`, `SpacedRepetition.kt:391-405`, `StudyScreen.kt:524-526`. One-time first-study hint; help text exists only in Settings (`strings.xml:585`). |
| F2 | Fixing a typo/option/key/passage keeps progress. | Any content change deletes `card_state`; only explanation/metadata keep it. Prev/Next auto-save does the same. The KDoc claiming disclosure is false. | `QuestionStore.kt:273-287`, `CardContent.kt:13-27`, `QuestionEditorScreen.kt:803-805` vs `strings.xml:291`. Inline note near Save; correct KDoc. |
| F3 | "Enable new cards" affects this paper/session. | It writes the global `introduceNewCards=true` setting. | `StudyViewModel.kt:1222-1237`. Relabel "for all papers". |
| F4 | A move just relocates the question. | Cross-paper move re-keys schedule/history/stats to the destination; destination stats absorb reviews; on collision source history is dropped. Warning states only the first half. | `QuestionStore.kt:518-558`, `strings.xml:61`. Add collision sentence. |
| F5 | Per-paper JSON is a complete backup. | Per-paper JSON has no scheduling; whole-bank JSON v2 has schedules but no settings/review log; only zip backup has everything. | `PaperExporter.kt:138-140`, `Exporter.kt:135,160-165`, `strings.xml:107,73`. Correct the format description. *(Plan seed "plain JSON lacks progress" was stale for whole-bank JSON.)* |
| F6 | Importing an FSRS `.apkg` preserves its scheduling. | Only the interval is read, re-derived via SM-2 bridge at fixed 0.9, `D=5`, losing source stability. | `AnkiScheduling.kt:63,92-96,148-164`, `PROJECT_NOTES.md:180-185`. Import warning or help article. |

## Medium / low — document or refine

- **F7 Optimize keeping weights** is already reported via `optimize_fsrs_done_kept`; ensure the copy explains why (not enough evidence / no better fit).
- **F8 Calibration epoch resets silently** and insufficient evidence prints "+0.0 pp"; caption the window and render "—" (`StatsStore.kt:282-302`, `RetentionSection.kt:402-428`).
- **F9 Import dedupe collapses identical copies**; completion dialog omits restored-schedule/paper counts (`Importer.kt:45-58`, `ImportScreen.kt:111-126`).
- **F10 Skipped vs advisory warnings** use a substring classifier; keep panels distinct and document that advisory notes mean no content loss (`ImportWarnings.kt:54-76`).
- **F11 "Next in …"** is a preview that can differ across a day boundary; prefix "≈" (`StudyViewModel.kt:851`).
- **F12 Restore does not wait for FSRS-7 rebuild**; un-reached cards use `Sf = S` transiently (`McqRepository.kt:629-657`).
- **F13 TESTS tab ignores the range and CSV mislabels ungraded answers** (`StatsScreen.kt:131-133`, `ResultsViewModel.kt:206-211`).

## Checked and considered — no new finding

Whole-bank JSON scheduling is present (seed was stale); 21→34 weight fallback has a one-shot Settings notice; 0 s dwell and third-exposure `RELEARNING` are deliberate under the premises; pause is for interruptions; passage-expanded move counts are disclosed; same-paper moves leave progress alone; backup wipe/replay warning is accurate; cache/key/dedupe internals are not user-visible.

## Owner decisions

Approve first-run study hint; inline schedule-reset copy vs confirm dialog; collision and per-paper-JSON wording; Anki warning vs help; calibration caption; CSV verdict copy.
