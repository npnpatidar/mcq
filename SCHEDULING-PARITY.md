# Scheduling Parity — Tracked Work Plan

Goal: make this app's **spaced-repetition layer** correct and Anki-consistent.
Explicitly **not** a feature-parity clone of Anki. See "Deliberately dropped" at the end.

- Baseline: `master` @ `794edeb`, v0.0.6, 658 JVM tests green
- Anki reference: desktop **26.09.3**, scheduler v3
- Rules: **one fix per commit**; no commit mixes tracks; CI must stay green
- Verify JVM with `sh /tmp/run-qemu-tests.sh`; `androidTest` only via CI `ui-test`

---

## Decisions (all answered 2026-10-05)

| # | Question | Decision | Blocks |
|---|---|---|---|
| ~~**D1**~~ | Badge: total unseen or session-serving count? | **Served count + grey overflow line** ("20 new" / "24 more waiting"), Anki's buried-count idiom | closed |
| ~~**D2**~~ | Do "tricky" (leech) cards count against the review limit? | **No — excluded.** Explicit drill, not backlog | closed |
| ~~**D3**~~ | Block new cards when the review limit is reached? | **Yes — Anki v3 default.** Add a "New cards ignore review limit" switch to opt out | closed |
| ~~**D4**~~ | Day boundary model? | **Align due to a configurable 04:00 boundary.** No schema change | closed |
| ~~**D5**~~ | Is FSRS wanted at all? | **Deferred.** Track E out of scope | closed |

---

## Track A — Bug: `reviewLimit` is never enforced

**Status:** ☑ **A1–A7 complete** (uncommitted). 13 tests added, suite `OK (671 tests)`.
**Why:** `SchedulerConfig.reviewLimit` (default 200) is persisted (`McqRepository.kt:176,204,232`) and editable (`SettingsScreen.kt:636`), but `Study.queue()` takes **only** `newLimit`. A user who sets "50/day" is still served every due card. This is a broken promise in our own UI, not a parity gap.

### A1 — Add `newCardsIgnoreReviewLimit` to config
- [ ] Add field to `SchedulerConfig` (`SpacedRepetition.kt:110`), default `false` (Anki default)
- [ ] No `sanitized()` change needed (Boolean)
- [ ] Read/write prefs alongside `review_limit` (`McqRepository.kt:176`, `:204`, `:232`)

### A2 — Thread `reviewLimit` through the queue
- [ ] `Study.queue(...)` gains `reviewLimit: Int` and `newCardsIgnoreReviewLimit: Boolean`
- [ ] Cap the **review** stream (due + `isLearning`) at `reviewLimit`, oldest `dueAt` first
- [ ] **Must not** double-count: the `when` in `queue` is already exclusive, so a due leech lands in `leeches` only
- [ ] Preserve the existing rule that `isLearning` bypasses the **new**-card limit (`SpacedRepetition.kt:326`)
- [ ] Preserve queue order: due → leeches → new

### A3 — Leech interaction
- [ ] Decide per **D2**
- [ ] If excluded from the cap: keep the current `leeches` list uncapped and document why

### A4 — New-card gating
- [ ] Decide per **D3**
- [ ] When review limit is reached and the flag is off, serve **0** new cards
- [ ] Optional: surface a hint (Anki suggests raising the limit on the congrats screen)

### A5 — Call site
- [ ] `getStudyQueue` (`McqRepository.kt:884`) passes `config.reviewLimit` and the new flag
- [ ] Log the cap when it bites

### A6 — Settings UI
- [ ] Add a switch row in the "Daily limits" group (`SettingsScreen.kt:626`)
- [ ] Add strings; keep Anki's exact wording

### A7 — Tests
- [ ] `SpacedRepetitionTest.kt`: due cards capped at `reviewLimit`
- [ ] `reviewLimit = 0` ⇒ zero reviews, zero new
- [ ] `isLearning` card still served when `newLimit = 0`
- [ ] D3 flag on ⇒ new cards served despite review cap
- [ ] Due leech counted once
- [ ] `RepositoryTest.kt`: end-to-end via `getStudyQueue`

**Commit:** `fix(scheduler): enforce the review limit in the study queue`

---

## Track B — Bug: badge count disagrees with what a session serves

**Status:** ☑ **B1–B5 complete**. 6 tests added, suite `OK (677 tests)`.
**Why:** `Study.newCount()` (`SpacedRepetition.kt:340`) is uncapped, so `LibraryScreen.kt:907-909` prints `Study (44 new)` while `Study.queue()` serves 20. After Track A the same divergence appears for `due`. Users read the badge as a promise.

### B1 — Decide the label format
- [ ] Answer **D1**
- [ ] Recommended: `"Study (20 due, 20 new)"` + a grey secondary line `"24 more new waiting"`

### B2 — Extract one shared selection function
- [ ] Add `Study.selection(...) -> StudySelection` (`SpacedRepetition.kt`), pure and scheduler-agnostic
- [ ] `StudySelection` carries both served counts **and** totals: `due`, `learning`, `leeches`, `fresh` + `dueTotal`, `freshTotal`
- [ ] Reimplement `Study.queue()` in terms of `selection()` — one code path, no drift
- [ ] Retire or reimplement `dueCount()` / `newCount()` in terms of the same result

