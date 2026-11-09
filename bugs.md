# Bug hunt — before the next feature

Scope: read-only bug hunt over every feature named in the request. Each pass
looks at the pure rules, the database path and the UI wiring, and records the
finding with the file/line that proves it. Nothing here is fixed yet; nothing
is committed.

Severity: **P1** wrong behaviour a learner can hit in normal use, **P2** wrong
figure or state in a narrower case, **P3** cosmetic / wording / low-risk.

Passes: 1 statistics, 2 test, 3 study, 4 FSRS (clean), 5 large papers, 6
import/export, 7 restore/backup, 8 search, 9 passages, 10 bookmarks (clean).
19 findings: 1 P1, 9 P2, 9 P3; the P1 is S5 ("Study again" after Stop).
All of it comes from reading the code: no build or test was run for this
hunt (Robolectric/SQLite tests cannot run on this ARM64 host anyway), so
treat the evidence as file/line reasoning rather than a reproduction.

---

## 1. Statistics

### S1 (P2) — Exam margin uses the pooled sample size but the latest score

`ExamStatsBuilder.build` computes `shown` as the sum over every comparable
attempt (`ExamStats.kt:248`, `val shown = comparable.sumOf { it.totalQuestions }`),
then feeds that pooled count to `marginPoints(latestPercent, shown, population)`
(`ExamStats.kt:277-278`). The ± margin is therefore attached to **one**
attempt's percentage while `n` is the number of questions across **all**
attempts. The UI prints it as `Approx. 95% margin ±x points · n=<shown>`
(`ExamSection.kt:199-202`, `strings.xml:591`).

Example: three comparable 20-question mocks, latest 60%. The true half-width
for that attempt is about ±21 points; the code computes it with n=60, about
±12 points. The margin shrinks by roughly √(attempts) as history grows, even
though the latest attempt's sample did not grow. Any score change that is
really inside the noise of a 20-question mock can therefore be reported as
"outside the margin".

### S2 (P2) — Exam trend/delta noise test uses one attempt's margin, not the difference's

`deltaIsNoise` (`ExamStats.kt:186-191`) compares `|latest - first|` with
`marginPoints` of the latest attempt only. The report path already does this
correctly by adding both margins (`TestReport.kt:712`:
`(current.marginPoints ?: 0.0) + (previous.marginPoints ?: 0.0)`). For two
independent 20-question attempts at 50%, the exam tab calls a 24-point swing
"real" when the combined band is ~30 points.

### S3 (P2) — Scoped retention breakdown is per question, labelled with its category

`RetentionAccumulator.countGroup` keys on `questionId` whenever a paper is
selected (`StatsAggregator.kt:786-789`), then `groupRetention()` labels each
question row with that question's category title (`StatsAggregator.kt:874-881`).
A scoped paper with 50 questions in 5 categories therefore renders up to 15
rows with repeated category labels — one row per question — and silently drops
the rest (`take(15)`), instead of aggregating the reviews by category. The card
is still titled "Retention by paper" (`strings.xml:719`) in a single-paper
view. `StatsRetentionStreamTest.kt:173-181` pins the per-question grouping, so
this is long-standing behaviour rather than a recent regression, but the label
and the cap make the card read as a category breakdown it never was.

### S4 (P2) — Session/sitting figures are computed over append order, so imported history is counted as sittings

`WorkloadAccumulator.countSession` and `GuessingAccumulator.add` both split
sittings on a 30-minute gap in the order rows are fed
(`StatsAggregator.kt:1009-1031`, `:1704-1718`), and `FsrsReplay.Stream` is fed
by ascending `review_log.id` (`StatsStore.kt:371-381`). That is append order,
not review time. A restored backup or an Anki import appends months of history
in one block: consecutive rows from different cards are minutes or months
apart in the clock, so the import invents hundreds of separate "sessions" and
the 30-minute sitting position on the Guessing tab is equally meaningless for
imported rows. Live rows are fine because id order and time order agree.

---

## 2. Test

### T1 (P2) — Report margin divides an answered-based accuracy by the shown count

`TestReportBuilder.scoreOf` defines `accuracy = correct / (correct + wrong)`, with
skips excluded (`TestReport.kt:825-841`). The report's margin then passes that
proportion with `sample = score.shown`, which includes skipped rows
(`TestReport.kt:325-328`), and `TestStatistics.marginPoints` documents `sample`
as the shown count (`TestReport.kt:1218`). Skipping questions leaves the
numerator on one denominator and the sample size on another, so the reported
± margin is too small — the more questions a learner skips, the more
overconfident the report becomes. The same mismatch is repeated for the prior
comparable attempt (`TestReport.kt:370-376`), for `isTrendWorthy`
(`TestReport.kt:726-734`) and in `trendOf` (`TestReport.kt:686-705`), so the
"change is inside the margin" judgement inherits it.

