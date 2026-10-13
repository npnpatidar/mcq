# Tests Required — gap analysis against the FSRS pull-based plan

Audit of the current suite (`app/src/test`, `app/src/androidTest`) against
`FSRS-PLAN.md`. The engine, ranking, and data layers are well covered; the
gaps sit in the study UI, settings persistence, and the session ViewModel.

## Already covered (no action)

| Area | File | Tests |
|---|---|---|
| FSRS-6 engine | `FsrsSchedulerTest` | 19 — reference vectors, ladder, initial states, retrievability, same-day, relearning, lapse/leech, day boundary, desired retention, ceiling, preview==persisted, param count |
| Ranking, modes, eligibility | `SpacedRepetitionTest` | 47 — pool order, risk ranking, tie-break, MEMORY_FIRST / EXPLORE_NEW policies, introduceNew off, leech drill, grade inference, retention decay, sanitization |
| Repository pull + review_log | `RepositoryTest` | 38 — pull order, badge↔pull consistency, no caps, edit resets, attempt-history does not seed, cascades, preview==persisted |
| Migration chain | `MigrationTest` | 12 — v9→v10 wipe + FSRS columns + `review_log`, cascade, full 3→10 chain |
| Interop | `AnkiSchedulingTest` (15), `ImportSchedulingTest` (7), `BackupSchedulingTest` (4) | derive-on-export, legacy + FSRS schedule import, round-trip |

All pure-JVM; Robolectric (DB) tests below are CI-authoritative on this ARM64 host.

---

## P1 — Study UI for the pull-based session (CI-only, androidTest)

`StudyAndSelectionUiTest` still only asserts study-marks, simplified grading,
drill refusal, export selection, and delete confirmation. Add:

| Test | Asserts |
|---|---|
| Reason chips render per pool | NEW / LEARNING / **"Likely to be forgotten soon"** on a low-recalls-risk review card; route the card through `study` with a seeded due state |
| Voluntary stop | "Done for now" visible at any point; tapping it ends the session and shows the summary; no prompt to continue |
| Break strip | after ~10 reviews the strip appears, is dismissible, and dismissal never blocks further cards |
| Session counter | "N reviewed this session" increments; **no** fixed-queue progress bar (previous `index/queue` UI is gone) |
| Empty state action | with nothing due and new cards off, the empty state offers "Introduce new cards" (`StudyScreen.kt:659`) |

Lessons from the previous D-track attempt (see `SCHEDULING-PARITY.md` D5):
assert via test tag and `useUnmergedTree = true`, and assert existence, not
`assertIsDisplayed` — layout/visibility assumptions failed under CI.

## P2 — Settings persistence (JVM) — currently the largest hole

There is no `RepositorySettingsTest` at all. New file
`app/src/test/java/com/mcqapp/RepositorySettingsTest.kt`:

| Test | Asserts |
|---|---|
| Round-trip | `studyMode`, `introduceNewCards`, `desiredRetention` written → `schedulerConfigNow()` reads them back |
| Fresh install | defaults: MEMORY_FIRST, introduceNew = true, retention 0.9 |
| Legacy install | DataStore with only old `anki_*` keys (or none) → config still sanitizes to the same defaults; removed quota keys are inert |
| Clamping | desiredRetention below 0.75 / above 0.97 coerces; bad study mode string falls back |
| Sage keys inert | `anki_new_limit`, `anki_review_limit`, `anki_new_ignore_review_limit` present in DataStore but ignored by the config |

## P3 — StudyViewModel pull flow (CI-only, androidTest or JVM with Robolectric)

The ViewModel moved from a prebuilt queue with `index++` to pull-per-card.
No test exercises this at ViewModel level:

| Test | Asserts |
|---|---|
| Pull-per-grade | grading the current card persists it and the next screen shows the **next ranked** card (repeat to cover a card returning intraday from learning) |
| Preview recomputation | interval preview labels correspond to the newly served card, not the graded one |
| Guess + change-grade | existing simplified paths still produce a `pendingGrade` that equals what was persisted |
| Early stop / restart | stop leaves schedules intact; restart re-pulls from the same question list |

## P4 — Repo-level mode end-to-end (JVM, `RepositoryTest`)

Mode behavior is proven only at domain level. Add:

| Test | Asserts |
|---|---|
| EXPLORE_NEW interleaves | with a backlog of due reviews and unseen cards, repeated `getNextStudyCard` eventually serves a new card before the backlog is exhausted |
| MEMORY_FIRST never starves reviews | while anything is due/learning, no new card is served |
| Mode switch mid-session | changing the setting changes what the next pull returns |
| introduceNew = off | new cards never served no matter the mode |

## P5 — Legacy parser scheduling fields (JVM, `LegacyParserTest`)

Fix `7c193a6` ("the legacy parser drops the FSRS fields it was handed") has no
parser-level regression test — currently only covered indirectly through
`ImportSchedulingTest`:

| Test | Asserts |
|---|---|
| FSRS fields parse | a scheduling entry with difficulty/stability/state/reviewCount survives parsing |
| Missing FSRS fields | entry with only ease/intervalDays/dueAt → memory-model fields default (card is new), no crash |
| Corrupt values | non-numeric or out-of-range fields fall back to defaults rather than throwing |

## P6 — Review-log history reads (JVM, small)

Fuel for the future Card Info / optimizer surface:

| Test | Asserts |
|---|---|
| Ordering | `reviewLogDao().historyForQuestion` returns rows ordered by `reviewedAt` across multiple sessions |
| Retention after lapse | a review after a lapse keeps prior rows immutable and the card's new row carries the collapsed stability |

---

## Suggested order

1. **P2** (JVM, runs locally, smallest surface, unblocks P4 confidence)
2. **P4** then **P5** (JVM, local)
3. **P3** (Robolectric, local if pure JVM suffices; CI if emulator needed)
4. **P1** last — CI-only, needs the D5 lesson applied (tags, `useUnmergedTree`)

## Commit sequence

1. `test(settings): cover FSRS study settings persistence and legacy fallback`
2. `test(study): mode-driven pulls end to end and parser scheduling regressions`
3. `test(study): the ViewModel pull flow grades, persists, and re-pulls`
4. `test(ui): cover the pull-based session surface on CI`
