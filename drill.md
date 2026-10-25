# Drill feature bug hunt

Date: 2026-10-24 · branch `feature/host-build-notes` @ 884748a

Scope read: `domain/Drill.kt`, `domain/SessionOrder.kt`, `domain/PassageBlocks.kt`,
`domain/TestSnapshot.kt`, `domain/ExamTiming.kt`, `ui/test/TestViewModel.kt`,
`ui/test/TestSessionScreen.kt`, `ui/library/LibraryPaperCard.kt`, `ui/library/LibraryScreen.kt`,
`ui/navigation/Routes.kt`/`McqNavHost.kt`, `ui/study/StudyViewModel.kt` (leech drill),
`data/repository/TestProgressStore.kt`, and the drill tests
(`DrillTest`, `DrillCountAvailabilityTest`, `SessionOrderTest`, `TestSnapshotTest`,
`ExamTimingSessionSecondsTest`, `StudyActionStructureTest`).

Baseline: drill-related pure-JVM tests pass (DrillTest, TestSnapshotTest, SessionOrderTest,
PassageBlocksTest, ExamTiming*, TestTiming*). Full pure-JVM run: 984 tests, 4 failures —
all import-side, none drill-related (`ImportPreviewClassificationTest` is the working tree's
own WIP edit; `JsonPullSeparatorRecursionTest` ×2 and `LegacyParserTest.rootQuestionsBeforePapersAddsNoPaper`
are unrelated). Robolectric drill tests cannot run on this aarch64 host; CI is authoritative.

---

## BUG 1 (real, high): resuming mixes drill and non-drill sessions — and the next resume loses the drill cut

`TestSnapshot.matches()` (`app/src/main/java/com/mcqapp/domain/TestSnapshot.kt:47`) only
compares paper + categories, so a snapshot saved by a **full test** is offered when you start
a **drill** of the same scope (and vice versa). `resume()`
(`app/src/main/java/com/mcqapp/ui/test/TestViewModel.kt:804-852`) then adopts the snapshot's
order but keeps the *new* session's drill config:

- `resume()` never restores `isDrill`/`drillRequested` from the snapshot (the state update at
  `TestViewModel.kt:828-841` omits them).
- `saved.drillSeed?.let { drillSampleSeed = it }` (`TestViewModel.kt:819`) has **no else-branch**,
  so a fresh drill session's `drillSampleSeed` (set at `:257`) survives a resume of a non-drill
  snapshot.
- `persistProgress` then writes `drillSeed = drillSampleSeed` +
  `drillRequested = current.drillRequested` (`TestViewModel.kt:891-892`) — a header that pairs a
  seed with a count that was never applied to the presented order.

`TestSnapshot.orderOf` (`TestSnapshot.kt:118-127`) only applies the cut when
`drillSeed != null && drillRequested > 0`, so the inconsistent header changes shape on the next
resume.

### Consequences (all reachable from the UI, no race needed)

| Killed session → new session | What happens |
|---|---|
| Full test (500 q) → drill(10), user taps Resume | Order becomes the full paper; the drill banner misreports ("drill 500 / requested 10"). Autosaves write `drillSeed=D, drillRequested=10` where D was never applied. Next kill+resume runs `sampled(10, D)` → the 500-question session **collapses to 10 questions**; submit then records a 10-question attempt. |
| Drill(10) → full test, Resume | Order is the 10-question cut with `isDrill=false`; autosaves write `drillSeed=S, drillRequested=0` → next resume **silently drops the cut** and resumes into the full shuffled paper, `currentIndex` coerced into the larger order. |
| Drill(10) → kill → Drill(20), same scope | Resume replays the 10-cut while state says 20; the next autosave redraws a different 20-cut — answers outside it vanish from the session. |

Collateral: after a cross-resume, `ungradedTotal` was computed for the old session's order
(`TestViewModel.kt:287`), so the ungraded dialog under/overcounts.

### Why it is untested

The only drill-resume test is `TestSnapshotTest.aSeedSnapshotReproducesADrillCut`
(`app/src/test/java/com/mcqapp/TestSnapshotTest.kt:130`); `drillSeed` appears nowhere else in
tests. `TestViewModel` is exercised only by Robolectric tests, which cannot run on this aarch64
host — a regression test would go through CI.

### Fix direction

In `resume()`, restore the drill fields from the snapshot:

```kotlin
drillSampleSeed = saved.drillSeed ?: 0L
val drill = saved.drillSeed != null && saved.drillRequested > 0
// into the state copy: isDrill = drill, drillRequested = if (drill) saved.drillRequested else 0
```

or make `matches()`/`checkResumeOffer` refuse offers whose drill-ness differs from the new
session (add the drill fields to the match).

---

## BUG 2 (trap, low): two disagreeing implementations of "the drill cut"

`Drill.sample` (`app/src/main/java/com/mcqapp/domain/Drill.kt:40-43`) shuffles and cuts **per
question** — it splits passage blocks, which the app treats as a correctness rule
(`PassageBlocks.kt:12-14`, pinned by `SessionOrderTest.aDrillNeverSplitsAPassage`). It has **no
main-code callers** (only tests; the live cut is `SessionOrder.sampled`), and
`DrillCountAvailabilityTest.kt:91` even pins the splitting behaviour. Any future caller importing
it would silently split comprehension sets.

Suggestion: delete `Drill.sample`/`Drill.canSample` (keep `checkCount`, which the dialog uses) or
make them delegate to the block rule.

---

## Nits

- `TestUiState.drillRequested` doc (`TestViewModel.kt:80`) references a nonexistent
  `[questions]` field (now `order`).
- The dialog's `drill_preview` plural promises an exact "N questions in M minutes"; a passage
  block may deliver more (documented D1 overshoot, explained post-start by the
  `drill_overshoot_shown` banner). Design choice, not a bug.
- Dialog caps count/minutes at 4 digits (9999); `ExamTiming.secondsFrom` clamps at
  MAX_MINUTES = 10080 — consistent, no bug.
