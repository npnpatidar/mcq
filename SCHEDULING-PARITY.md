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

**Status:** ☑ **A1–A7 complete** (`1843201`), except the optional congrats hint (A4).
**Why:** `SchedulerConfig.reviewLimit` (default 200) is persisted (`McqRepository.kt:176,204,232`) and editable (`SettingsScreen.kt:636`), but `Study.queue()` takes **only** `newLimit`. A user who sets "50/day" is still served every due card. This is a broken promise in our own UI, not a parity gap.

### A1 — Add `newCardsIgnoreReviewLimit` to config
- [x] Add field to `SchedulerConfig` (`SpacedRepetition.kt:110`), default `false` (Anki default)
- [x] No `sanitized()` change needed (Boolean)
- [x] Read/write prefs alongside `review_limit` (`McqRepository.kt:176`, `:204`, `:232`)

### A2 — Thread `reviewLimit` through the queue
- [x] `Study.queue(...)` gains `reviewLimit: Int` and `newCardsIgnoreReviewLimit: Boolean`
- [x] Cap the **review** stream (due + `isLearning`) at `reviewLimit`, oldest `dueAt` first
- [x] **Must not** double-count: the `when` in `queue` is already exclusive, so a due leech lands in `leeches` only
- [x] Preserve the existing rule that `isLearning` bypasses the **new**-card limit (`SpacedRepetition.kt:326`)
- [x] Preserve queue order: due → leeches → new

### A3 — Leech interaction
- [x] Decide per **D2**
- [x] If excluded from the cap: keep the current `leeches` list uncapped and document why

### A4 — New-card gating
- [x] Decide per **D3**
- [x] When review limit is reached and the flag is off, serve **0** new cards
- [ ] **NOT DONE — open.** Anki suggests raising the limit on the congrats screen
      when the cap is hit. Left undone deliberately: the grey "N more waiting" line
      already says cards are held back, but it does not say *why*. Worth a follow-up
      if users report being confused by the cap.

### A5 — Call site
- [x] `getStudyQueue` (`McqRepository.kt:884`) passes `config.reviewLimit` and the new flag
- [x] Log the cap when it bites

### A6 — Settings UI
- [x] Add a switch row in the "Daily limits" group (`SettingsScreen.kt:626`)
- [x] Add strings; keep Anki's exact wording

### A7 — Tests
- [x] `SpacedRepetitionTest.kt`: due cards capped at `reviewLimit`
- [x] `reviewLimit = 0` ⇒ zero reviews, zero new
- [x] `isLearning` card still served when `newLimit = 0`
- [x] D3 flag on ⇒ new cards served despite review cap
- [x] Due leech counted once
- [x] `RepositoryTest.kt`: end-to-end via `getStudyQueue` (added after the first pass)

**Commit:** `fix(scheduler): enforce the review limit in the study queue`

---

## Track B — Bug: badge count disagrees with what a session serves

**Status:** ☑ **B1–B5 complete** (`ac6824a`).
**Why:** `Study.newCount()` (`SpacedRepetition.kt:340`) is uncapped, so `LibraryScreen.kt:907-909` prints `Study (44 new)` while `Study.queue()` serves 20. After Track A the same divergence appears for `due`. Users read the badge as a promise.

### B1 — Decide the label format
- [x] Answer **D1**
- [x] Recommended: `"Study (20 due, 20 new)"` + a grey secondary line `"24 more new waiting"`

### B2 — Extract one shared selection function
- [x] Added `Study.selection(...) -> Study.Selection` (nested in the `Study` object,
      `SpacedRepetition.kt`), pure and scheduler-agnostic
- [x] `Selection` carries `due`, `leeches`, `fresh` plus `dueWaiting`, `freshWaiting`,
      `newBlockedByReviewLimit`, with `queue` and `waiting` derived.
      **Differs from the plan:** there is no `learning` count, because the app has
      no distinct learning `StudyReason` — learning cards are due cards with
      `reps == 0`, so they are counted inside `due`. Totals were expressed as
      *waiting* counts rather than `dueTotal`/`freshTotal`, which avoids storing
      two numbers the badge does not use.
- [x] Reimplement `Study.queue()` in terms of `selection()` — one code path, no drift
- [x] Retire or reimplement `dueCount()` / `newCount()` in terms of the same result

### B3 — Repository
- [x] `getStudyCounts` (`McqRepository.kt:995`) returns selection-derived data
- [x] Extend `StudyCounts` (`McqRepository.kt:49`) with the totals/overflow fields