### B3 — Repository
- [ ] `getStudyCounts` (`McqRepository.kt:995`) returns selection-derived data
- [ ] Extend `StudyCounts` (`McqRepository.kt:49`) with the totals/overflow fields

### B4 — Library UI
- [ ] `LibraryScreen.kt:340-369` — pass served + overflow
- [ ] `LibraryScreen.kt:907-909` — new label
- [ ] Grey overflow line beside the "tricky" chip (`:914-919`)

### B5 — Tests
- [ ] `StudyQueueTest.kt`: `selection(...).servedCount == queue(...).size` for capped **and** uncapped configs
- [ ] `StudyCountsInputTest.kt`: badge reflects limits
- [ ] `DrillCountAvailabilityTest.kt`: unchanged behaviour
- [ ] `LibraryStudyCountRefreshTest.kt`: still refreshes on resume
- [ ] Regression guard: a test that asserts the badge never exceeds what the queue serves

**Commit:** `fix(ui): derive the library study badge from the same selection as the queue`

---

## Track C — Day boundary

**Status:** ☐ not started
**Why:** due dates are `now + interval*24h` (`SpacedRepetition.kt:188`), so the app's "tomorrow" is 24h from grading and days roll at local midnight. Anki rolls at a configurable hour (default **04:00**, TZ-relative) and stores due as a day number. A 23:59 study currently gets a fresh quota at 23:59.

### C1 — Decide scope
- [ ] Answer **D4**
- [ ] **Recommended:** boundary-aligned due — round the next due date up to the next day boundary instead of `now + N*24h`. Anki-equivalent for integer intervals, **no schema change** (`dueAt` stays an absolute timestamp).
- [ ] Document the full day-number model (collection epoch, `due` as day count) as a rejected-for-now option with its migration cost

### C2 — Config + pure helper
- [ ] Add `dayStartHour: Int = 4` to `SchedulerConfig` (`SpacedRepetition.kt:110`)
- [ ] Add to `sanitized()`: coerce into `0..23`
- [ ] Persist/read alongside the other scheduler prefs (`McqRepository.kt:176/204/232`)
- [ ] New pure helper `Study.startOfDay(nowMillis, dayStartHour, zone)` — no Android types, matching the file's existing purity rule
- [ ] New pure helper `Study.nextDueFrom(now, intervalDays, dayStartHour, zone)`

### C3 — Apply
- [ ] `Sm2Scheduler.next` uses the aligned due date (`:186-189`)
- [ ] Keep the sub-day relearn path (`relearnMs`) on plain `now + relearnMs` — Anki does not day-align intraday steps
- [ ] Pass `now`/`zone` through rather than reading the clock inside the scheduler (purity rule at `:13-14`)

### C4 — Export consistency
- [ ] `AnkiScheduling.toAnki` (`AnkiScheduling.kt:131`) still produces correct `queue`/`due` units after the change
- [ ] `AnkiSchedulingTest.kt` + `AnkiRoundTripTest.kt` stay green — these are the regression net for this track

### C5 — Settings UI
- [ ] "Next day starts at" row (hour, 0–23) in the scheduler section

### C6 — Tests
- [ ] Grading at 23:59 with interval 1 ⇒ due next day at 04:00, not 24h later
- [ ] Grading at 03:59 ⇒ due same calendar day at 04:00
- [ ] `dayStartHour = 0` reproduces current behaviour exactly
- [ ] Timezone-sensitive: pin the zone in tests, do not rely on the host default

**Commit:** `fix(scheduler): align card due dates to the Anki day boundary`

---

## Track D — Interval preview on the grade buttons

**Status:** ☐ not started
**Why:** Anki's most-used study-screen feature — each of Again/Hard/Good/Easy shows the resulting delay. Ours shows only the label.

### D1 — Confirm the state is available
- [ ] Verify `StudyViewModel` already holds the current `CardState` via `StudyCard.state` (it arrives from `getStudyQueue`, `McqRepository.kt:884`)
- [ ] If not, add it to the load — do **not** add a second DB read

### D2 — Pure formatter
- [ ] New `util/IntervalFormat.kt`: `fun format(millis: Long): String`
- [ ] Anki-style ladder: minutes → hours → days → months → years (`10m`, `1d`, `2.5mo`, `1.2y`)
- [ ] Relearn delays render as minutes even though they are sub-day
- [ ] New test file `IntervalFormatTest.kt` — boundary values, zero, negatives rejected

### D3 — ViewModel
- [ ] `StudyUiState` gains `previews: Map<ReviewGrade, String>`
- [ ] Compute via the existing pure `Sm2Scheduler.next(state, grade, now)` — **no persist**, no interface change
- [ ] Recompute on each question change; clear on finish
- [ ] Timestamp must be injected, not read from the clock in the ViewModel (testability)