Example: 20 shown, 10 answered (8 correct), 10 skipped. Reported accuracy 80%
with n=20; the 80% is measured on 10 answers, and the Wilson interval for it is
about twice as wide.

### T2 (P2) — Negative marking is reconstructed from question counts, not marks

`markingOf` recovers the penalty as `(correctCount - score) / wrongCount`
(`TestReport.kt:848-858`). That treats every correct question as one mark.
Questions carry their own `marks` (`domain/Marks.kt`, editable in the editor),
and `maxScore` sums them, so on a paper with any question worth ≠ 1 the
recovered value is not the penalty rate. Example: a 0.25-penalty paper with a
4-mark question answered wrongly and a 2-mark question right stores
`correctCount = 1`, `score = 2 - 1 = 1`; the formula returns `(1-1)/1 = 0.0`.
The results header then says there was no negative marking when there was, and
`guessingOf` is re-enabled for that attempt (`TestReport.kt:804`), showing a
guessing correction the marking rules out.

### T3 (P3) — A legacy route does not take over a saved bounded session's answers

`checkResumeOffer` arms `dropStaleAnswersOnSave` only when `sessionPlan != null`
(`TestViewModel.kt:1148-1156`). Answer rows are keyed by `(paperId,
questionId)`, and a legacy session (deep link, or the no-paper-record fallback
at `LibraryScreen.kt:242`) has `sessionPlan == null`. If it starts on a paper
whose slot holds a bounded session, its first `persistProgress` overwrites the
header but leaves the bounded session's rows in place
(`TestProgressStore.kt:84-92`). A later resume of the legacy session joins
those rows into the drill (`offer` → `withAnswers`,
`TestProgressStore.kt:116-121`), so it opens with answers the learner never
gave in it. Live bounded sessions are protected; only the legacy start path is
not.

### T4 (P3) — A drill asking for no clock still inherits one

`ExamTiming.sessionSeconds` only honours `drillMinutes` when it is `> 0`
(`ExamTiming.kt:55-60`). `0` is the untimed value used everywhere else in the
same function, so a drill whose dialog produced 0 falls through to the
per-attempt override or the paper's duration and runs timed. No current UI
emits `drillCount` (only deep links do), which keeps this low.

---

## 3. Study

### S5 (P1) — "Study again" after Stop leaves the session stopped; the first grade is written and then re-gradable

`stop()` sets `stopped = true` (`StudyViewModel.kt:899-907`) and only `load()`
clears it (`:251`). `restart()` (`:981-1044`), which is what the finished
screen's "Study again" button calls, never clears the flag. It does pull a new
card, so the session looks alive. The first grade then runs
`recordStudyReview`, `advanceStats`, and hits `if (stopped.get())` before
`pullCard()` (`:845-855` for four buttons, `:773-783` for simplified), so it
returns with the same card still on screen, still revealed, and `pendingGrade`
still set. The guard `failed to advance` is invisible (`gradeError` is null and
`gradedAndStuck` is false, so the UI offers no Retry), and the grade/Next
buttons stay enabled. Tapping again persists a **second** review for the same
question: two `review_log` rows, two FSRS applications (so the interval,
`reviewCount` and possibly the leech flag move twice), and doubled statistics.
The same double-write is what `gradedAndStuck` exists to prevent
(`StudyScreen.kt:364-366`), but that flag is only set on a pull exception.

Reproduction: study a few cards → Stop → Study again → grade the first card →
the card stays → grade it again.

### S6 (P3) — Study dwell runs on the wall clock

Study measures the per-question clock with `System.currentTimeMillis()`
(`StudyViewModel.kt:408`, `:508`, `:530`, `:603-628`), and the simplified flow
feeds that elapsed time to `Study.autoGrade`, where it chooses Easy/Good/Hard
(`StudyViewModel.kt:651-681`). The Test session deliberately moved the same
measurement to `SystemClock.elapsedRealtime()` for exactly this reason
(`TestViewModel.kt:285-294`, the D10 comment). A manual or NTP clock jump while
studying can therefore inflate the dwell and turn a Good answer into a Hard
one; a backwards jump silently drops the segment to zero. The session summary
time is distorted the same way.

### S7 (P3, scalability) — Every study pull scans all of the paper's card rows

