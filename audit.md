# Audit — bugs / technically unsound only

> Auditor ping: verification replies are inline below, marked `**Reply**`.

**Reply — scope of this verification:** I checked the current dirty working tree, not only `HEAD`. `git status` shows the scheduler removal as uncommitted changes/deletions; committed `HEAD` still contains Room v7, `MIGRATION_6_7`, `CardStateEntity`, scheduler code, and the APKG paths. I independently reran `:app:compileDebugKotlin` and it fails with the unresolved scheduler symbols.

Scope: UI and functionality choices are out of scope per request. Only bugs and technically unsound patterns are listed.

Verified by `:app:compileDebugKotlin` on the dirty working tree (fails) + code reading. `HEAD` still contains the scheduler implementation and v7 migration; P0 below is a dirty-tree break, not a committed-`HEAD` break.

## P0: working tree does not compile — partial feature removal

`SpacedRepetition.kt`, `ui/study/*`, `MigrationTest`, `SpacedRepetitionTest` were deleted and `AppDatabase.kt`, `Daos.kt`, `Entities.kt`, `McqRepository.kt` were stripped to v6 / no-scheduler, but callers were left intact:

**Reply — confirmed for the dirty working tree:** the deletions appear in `git status`; the remaining scheduler references are real; and the compiler stops on them. `ImportSchedulingTest.kt` was not itself deleted, so it also cannot compile once test compilation is reached. This is not a committed-`HEAD` break: `git show HEAD:.../AppDatabase.kt` still has `version = 7` and `MIGRATION_6_7`.

* `app/src/main/java/com/mcqapp/data/io/Importer.kt:5,217-218` — `CardStateEntity` / `db.cardStateDao()`
* `app/src/main/java/com/mcqapp/data/export/PaperExporter.kt:53-63` — `db.cardStateDao().getByPaper()`
* `app/src/main/java/com/mcqapp/ui/settings/SettingsViewModel.kt:107-127` — `domain.SchedulerConfig`, `repository.schedulerConfig()` / `setSchedulerConfig()` / `resetSchedulerConfig()` (no longer exist in `McqRepository.kt`)
* `app/src/main/java/com/mcqapp/ui/settings/SettingsScreen.kt:67,259-262,402-599` — `schedulerConfig`, `AnkiSchedulerSection`, all `config.copy(...)` fields
* `app/src/main/java/com/mcqapp/ui/library/LibraryScreen.kt:145` — `'when' must be exhaustive, add APKG branch` (format list vs. `when` diverged in same edit)
* `app/src/test/java/com/mcqapp/ImportSchedulingTest.kt:94,112,124,139,163` — `db.cardStateDao()`

Compiler output (`:app:compileDebugKotlin`):

* `PaperExporter.kt:53: Unresolved reference 'cardStateDao'`
* `Importer.kt:5,217-218: Unresolved reference 'CardStateEntity' / 'cardStateDao'`
* `SettingsViewModel.kt:107,112,120-127: Unresolved reference 'SchedulerConfig' / 'schedulerConfig' / 'setSchedulerConfig' / 'resetSchedulerConfig' / 'sanitized'`
* `SettingsScreen.kt:67,261,407-599: Unresolved reference 'SchedulerConfig' + `collectAsState` type-inference failure`
* `LibraryScreen.kt:145: 'when' expression must be exhaustive`

**Reply — confirmed:** my compile reproduced the `cardStateDao`, `CardStateEntity`, `SchedulerConfig`, scheduler-method, and `LibraryScreen` exhaustiveness failures. For `LibraryScreen`, the working tree deleted `exportApkgLauncher` and its `ExportFormat.APKG` branch, while `ExportFormat.APKG` remains declared, so the `when` is incomplete. The ImportScheduling references are likewise unresolved by inspection.

Fix: either revert the deletion or finish it (delete scheduler UI / import / export paths + test, fix `LibraryScreen` `when`).

**Reply — agreed, with one missing product decision:** finishing the removal is not only deleting dead references. The same uncommitted diff also removes direct `.apkg` import handling, scheduling import/export, study entry points, scheduler settings, and associated tests. The owner should explicitly choose “no scheduler/APKG scheduling” before this is stitched up.

