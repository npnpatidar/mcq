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

Nothing open. The whole-bank hash set is gone (see Fixed); what remains of the
legacy whole-DTO path is either bounded or a deliberate feature (see Parked /
accepted).

## Parked / accepted (deliberate)

- **A JSON backup picked through "Import questions"** is still parsed
  whole-file: `ImportViewModel.loadLargeJson` falls back to `LegacyParser.parse`
  when the bounded preview sees a backup, because that preview cannot represent
  papers 2+ or review progress. The feature is deliberate and tested
  (`ImportBackupRestoreTest` pins the file the Import button writes), so it is
  not deleted; streaming it needs a multi-paper streaming reader the parser does
  not have. Settings → Restore backup is the streaming path.
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
  collection, collection zipped in chunks, schedules asked per batch); its
  media dedupe map is keyed on the image's digest (see Fixed).
- JSON import/restore, backup write/restore, storage report, category counts,
  badge counts, ZIP/PDF/Anki writers, stats retention, snapshot saves, the review
  sheet — all stream or aggregate in SQL.

## Fixed

- **Every whole-file JSON/docx import read the whole content-hash table** —
  `Importer.import` opened with
  `questionDao.getAllContentHashes().toHashSet()`: a million-row scan into a
  `HashSet<String>` for every import, even a one-question file, so importing a
  small bank into a full library was an O(bank) allocation for an O(file)
  question. The dedup probes only ever ask about the file's own hashes, so the
  pre-write snapshot now reads just those
  (`getMatchesByContentHashesChunked(importHashes() + unscaledImportHashes())`,
  the scaled and pre-scaling sets the two probes use); `getAllContentHashes` is
  gone and `importHashes()` is memoised across its two callers. `Importer` stays
  as the whole-file path (bounded by the 4 MB JSON threshold) and the
  round-trip oracle.
- **FSRS tuning held the whole review log** — `FsrsTuner.optimize` called
  `reviewLogDao.getAll()` (the log as one `List<ReviewLogEntity>`) and
  `buildFsrsTrainingCards` grouped it into a second full-bank structure beside
  the training cards. `buildTrainingCards` now pages the log a set of questions
  at a time (`pageQuestionIdsAfter` + `rowsForQuestions`, one page of entities
  live), and the cards are re-sorted by each question's minimum row id to keep
  the exact order `groupBy` produced. `buildFsrsTrainingCards` stays as the
  test oracle; `FsrsTrainingLogStreamTest` pins the paged build to it at page
  sizes down to one. (The fit itself is still a batch over every card, by
  design — see `scaling.md` §5.2.)
- **Deleting a paper or category read the whole id list first** —
  `PaperStore.deletePaper` / `deleteImpact` called `questionDao.getIdsByPaper`
  (and `deleteCategory` `getIdsByCategory`), materialising every question id of
  a million-question paper just to delete its bookmarks or count them for the
  confirmation dialog. The paper→question relation is a join through
  `categories`, so it is now resolved in SQL: `bookmarkDao.removeAllByPaper` /
  `removeAllByCategory` and `bookmarkDao.countByPaper` /
  `questionDao.countByPaper` do the delete and the count in one statement each,
  before the cascade removes the questions. `getIdsByPaper` / `getIdsByCategory`
  are gone.
- **The stats Workload / Memory / Cards tabs held the whole log and the whole
  card library** — `StatsViewModel` fed them `inputOf(loadedLibrary(),
  loadedReviews())`, two lists cached for the ViewModel's life: one
  `CardSnapshot` per studied card and one `ReplayReview` per review. Each tab
  now streams through its own accumulator (`StatsStore.loadWorkload` /
  `loadMemory` / `loadCards`), the same objects the in-memory build feeds:
  per-day buckets and per-question roll-ups instead of the log, and — for the
  histograms' true medians, which no counter can give — a `DoubleArray` of
  eight bytes a value instead of an object a card. `StatsStore.loadLibrary` /
  `loadReviews` stay only as the stream tests' oracle. (`StatsTabsStreamTest`
  gates the streamed build against the in-memory one.)
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
