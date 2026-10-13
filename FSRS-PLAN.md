# FSRS Pull-Based Spaced Repetition — Tracked Work Plan

> **Status: implemented.** All six phases shipped on `exp/fsrs-scheduling` and merged
> through PR #2 — FSRS-6 engine, schema v10 with `review_log`, pull-based candidate
> pools, the Study UX and settings, and FSRS-aware backup/Anki interop. CI green
> throughout; the phase sections below are the record of what was built.

Pressure-free, adaptive spaced-repetition engine for this app: FSRS-6 scheduling,
immutable review history, dynamically ranked candidate pools, user-controlled
sessions. No daily quotas, no session caps, no forced backlog clearing, no
guilt messaging.

- Baseline: `main` @ current HEAD (v10 schema work starts from DB version 9)
- Supersedes the previous scheduling-parity plan (Track E deferred exactly this work)
- Rules: one fix per commit; no commit mixes tracks; CI must stay green
- Verify JVM locally with the AGENTS.md gradle command; Robolectric DB tests are
  CI-authoritative (native SQLite unsupported on this ARM64 host)

---

## Decisions (confirmed)

| # | Question | Decision |
|---|---|---|
| 1 | FSRS source | **In-house pure-Kotlin FSRS-6 port** in `domain/`, faithful to official formulas, validated against official reference vectors. No new dependencies. (GPL-3.0 `FsrsKt` rejected: conflicts with the app's Apache-2.0 license. MIT `FSRS-Kotlin` copy rejected: not on Maven, 11 open correctness defects.) |
| 2 | Existing SM-2 schedules | **Reset to new.** Migration wipes `card_state`; attempt-history auto-seeding is removed (it would resurrect schedules). Explicit JSON/Anki import still restores what it carries. |
| 3 | Daily limits | **Removed.** No `newLimit`/`reviewLimit` enforcement, no new-blocked-by-review cap. Replaced by ranking mode + "introduce new cards" toggle. |
| 4 | Anki interop | **Derive SM-2 fields on export.** Keep the .apkg/DTO shape; derive `ivl`/`factor` from FSRS on export, convert Anki schedules → FSRS state on import. JSON backup gains lossless FSRS fields; legacy backups import as new. |
| 5 | Optimizer | **Deferred.** Fixed FSRS-6 default params + configurable desired retention (default 0.9). Revisit once `review_log` has volume (spec §10: only with sufficient review history). |

---

## Core product principle

Pull-based and adaptive: the user requests the next card whenever they want;
the scheduler selects the best card from eligible candidates ranked by memory
state, predicted forgetting risk, and learning value. A missed due date is a
scheduling signal, not an obligation.

---

## Phase 1 — FSRS-6 engine (domain, pure Kotlin)

**New `domain/Fsrs.kt`** (or extend `SpacedRepetition.kt`): official FSRS-6
math — 21 default parameters, initial stability/difficulty, same-day
short-term memory, stability/difficulty updates per rating, retrievability
`R(t, S)`, interval `I = S·(R^(−1/decay) − 1)/factor` clamped 1..36500 days.
No clock, no Android types, no randomness (no fuzz).

**Extend `CardState`**:
- New fields: `difficulty: Double`, `stability: Double` (days),
  `state: CardStateKind` (`NEW` / `LEARNING` / `REVIEW` / `RELEARNING`),
  `reviewCount: Int`.
- Keep `ease`, `intervalDays`, `reps`, `lapses`, `leech`, `dueAt`,
  `lastReviewedAt` as **derived mirrors** (updated on every review) so export
  and UI keep working: `intervalDays` = FSRS interval, `ease` derived from
  difficulty (1.3–3.0 scale), `reps` = reviewCount.

**New `FsrsScheduler : Scheduler`**:
- `initial()` → NEW state, zeroed memory.
- `next(state, grade, now)` → FSRS transition. Sub-day intervals (learning
  steps, Again) use `now + delayMs` like SM-2's relearn path; multi-day
  intervals snap via existing `DayBoundary.dueAfter`. Again on a REVIEW card →
  RELEARNING + lapse++ (leech flag at threshold). GOOD/EASY graduate → REVIEW.
- `retention(state, now)` → actual FSRS retrievability from **elapsed** time
  since `lastReviewedAt` and `stability` (replaces the SM-2 heuristic).
- `reset()` → initial.

Keep `Sm2Scheduler` in the codebase (tests, reference); `StudyStore`/UI switch
to `FsrsScheduler` permanently — no runtime scheduler toggle.

**Tests** (`FsrsSchedulerTest`):
- Official FSRS-6 reference vectors: initial states for all four ratings,
  first-review transitions, multi-review sequences.
- Retrievability at t=0, t=S, t=2S; interval clamps; same-day behaviour;
  lapse → relearning.
- Preview (`Study.previewDelays` via `next`) == what grading persists, for all
  four ratings.

**Commit:** `feat(scheduler): add FSRS-6 engine`

---

## Phase 2 — Schema v10: FSRS columns + `review_log`

### `card_state` migration (v9 → v10)

In `AppDatabase.kt` + `Entities.kt`:

- Add columns: `difficulty REAL NOT NULL DEFAULT 0`,
  `stability REAL NOT NULL DEFAULT 0`, `cardState TEXT NOT NULL DEFAULT 'new'`
  (enum name), `reviewCount INTEGER NOT NULL DEFAULT 0`.
- Keep all existing columns (mirrors + `contentHash` + `leech` for the
  "tricky" drill).
- **`DELETE FROM card_state`** inside the migration (reset-to-new decision).
- Indices unchanged (`questionId`, `dueAt`).

### New `review_log` table (spec §5, immutable history)

| Column | Type | Notes |
|---|---|---|
| `id` | INTEGER PK autoincrement | Deviation from spec UUID; matches `attempts` convention. Local-only history. |
| `paperId` | TEXT NOT NULL | FK → papers, CASCADE (matches `card_state`) |
| `questionId` | TEXT NOT NULL | |
| `rating` | SMALLINT NOT NULL | 1=Again, 2=Hard, 3=Good, 4=Easy |
| `reviewedAt` | LONG NOT NULL | |
| `elapsedSeconds` | INTEGER | dwell time |
| `stateBefore` | TEXT | card state prior to the review |
| `stabilityAfter` | REAL | post-review memory state |
| `difficultyAfter` | REAL | |
| `intervalDaysAfter` | INTEGER | |

Indices: `(questionId, reviewedAt)`, `(paperId)`.
New `ReviewLogDao`: insert + per-question history reads.

### Remove attempt-history seeding

Delete the `Study.rebuild` call path in `StudyStore.resolveStudyStates` /
`historySignalsFor`. Without this the wipe would be undone on next load.
JSON/Anki import paths remain the only warm-start vectors.

**Tests:** `MigrationTest` (CI-only Robolectric) asserts rows gone, new
columns present, `review_log` creatable and insertable.

**Commit:** `feat(db): add review_log and FSRS columns, reset card_state (v10)`

---

## Phase 3 — Wire FSRS through repository + settings

### `StudyStore`

- Swap `Sm2Scheduler` → `FsrsScheduler` everywhere (`resolveStudyStates`,
  `getStudyCounts`, `recordStudyReview`).
- `recordStudyReview(paperId, questionId, grade, dwellSeconds, now)`:
  one `db.withTransaction { cardStateDao.upsert; reviewLogDao.insert }` —
  event + state update atomic (spec §6).
- Extend `StudyCounts`: drop `dueWaiting` / `freshWaiting` /
  `newBlockedByReviewLimit` (no caps); keep `due` (learning + review due),
  `leeches`, `fresh`. Badges = eligible counts, never quota remainders.

### `SchedulerConfig` / `RepositorySettings`

- Remove `newLimit`, `reviewLimit`, `newCardsIgnoreReviewLimit` (stop reading
  old `anki_*` keys; DataStore keys become inert).
- Keep `dayStartHour`, `leechThreshold`, `relearnMs`, `fastSeconds`,
  `slowSeconds`.
- Add: `desiredRetention` (0.75–0.97, default 0.9, key
  `fsrs_desired_retention`), `studyMode` enum
  (`MEMORY_FIRST` | `EXPLORE_NEW`, key `study_mode`),
  `introduceNewCards: Boolean` (default true, key `introduce_new_cards`).
- `sanitized()` updated accordingly.

### `McqRepository`

- Expose new settings getters/setters.
- Update the study-queue surface (see Phase 4).

**Commit:** `feat(study): persist reviews to review_log transactionally`

---

## Phase 4 — Pull-based candidate ranking (replaces quota `selection()`)

Rework `Study.selection()` → `Study.selectNext(...)` (pure, in
`SpacedRepetition.kt`):

### Candidate pools

All eligible = `dueAt <= now` (intraday learning included):

1. **LEARNING / RELEARNING** cards due now — short-term repetition (spec §3).
2. **REVIEW** cards ranked by predicted forgetting risk — retrievability
   ascending (lowest R first = "most likely to be forgotten"), ties → oldest
   due. Uses actual FSRS retrievability, not a custom difficulty score.
3. **NEW** cards, only when `introduceNewCards` is on.

Leeches keep their drill surface (`onlyLeeches`) but no longer sit in the main
ranking as a separate capped stream — a due leech is just a low-R review plus
a chip.

### Mode policy (spec §7)

- `MEMORY_FIRST`: pool 1 → pool 2 → pool 3 (new only when nothing is due).
- `EXPLORE_NEW`: pool 1 → new cards interleaved ahead of due reviews (surface
  new after each learning completion / every few reviews) → remaining due.
  Pacing hint only, never a block.

Returns `NextCard(card, reason)` where reason ∈ `LEARNING`, `LOW_RECALL_RISK`,
`DUE`, `NEW` — drives the "Likely to be forgotten soon" chip.

### Invariants

- No limits, no caps, no `newBlockedByReviewLimit`.
- Starvation handled by the mode switch + Explore interleave (spec §7);
  ranking weights live in one obvious place for future tuning (spec §7: do
  not invent a universal optimal score; tune experimentally).
- `StudyCounts` / library badges read the same eligibility function — badge
  can never disagree with what the session serves.

### `McqRepository`

- Add `getNextStudyCard(paperId, leechesOnly)` + 
  `getStudyEligibility(paperId)`.
- Keep or thin `getStudyQueue` for the leeches drill.

**Tests:** pool priority order (learning beats due, risk ascending), new
starvation prevention via mode switch, badge ↔ next-card consistency,
leeches-only, no quota blocking ever.

**Commit:** `feat(scheduler): replace daily limits with ranked candidate pools`

---

## Phase 5 — Study UX (pull-based, guilt-free)

### `StudyViewModel`

- Drop prebuilt `queue` / `index`; hold only the current `StudyCard` plus
  session stats (`reviewed`, `againCount`, `goodCount`).
- `next()` → grade (existing simplified/manual paths unchanged) → persist →
  `getNextStudyCard` → display.
- Interval previews keep reading `Study.previewDelays` off `FsrsScheduler`.

### `StudyScreen`

- Replace the fixed-queue `LinearProgressIndicator` with
  **"N reviewed this session"** — no denominator, no target (spec §8).
- Reason chip on each card: NEW / LEARNING / "Likely to be forgotten soon"
  (`LOW_RECALL_RISK`) — optional display per spec §8.
- **Voluntary stopping point**: always-visible "Done for now" affordance;
  after every ~10 reviews a dismissible "Reviewed 10 cards — take a break?"
  strip (never blocks, never guilts).
- Empty state: "Nothing due right now" + optional "Introduce new cards"
  action if toggled off — no "clear your backlog" language.
- Session summary on stop keeps the current end card; add optional session
  accuracy line — stats only, no goals (spec §8).
- Leeches drill (`leechesOnly`) entry unchanged; serves the leech list.
- Simplified mode (guess toggle, auto-grade, change-grade) and manual mode
  (four buttons with FSRS interval previews) both preserved.

### Settings screen

- Delete the "Daily limits" group (new/review caps + ignore-limit switch).
- Add "Study" section:
  - Ranking-mode picker: Memory-first / Explore new cards.
  - "Introduce new cards" switch + workload hint ("Many new cards increase
    future review load", spec §9).
  - Desired-retention slider (0.75–0.97).
- Keep day-boundary, leech threshold, simplified mode, dwell thresholds.
- **Audit all strings for quota/guilt language** — none allowed (spec §8).

**Commit:** `feat(ui): pull-based study session with ranking modes`

---

## Phase 6 — Interop & backup

- **`JsonDto.CardScheduleDto`**: add `difficulty`, `stability`, `cardState`,
  `reviewCount` (defaults preserve the old shape).
  - `Exporter` writes them from FSRS state.
  - `Importer` restores them.
  - **Legacy** entries (no FSRS fields) import with FSRS columns at defaults
    (card = new); legacy columns still written for continuity.
  - Update `BackupSchedulingTest` / `ImportSchedulingTest`.
- **`AnkiScheduling` + apkg writer/reader**:
  - Export derives `ivl` from `intervalDays` (already mirrored) and `factor`
    from `ease` (mirrored from difficulty) — writer shape unchanged;
    `AnkiSchedulingTest` stays the regression net.
  - Import: `fromAnki` DTO → FSRS conversion helper (`ivl → stability ≈ ivl`,
    `factor → difficulty` estimate, queue/type → cardState, lapses → lapses)
    in the importer mapping layer.
  - No revlog writing to .apkg (rejected scope).

**Commit:** `feat(interop): derive SM-2 fields from FSRS on export/import`

---

## Validation (spec §10)

- **Local (ARM64 host)**: pure-JVM tests via the AGENTS.md gradle command
  (`:app:assembleDebug :app:testDebugUnitTest` with the aapt2 override) —
  `FsrsSchedulerTest`, ranking tests, `IntervalFormatTest`, updated
  backup/an ki tests.
- **Robolectric DB tests** (`MigrationTest`, repository transaction tests)
  are **CI-authoritative** (native SQLite unsupported on this host); push and
  confirm all three CI jobs; download the `app-debug` APK per AGENTS.md.
- **Manual smoke**:
  1. Fresh install studies new cards.
  2. Again relearns same day; Good schedules ~FSRS interval.
  3. Badge counts match what "Next" serves.
  4. Explore mode surfaces new cards mid-backlog.
  5. Return after 3 weeks ranks by risk without demanding backlog clearance.
  6. No guilt/overdue messaging anywhere.
- Future metric tracking (post-optimizer): retention at comparable review
  effort, review burden, recall accuracy, new-card learning.

---

## Commit sequence (Conventional Commits; user commits/pushes)

1. `feat(scheduler): add FSRS-6 engine`
2. `feat(db): add review_log and FSRS columns, reset card_state (v10)`
3. `feat(study): persist reviews to review_log transactionally`
4. `feat(scheduler): replace daily limits with ranked candidate pools`
5. `feat(ui): pull-based study session with ranking modes`
6. `feat(interop): derive SM-2 fields from FSRS on export/import`
7. `docs: update scheduling section for FSRS`

---

## Files touched (main)

| Area | Files |
|---|---|
| Domain | `domain/SpacedRepetition.kt`, new `domain/Fsrs.kt` |
| DB | `data/local/{Entities,Daos,AppDatabase}.kt`, new `ReviewLog` DAO bits |
| Repository | `data/repository/{StudyStore,RepositorySettings,McqRepository}.kt` |
| Interop | `data/io/{JsonDto,Exporter,Importer,LegacyParser}.kt`, `data/anki/*` (mapping only) |
| UI | `ui/study/{StudyScreen,StudyViewModel}.kt`, `ui/settings/SettingsScreen.kt`, `ui/library/*` (badge fields) |
| Tests | `app/src/test/...` (`FsrsSchedulerTest`, `MigrationTest`, `StudyQueueTest`, backup/an ki tests, ranking tests) |
| Docs | `README.md` scheduling section; `audit.md` tracks the branch's open findings |

---

## Out of scope (deferred)

- FSRS parameter optimizer (needs review_log volume; spec §10)
- Anki revlog export / full FSRS-aware .apkg memory-state columns
- Per-paper FSRS presets / desired retention per deck
- Statistics graphs beyond session accuracy
- SM-2 runtime fallback or scheduler toggle
- Fuzzed intervals (kept deterministic; revisit with optimizer)
