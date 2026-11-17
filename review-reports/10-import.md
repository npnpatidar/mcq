# Phase B report — Import

**Batch:** B10 · **Reviewer:** full-lens audit of `ui/importscreen/**`, importers/readers and handoff paths · **Read-only.**

## Findings

| Sev | Finding | Evidence / fix |
|---|---|---|
| **High** | Suggestion fields use `PrimaryNotEditable` anchor on an editable field: typing opens the menu and moves focus away, so novel paper/category names are unreliable. | `ImportScreen.kt:646-659`. Use `PrimaryEditable` ([docs](https://developer.android.com/reference/kotlin/androidx/compose/material3/ExposedDropdownMenuAnchorType)). Add emulator test. |
| **High** | `.apkg` imports bypass the Import screen entirely; blank-stem notes are dropped and recall-converted notes are invisible, with no warnings in the report. | `LibraryViewModel.kt:185-192`, `AnkiPackageReader.kt:886-911,1019-1031`, `AnkiApkgImporter.kt:83-92`. Surface dropped/recall counts in Library's report. **Owner: preview or warning?** |
| Medium | Import button not synchronously single-shot; double-tap can launch two imports. | `ImportViewModel.kt:854-865,919-926`, `ImportScreen.kt:537`. Add `AtomicBoolean` guard. |
| Medium | Per-batch partial commits are disclosed only after failure; a large import can leave partial data without upfront warning. | `StreamingImporter.kt:45-47`, `ImportViewModel.kt:907-915`. One-line notice near preview. **Owner: copy.** |
| Medium | Completion dialog omits restored schedules and paper counts — the main reassurance for a backup restore. | `ImportScreen.kt:111-126`, `Importer.kt:37`. Add plural-aware lines. |
| Medium | Whole flow uses hardcoded English literals (screen, VM, warnings, journal, SharedImport). | Multiple files; string guard only checks existing `stringResource` sinks. **Owner: localisation scope.** |
| Medium | Correct-answer markers are bare glyph/color with no semantics. | `ImportScreen.kt:599-606`. Merge semantics with "correct answer". |
| Low | Editor return with id mismatch clears `lastEdited` but not `pendingEditQuestion`; `EditorSession` never cleared on leaving Import; skipped/advisory split relies on the substring "skipped"; progress counts raw batches; unbounded buffered schedules; empty paper shell on all-duplicate import; dialog line runs together. | `ImportScreen.kt:171-182`, `ImportWarnings.kt:72-76`, `StreamingImportSession.kt:199-200,239`, `Importer.kt:138-151`, `ImportScreen.kt:115-119`. |

## Checked with no defect

Source fingerprint and identity guards across rotation/editor return; import fail-closed via route source; dedupe parity among preview and both writers; paper/category identity rules; backup completeness and redirect; schedule key resolution by declared version; re-import dedupe of bookmarks/attempts; Anki bridge fixed at 0.9 retention; parser teardown; warning keys and icon labels.

## Test gaps / owner decisions

No Import instrumentation; confirm negative cases for pure helpers, end-to-end retry after mid-stream failure (CI-only), report-field enumeration, Anki dropped-note disclosure. Decisions: Anki preview vs warnings, upfront partial-import notice, restored-schedule display, empty paper shell.
