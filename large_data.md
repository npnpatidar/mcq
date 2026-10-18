# Large-data tracking (1M questions / 1000 categories)

Everything the app still builds **in proportion to the whole bank**, plus what
has already been fixed. `scaling.md` holds the per-screen design work; this file
is the short, ranked to-do list for the "does it survive a million questions"
question, so it does not have to be re-derived each time.

Targets: `minSdk = 26`, `targetSdk = 36`, `compileSdk = 37`.

Verification note: Robolectric/SQLite tests **cannot run on the aarch64 dev
host** ("The Robolectric native runtime is not supported on Linux (aarch64)").
Anything that touches real SQL is confirmed on x86_64 CI only; pure-JVM tests
run locally. Line numbers below are anchors, not promises.

## Open — ranked worst-first

### 1. `.apkg` import (the one non-streamed import)
`LibraryViewModel.importAnkiPackage` (`LibraryViewModel.kt:~353`) reads the whole
file to bytes → `AnkiPackageReader.read(bytes)` expands the entire archive into a
`Map<String, ByteArray>` and returns every paper as one `McqFileDto` → `Importer`
holds that DTO beside a whole-bank hash set (`getAllContentHashes`).

Effect: a large deck is refused at the 256 MB expansion cap (`SafeZip.MAX_TOTAL_BYTES`,
`SafeZip.kt:25`) or OOMs under it.

Fix shape: extract entries to temp files under the same caps, drive the reader
per paper (decks are known up front; a deck's cards are a scoped `(nid, id)`
query), spool media to temp files, and feed the existing per-batch writer,
applying each paper's schedules as it is written. Needs an equivalence test over
the existing apkg fixtures (streamed import must produce the same database as
the whole-file reader) before it can land. Full plan: `scaling.md` §5.5 / §8 Open 4.

### 2. Stats Workload / Memory / Cards tabs — whole card library + whole review log
`StatsViewModel.kt:~232` calls `inputOf(loadedLibrary(), loadedReviews(), now)`;
both are cached for the ViewModel's life.
- `StatsStore.loadCards()` (`StatsStore.kt:~159`) pages every `CardSnapshot`
  into one `ArrayList` then returns it — one object per *studied* card.
- `StatsStore.loadReviews()` (`StatsStore.kt:~53`) returns the entire replayed
  review log as `List<ReplayReview>`.

Retention streams past the log and Overview streams; these three tabs don't.
At a million studied cards this is the largest remaining in-app construct.

Fix shape: page the log/cards through the aggregators, or compute the three
sections as SQL aggregates the way retention does.

### 3. `PaperStore.deletePaper` / `deleteImpact` — the paper's whole id list
`PaperStore.kt:~121` and `:~139` call `getIdsByPaper(paperId)`, returning every
question id before deleting (downstream `removeAllChunked` /
`countForQuestionsChunked` chunk it, but the id list itself is the peak).

Fix shape: chunk the id read too, or delete/count by `paperId` in SQL.

### 4. `FsrsTuner.optimize` — whole review log
`FsrsTuner.kt:~44`: `db.reviewLogDao().getAll()` then `buildFsrsTrainingCards`
over it. Tuning on a million reviews holds the log.

Fix shape: page the log into the fit.

### 5. The legacy in-memory `Importer` reachable outside `.apkg`
- `ImportViewModel.kt:~798` — the non-preview / edited-file path (a large bank
  takes the streamed path at `:~742`).
- `LibraryViewModel.kt:~386` — "Load sample data" (tiny asset; harmless).

So the whole-bank hash set (`getAllContentHashes`) and whole-DTO are not solely
an apkg concern. Fix shape: finishes with item 1 (delete the legacy reader once
everything streams).

## Parked / accepted (deliberate)

- **Search is `LIKE '%q%'`** — a full scan. Instant search is on hold by request
  (FTS5 paused).
- **`observeBookmarks()` full id list** in `TestViewModel.kt:~170` and
  `ResultsViewModel.kt:~77` — every bookmark id observed to mark rows saved
  (a "bookmark everything" bank is a million strings). Plan in `scaling.md` §8 Open 3.
- **Test session `SessionOrder`** inherently holds every session id (~20 MB
  packed at 1M) — accepted.
- **`Exporter.exportAllDto` / `getAllQuestionResults` / `getBookmarkedQuestions`**
  — test oracles; `NoDeadCodeTest` refuses a production call.

## Not a gap

- `.apkg` **export** is streamed and its media is spooled. The only apkg problem
  is import (item 1).
- JSON import/restore, backup write/restore, storage report, category counts,
  badge counts, ZIP/PDF/Anki writers, stats retention, snapshot saves, the review
  sheet — all stream or aggregate in SQL.

## Fixed

- **Library mistake badges read one standing per graded question** — replaced by
  `AttemptDao.countMistakesByPaper()` (SQL, one row per paper) and
  `getMistakenQuestionIdsForPaper(paperId)`; `getLatestStandings` +
  `Mistakes.mistakenIdsByPaper` kept as the tests' oracle. (`46bf279`)
- **Bookmark export assembled as one DTO** — replaced by
  `Exporter.bookmarkStream()` (keyset page per heading) via
  `PaperExporter.exportStream`; `getBookmarkExportDto` kept as the oracle.
  (`46bf279`)
- **Whole-bank JSON export encoded its bookmark list into one `String`** — now
  written one keyset page at a time (`BookmarkDao.pageIdsAfter`). (`46bf279`)

## Removed

- **Duplicate paper** — deleted entirely: `PaperStore.duplicatePaper`,
  `McqRepository.duplicatePaper`, `LibraryViewModel.duplicatePaper`, the library
  card's Duplicate action, the `R.string.duplicate` string, `PaperClone.kt`, and
  the paper-copy tests. (Duplicate **question** in Browse is unrelated and stays.)
  This also retires the "duplicating a whole 1M-question paper" gap.
