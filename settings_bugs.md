# Thirteenth bug report — scoped pass: the Settings screen

A scoped pass over the Settings feature — **status:** report only, nothing
fixed yet. Date: 2026-10-25 · branch `feature/host-build-notes`. Files
covered: the screen and ViewModel (`SettingsScreen.kt`, `SettingsViewModel.kt`),
the preference store (`RepositorySettings.kt`), the config it feeds
(`SpacedRepetition.kt` `SchedulerConfig`), the shared numeric-entry pipeline
(`NumericInput.kt`), `FontScale.kt`, the log-export path (`Logger.kt`,
`file_paths.xml`), and the backup edges the screen drives (`Backup.kt`
`restoreStreaming`/`collect`, `Daos.kt` counts). Strings referenced by the
screen were cross-checked against `strings.xml`, and the restore transaction
was read through to its commit.

Already-known findings are not repeated: export-on-Main (second hunt #11),
comma-locale numeric fields and default-locale `formatValue` (fourth #38),
stepping from an invalid typed value (fourth #38 tail), and the
settings-restore resume-slot ordering (second hunt #25). What follows is new.

**Confidence legend:** **Confirmed** = defect proven by reading the current
code; **Likely** = code defect real, one reachability step depends on timing
or data shape.

---

## Summary

| # | Sev | Area | Bug | Conf. |
|---|-----|------|-----|-------|
| 1 | Medium | Screen | Backup and restore failures report under the "Export failed" dialog title | Confirmed |
| 2 | Medium | Scheduler rows | A typed value the app silently rejects stays in the field with no error; the row then lies about the stored setting | Confirmed |
| 3 | Low | Scheduler rows | fastSeconds UI range (1..600) disagrees with the domain clamp (1..3600) | Confirmed |
| 4 | Low | ViewModel | A whole-config write from a stale snapshot can revert freshly fitted FSRS weights; knob edits can also briefly show stale values | Likely |
| 5 | Low | ViewModel | Backup manifest lists every attempt row into memory just to count them; a COUNT query already exists | Confirmed |
| 6 | Low | Screen | No busy state, progress, or feedback for export/backup/restore; leaving the screen silently cancels a restore | Confirmed |
| 7 | Nit | Screen | Hardcoded English labels; six description strings spliced mid-sentence with hardcoded tails | Confirmed (latent) |
| 8 | Nit | Scheduler rows | Fractional relearn minutes render truncated ("1" for 1.5 min), steppers step from the fraction | Confirmed (latent) |
| 9 | Nit | Screen | "Export Logs" can silently do nothing; `startActivity` unwrapped | Confirmed |
| 10 | Nit | Screen | Transient dialog state is plain `remember` — rotation dismisses the restore confirm and loses the picked URI | Confirmed |

---

## Findings

### 1 · MEDIUM · Screen — backup/restore failures surface under the wrong title

One `exportError` state serves all three data operations: `backupAll`'s
callback writes it (SettingsScreen.kt:95-103 → SettingsViewModel.kt:382-385)
and so does `restoreBackup`'s (SettingsScreen.kt:541-549 →
SettingsViewModel.kt:421-428). The dialog it drives is unconditionally titled
`R.string.export_failed` (SettingsScreen.kt:571-580). A restore that dies —
say "That backup is over the … restore limit" — opens a dialog headed
**"Export failed"** with restore text inside. Same for backup failures.

**Fix.** Either three error slots, or one error holder carrying its title
(`data class ErrorUi(val title: String, val message: String)`).

### 2 · MEDIUM · Scheduler rows — the field shows a typed value the app silently rejected

`sanitized()` enforces `slowSeconds ≥ fastSeconds` by clamping slow **up** to
fast (SpacedRepetition.kt:204). The row's validity check only knows its own
range: `valid = parsed in 1..3600` (SettingsScreen.kt:912-917), so a typed
value below `fastSeconds` counts as valid and `onChange` fires
(SettingsScreen.kt:940-945). `updateSchedulerConfig` stores
`config.sanitized()`, in which slow did not move — and because the sanitized
config is *equal* to the previous one, the StateFlow does not emit, `current`
does not change, and `remember(current)` does not reset the text
(SettingsScreen.kt:911).

Concretely: with fast = 120, type `5` into the slow-threshold row. The field
shows `5`, no error, minus/plus enabled — while the stored value stays 120.
Every further keystroke and stepper press is swallowed the same way, and the
steppers now step from the phantom (`5 → 6` shown, still 120 stored). The row
is stuck until the user lowers fast first, and nothing on screen says so.

**Fix.** The row must react to sanitization, not just range: after an
`onChange`, compare the candidate with the sanitized round-trip (e.g. pass the
effective value down, or mark `isError`/show a hint when
`parsed != the field's value in config.sanitized()`). The cross-field clamp is
domain-pinned by `SpacedRepetitionTest` (`broken.slowSeconds >= broken.fastSeconds`),
so the fix belongs in the UI's honesty about it.

### 3 · LOW · Scheduler rows — fastSeconds range mismatch, both directions

The row's range is `1f..600f` (SettingsScreen.kt:804) but `sanitized()` clamps
fastSeconds to `1..3600` (SpacedRepetition.kt:203). Two symptoms:

- A backup restoring `anki_fast_seconds` in 601..3600
  (RepositorySettings.kt:317-350 writes preferences verbatim) produces a row
  that renders as a permanent error with disabled steppers for a value the
  domain considers legal.
- The user cannot type 601..3600 at all, though the domain accepts them.

`slowSeconds`' row range (1..3600) matches its clamp; fastSeconds is the odd
one out.

**Fix.** Widen the row to `1f..3600f` (or narrow the domain clamp to 600 if
600 is the real ceiling — but then a restored 601+ value silently clamps down
on next read, which is at least self-consistent).

### 4 · LOW · ViewModel — whole-config writes can revert freshly fitted weights

`updateSchedulerConfig` sends the *composition's* config snapshot down as a
full write of every key (SettingsViewModel.kt:192-195 →
RepositorySettings.kt:200-214). The fitted weights reach the screen only
asynchronously: the tuner persists them with a single-key write
(`setFsrsParameters`, RepositorySettings.kt:222-229), the DataStore flow then
re-emits, and the collector applies them on the main dispatcher
(SettingsViewModel.kt:182-186).

Between the tuner's write landing and that re-emission being applied, the
screen's `config` still holds the pre-fit weights — and every scheduler
control stays live while "Optimizing…" runs. A retention-slider drag or a
number-row edit in that window submits a full write carrying the **old**
weights, permanently overwriting the fit — while the UI goes on to show the
"Fitted on N review points…" success banner. The window is a few
main-queue hops wide, but a drag fires ~60 events/s, so a drag spanning the
fit's completion has real odds. The `setFsrsParameters` doc comment
(RepositorySettings.kt:216-221) names this exact hazard for read-modify-write;
the full-config path recreates it from the other side.

The same mechanism has a milder everyday form: a write that *lands* after a
newer gesture produces a flow emission with the older config, briefly
reverting the newer optimistic value on screen until the newer write's own
emission arrives (a visible flicker during fast slider drags).

**Fix.** Make the write re-base inside the edit (read the current DataStore
values in the `edit` lambda and overwrite only the keys the user actually
changed), or version the optimistic state so a stale emission cannot go
backwards.

### 5 · LOW · ViewModel — the manifest counts attempts by listing them

SettingsViewModel.kt:344: `attempts = db.attemptDao().getAllAttempts().size`
— every attempt row decoded into entities and held, purely to count. The very
next line counts reviews with `countAll()` under the comment *"Counted in
SQL: the rows are streamed below, never listed"*, and the count query already
exists (`SELECT COUNT(*) FROM attempts`, Daos.kt:2016-2019). On a bank with a
large attempt history this is a pointless memory spike inside the backup
path — the feature whose point (per the surrounding comments) is to never
hold a table whole. The same pattern sits in the non-streaming `collect()`
(Backup.kt:625).

**Fix.** `db.attemptDao().countAll()` in both places.

### 6 · LOW · Screen — data operations run with no busy state and die silently on exit

Export, backup, and restore buttons are always enabled and give no feedback
(SettingsScreen.kt:447-471); nothing in the ViewModel marks an operation in
flight (SettingsViewModel.kt:273-277, 320-387, 394-430). Consequences:

- **Leaving Settings mid-restore cancels the job** (`viewModelScope`): the
  single big transaction rolls back cleanly (Backup.kt:844-888 is one
  `withTransaction`), so no corruption — but the restore simply never
  happened, with no error and no completion report possible (the screen is
  gone). Same silent abort for export/backup, which also leave a partial
  file at the chosen URI.
- **A second restore queues behind the first's transaction and then wipes its
  result** — last one wins, invisibly.
- On a large bank the restore gives zero visual feedback for its whole
  duration; the screen looks frozen and the user has every reason to tap
  again.

**Fix.** An `isBusy` state (or per-operation Job tracking) that disables the
three buttons and shows a progress line; run the operation on a scope that
outlives the destination (or at minimum report cancellation when it happens).

### 7 · NIT · Screen — hardcoded English, and sentences spliced across the resource boundary

The screen resource-strings everything except: the theme radio labels
(`"System default"/"Light"/"Dark"`, SettingsScreen.kt:141), the "Export Logs"
chooser title (SettingsScreen.kt:493), and the whole advanced-Anki section —
"Next day starts at", "Maximum interval", "Relearning delay", "Leech
threshold" and their help lines are inline literals (SettingsScreen.kt:847-883)
while their sibling rows use `stringResource`.

Worse for translation: six descriptions concatenate a resource that ends
mid-sentence with a hardcoded English tail — 258-261, 339-342, 410-413,
502-504, 515-517, 624-626, 820-822. The resources confirm the splice
(e.g. `when_a_file_repeats_a_question_s_text_and_options_but_correc` =
"When a file repeats a question's text and options but corrects ",
`image_weight_counts_embedded_pictures_large_banks_shrink` =
"Image weight counts embedded pictures; large banks shrink "). Any future
translation of the fragment produces a garbled sentence. Latent: the app
ships `values/` only today.

**Fix.** Make each description one resource string (whole sentences), and
resource the advanced labels.

### 8 · NIT · Scheduler rows — fractional relearn minutes render truncated

`formatValue` with step 1.0 does `value.toInt()` (SettingsScreen.kt:963-973),
and the relearn row displays `config.relearnMs / 60000.0`
(SettingsScreen.kt:870). UI-produced values are always integral minutes, but
a restored backup can carry e.g. 90000 ms (1.5 min — inside the domain clamp
60000..86400000): the row shows "1" while the real value is 1.5, and the
steppers step from the fraction (`+` → 2.5, shown as "2"). Reachable only via
restore; latent.

**Fix.** Display one decimal when the value is non-integral, or store/show
the row in seconds.

### 9 · NIT · Screen — "Export Logs" can silently do nothing

`Logger.logFilePath()` returns null when logging never initialized (no
writable dir — Logger.kt:66-91 falls through both candidates); the button's
`if (path != null)` has no else (SettingsScreen.kt:478-495), so the tap does
nothing with no explanation. The `startActivity` is also unwrapped — an
`ActivityNotFoundException` on a device with no chooser handler would crash.

**Fix.** An else-branch (toast/snackbar "no log file"), and a try/catch (or
`ActivityOptions`-safe launch) around the chooser.

### 10 · NIT · Screen — transient state lost on configuration change

`exportError`, `showRestoreConfirm`, `restoreDone`, `restoreUri`
(SettingsScreen.kt:89, 105-107) and `advancedExpanded` (609) are plain
`remember`. A rotation (or any activity recreate) while the restore-confirm
dialog is up dismisses it and loses the picked URI — the user must re-pick
the file through the picker again; a visible error/result dialog vanishes;
the advanced section collapses.

**Fix.** `rememberSaveable` for the four dialog states (a `Uri` needs a
custom saver) and the expand flag.

---

## Not bugs (checked and deliberately left alone)

- **The font-scale preview math** (SettingsScreen.kt:187-194): under the
  nav-host's density override (`Density(density.density, fontScale = scale)`,
  McqNavHost.kt:39), `dp.toSp()` divides by the ambient fontScale, so
  `baseSize * (scale / DEFAULT)` renders at exactly `scale ×` the base size —
  the comment's claim holds.
- **Retention slider granularity**: 21 steps over 0.75..0.97 gives 0.01
  quanta, exactly matching `sanitized()`'s clamp. `fastSeconds` is the only
  row/range mismatch (#3).
- **`restoreBackup` reading `updateAnswersOnDuplicate` before applying the
  backup's settings**: deliberate and sensible — duplicates *inside the
  backup* are resolved with the user's live preference; the backup's own
  value lands afterwards with the rest of the settings.
- **Restore atomicity**: the streamed restore is one `withTransaction`
  covering wipe + import + study rows (Backup.kt:844-888), so failure or
  cancellation rolls back to the pre-restore library. The problem in #6 is
  feedback, not data.
- **`allowLeadingMinus = true` on non-negative rows** (SettingsScreen.kt:940):
  a stray `-` parses null → error state; no crash, no bad write.
- **Restore launcher MIME list** (`zip` + `octet-stream`): covers the
  DocumentsUI providers that mislabel zips.
- **Error callbacks invoked on `Dispatchers.IO`** (export/backup/restore):
  they write Compose snapshot state, which is thread-safe to write; the
  dialogs read on the main thread.
- **The `collect()` full-document backup path listing attempts** (Backup.kt:625):
  noted in #5, but the Settings screen itself uses the streaming writer.