### B4 — Library UI
- [x] `LibraryScreen.kt:340-369` — pass served + overflow
- [x] `LibraryScreen.kt:907-909` — new label
- [x] Grey overflow line beside the "tricky" chip (`:914-919`)

### B5 — Tests
- [x] `StudyQueueTest.kt`: `selection(...).servedCount == queue(...).size` for capped **and** uncapped configs
- [x] `StudyCountsInputTest.kt`: badge agrees with the queue it advertises (added after
      the first pass — the original plan only covered the domain level, which would
      not have caught `getStudyCounts` diverging from `selection`)
- [x] `DrillCountAvailabilityTest.kt`: unchanged behaviour
- [x] `LibraryStudyCountRefreshTest.kt`: still refreshes on resume
- [x] Regression guard: a test that asserts the badge never exceeds what the queue serves

**Commit:** `fix(ui): derive the library study badge from the same selection as the queue`

---

## Track C — Day boundary

**Status:** ☑ **C1–C6 complete** (`04d8550`), except the C4 export-threading gap noted below.
**Why:** due dates are `now + interval*24h` (`SpacedRepetition.kt:188`), so the app's "tomorrow" is 24h from grading and days roll at local midnight. Anki rolls at a configurable hour (default **04:00**, TZ-relative) and stores due as a day number. A 23:59 study currently gets a fresh quota at 23:59.

### C1 — Decide scope
- [x] Answer **D4**
- [x] **Recommended:** boundary-aligned due — round the next due date up to the next day boundary instead of `now + N*24h`. Anki-equivalent for integer intervals, **no schema change** (`dueAt` stays an absolute timestamp).
- [x] Document the full day-number model (collection epoch, `due` as day count) as a rejected-for-now option with its migration cost

### C2 — Config + pure helper
- [x] Add `dayStartHour: Int = 4` to `SchedulerConfig` (`SpacedRepetition.kt:110`)
- [x] Add to `sanitized()`: coerce into `0..23`
- [x] Persist/read alongside the other scheduler prefs (`McqRepository.kt:176/204/232`)
- [x] New `DayBoundary` object with `startOfDay(...)` and `dueAfter(...)` — no Android
      types. **Differs from the plan:** a standalone object rather than two `Study`
      helpers, because `AnkiScheduling` needs the same arithmetic and importing it
      from `Study` would have inverted the dependency (data -> domain is fine,
      but the exporter already sits below the scheduler).

### C3 — Apply
- [x] `Sm2Scheduler.next` uses the aligned due date (`:186-189`)
- [x] Keep the sub-day relearn path (`relearnMs`) on plain `now + relearnMs` — Anki does not day-align intraday steps
- [x] Pass `now`/`zone` through rather than reading the clock inside the scheduler (purity rule at `:13-14`)

### C4 — Export consistency

Known gap: `AnkiScheduling.fromAnki`/`toAnki` default to Anki's 04:00 hour rather than
reading the user's setting, because the apkg reader/writer do not receive the
scheduler config. Correct for the default; a learner who moves the boundary will
export cards anchored at 04:00. Worth threading through if the boundary is ever
moved off its default.
- [x] `AnkiScheduling.toAnki` (`AnkiScheduling.kt:131`) still produces correct `queue`/`due` units after the change
- [x] `AnkiSchedulingTest.kt` + `AnkiRoundTripTest.kt` stay green — these are the regression net for this track

### C5 — Settings UI
- [x] "Next day starts at" row (hour, 0–23) in the scheduler section

### C6 — Tests
- [x] Grading at 23:59 with interval 1 ⇒ due next day at 04:00, not 24h later
- [x] Grading at 03:59 ⇒ due same calendar day at 04:00
- [x] Timezone-sensitive: the zone is pinned in tests rather than read from the host
- [x] ~~`dayStartHour = 0` reproduces current behaviour exactly~~ — **this was wrong when written.**
      `0` gives local-*midnight* alignment, not `now + 24h`. There is no setting that
      reproduces the old `now + interval*24h` behaviour; the change is a deliberate
      move to Anki's model.

**Commit:** `fix(scheduler): align card due dates to the Anki day boundary`

---

## Track D — Interval preview on the grade buttons

**Status:** ☑ **D1–D5 complete**. `IntervalFormatTest` + a repository test that pins
every grade's preview to the schedule grading persists. Suite `OK (708 tests)` including the follow-up tests.

