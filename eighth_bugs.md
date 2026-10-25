# Eighth bug report — scoped pass: import and export

A scoped pass over the import/export feature — **status:** report only, nothing
fixed yet. Files covered: the JSON readers (`LegacyParser.kt`, `JsonPull.kt`,
`ImportPreview.kt`, `StreamingImporter.kt`, `StreamingImportSession.kt`), the
importer (`Importer.kt`, `ContentHash.kt`), the import UI
(`ImportViewModel.kt`), the exporters (`Exporter.kt`, `PaperExporter.kt`,
`PaperJsonWriter.kt`, `ZipPaperWriter.kt`, `MediaSpool.kt`, `Backup.kt`) and
the Anki writer (`AnkiPackageWriter.kt`, `AnkiMediaPool.kt`, `AnkiHtml.kt`).

Already-known findings are not repeated: the `JsonPull.skipValue` recursion
(`bugs_all.md` #4, first hunt), the DOCX answer-separator bug, the duplicate
option-id REPLACE, and the import-button gating for an all-duplicate backup.

**Confidence legend:** **Confirmed (run)** = proven by a test that fails on this
host; **Confirmed (code)** = proven by reading the current code, with a
regression test added for CI. This host is aarch64 and cannot run Robolectric,
so SQLite-backed tests are CI-authoritative.

---

## Summary

| # | Sev | Area | Bug | Conf. |
|---|-----|------|-----|-------|
| 1 | High | Parser | `JsonPull.scan()` recurses once per consecutive separator → `StackOverflowError`; it is an `Error`, so every `catch (Exception)` misses it and the app crashes | Confirmed (run) |
| 2 | High | Importer | A question id already owned by **another paper** is REPLACE-upserted into the importing paper: the stored question moves, losing its paper, options and answer key | Confirmed (code) |
| 3 | High | Importer | A passage id already owned by another paper moves too, and the stray-folding pass then drags that paper's member questions into the importing paper | Confirmed (code) |
| 4 | Medium | Import preview | Same content under a different id counts as **new** in the preview but is skipped as a duplicate by both importers | Confirmed (run) |
| 5 | Low | Parser | A root `questions` wrapper placed **before** `papers` becomes an extra paper in the streaming reader (preview, large import) but not in the whole-file reader (small import) | Confirmed (run) |
| 6 | Nit | Anki export | `deckIds` is seeded with the raw paper title while notes use the cleaned segment, so a title containing `::` exports an extra empty deck | Confirmed (code) |

---

## Findings

### 1 · HIGH · Parser — separator runs overflow the stack and crash the app

`app/src/main/java/com/mcqapp/data/io/JsonPull.kt:205-217`

```kotlin
private fun scan() {
    skipWhitespace()
    val c = read()
    ...
    when (val ch = c.toChar()) {
        ',' , ':' -> {
            // A separator outside string context: the next token follows.
            scan()          // <-- one frame per separator
            return
        }
```

`scan()` calls itself for every consecutive `,`/`:` instead of looping. A
hundred thousand commas in one spot (legal input, trivially produced by a
corrupt or hostile file) overflows the stack. The `skipValue` recursion was
made iterative earlier; this one was missed.

**Impact.** `StackOverflowError` is an `Error`, not an `Exception`. It escapes
every parse boundary — `LegacyParser.parse` (`LegacyParser.kt:1278`),
`StreamingImporter` (`Importer.kt:749`), and the import screen's handlers
(`ImportViewModel.kt:404,484,543,577,662,691,710,870,920`) — so the whole
process dies instead of the screen reporting "Could not parse the file".

**Evidence.** `app/src/test/java/com/mcqapp/JsonPullSeparatorRecursionTest.kt`
— both tests fail locally with `StackOverflowError` at `JsonPull.kt:215`.

**Fix.** Turn the separator branch into a loop (skip separators until a real
token), which also keeps the `Token` contract. Optionally also catch
`Throwable` (rethrow `CancellationException`) at the parse boundary so no
future `Error` can kill the app.

---

### 2 · HIGH · Importer — a question id owned by another paper is moved, not copied

`app/src/main/java/com/mcqapp/data/io/Importer.kt:404-431`

```kotlin
val existingQuestion = db.questionDao().getById(questionDto.id)
val resolvedSortOrder = if (existingQuestion == null) { ... } else {
    updatedQuestions++
    existingQuestion.sortOrder
}
...
db.questionDao().upsert(
    scaledQuestion.toQuestionEntity(categoryId = effectiveCatId, ...)
)
```

`getById` is global: it does not check which paper owns the row. The upsert is
`@Insert(onConflict = OnConflictStrategy.REPLACE)`
(`Daos.kt:1244-1245`), so the stored row is deleted and re-inserted with the
**importing** category. The question leaves its original paper; its options
and correct answers are replaced immediately after (`Importer.kt:471-476`).

The category path explicitly guards against this case
(`Importer.kt:242-253`: "A category id owned by a DIFFERENT paper must never be
reused: remap to stay unique (else CASCADE wipe)"). Questions have no such
guard. `StreamingImportSession` has the same hole (`:459-484`).

**Trigger.** Any file that reuses an id the library already uses for a
different question under another paper. Hand-authored banks number questions
from `1` (the README examples use `q-s1`, `q-r1`, `q1`), so two different banks
collide easily. The content check does not help: different text means a
different hash, so the question is treated as an update, not a duplicate.

**Impact.** Silent cross-paper data loss: paper A loses the question, and paper
B silently replaces it (text, options, answer key) with the incoming one.
Re-importing a bank under a new title is safe only because identical content is
deduplicated first; different content under the same id is not.

**Evidence.** `ImportPaperIdentityTest.aQuestionIdOwnedByAnotherPaperIsNotMoved`
(CI-authoritative; Robolectric cannot run on this host).

**Fix.** Resolve the incoming id's owning paper the way categories are resolved:
if the row exists under a different paper, remap it to a fresh id (e.g.
`"$effectivePaperId-$id"`, the category convention) or skip it with a warning,
instead of overwriting. Do the same in `StreamingImportSession`.

---

### 3 · HIGH · Importer — a reused passage id drags another paper's questions along

`app/src/main/java/com/mcqapp/data/io/Importer.kt:293-306` and `:485-497`

```kotlin
val existing = db.passageDao().getById(passageId)
db.passageDao().upsert(
    passageDto.withDownscaledImages().toPassageEntity(categoryId = effectiveCatId, ...)
)
...
for (passageId in paperPassageIds) {
    val passage = db.passageDao().getById(passageId) ?: continue
    val strays = db.questionDao().getMemberCategoriesByPassage(passageId)
        .filter { it.categoryId != passage.categoryId }
    ...
    db.questionDao().updateCategory(listOf(stray.id), passage.categoryId)
}
```

The passage upsert has the same REPLACE/move problem as finding 2 (passage
ids are primary keys). Worse, the stray-folding pass then looks up **all**
members of that passage id — including the questions of the paper that owned
it — and moves every member whose category differs into the importing paper's
category. A single colliding passage id migrates a whole passage group across
papers. `StreamingImportSession.kt:569-589,592-608` mirrors it.

**Evidence.** `ImportPaperIdentityTest.aPassageIdOwnedByAnotherPaperDoesNotDragMembers`
(CI-authoritative).

**Fix.** Same ownership check as finding 2 for the passage row, and restrict
the fold to ids this import actually wrote (or to members whose paper is the
importing one).

---

### 4 · MEDIUM · Import preview — "new" in the preview, "duplicate" in the import

`app/src/main/java/com/mcqapp/ui/importscreen/ImportViewModel.kt:297-335`

```kotlin
val sameContent = storedById[q.id]?.let { stored ->
    ContentHash.sameQuestionContent(stored, storedOptionsByQuestion[q.id].orEmpty(), scaled)
} == true
if (hash in existingHashes && (sameContent || q.id in existingIds)) {
    ...
} else if (q.id in existingIds) {
    changed.add(q.id)
}
```

`storedById` only covers the **incoming** ids: `refreshDuplicates`
(`:451-467`) collects ids that match and hashes that match, then fetches rows
for `interesting = ids ∪ hashes`, but `classifyPreview` looks up
`storedById[q.id]` only. A question whose content matches a stored row under a
*different* id therefore has `sameContent == false`; with `q.id !in
existingIds` it is counted as **new**.

Both importers deduplicate by content across the whole library
(`Importer.kt:583-597` `isSameContentAsAnyStored`, `:371-373`; the streaming
session's twin at `:449-457`), so they skip it. The preview says
"Import N new" and the import adds nothing — and an all-duplicate paper is
skipped outright (`Importer.kt:196-223`) while the preview still counts its
questions as new.

**Evidence.**
`ImportPreviewClassificationTest.sameContentUnderADifferentIdIsADuplicateLikeTheImporter`
— fails locally (`AssertionError` at the test's classification assertion).

**Fix.** Classify against the stored rows that matched by hash as well, not just
the same id: pass the hash-matched rows into `classifyPreview` (or have it ask
`isSameContentAsAnyStored`-style across `storedOptionsByQuestion` for the
hash candidates).

---

### 5 · LOW · Parser — root `questions` before `papers` adds a phantom paper

`app/src/main/java/com/mcqapp/data/io/LegacyParser.kt:334-355` (streaming) vs
`:711-717` (whole file)

```kotlin
// streaming: decides when the key is read
"questions" -> {
    ...
    val asPaper = papersEmitted == 0
    if (asPaper) sink.onPaper(PaperHeader(paperId, "Imported Questions", "", 0, 0.0))
    ...
}

// whole file: decides after the whole object is walked
if (papersEmitted == 0 && topLevelQuestions != null) {
    onPaper(ephemeralPaper(topLevelQuestions!!))
}
```

JSON objects are unordered, so a file may list `questions` before `papers`. The
streaming reader (used by the preview and by large imports) then emits an
ephemeral "Imported Questions" paper *and* the real `papers`; the whole-file
reader (used by small imports and `parse`) drops the root questions because a
paper exists. The same file yields two papers or one depending on its size.

**Evidence.** `LegacyParserTest.rootQuestionsBeforePapersAddsNoPaper` fails
locally: whole-file `[p1]` vs streaming `[paper-import-…, p1]`.

**Fix.** In the streaming walk, remember the root questions and decide after
the object ends (as the whole-file walk does), or track a `papers`-seen flag
and drop the wrapper's paper if `papers` appears later.

---

### 6 · NIT · Anki export — a paper title containing `::` exports an extra deck

`app/src/main/java/com/mcqapp/data/anki/AnkiPackageWriter.kt:132-134,341-361`

```kotlin
val deckIds = LinkedHashMap<String, Long>()
deckIds[paperTitle.trim()] = DECK_ID            // raw title
fun deckIdFor(name: String): Long = deckIds.getOrPut(name) { DECK_ID + deckIds.size }
...
deckId = deckIdFor(deckNameFor(paperTitle, categoryPaths[categoryId]))
```

Notes use `deckNameFor` → `segmentFor(paperTitle)`, which replaces the `::`
separator with `: `. The map is seeded with the *raw* trimmed title, so a paper
titled `A::B` registers deck `A::B` (id 1, unused) and deck `A: B` (id 2), and
`decksJson` writes both. Anki imports the phantom `A::B` as an empty nested
deck. A blank title has the same effect (`""` vs `Untitled`).

**Fix.** Seed with `segmentFor(paperTitle, "Untitled")`, the same value the
notes use.

---

## How these were verified

- Findings 1, 4 and 5 fail on this host with the pure-JVM suite:

  ```bash
  . apk-build/env.sh
  sh gradlew :app:testDebugUnitTest \
    -Pandroid.aapt2FromMavenOverride=/tmp/mcq-apk-env/aapt2/aapt2 \
    --dependency-verification=lenient --no-daemon \
    --tests JsonPullSeparatorRecursionTest \
    --tests ImportPreviewClassificationTest \
    --tests LegacyParserTest
  ```

  A full pure-JVM pass is 984 tests, 4 failures — exactly these three findings
  (finding 1 has two tests).

- Findings 2, 3 and 6 are SQLite/Anki-writer paths. Robolectric cannot run on
  this aarch64 host ("The Robolectric native runtime is not supported on Linux
  (aarch64)"), so the regression tests for 2 and 3 are CI-authoritative:
  `ImportPaperIdentityTest.aQuestionIdOwnedByAnotherPaperIsNotMoved` and
  `aPassageIdOwnedByAnotherPaperDoesNotDragMembers`. Finding 6 is a pure map
  seed and needs no DB.