### D4 — UI
- [ ] `StudyScreen.kt:295 GradeButtons` — pass previews
- [ ] `StudyScreen.kt:323 GradeButton` — second line under the label
- [ ] Long labels must not clip or reflow the row; check with a max-interval card (36500d)
- [ ] Add strings

### D5 — Tests
- [ ] `IntervalFormatTest.kt`
- [ ] `StudyViewModelTest`-style check: preview for grade G equals the interval later persisted by `recordStudyReview`
- [ ] `StudyAndSelectionUiTest.kt` (androidTest, CI only): all four previews visible after reveal

**Commit:** `feat(study): preview the next interval on each grade button`

---

## Track E — DEFERRED: review log, then FSRS

**Status:** ⛔ **Deferred 2026-10-05 (D5: "defer FSRS for now"). Out of scope.**
**Do not start** without a new explicit go-ahead.

Note that E1 (the `review_log` table) is deferred **with** it. Its only two consumers
were Card Info and the Answer Buttons graph, and both are on the dropped list below.
A Room migration with no reader is pure cost, so it is not worth landing now. Revisit
E1 only when something actually needs the history.

The `Scheduler` interface (`SpacedRepetition.kt:70`) stays as-is — it was designed for
the swap (`:12`) and costs nothing to leave ready.

### E1 — `review_log` table (deferred)
- [ ] Schema: `id, paperId, questionId, grade, intervalPrev, intervalNext, easeNext, timeMs, reviewedAt, type`
- [ ] New Room migration (`exportSchema = false`, manual migrations — see `AppDatabase.kt`)
- [ ] Write one row per grade inside `recordStudyReview` (`McqRepository.kt:1019`), in the same transaction as the `card_state` upsert
- [ ] Backfill: **none**. History starts fresh — same trade-off Anki makes when switching to FSRS
- [ ] `Study.inferGrade` currently *re-infers* grades from `question_results.dwellSeconds`; note that real grades now exist and the re-inference is legacy-only

### E2 — What E1 unblocks
- [ ] Card Info (per-review interval/ease history)
- [ ] Answer Buttons statistics graph
- [ ] Historical Retention / Ignore Cards Reviewed Before

### E3 — FSRS (only after E1)
- [ ] Implement `Scheduler` (`SpacedRepetition.kt:70`) — the interface was designed for this swap (`:12`)
- [ ] Source: `fsrs-rs` or the documented FSRS-6 formulas
- [ ] Desired retention per deck; optimiser needs real review history, so it is E1-gated
- [ ] Reschedule-on-change semantics
- [ ] Keep SM-2 available; do not remove it in the same change

**Commits:** `feat(db): persist a review log` then `feat(scheduler): add FSRS`

---

## Verification matrix

| Track | JVM (`/tmp/run-qemu-tests.sh`) | androidTest (CI only) | Extra |
|---|---|---|---|
| A | `SpacedRepetitionTest`, `RepositoryTest`, `StudyQueueTest` | — | — |
| B | `StudyQueueTest`, `StudyCountsInputTest`, `DrillCountAvailabilityTest`, `LibraryStudyCountRefreshTest` | `StudyAndSelectionUiTest` | — |
| C | `SpacedRepetitionTest`, `AnkiSchedulingTest`, `AnkiRoundTripTest`, `BackupSchedulingTest` | — | pin TZ in tests |
| D | `IntervalFormatTest`, `SpacedRepetitionTest` | `StudyAndSelectionUiTest` | — |
| E | `MigrationTest`, `BackupRestoreTest`, `BackupSchedulingTest` | — | check export round-trip |

Baseline before starting: `sh /tmp/run-qemu-tests.sh` must report `OK (658 tests)`.

---

## Deliberately dropped — do not let these creep back

| Dropped | Why |
|---|---|
| Sync / AnkiWeb | Multi-client, multi-year. Also licence-encumbered for non-AnkiDroid apps. |
| Statistics graphs | Needs E1. Not a scheduling-correctness issue. |
| Card browser bulk actions, saved searches | Anki-as-a-platform surface area. |
| Filtered decks / custom study | Different product. |
| Per-deck presets | Would require re-modelling papers as decks. |
| Flags / suspend / bury | Nice-to-have, no effect on scheduling correctness. |
| Custom scheduling JS | Desktop-only in Anki; no mobile equivalent. |
| Full Anki day-number model | Costly migration; C's aligned-due gets ~95% of the behaviour. |
| Image Occlusion, cloze authoring, template styling | Different product. |

---

## Progress log

Update this table in the same commit as the work.

| Track | Status | Commit | Notes |
|---|---|---|---|
| A — review limit | ☑ done, uncommitted | — | 13 new tests, `OK (671 tests)`. D2/D3 applied: leeches uncapped, new cards blocked at the cap, opt-out switch added. |
| B — badge parity | ☑ done | — | `Study.selection()` is now the single source for both badge and queue; grey "N more waiting" line added. |
| C — day boundary | ☐ | | |
| D — interval preview | ☐ | | |
| E — revlog + FSRS | ⛔ deferred | — | D5: deferred 2026-10-05. E1 deferred with it — no consumer. |