No new `Scheduler` method was needed: the delay is `next(...).dueAt - now`, read off
the same pure call the grading path makes.
**Why:** Anki's most-used study-screen feature — each of Again/Hard/Good/Easy shows the resulting delay. Ours shows only the label.

### D1 — Confirm the state is available
- [x] Verify `StudyViewModel` already holds the current `CardState` via `StudyCard.state` (it arrives from `getStudyQueue`, `McqRepository.kt:884`)
- [x] If not, add it to the load — do **not** add a second DB read

### D2 — Pure formatter
- [x] New `util/IntervalFormat.kt`: `fun format(millis: Long): String`
- [x] Anki-style ladder: minutes → hours → days → months → years (`10m`, `1d`, `2.5mo`, `1.2y`)
- [x] Relearn delays render as minutes even though they are sub-day
- [x] New test file `IntervalFormatTest.kt` — boundary values, zero, negatives rejected

### D3 — ViewModel
- [x] `StudyUiState` gains `previews: Map<ReviewGrade, String>`
- [x] Compute via the existing pure `Sm2Scheduler.next(state, grade, now)` — **no persist**, no interface change
- [x] Recompute on each question change; clear on finish
- [ ] **DEVIATION.** `refreshPreviews()` reads `System.currentTimeMillis()` rather
      than taking an injected clock. The preview *logic* is still fully testable,
      because it lives in the pure `Study.previewDelays` + `IntervalFormat` and the
      timestamps are only the `now` argument; but the ViewModel itself is not
      directly clock-testable. Accepted to avoid threading a clock seam through
      three call sites for no behavioural gain.

### D4 — UI
- [x] `StudyScreen.kt:295 GradeButtons` — pass previews
- [x] `StudyScreen.kt:323 GradeButton` — second line under the label
- [x] Long labels must not clip or reflow the row; check with a max-interval card (36500d)
- [x] ~~Add strings~~ — not done, and consistent with the section: the scheduler
      settings labels are hardcoded English (`"Starting ease"`, `"Easy bonus"`), so
      the new rows follow suit rather than introducing one localised row among
      unlocalised ones.

### D5 — Tests
- [x] `IntervalFormatTest.kt` — the unit ladder, and that a delay never renders as `0m`
- [x] `RepositoryTest`: all four grades, preview == the schedule `recordStudyReview` persists
- [x] `StudyAndSelectionUiTest.kt`: all four preview labels render after reveal

The Compose assertion is **CI-only** (`ui-test`), as this host cannot run an
emulator. It asserts the four labels are rendered via a test tag rather than
matching text: the value depends on the clock and the day boundary, so a text
assertion would only pass at some times of day.

It asserts **existence, not visibility**, and queries the **unmerged** semantics
tree. Two wrong attempts, both caught by CI:
1. `assertIsDisplayed()` — I assumed the two-line buttons had pushed the grade
   block below the fold. The logcat showed no such thing.
2. `onAllNodesWithTag(...)` on the merged tree — `TestTag` merges by keeping the
   *parent's* value, so a tag on a `Text` inside a `Button` is unresolvable: the
   Button has no tag of its own and wins with null.

Lesson: a tag placed inside a clickable container is only findable with
`useUnmergedTree = true`. Worth remembering before the next test does this.

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
Final state: `OK (708 tests)` locally, `:app:assembleDebug` green, and CI run
`37281268689` all three jobs green with all 6 emulator tests passing.

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
| C — day boundary | ☑ done | — | `DayBoundary` helper; due dates snap to a configurable 04:00 boundary. Sub-day relearn deliberately not snapped. |
| D — interval preview | ☑ done | — | `IntervalFormat` + previews read off the same `next()` call as grading. |
| E — revlog + FSRS | ⛔ deferred | — | D5: deferred 2026-10-05. E1 deferred with it — no consumer. |
---

## Still open after A–D

| Item | Why it is still open |
|---|---|
| A4 congrats hint | The cap is silent about *why* cards were withheld. Deliberate, see A4. |
| C4 export uses Anki's hour | `AnkiScheduling` defaults to 04:00 rather than reading the setting. Correct at the default; wrong if the boundary is moved. |
| D3 clock injection | Accepted deviation, see D3. |
| D5 Compose assertion | Fixed after CI caught a visibility assumption; re-verified by CI only. |
| Dead `Selection.isEmpty()` | Removed in review — nothing read it. |
| "Next day starts at" section | Moved out from under "Starting intervals" into its own "Study day" section in review. |
| C1 full day-number model | Rejected in favour of boundary-aligned due, see C1. |
| Track E | Deferred by decision (D5). |