Downgrade side-effect: `app/src/main/java/com/mcqapp/data/local/AppDatabase.kt:62` is now `version = 6` with only `3_4, 4_5, 5_6`. Any install that already ran v7 has no downgrade path and no destructive fallback → Room `IllegalStateException` on open. Needs an explicit decision: keep `MIGRATION_6_7`, or add a downgrade/migration policy.

**Reply — confirmed as a rollback-specific migration hazard:** the diff removes both the `card_state` entity and `MIGRATION_6_7`; Room has no downgrade fallback configured here. So a database file already at v7 cannot simply be opened by this v6 tree. Whether “any install” is affected depends on which APK/database users actually have, but the code state needs the explicit policy the audit requests.

## P1: data integrity

* `app/src/main/java/com/mcqapp/data/local/Entities.kt:90-98` — `CorrectAnswerEntity` has no `FK(questions)`, unlike `OptionEntity`. `McqRepository.kt:381-388 deleteQuestion/deleteQuestions` relies on `DELETE questions` cascading options but leaves orphan `correct_answers` rows. Same for `BookmarkEntity:100-104` (no FK; skipped in UI but never cleaned, so bookmarks accumulate for deleted questions).

**Reply — confirmed:** neither child table declares a foreign key to `questions`; `deleteQuestion`/`deleteQuestions` delete only the question row; and I found no bookmark cleanup on question deletion. The UI does tolerate missing rows through `mapNotNull`, but tolerance is not cleanup, so orphan bookmark and answer-key rows can accumulate.
* `app/src/main/java/com/mcqapp/data/repository/McqRepository.kt:332-379 saveQuestion`, `402-422 move/copy`, `471-491 bulkUpdate`, `442-468 duplicatePaper`, `494-502 swapQuestionOrder` — multi-row writes with no `withTransaction`. `saveQuestion` does `delete options → insert options → delete answers → insert answers`; a crash in the middle loses the answer key. `swapQuestionOrder` does two `updateSortOrder` calls non-atomically; a crash leaves both rows with the same order.

**Reply — confirmed:** there is no `withTransaction` or Room `@Transaction` anywhere in `McqRepository.kt`. Individual DAO statements are atomic, but these multi-statement repository operations are not all-or-nothing. The `saveQuestion` delete/reinsert ordering and the two-step order swap are the clearest examples.
* `app/src/main/java/com/mcqapp/data/io/ContentHash.kt:12-15` (+ duplicated logic in `McqRepository.kt:315-319`) — hash covers `text + optionTexts + optionImages` only. The narrow hash is deliberate and documented, and the import report tells the user duplicates were skipped, but the residual defect is real: an answer/explanation/marks-only correction is classified as duplicate and not applied. `McqRepository.computeContentHash` also duplicates the canonical formula despite the "single source of truth" comment and can drift.

**Reply — confirmed in substance, with context:** the narrow hash is deliberate—`ContentHash.kt` and the README both document that answers/explanations do not affect duplication—and the UI/report does tell the user that duplicates were skipped. The remaining technical defect is real: an answer-only correction is not applied, and `McqRepository.computeContentHash` duplicates the canonical hash formula despite the “single source of truth” comment.
* `app/src/main/java/com/mcqapp/data/io/Importer.kt:60-66` — paper identity falls back from `id` to `title`. Intentional for bare-array imports with parse-local ids (and documented), but the residual hazard is that genuinely different same-titled papers can merge. `109-114` "all hashes exist → skip paper" compares only against the database-wide hash set with no per-category/per-source-paper attribution, so the counter can overstate locally meaningful duplicates.

**Reply — confirmed, with a wording correction:** the title fallback is intentional for bare-array imports whose paper ids are parse-local, and the README documents title merging. The hazard remains that genuinely different same-titled papers can merge. For `109-114`, the incoming hashes are compared, but only against the database-wide hash set; there is no per-category or per-source-paper attribution, so that counter can overstate locally meaningful duplicates.
* `app/src/main/java/com/mcqapp/data/local/Daos.kt:91-100` — `search` / `searchIncludingOptions` use `LIKE '%' || :query || '%'` with no `ESCAPE`. `%` and `_` are still `LIKE` metacharacters (bound params prevent SQL injection, but not wildcard semantics), and the leading `%` prevents index use; also full-table scan, no FTS. Currently `searchGlobal` post-filters with literal Kotlin `contains`, so the observed impact is overfetch/scan cost, not proven wrong results — frame as performance/future-correctness.

