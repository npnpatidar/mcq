# Phase B report — Settings

**Batch:** B7 · **Reviewer:** full-lens audit of `ui/settings/**`, `RepositorySettings`, `GradingRulesStore`, backup/restore paths · **Read-only.**

## Findings

| Sev | Finding | Evidence / fix |
|---|---|---|
| **High** | A failed restore permanently wedges all Backup & data controls. `busy=false` is only in `onDone`, called only on success; both catches call `onError` only. `busy` is `rememberSaveable`, so it also survives navigation. | `SettingsViewModel.kt:833-868`, `SettingsScreen.kt:302-309`, `BackupDataSection.kt:55-142`. Reset in `finally`. |
| Medium | Storage can show "Measuring…" forever on failure; raw exception only logged. | `SettingsViewModel.kt:542-557`, `BackupDataSection.kt:159-162`. Expose error state + retry. |
| Medium | Per-keystroke cut edits mint multiple `manual` grading versions and restart the calibration epoch. | `StudySchedulerSection.kt:564-578`, `GradingRulesStore.kt:75-101`. Commit on focus loss/coalesce. **Owner: overrides documented choice.** |
| Medium | Hardcoded technical copy and raw exceptions reach users; string guard blind to VM literals. | `SettingsViewModel.kt:100-128,590,601,654,717,863,866,878-880,525`, `BackupDataSection.kt:117,133,136`. Move to resources. **Owner: copy.** |
| Low | Share coroutine can leave `busy` stuck on cancellation; double-tap can start two shares. | `SettingsScreen.kt:95-117`. `finally` + one-shot guard. |
| Low | Removing the selected custom theme can leave a dangling key (StateFlow lag); theme-removal confirm uses plain `remember`; nested roles may double-announce; fast-cut down button silent no-op at boundary. | `SettingsViewModel.kt:164-180`, `AppearanceSection.kt:72`, `SettingsRows.kt:48-52,88-107`, `StudySchedulerSection.kt:554-563`. |
| Nit | KiB/MiB labelled KB/MB. | `StorageInfo.kt:31-36`. |

## Checked with no defect

One-shot FSRS-7 notice after restore; unrelated setting edits do not mint grading versions; happy-path export/backup busy gating; backup consistency guard and document deletion; storage preview cap; defaults and section ownership; reset flows and error display; theme import cap and same-name replacement; back navigation.

## Test gaps / owner decisions

No test for failed-restore wedge, concurrent operations, cut-edit version minting or storage failure. Decisions: keep per-keystroke commits, approve string/error copy work, surface storage/theme failures visibly.