`getNextStudyCard` calls `resetEdited(paperId)` on every pull
(`StudyStore.kt:143-151`), which runs `staleForPaper` (`Daos.kt:1757-1764`) — a
join over every `card_state` row of the paper comparing two hashes. The query
returns only edited cards, but its cost is the paper's whole study history, so
on a 250k-card paper every served card pays that scan instead of only the
pulls near an edit. The index cannot help: the predicate compares columns from
two tables. This is structural, not measured on this host (no emulator/build
here); it is the kind of cost the large-paper pass should time.

---

## 4. FSRS

No confirmed defect. The formulas match FSRS-6, the optimizer port matches the
reference update rules, and `rowsForQuestions` pages correctly (ordered and
grouped by question). No section here for that reason; the pass is recorded so
the numbering shows it was not skipped.

## 5. Large papers (many categories / many questions)

### L1 (P2) — Phase-3 scoped reads bind every selected category in one statement

The codebase is explicit that SQLite on the supported API 26–30 devices allows
999 bind variables and that a 1000-category paper exists (`ChunkedQueries.kt:5-10`
and its `chunks()` wrappers). Four scoped reads were missed:

- `tagCounts` — `Daos.kt:2181`, the Tag picker's counts (`McqRepository.kt:581-588`,
  called from `LibraryViewModel.kt:748-751`);
- `questionIdsForTag` — `Daos.kt:2196`, the Tag test's pool
  (`McqRepository.kt:594-605`, called from `LibraryViewModel.kt:719-723` and
  `TestViewModel.kt:374-378`);
- `bookmarkedIdsInScope` — `Daos.kt:2215`, the Bookmarked test's pool
  (`McqRepository.kt:606-613`);
- `dwellSamplesInScope` — `Daos.kt:2235`, the Slowest test's median dwell
  (`McqRepository.kt:693-705`).

