# MCQ App — Bugs, Misleading Help Text, and UX Issues

> Collected during the README rewrite (code inventories of Settings, Statistics, Test, Study, Library/Browse/Editor/Passages/Bookmarks/Search, Import/Export/Backup). All references point to source files under `app/src/main/java/com/mcqapp/`.

---

## Audit status (2026-11-10)

Every item was checked against the source on branch `fix/bug-docs`. `Verified`
means the code did what the item claimed; `Fixed` means it was changed in this
branch. `Not a bug` means the behaviour is deliberate, documented, or a feature
request rather than a defect.

| # | Verdict | Action |
|---|---------|--------|
| 1 | Verified | Fixed: the failure dialog reads `exportErrorTitleRes`. |
| 2 | Verified | Fixed: help now describes Simplified study auto-grading. |
| 3 | Verified | Fixed: auto-advance is skipped in practice mode; help says when it fires. |
| 4 | Verified | Fixed: help describes the per-category allocation. |
| 5 | Verified | Not changed: the two definitions are deliberate and separately documented (Coverage is row-based; Unseen is the selector's pool). |
| 6 | Verified | Fixed: same-form families use the across-attempt split-half; the per-attempt mean is only the different-form fallback. |
| 7 | Verified | Fixed: the SEM string carries its unit (`questions`). |
| 8 | Verified | Fixed: corrupt ratings are skipped by both charts. |
| 9 | Verified | Not changed: History never had delete; this is a missing feature, not a defect. |
| 10 | Verified | Fixed: help says due now. |
| 11 | Verified | Fixed: help names the no-card / NEW / stale-hash rule. |
| 12 | Verified | Fixed: help names all four fresh-sample types. |
| 13 | Verified | Fixed: the target-retention percentage is shown and follows the thumb. |
| 14 | Verified | Fixed: Reset asks first and the dialog names everything it resets. |
| 15 | Verified | Fixed: help lists categories, questions, passages and schedules, and says settings/log are only in the zip. |
| 16 | Verified | Fixed: the JSON help line sits under the JSON button. |
| 17 | Verified | Fixed: full stop added. |
| 18 | Verified | Fixed: `-wal` and `-shm` are included in the database figure. |
| 19 | Verified | Fixed: Reset and Restore defaults both ask for confirmation. |
| 20 | Verified | Fixed: Export Logs is disabled while busy. |
| 21 | Verified | Not changed: silent success is a UX gap, not wrong behaviour; no completion feedback was added. |
| 22 | Verified | Fixed: deleting a category asks for confirmation. |
| 23 | Verified | Fixed: the comparability line says `fresh sample if enough others remain`. |
| 24 | Verified | Fixed: the editor preview uses `BlueprintQuotas.allocate`, the same allocation Start uses. |
| 25 | Verified | Fixed: Balanced and Blueprint show a mock banner, not `Practice`. |
| 26 | Verified | Fixed: a skipped review is labelled and coloured `Skipped`. |
| 27 | Verified | Fixed for the listed strings in `ResultsScreen` and the Browse filter; the bookmark strings already used resources. |
| 28 | Verified | Not changed: leaving without submitting is a missing feature. |
| 29 | Verified | Not changed: countdown/background policy is a design decision. |
| 30 | Verified | Not a bug: the 60 s floor is documented in `ReviewFilters`. |
| 31 | Verified | Fixed: the Attempts header always renders. |
| 32 | Verified | Not changed: auto-select on import and same-name replace are design choices. |
| 33 | Verified | Not changed: deleting an unselected theme is a missing affordance. |
| 34 | Verified | Not a bug in practice: the hash is a pre-filter and `sameQuestionContent` is authoritative, so a table/formula-only duplicate is not dropped. Only a table/formula-only edit fails to reset the card. |
| 35 | Verified | Not changed: stale-until-Refresh is accepted behaviour. |
| 36 | Verified | Fixed: comment says eight tabs. |
| 37 | Verified | Fixed: the `formatDuration` comment matches the pinned output. |
| 38 | Verified | Not a bug: legend placement is a design choice. |
| 39 | Verified | Fixed: label is the final tie-break. |
| 40 | Verified | Not a bug: the same-day band is documented as deliberate in `bandOf`. |
| 41 | Verified | Not changed: wording nit; the row shows the configured value. |
| 42 | Verified | Not a bug: exclusions are documented and the guard string is shown. |
| 43 | Verified | Not a bug: `stats_forecast_help` already explains the overdue start. |
| 44 | Verified | Not a bug: due-this-week is a superset by design. |
| 45 | Verified | Not a bug: Monday-first mapping is correct. |

Verification: `:app:compileDebugKotlin`, `:app:compileDebugUnitTestKotlin`,
and the JVM tests `AutoAdvanceTest`, `BlueprintQuotasTest`, `StatsChartKitTest`,
`ReliabilityTest`, `StatsAggregatorTest`, `StorageInfoTest` and
`WorkloadAdditionsTest` (including the new corrupt-rating regression) pass on
the NixOS arm64 host. Robolectric/SQLite tests remain CI-only there.

---

## 🔴 Critical Bugs (Wrong Behaviour)

### 1. Failure dialog always titled "Export failed"
**Files:** `ui/settings/SettingsScreen.kt` (~line 328), `ui/settings/SettingsRows.kt`
- `SettingsScreen` tracks intended title in `exportErrorTitleRes` (assigned in 5 places: export, backup, restore, share, theme import, export-logs).
- Dialog renders `stringResource(R.string.export_failed)` **unconditionally**.
- A `Backup failed`, `Restore failed`, `Theme import failed`, or `Export Logs` failure appears under the wrong heading.
- `exportErrorTitleRes` is written and never read (dead state).

### 2. Stale help text: "Response-time thresholds don't apply to Study"
**Files:** `res/values/strings.xml` (`anki_thresholds_help`), `ui/settings/StudySchedulerSection.kt`
- Help: `The response-time thresholds only apply when rebuilding a schedule from past test attempts. In Study you grade every card yourself.`
- **Reality:** The only consumer of `anki_fast_seconds` / `anki_slow_seconds` is `Study.autoGrade()` running inside **Simplified study** (default **ON**).
- There is no "rebuild a schedule from past attempts" path left (`StudyStore` explicitly stopped re-seeding from attempt history).
- With Simplified study on, changing these two numbers silently changes how Study grades every card.

### 3. Auto-advance advances on selection, not "after answering"
**Files:** `domain/AutoAdvance.kt`, `ui/test/TestViewModel.kt`, `res/values/strings.xml` (`auto_advance_help`)
- Help: `Move to the next question after answering (single-answer only)`
- Code: `AutoAdvance.nextIndex()` is invoked from `TestViewModel.toggleOption()` — advances **the instant an option is tapped**, before any reveal/confirmation.
- A declared guess still advances.
- Only single-answer questions; never on last question.

### 4. Balanced help says "mix difficulty bands"; code allocates by category size
**Files:** `domain/signals/TestSelector.kt`, `res/values/strings.xml` (`test_type_balanced_help`)
- Help: `Mix difficulty bands so easy, medium and hard questions all appear.`
- Code: allocates requested count **proportionally per category** (largest remainder, min 1 where possible), then round-robin interleaves categories.
- Difficulty bands are not used anywhere in the Balanced selector.

### 5. Two different "unseen" definitions in the same app
**Files:** `domain/stats/CoverageStats.kt`, `domain/TestProgress.kt`, `data/repository/McqRepository.kt`
| Location | Definition |
|----------|------------|
| **Cards tab (Coverage)** | `studied` = any `card_state` row (even `NEW`). `unseen = max(questions − studied, 0)`. |
| **Tests tab (Current scope)** | `unseen` = questions with **no** `card_state` row that both matches current `contentHash` **and** is not `NEW`. |
- A `NEW` card counts as **studied** for Coverage but **unseen** for the Tests scope counter.
- The README documents both; the app should pick one definition.

### 6. Reliability uses wrong estimator (displayed `r` is mean of within-attempt split-half)
**Files:** `domain/stats/Reliability.kt`, `domain/stats/ExamStats.kt`, `ui/stats/sections/TestsSection.kt`
- `Reliability.splitHalfAcrossAttempts()` implements the across-attempt estimator and is documented as "the estimator comparable to kr20".
- **It is never called** from the stats path.
- Displayed `r` = mean of per-attempt split-half (odd vs even index in the order asked) → Spearman–Brown `2r/(1+r)`.
- The across-attempt estimator exists but is dead code on the Statistics screen.

### 7. SEM printed on raw question-count scale with no unit
**Files:** `domain/stats/ExamStats.kt`, `ui/stats/sections/TestsSection.kt`
- `SEM` = `SD(total scores) × √(1 − r)` where SD is population SD of attempts' total scores (0..n).
- Shown as `SEM %2$.1f` (e.g. "SEM 4.0") on the same line as `r = 0.58`.
- **No unit** — reads like percentage points but is questions.
- Suppressed when `r ≤ 0` or SD not finite.

### 8. Grade mix drops corrupt ratings; Answer buttons folds them into Easy
**Files:** `domain/stats/StatsAggregator.kt` (`DayBucket.add` vs `countAnswerButtons`)
- `DayBucket.add`: `rating !in 1..4` → **skipped** (not counted in any segment).
- `countAnswerButtons`: same corrupt ratings → **counted as Easy**.
- The two cards (Grade mix over time vs Answer buttons) can disagree by one row.

### 9. No attempt delete in History
**Files:** `ui/history/HistoryScreen.kt`, `ui/history/HistoryViewModel.kt`
- History has no delete/clear control and no confirmation for attempts.
- Deleting a paper, category, question, panel, or blueprint **has** a confirm dialog.
- Asymmetry looks accidental; the brief requested "delete confirmation" for attempts.

---

## 🟠 Misleading Help Text (Strings Should Be Fixed)

### 10. "At risk: due or nearly due" — only due cards selected
**File:** `res/values/strings.xml` (`test_type_at_risk_help`)
- Help: `Questions whose memory is due or nearly due, before they are forgotten.`
- Code (`TestSelector.kt`): `atRisk` = `dueAt <= now`. A card one day early is **not** included.
- "Nearly due" is not implemented.

### 11. "Unseen: never answered" — code means no card / NEW / stale hash
**File:** `res/values/strings.xml` (`test_type_unseen_help`)
- Help: `Questions you have never answered in this scope.`
- Code: `unseen` = no `card_state` row **OR** row is `NEW` **OR** `contentHash` stale (question edited since card built).
- A question can be "unseen" after being answered if its content hash changed.

### 12. "Avoid recently tested questions" names only Random/Balanced
**File:** `res/values/strings.xml` (`avoid_recent_questions_help`)
- Help: `A Random or Balanced test skips questions asked in your last three comparable tests...`
- Code (`TestType.supportsFreshSample`): also covers `EASIEST_QUESTIONS` and `BLUEPRINT`.

### 13. Target retention slider shows no numeric value
**File:** `ui/settings/StudySchedulerSection.kt`
- Slider range 0.75–0.97, 21 steps, default 0.90.
- **No label, no `%`, no "90%" text** — learner infers value only from thumb position.
- Every other scheduler number is displayed exactly.

### 14. "Reset" in Study section resets more than implied
**Files:** `ui/settings/StudySchedulerSection.kt`, `data/repository/RepositorySettings.kt`
- Visually attached to `Spaced repetition` heading.
- Actually resets: max interval, relearn delay, leech threshold, day-start hour, easy threshold, hard threshold, **target retention, ranking mode, introduce-new-cards**.
- Silently leaves fitted FSRS weights alone (correct, but undocumented in UI).

### 15. JSON export help understates contents
**File:** `res/values/strings.xml` (`export_all_help`)
- Help: `Backs up every paper, bookmark and attempt to one JSON file.`
- File also carries: categories, questions, passages, **`scheduling` map** (schedules/interval state).
- Carries **no settings and no review-log history** (only Backup zip has those).

### 16. Help line for "Export all data" sits under "Export Logs"
**File:** `ui/settings/BackupDataSection.kt` (render order)
- The description for `Export all data (JSON)` is placed under the `Export Logs` button.

### 17. Hard answer threshold help missing full stop
**File:** `res/values/strings.xml` (`anki_slow_seconds_help`)
- `...above this response time` (no period) while every sibling help string has one.

### 18. Storage "Database:" figure excludes WAL
**File:** `ui/settings/BackupDataSection.kt`, `domain/StorageInfo.kt`
- Uses `context.getDatabasePath("mcq.db").length()` — **main DB file only**.
- `-wal`/`-shm` not counted; label reads as total DB size.

---

## 🟡 UX Issues (Design Trade-offs Worth Discussing)

### 19. Reset / Restore defaults unguarded
**Files:** `ui/settings/StudySchedulerSection.kt` (`Reset`), `ui/settings/AppearanceSection.kt` (`Restore defaults` in optimizer)
- Both destructive (pacing knobs / fitted FSRS weights).
- Both act immediately with **no confirmation dialog and no feedback**.
- `Restore backup` by contrast double-checks with `Replace everything`.

### 20. Export Logs not disabled while busy
**File:** `ui/settings/BackupDataSection.kt`
- 5 data buttons gated by `!busy`; `Export Logs` is **always enabled**.
- A share can start mid-export/backup/restore.

### 21. Success is silent for export/backup/share
**File:** `ui/settings/SettingsScreen.kt`, `ui/settings/SettingsRows.kt`
- Only restore reports back (dialog with summary).
- Export/backup/share give no completion signal other than button flicker.
- Cancelled picker gives none either.

### 22. Delete category immediate, no confirmation
**File:** `ui/library/LibraryPaperCard.kt` (`ManageCategoryRow` menu)
- Red menu item behind divider.
- One tap deletes category **and all its questions**.
- No confirm dialog (other deletes have confirms).

### 23. Fresh-sample note can be a promise the attempt doesn't keep
**Files:** `ui/library/TestSetupSheet.kt`, `domain/TestPlan.kt`
- Note shown from setting + type (Random, Balanced, Easiest, Blueprint).
- At session load: exclusion applied **only if** whole passage blocks removed **and** remaining pool ≥ 2× request.
- If not applied, attempt records `exclusionMode = NONE` (family = Mock) even though note was shown.

### 24. Blueprint editor shows rounded share, not real quota
**File:** `ui/library/BlueprintEditorSheet.kt`
- Share line: `round(globalShare × length)`.
- Real allocation (Start): recursive floors, remainders, caps, steal-one.
- Can differ by ≥ 1; sheet preview rows use real allocation.

### 25. Session banner calls Balanced/Blueprint "Practice"
**Files:** `ui/test/TestSessionScreen.kt`, `ui/results/ResultsScreen.kt`
- Any non-Random type gets banner `Practice · <Type> · N questions`.
- Results calls same attempt `Mock` (for Balanced/Blueprint).
- Template choice, not a claim about comparability, but confusing.

### 26. Skipped graded question labelled "Wrong" on review card
**Files:** `ui/results/ResultsScreen.kt` (per-question card), `domain/Scoring.kt`
- Header counts as `Skipped`; `Skipped` filter selects it.
- Per-question card status word: `Wrong` (literal, not resource).
- Container colour: red (wrong).

### 27. Several visible strings hard-coded (not in resources)
**Files:** Multiple
| String | Location |
|--------|----------|
| `Results` / `Attempt review` | `ResultsScreen.kt` (screen title) |
| `Correct` / `Wrong` / `Skipped` | `ResultsScreen.kt` (stat labels) |
| `Not scored` / `Correct` / `Wrong` | `ResultsScreen.kt` (per-question status) |
| `Time spent:` | `ResultsScreen.kt` |
| `Bookmark question` / `Remove bookmark` | `ResultsScreen.kt`, `ui/browse/BrowseQuestionCard.kt`, `ui/bookmarks/BookmarksScreen.kt` |
| `Uncategorized` | `ui/browse/BrowseScreen.kt` |

### 28. No way to leave a live sitting without submitting
**File:** `ui/test/TestSessionScreen.kt` (BackHandler)
- Top-bar back arrow opens submit dialog; `Cancel` returns to question.
- Killing app = only "abandon" (leaves resumable snapshot).

### 29. Countdown never pauses, but dwell clock does
**Files:** `domain/ExamTiming.kt`, `domain/TestTiming.kt`, `ui/test/TestSessionScreen.kt`
- Auto-submit can fire while submit dialog, palette, or answer review is open.
- Background time still counts against timed paper.

### 30. Slow filter threshold hides everything on fast paper
**File:** `domain/ReviewFilters.kt`, `ui/results/ResultsScreen.kt`
- `Slow` = `dwell >= max(60 s, 2 × median)`.
- Median 10 s still requires 60 s; a question slower than median but under a minute is not "Slow".

### 31. Attempts header disappears when every attempt is legacy
**File:** `ui/history/HistoryScreen.kt`
- `Attempts` header nested under `if (trends.isNotEmpty())`.
- If all attempts are legacy, no trends → no header → attempt list appears without heading.

### 32. Imported themes auto-selected, same-name silently replaces
**Files:** `ui/settings/AppearanceSection.kt`, `ui/theme/CustomThemeCodec.kt`
- Successful import selects the theme (writes `theme_palette`).
- No success toast/dialog.
- Same-name import (case-insensitive) **replaces** existing entry instead of adding.

### 33. Remove this theme only for currently selected custom theme
**File:** `ui/settings/AppearanceSection.kt`
- Cannot delete a custom theme that is not selected without selecting it first (which changes the app's look).

### 34. Content hash excludes tables and MathML formulas
**Files:** `data/io/ContentHash.kt`, `data/io/Importer.kt`
- Hash covers stem textContent, all options, explanation, image positions.
- **Tables and MathML invisible to hash** — hash collision triggers full `sameQuestionContent` comparison, but only on collision.
- Two questions differing only in a table/formula share a hash.

### 35. Measuring... is a one-shot read
**File:** `ui/settings/BackupDataSection.kt`, `ui/settings/SettingsViewModel.kt`
- Report loaded in ViewModel constructor; only re-read on `Refresh` or after successful restore.
- Importing a paper elsewhere leaves numbers stale until `Refresh` tapped.

---

## 🟢 Minor / Cosmetic

### 36. StatsScreen doc comment says "six tabs"; there are eight
**File:** `ui/stats/StatsScreen.kt:54`

### 37. formatDuration doc comment wrong on all three examples
**File:** `ui/stats/charts/ChartKit.kt:184-198`
- Claims: `59 → "59s"`, `90 → "1.5m"`, `5400 → "1h 30m"`
- Actual: `"59 s"` (space), `"1m"` (integer division), `"1 h 30 m"` (spaces).

### 38. Test result families legend lives on Overview tab, not Tests
**File:** `ui/stats/sections/OverviewSection.kt`

### 39. Group retention has no label tie-break
**File:** `domain/stats/StatsAggregator.kt:892`
- `compareBy(retention, -reviews)` while tag/category use `compareBy(retention, -reviews, label)`.
- Equal-retention papers can swap order between builds.

### 40. Same-day relearning steps land in 1–7 d interval band
**File:** `domain/stats/StatsAggregator.kt:736-742`
- `days <= 7` catches 0 days → mixes genuine 1–7-day lapses with same-day repeats.

### 41. Leech threshold configurable (1..100) but strings don't name the number
**Files:** `domain/SpacedRepetition.kt:163,200`, `res/values/strings.xml`
- Default 8 (Anki default). Strings say "repeated forgetting across the whole history" without naming 8.

### 42. Calibration excludes first review & learning steps; sample can be zero on busy account
**File:** `domain/stats/StatsAggregator.kt:801-802, 878`
- First review excluded (no state to predict from); learning steps excluded.
- Guard text: `Calibration needs reviews of cards that already had a memory state.`

### 43. Forecast cumulative line starts from overdue count — first point above first bar
**File:** `domain/stats/StatsAggregator.kt:1419-1432`
- Correct but visually surprising; worth a sentence in docs.

### 44. dueThisWeek includes today and tomorrow (superset, not disjoint)
**File:** `domain/stats/StatsAggregator.kt:1420`

### 45. Weekday retention uses `floorMod(studyEpochDay + 3, 7)` → 0 = Monday
**File:** `domain/stats/StatsAggregator.kt:878`
- Not documented in UI; user sees Mon–Sun but mapping is implicit.

---

## Suggested Fix Priority

| Priority | Items |
|----------|-------|
| **P0 (bugs)** | 1, 2, 3, 4, 5, 6, 7, 8, 9 |
| **P1 (misleading help)** | 10, 11, 12, 13, 14, 15, 16, 17, 18 |
| **P2 (UX)** | 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35 |
| **P3 (minor)** | 36, 37, 38, 39, 40, 41, 42, 43, 44, 45 |

---

## How to Use This List

1. **Pick a batch** (e.g. P0 + P1) and create a feature branch `fix/bug-batch-1`.
2. **Each fix** = one commit with a clear message referencing the bug number.
3. **Update strings** in `res/values/strings.xml` where help text is wrong.
4. **Add tests** where behaviour changes (e.g. AutoAdvance timing, Balanced selector, Reliability estimator).
5. **Open draft PR**; owner decides merge order.

---

*Generated 2026-11-10 from exhaustive code inventories. All file references are to `app/src/main/java/com/mcqapp/` unless noted.*