**Reply — partly confirmed:** `%` and `_` are SQLite `LIKE` metacharacters, and the leading `%` prevents index use; I also found no FTS table. Two qualifications: bound parameters still prevent SQL injection, and `\` is not itself a wildcard without an `ESCAPE` clause. More importantly, `searchGlobal` post-filters with literal Kotlin `contains`, so I did not find wildcard-caused wrong results—only overfetch/scan cost. The finding should be framed as performance/future-correctness, not current wrong-result behavior.

## P1: correctness bugs in shipped code

* `app/src/main/java/com/mcqapp/data/export/HtmlPaperWriter.kt:106-107` — `appendImage(question.image)` is called twice in `appendQuestion`; static HTML renders every question image twice (quiz path is correct).

**Reply — confirmed:** both calls are adjacent and unconditional; quiz mode calls the image helper once. The existing HTML image test only asserts presence, not count, which is why this survives.
* `app/src/main/java/com/mcqapp/data/anki/AnkiPackageWriter.kt:223-224 letterFor` — index ≥ 26 yields `(1A)`-style labels (26 → `(1A)`, 27 → `(1B)`) instead of `AA/AB`. Display-only deviation; the reader's option regex accepts that shape so our round trip is unaffected.

**Reply — confirmed as a display-only deviation, with a small correction:** index 26 yields `(1A)`, index 27 yields `(1B)`, and so on. The reader’s option regex accepts that shape, so this does not break our round trip; it only differs from spreadsheet-style `AA/AB` labeling.
* `app/src/main/java/com/mcqapp/data/repository/McqRepository.kt:584,597` — `saveAttempt` computes `isCorrect` twice with different semantics. The scoring loop treats empty `correctOptionIds` as `ungraded` (excluded from score); the stored `QuestionResultEntity.isCorrect` recomputes `selected == correct` even for ungraded questions, so a stored result can say `false` for a question excluded from the score.

**Reply — confirmed:** aggregate scoring excludes the ungraded row, but line 597 stores strict selected/answer equality for that same row. The surviving repository test checks aggregate score/max, not the per-question stored `isCorrect`, so it would not catch this inconsistency.
* `app/src/main/java/com/mcqapp/data/io/LegacyParser.kt:47-52,121-125,180-184,283-287` — `catch (_: Exception) { null / emptyList }`. One malformed paper/question is silently dropped; a scalar root returns an empty file ("No papers found" downstream, no diagnostic pointing at the bad row).

**Reply — confirmed as an intentional-but-lossy robustness tradeoff:** line 179 explicitly says malformed rows are skipped rather than fatal. A completely unreadable file still surfaces through `loadJson`, but a bad paper/category/question/answer list inside an otherwise valid file is dropped without row-level diagnostics. The fix should preserve partial-import tolerance while accumulating errors.
* `app/src/main/java/com/mcqapp/data/anki/AnkiPackageReader.kt:636-680 MediaIndex.mimeOf` — weak sniffing (two-byte PNG/GIF checks, `RIFF`-without-`WEBP` labeled WebP, JPEG fallback). Only the data-URI label is affected; bytes are unchanged. Tag handling is narrower than first stated: our own difficulty is restored from `payload.difficulty` (`AnkiPackageReader.kt:360-365`); the residual issue is foreign packages whose `mcqapp-difficulty-*` tags are stripped with no fallback recovery path.

**Reply — partly confirmed:** the MIME sniffing is weak—two-byte PNG/GIF checks, `RIFF`-without-`WEBP` labeled WebP, and JPEG as the fallback—but only the data-URI label is affected; the bytes are unchanged. The tag observation is also narrower than stated: our own difficulty is restored from `payload.difficulty` at reader lines 360–365. The genuine residual issue is foreign packages: their `mcqapp-difficulty-*` tags are stripped with no fallback recovery path.
* `app/src/main/java/com/mcqapp/data/anki/AnkiPackageWriter.kt:95,111` — `noteId = nowMillis + index`, `card.id = noteId + 1`. Same-millisecond exports can reuse the timestamp base across packages. Intra-package collision is not established: within one export note ids and card ids are each distinct, and `notes.id` / `cards.id` are separate primary keys, so a note numerically equaling a card is not a SQLite key collision.

**Reply — only partly confirmed:** same-millisecond exports can indeed reuse a timestamp base across packages. But the “large paper”/intra-package part is not established. Within one export the note ids are distinct, the card ids are distinct, and `notes.id` and `cards.id` are separate primary keys in `AnkiSchema11`, so a note numerically equaling a card is not itself a SQLite key collision. Keep the cross-export timestamp risk; drop or separately prove the intra-package collision claim.

## P2: performance / resources / lifecycle

* `app/src/main/java/com/mcqapp/data/repository/McqRepository.kt:219-231 observeQuestionsForPaper` — triggered by global `observeCategoryCounts()`, then `getByPaper + flatMap getByCategory` (N queries) + `toDomainBulk` on every emission. Any question write reloads the whole paper. `233-253 observeQuestion` maps `observeAll()` (whole table) to find one id. `255-261 getQuestionsForCategories` issues N queries; should be a single `WHERE categoryId IN`.

**Reply — confirmed as read-path inefficiency:** the query shapes are exactly as described. `toDomainBulk` batches option/answer association, but it does not batch the per-category question fetch, and the single-question observer starts from the whole question table. I did not measure user-visible jank, so this stays P2.
* `app/src/main/java/com/mcqapp/data/io/Importer.kt:49-310` — `db.withTransaction { ... ImageDownscale.downscaleDataUri (Bitmap decode/scale/compress) ... }`. Image preparation runs inside the SQLite transaction. `ImportViewModel` runs this on `Dispatchers.Default` but `LibraryViewModel.loadSampleData` invokes the importer from Main scope, so the mechanism is real while blocking-readers/ANR impact is unmeasured. Restructure: downscale / prepare images before entering the transaction.

**Reply — confirmed as a valid restructuring opportunity, with a threading qualification:** the whole import body, including `downscaleDataUri`, runs inside `withTransaction`. `ImportViewModel` runs that work on `Dispatchers.Default`, but `LibraryViewModel.loadSampleData` invokes the importer from `viewModelScope`/Main. So the mechanism is real, while “blocks readers” and “risks ANR” are not measured here. Downscaling first or separating image preparation from the database transaction is the right direction.
* `app/src/main/java/com/mcqapp/util/QuestionImage.kt:32-43` — `produceState` decodes Base64 off Main and does not call `recycle`. Unproven as a leak: unreferenced `Bitmap`s are GC-managed and recycling a bitmap still owned by composition would be unsafe. Rapid `src` changes can transiently retain the old bitmap until collection. Needs a memory profile before calling it a native-heap leak; downgraded from defect to needs-measurement.

**Reply — not confirmed as a leak:** the code does decode off Main and does not call `recycle`, but an unreferenced Android `Bitmap` is GC-managed; recycling a bitmap still owned by composition would be unsafe. Rapid `src` changes can transiently retain the old bitmap until collection, but the audit does not demonstrate accumulation or native-heap growth. This needs a memory profile before being called a leak.
* `app/src/main/java/com/mcqapp/util/Logger.kt:96-107` — `synchronized + file.appendText` runs synchronously on the caller thread and the `renameTo` rotation result is unchecked. Whether repository `d()` calls hit Main depends on the caller (some import/database paths are backgrounded, view-model/Main call sites are not), so this is a threading/robustness smell; the unchecked `renameTo` is the crispest part.

**Reply — confirmed as a threading/robustness smell, not universally proven as Main-thread IO:** logging and rotation do happen synchronously on whatever thread called them, and the rotation result is unchecked. Whether “every repository `d()`” hits Main depends on the caller: some import/database paths are explicitly backgrounded, while view-model/Main call sites are not. The ignored `renameTo` result is the crispest part.
* `app/src/main/java/com/mcqapp/ui/navigation/McqNavHost.kt:30` — `repository.fontScale().collectAsState()` instead of `collectAsStateWithLifecycle` (`MainActivity.kt:8` uses the preferred API; feature screens also use plain `collectAsState`). Confirmed as a consistency/low-risk cleanup; continued collection while stopped and CPU/battery cost are unmeasured, so not a demonstrated lifecycle bug.

**Reply — confirmed as an inconsistency/low-risk cleanup:** the lifecycle-aware dependency is already present and `MainActivity` uses the preferred API, while the nav host and feature screens use plain `collectAsState`. I did not establish continued collection while stopped or any resulting CPU/battery cost, so this is a consistency recommendation rather than a demonstrated lifecycle bug.
* `app/src/main/java/com/mcqapp/data/anki/AnkiPackageReader.kt:557-568 unzip`, `AnkiPackageWriter.kt:297-325 zip`, `PdfPaperWriter`, `ZipPaperWriter`, `ImportViewModel.loadJson` — whole package/file held as `ByteArray` / `Map<String, ByteArray>` with Base64/data-URI copies alongside decoded bytes, so peak memory can substantially exceed the nominal 26 MB input noted in `ImageDownscale.kt`. Demonstrated crash / device-class OOM is unmeasured — keep as P2 memory-architecture concern, not a proven crash. `AnkiMediaPool.kt:25` dedupe by full data-URI string adds to the retained copies.

**Reply — partly confirmed:** the package reader/writer paths do retain whole-package bytes, unzip all entries into a byte map, and add Base64/data-URI copies alongside decoded image bytes, so peak memory can substantially exceed the nominal 26 MB input. But “will OOM on low-end devices” is not proven by the cited comment; no heap measurement, device memory class, or failing large-file reproduction was supplied. Keep it as a P2 memory-architecture concern, not a demonstrated crash.
* Temp SQLite files in `AnkiPackageWriter.kt:271-294` and `AnkiPackageReader.kt:82-97` — normal-exception cleanup via `finally { file.delete() }` is present. Missing `deleteOnExit` would not fix process kill (`SIGKILL`/Android process death skips shutdown hooks). Stronger fix: startup cleanup of the known temp prefix plus checking the `delete()` result.

**Reply — partly confirmed:** normal-exception cleanup is present, and missing `deleteOnExit` is true, but `deleteOnExit` would not solve process kill either. Shutdown hooks do not run after `SIGKILL`, and Android process death is not orderly JVM shutdown. The stronger fix is startup cleanup of the known temp prefix, plus checking the `delete()` result.
* `AppDatabase.kt:22 exportSchema = false`, migrations forward-only — ordinary unless schema history or app rollback is required. Actionable part duplicates the P0 rollback issue above: the uncommitted v7→v6 rollback needs an explicit downgrade/migration decision.

**Reply — acknowledged as a duplicate of the P0 rollback issue:** `exportSchema = false` and forward-only migrations are ordinary unless schema history or app rollback is required. The actionable defect remains the uncommitted v7→v6 rollback without a downgrade/migration decision.

## Notes (checked, not bugs)

* `BUILDING.md` / `AGENTS.md` ARM64 notes (`aapt2FromMavenOverride`, Robolectric aarch64 SQLite) match the observed environment behavior.
* Anki schema-11 legacy `.apkg` with no `meta`, three-field notetype with `mcqapp` JSON payload, front=options / back=answers, and filename-based (not numeric) media `src` are deliberate interop choices per `AGENTS.md` and code comments.

**Reply — no dispute:** I did not rerun the ARM64 SQLite failure in this pass, but the cited Anki shapes match the reader/writer code and comments I inspected.

---

**Auditor ping:** verification is complete and inline above. The dirty working tree does not compile; `HEAD` still contains the scheduler implementation and v7 migration. Most P0/P1 code observations check out, while the leak, intra-package ID collision, wildcard-result, and OOM claims need the qualifications noted before they are treated as proven defects.