All four receive the **ticked** category ids (`LibraryScreen.kt:471/475`,
`TestViewModel.kt:370-378`), not the expanded subtree, so they are safe for a
normal small selection — but ticking more than ~996 maximal nodes (for example
1000 flat categories, or 1000 leaves individually) binds over the ceiling and
throws `SQLiteException: too many SQL variables` on Android 11 and older. The
same reads are not used for ordinary random/focus tests, so this is a crash in
a narrower case, not the main path. The fix is the existing `chunks()` wrapper
pattern (or a paper-scoped query like browse's).

### L2 (P3, scalability) — Resuming rebuilds a whole-paper id map to find <150 saved ids

`TestSnapshot.orderOf` replays a frozen sample with
`base.subsetInOrder(saved.selectedQuestionIds)` and a legacy snapshot with
`base.reorderedTo(saved.questionIds)` (`TestSnapshot.kt:205-215`). Both build a
`HashMap<String, Int>(size * 2)` plus a `BitSet(size)` over the **base order**,
which is the whole paper for a paper-wide or scoped session
(`SessionOrder.kt:196-256`). `base` is streamed precisely so a million-question
paper is a byte pool plus two `IntArray`s (`QuestionStore.kt:161-178`), but the
resume path then allocates one fresh `String` per row (`idOfRow`,
`SessionOrder.kt:246-247`) and a boxed `Int` per entry in the map. A bounded
20-question test on a million-question paper therefore pays a transient
>100 MB (unmeasured, no build/emulator on this host) and defeats the streaming
design at exactly the moment the learner reopens the app. Building the lookup
from `savedIds` and the pool (or streaming the order while comparing against a
small saved-id set) would keep this O(saved) in memory.

---

## 6. Import / export

### I1 (P2) — Category parent links are written verbatim, so a remap or a subtree export orphans the child

`CategoryEntity.parentId` is written straight from the incoming DTO in both
import paths (`Importer.kt:273`, `:282`, `:293`; `StreamingImportSession.kt:157`).
The category **id**, however, is not the incoming one in two supported cases:

- the id belongs to another paper, so it is remapped to
  `$effectivePaperId-$rawId` (`Importer.kt:252-267`);
- the id is unknown but a category with the same title exists in this paper,
  so the stored row's id is adopted (`Importer.kt:252-253`).

A child's `parentId` still names the file's raw parent id, which is then not a
row in the destination paper. The tree is built only from `parentId == null`
roots (`PaperStore.kt:76-92`), so the child and its whole subtree silently
disappear from the drawer and from `CategorySelection`; `Paper.totalQuestions`
also stops counting them (`Models.kt:310`). The questions stay in the DB
and still appear in a whole-paper session, which makes the symptom look like a
randomly missing section rather than an import failure.

The same dangling parent is produced on the **export** side: exporting a
nested category keeps its real `parentId` (`Exporter.kt:269-279`, header at
`:431`), while the ancestor is not part of the exported subtree. Importing
that document as a new paper (the usual "share one section" flow) creates the
root category with a parent row that was never shipped. The Anki path is not
affected because deck names carry the hierarchy itself.

Fix direction: map raw category id -> effective id and resolve `parentId`
through it on import, and/or normalise a selected export root's parentId to
null (and treat an unknown parent as a root when building the tree).

### I2 (P3) — "Update answers on duplicate" can write the key onto the wrong stored question

With the Settings toggle on, the refresh branch takes the **first stored row
sharing the content hash** without checking it is actually the same content:
`candidates[contentHash]?.firstOrNull()` (`Importer.kt:390-392`,
`StreamingImportSession.kt:450-452`). The hash deliberately cannot see tables
or formulas (`ContentHash.kt:106-112` documents exactly that), and the branch
skips the same `sameQuestionContent` guard the normal duplicate path applies
(`Importer.kt:436-438`, helper at `:718-733`). So a file carrying a question that differs
from a stored one only by a table option — a hash collision by design — has its
answer key, explanation, marks, difficulty and tags written onto the stored
question, and the incoming question is skipped (`return@forEachIndexed`).
`candidates` is also grouped from an unordered hash match, so "first" is not
necessarily the same id. Off by default, hence P3, but the effect is
corruption rather than a skipped row.

### I3 (P3) — In-file duplicate check treats a rich question and a flat-text question as the same

`ContentHash.sameQuestionContent(a: QuestionDto, b: QuestionDto)` only compares
elements when **both** sides have them (`ContentHash.kt:149-152`), and only
compares an option's elements when both sides have them (`:156-160`). The
stored-row variant normalises the flat side first (`dto.elements.ifEmpty {
listOf(TextElement(dto.text)) }`, `ContentHash.kt:125-127`), so the two
comparisons disagree. A file that mixes element-encoded and flat questions
(a hand-authored file, or a merge of an export with a legacy one) whose visible
text collides has the second one dropped as a duplicate even when the first
carries a table or formula the second does not — the exact case the content
check exists to protect (`ContentHash.kt:88-100`). Only affects duplicates
within the one file, hence P3.

---

## 7. Restore / backup

### R1 (P2) — A backup restore reverses the paper order (custom order is lost)

The only backup the UI writes is the streamed one (`SettingsViewModel.backupAll`,
`:466-548`), and it builds its paper rows as
`PaperCreated(it.id, it.createdAt)` (`:483-485`), leaving `sortOrder` at its
`null` default. `PaperDto` has no sort order field either (`JsonDto.kt:102-115`),
so `content.json` cannot carry it.

On restore the importer creates each paper as new, and a new paper takes the
top slot: `sortOrder = (minSortOrder ?: 0L) - 1L` (`Importer.kt:148`,
`StreamingImportSession.kt:294`). The export walks papers in display order
(`Exporter.kt:41` via `paperDao().getAll()`, `ORDER BY sortOrder ASC`, `Daos.kt:15`),
so with papers A, B, C the import assigns A=0, B=-1, C=-2 and the drawer shows
C, B, A after a restore. Any custom order the user had set is discarded in the
same pass, since only these freshly assigned values survive. `restoreStreaming` does try to apply the backup's order
(`Backup.kt:962-965`) but only when `sortOrder` is non-null, and this writer
never sets it.

The in-memory `BackupSource.collect` does set it (`Backup.kt:665-667`), and the
round-trip test uses `collect` (`BackupRestoreRoundTripTest.kt:218`), so the
suite covers the path the UI does not use and misses this one. Fix direction:
include `sortOrder` in `backupAll`'s `PaperCreated`, the same way `collect`
does; the restore side already honours it.

---

## 8. Search

### SE1 (P3) — The result count is the number loaded so far, and mostly lacks the "+"

`SearchScreen` shows `"${hits.itemCount}${if (appending) "+" else ""} matches"`
(`SearchScreen.kt:104-110`). With `enablePlaceholders = false`
(`SearchViewModel.kt:75`), `itemCount` is only the rows Paging has loaded, and
an append only starts when the list is scrolled near its end. A query with
thousands of hits therefore shows e.g. "200 matches" with no "+", and the
count jumps as the user scrolls. It reads as an exact total, which nothing
computes (the prefilter count is not queried). Either query a count or label
it "N loaded" / always show "+" when `nextKey` is non-null at the last load.

Search otherwise came out clean: the prefilter (whole query OR longest word,
escaped, Kotlin-folded) is a superset of the in-memory authority for all three
scopes, the passage union is kept consistent with it, offsets advance by the
prefilter's page size, and `SearchPagingSource` keeps fetching windows until
it has enough hits, clearing `nextKey` on a short window.

---

## 9. Passages

### PA1 (P3) — Folded members land at the category tail, not beside their group, so a passage can end up split

When an imported file puts a passage's members in different categories, both
importers move them into the passage's category and give them the category's
next sort orders — i.e. append them after everything in that category
(`Importer.kt:603-621`, `StreamingImportSession.kt:740-754`). The comment says
they "at least arrive as one contiguous run", and they do form a run, but not
the passage's run: if the passage already has members in that category, the
category now holds two runs of the same passage, like
`[A1 A2 B1 stray-A3]`.

`PassageBlocks.blockBounds` treats every run as its own block
(`PassageBlocks.kt:31-44`), so the session layer sees a 2-member passage and a
1-member passage instead of one group:

- `shuffled` / `sampled` can put the two runs far apart, interleaving unrelated
  questions inside a comprehension set (`SessionOrder.kt:76-88, 314-327`);
- `sampledBounded`'s knapsack can select one run and not the other
  (`SessionOrder.kt:105-190`), so a test that promises whole-passage sampling
  can ask only part of the set;
- `minBlockSize` under-reports the group's real size (`SessionOrder.kt:245-255`).

`excludingBlocksContaining` is unaffected (it excludes by passage index, all
runs). Fix direction: after the fold, place the strays with `placeWithGroup`
(right after the group's last member) instead of appending at the category tail,
or call `placeWithGroup` for each stray once the category is updated.

The rest of the passage surface came out clean: block-aware reorder pins the run
edges (`PassageReorder.canMove`), the drill/sample only ever overshoots by a
whole block, deleting a passage with members is refused, moving a passage
moves every member with it, and a content edit resets the members' schedules.

---

## 10. Bookmarks

No confirmed defect in the normal flows. The list pages by cursor
(`(bookmark.createdAt, questionId)` DESC, matched exactly by the WHERE clause),
the cursor is only used as `nextKey` while `hasMore`, a toast-tap delete is
idempotent, a question delete/paper delete/category delete removes the rows
(`QuestionStore.kt:406,422`, `PaperStore.kt:202,261`), and the export keeps
passage context and paper-title grouping.

Two notes moved to Open questions rather than claimed as bugs: the "N
bookmarks" header counts raw `bookmarks` rows while the list inner-joins
`questions`, and the importers add bookmark rows without checking that the
question exists, so only a hand-built or edited file can create the orphan
that makes those two disagree.

---

## Open questions / not yet classified

- Statistics: whether `getExamCategoryTotals`' `COUNT(*) AS asked` including
  skipped rows is intended. It matches the report's `correct / asked` topic
  rule (`TestReport.kt:103`), so it is not recorded as a bug.
- Tests: `TestProgress.families` averages attempt percentages unweighted while
  `ExamStats` weights by `score / maxScore`. Both are defensible readings of
  "average"; only worth aligning if the two cards should agree.
- Import/export: `PaperDto.passages` is ignored on import (the importers read
  only the file's top-level `passages`), and the app's own writers emit both, so
  every normal round-trip is safe. Only a third-party file that puts passages on
  the paper would lose them; treating that as a bug would mean deciding which of
  the two arrays wins.
- Bookmarks: `BookmarksScreen` decides its empty state from
  `observeBookmarkCount()` (raw rows over `bookmarks`), while its list is an
  inner join with `questions` and drops questions whose category is gone
  (`HistoryStore.kt:170-190`). The flows above keep the two in step, but a file
  that names an unknown question id in its `bookmarks` array is still inserted
  as a row (`Importer.kt:625-629`, `StreamingImportSession.kt:758-762`), which
  would leave a count with an empty list and an export that says there is
  nothing to export. The delete path deliberately tolerates such rows
  (`QuestionStore.kt:401-405` says backup restores rely on that tolerance), so
  this may be intended; not reproduced through the UI.
- Study/Test hold every bookmark id in a `Set` (`StudyViewModel.kt:204-210`,
  `TestViewModel.kt:255-262`) to paint one header icon; the bookmarks screen
  deliberately avoids that (`HistoryStore.observeBookmarkCount`). Correct, but
  a "bookmark everything" bank copies its id list into the session's state on
  every toggle.
