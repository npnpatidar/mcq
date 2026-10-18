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

### 1. Stats Workload / Memory / Cards tabs — whole card library + whole review log
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

### 2. `PaperStore.deletePaper` / `deleteImpact` — the paper's whole id list
`PaperStore.kt:~121` and `:~139` call `getIdsByPaper(paperId)`, returning every
question id before deleting (downstream `removeAllChunked` /
`countForQuestionsChunked` chunk it, but the id list itself is the peak).

Fix shape: chunk the id read too, or delete/count by `paperId` in SQL.

### 3. `FsrsTuner.optimize` — whole review log
`FsrsTuner.kt:~44`: `db.reviewLogDao().getAll()` then `buildFsrsTrainingCards`
over it. Tuning on a million reviews holds the log.

Fix shape: page the log into the fit.

### 4. The legacy in-memory `Importer` reachable outside `.apkg`
- `ImportViewModel.kt:~798` — the non-preview / edited-file path (a large bank
  takes the streamed path at `:~742`).
- `LibraryViewModel.kt:~386` — "Load sample data" (tiny asset; harmless).

So the whole-bank hash set (`getAllContentHashes`) and whole-DTO are not solely
an apkg concern. Fix shape: delete the legacy reader once the JSON path's last
non-preview caller streams too.

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

- `.apkg` **export** streams its structure (notes batched into a temp
  collection, collection zipped in chunks, schedules asked per batch) but the
  media dedupe map is a whole-paper construct — see item 2.
- JSON import/restore, backup write/restore, storage report, category counts,
  badge counts, ZIP/PDF/Anki writers, stats retention, snapshot saves, the review
  sheet — all stream or aggregate in SQL.

## Fixed

- **Rich exports held every image's data URI as a map key** — the writers
  dedupe media so one picture is stored once, but keyed that map on the full data
  URI, putting every image back in memory as a base64 string on top of the
  spool. `AnkiMediaPool.assigned` (`.apkg`), `ZipPaperWriter.uriToPath` (`.zip`)
  and `DocxPaperWriter.uriToRid` (`.docx`) now key on `mediaKey` — the URI's
  SHA-256, 64 hex chars — so the map is O(media) × 32 bytes and the decision is
  the same one it made before.
- **`.apkg` import read the whole package** — the picker read the file to
  bytes and `AnkiPackageReader.read` expanded the archive into a
  `Map<String, ByteArray>`, inlined every picture into one DTO for the whole
  bank, and handed that to a whole-file `Importer` beside a whole-bank hash set.
  Now the picker classifies by streaming (`classifyPickedArchive`) and
  `AnkiApkgImporter` streams: entries go to temp files under `SafeZip`'s caps,
  media is spooled, and the collection is walked one deck at a time into the
  shared per-batch writer (`StreamingImportSession`), with review schedules
  applied per batch. `AnkiPackageReader.read` stays as the equivalence test's
  oracle (`AnkiStreamEquivalenceTest`), and `NoDeadCodeTest` refuses a
  production call to it.
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
