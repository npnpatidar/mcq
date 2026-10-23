# Consolidated bug reports

This is the single-file consolidation of the six bug-report files in the repository: `bugs.md` (first hunt), `new_bugs.md` (second hunt), `third_bugs.md`, `fourth_bugs.md`, `fifth_bugs.md`, and `sixth_bugs.md`.

The original files remain in the tree so existing references such as `new_bugs.md #2` keep resolving. Finding numbers restart in each report, exactly as they do in the source files; use the section name with a number when referring to a finding unambiguously.

## Contents

- [First bug hunt (`bugs.md`)](#first)
- [Second bug hunt (`new_bugs.md`)](#second)
- [Third bug hunt (`third_bugs.md`)](#third)
- [Fourth bug report (`fourth_bugs.md`)](#fourth)
- [Fifth bug report (`fifth_bugs.md`)](#fifth)
- [Sixth bug report (`sixth_bugs.md`)](#sixth)

---

## Verification status

Verdict: **Bug** = observable incorrect behaviour/data loss; **Nit** = quality, doc, or latent edge; **Mixed** = bundle with both; **Not a bug** = premise contradicted; **By design** = intended trade-off. Status: **Fixed**, **Open**, **Partially fixed**, or **N/A**.

Current result: **84 findings — 82 fixed, 0 partially fixed, 0 open, 2 N/A**.

### First bug hunt

| # | Verdict | Status | Evidence |
|---|---|---|---|
| 1 | Bug | Fixed | `EditorViewModel.kt:358-360` picks the first unused id; regression test. |
| 2 | Bug | Fixed | `LegacyParser.kt:551-554` memoises the resolved category id. |
| 3 | Bug | Fixed | `AnkiPackageReader.kt:695-706` decodes protobuf; test seeds a cloze config. |
| 4 | Bug | Fixed | `McqRepository.kt:882-884` clears `nextKey` before the null break. |
| 5 | Bug | Fixed | `Importer.kt:338,387`; `Daos.kt:1044` persists the scaled image. |
| 6 | Bug | Fixed | `PaperStore.kt:232-233` sweeps card/review rows before deletion. |
| 7 | Bug | Fixed | `AnkiPackageReader.kt:1160,1182` selects the cloze number, not list position. |
| 8 | Bug | Fixed | `DocxParser.kt:909-931` tries the whole option text before separators. |
| 9 | Bug | Fixed | `DocxPaperWriter.kt:535,763` keeps a marker with its first content block. |
| 10 | Bug | Fixed | `StreamingImportSession.kt:322-325` flushes empty papers' categories. |
| 11 | Bug | Fixed | `PdfPaperWriter.kt:188-190,717` prints the legacy explanation in the key. |
| 12 | Bug | Fixed | `Fsrs.kt:269-271` clamps in `Long`, narrows to `Int` last. |
| 13 | Bug | Fixed | `AnswerReview.kt:41` uses `searchableText` for row summaries. |
| 14 | Bug | Fixed | `DocxReader.kt:451-453` reads `r:id` for VML images. |
| 15 | Bug | Fixed | `HtmlPaperWriter.kt:109-114` renders `looseQuestions`. |
| 16 | Bug | Fixed | `HtmlPaperWriter.kt:198-207` emits the passage image without elements. |
| 17 | Bug | Fixed | `PdfPaperWriter.kt:651-655` keeps unknown inline tags literal. |
| 18 | Nit | Fixed | `MediaSpool.kt:69-70` percent-encodes names injectively. |
| 19 | Bug | Fixed | `MathMlToOmml.kt:24,27,72,220` handles void/transparent/attribute forms. |
| 20 | Bug | Fixed | `AnkiHtml.kt:24` accepts both hex cases in one decode pass. |
| 21 | Nit | Fixed | `DocxPaperWriter.kt:1064-1066` returns `this` after appending a char. |
| 22 | Nit | Fixed | `ContentHash.kt:193` compares difficulty case-insensitively. |
| 23 | Bug | Fixed | `Importer.kt:797-808` scales images inside `elements`. |
| 24 | Nit | Fixed | `SessionOrder.kt:107` de-duplicates; the dead `reorder` path is gone. |
| 25 | Bug | Fixed | `ImportViewModel.kt:250-253` keeps the import button enabled for a backup. |
| 26 | Bug | Fixed | `StudyViewModel.kt:441-452`; `TestViewModel.kt:478-486` gate on visibility. |

### Second bug hunt

| # | Verdict | Status | Evidence |
|---|---|---|---|
| 1 | Bug | Fixed | `AnkiPackageReader.kt:581` uses `max(id)`; regression test at `AnkiPackageReaderTest.kt:910`. |
| 2 | Bug | Fixed | `DocxParser.kt:658,687`; writer shares the `(a)`-`(z)` marker guard. |
| 3 | Bug | Fixed | `MathMlToOmml.kt:153` strips XML-illegal controls; round-trip test. |
| 4 | Bug | Fixed | `JsonPull.kt:109` is iterative; 50,000-level test passes. |
| 5 | Bug | Fixed | `TestViewModel.kt:315` keeps the resume gate before the timer starts. |
| 6 | Bug | Fixed | `LegacyParser.kt:1307-1332` applies `uniqueOptionIds` and warns. |
| 7 | Bug | Fixed | `SelectionExport.kt:29` hashes a stable category id. |
| 8 | Bug | Fixed | `PaperStore.kt:176,237`; `QuestionStore.kt:367,381` sweep `question_stats`. |
| 9 | Bug | Fixed | `Daos.kt:659` searches folded columns; repository binds folded terms. |
| 10 | Bug | Fixed | `TestViewModel.kt:502,540,566` refuses edits/navigation after submit. |
| 11 | Bug | Fixed | `SettingsViewModel.kt:298` exports on `Dispatchers.IO`. |
| 12 | Bug | Fixed | `ImageUtils.kt:37,75` applies EXIF uprighting on encode. |
| 13 | Bug | Fixed | `NumericInput.kt:15` is the shared comma-aware parser. |
| 14 | Bug | Fixed | `PdfPaperWriter.kt:339` bands oversized blocks. |
| 15 | Bug | Fixed | `AnkiScheduling.kt:180-200` emits learn/relearn rows. |
| 16 | Bug | Fixed | `HtmlPaperWriter.kt:142` includes `explanationImage`. |
| 17 | Bug | Fixed | `RichTextHtml.kt`/`PdfMath.kt` share one entity decoder. |
| 18 | Bug | Fixed | `ExportImages.kt:31` returns a bounded unreadable label. |
| 19 | Bug | Fixed | `DocxPaperWriter.kt:863-887` computes EMU in `Long`. |
| 20 | Bug | Fixed | `Exporter.passagesOf` carries selection/bookmark passages. |
| 21 | Bug | Fixed | `StreamingImportSession.kt:195-206,683-696` buffers and applies schedules last. |
| 22 | Bug | Fixed | `AnkiPackageReader.kt:880-892`; writer `:213-219` writes image-derived `sfld`. |
| 23 | Bug | Fixed | `AnkiPackageReader.kt:861-863` searches below the passage `<hr>` only. |
| 24 | Bug | Fixed | `TestProgressStore.kt:57,75-78` serialises rows then header under a mutex. |
| 25 | Bug | Fixed | `RepositorySettings.kt:296,317-326` keeps `in_progress_test` out of settings snapshot/restore; regression tests. |
| 26 | Bug | Fixed | `Daos.kt:304-313` uses a `(createdAt, questionId)` keyset cursor. |
| 27 | Bug | Fixed | `McqRepository.kt:573-613` resolves categories to rowids. |
| 28 | Nit | Fixed | `PaperStore.kt:127-137` uses `insertIgnore` plus targeted updates. |
| 29 | Nit | Fixed | `InlineHtml.kt:98-105`, DOCX dead branch, and `ChartKit.kt:231` last-label collision helper used by both charts; test. |
| 30 | Bug | Fixed | `AnkiPackageWriter.kt:77,245,159-164` fixes U+001F, `col.mod`, card ids. |
| 31 | Bug | Fixed | `Backup.kt:633-635` counts `paper.questions`. |
| 32 | Bug | Fixed | `StreamingImporter.kt:113-133` stops the parser via `AtomicBoolean`. |
| 33 | Bug | Fixed | `StreamingImportSession.kt:601` reads the category-id projection. |
| 34 | Bug | Fixed | `StudyViewModel.kt:735-740` re-pulls before resetting the drill. |
| 35 | Bug | Fixed | `StatsViewModel.kt:117,125-128` drops stale continuations. |
| 36 | Bug | Fixed | `ImageDownscale.kt:66-79` decodes bounded; OOM fallback remains. |
| 37 | Bug | Fixed | `Logger.kt:20-33` uses `DateTimeFormatter`; `:103-124` writes crash lines synchronously. |
| 38 | Mixed | Fixed | `HistoryScreen.kt:273` and `StorageInfo.kt:33-34` use `Locale.ROOT`; `BrowseScreen.kt:577-638` rejects invalid marks; `SettingsScreen.kt:917-955` steps the typed value; chart label helper. |
| 39 | Mixed | Fixed | `ImportRunJournal.kt:87-91` uses the recorded total and rejects a changed file; settings restore preserves the resume slot; `statsReviews` remains test-only and `NoDeadCodeTest`-guarded; `dueNow` by design; Exception catches accepted. |

### Third bug hunt

| # | Verdict | Status | Evidence |
|---|---|---|---|
| 1 | Bug | Fixed | `StudyViewModel.kt:209-215`; `CategoryFlatOrderTest` pins 600-category alignment. |
| 2 | Bug | Fixed | `ImageUtils.kt:49-56` preserves PNG transparency. |
| 3 | Nit | Fixed | `LibraryPaperCard.kt:222-226,363-374`; strings/plurals at `strings.xml:95-104,144-146`. |
| 4 | Nit | Fixed | `BrowseViewModel.kt:139-148` shares `initialFilterState`. |
| 5 | Not a bug | N/A | `BrowseViewModel.kt:382-383`: both lambdas read the live `windowIds`. |
| 6 | Nit | Fixed | `BrowseViewModel.kt:686,722` names the `JUMP_PAGES` constant pair. |
| 7 | Nit | Fixed | `McqRepository.kt:129-135`; `NoDeadCodeTest` rejects production callers. |
| 8 | Bug | Fixed | `PickedArchive.kt:46-71` counts only real files. |

### Fourth bug report

| # | Verdict | Status | Evidence |
|---|---|---|---|
| 1 | Bug | Fixed | `StudyViewModel.kt:775-782` clears loading and sets `loadError`; test 502caf5. |
| 2 | Bug | Fixed | `StudyViewModel.kt:65,597,617,635,655` stores `gradeError`; `StudyScreen.kt:246,288` shows it inline and leaves the card retryable. |
| 3 | Nit | Fixed | `Fsrs.kt:21-23`; `StudyViewModel.kt:710-714` now describe previews as instant-specific, not promises. |

### Fifth bug report

| # | Verdict | Status | Evidence |
|---|---|---|---|
| 1 | Bug | Fixed | `TestTiming.kt:41`; `TestViewModel.kt:343-370` anchor on `elapsedRealtime`; test. |
| 2 | Bug | Fixed | `TestSessionScreen.kt:147-149` uses `rememberSaveable` for all three flags. |
| 3 | Nit | Fixed | `TestSessionScreen.kt:162-192,651`; `strings.xml:460-495`; `AnswerReview` dash remains a symbol. |

### Sixth bug report

| # | Verdict | Status | Evidence |
|---|---|---|---|
| 1 | Bug | Fixed | `DocxReader.kt:252-281` applies insertions and drops deletions/move-outs; test. |
| 2 | Nit | Fixed | `DocxParser.kt:46-63` counts only non-directory files. |
| 3 | Nit | Fixed | `DocxPaperWriter.kt:1140-1168` skips no-length markers; regression test. |
| 4 | Bug | Fixed | `DocxPaperWriter.kt:169-177` warns when the passage is missing. |
| 5 | By design | N/A | `DocxPaperWriter.kt:211` clamps headings at 9; payload preserves deeper trees. |

---

<a id="first"></a>

## Bug hunt — `main` @ 12166c3

**Scope:** all production Kotlin under `app/src/main/java/com/mcqapp/` (368 files), reviewed by
subsystem against the current tree. This complements `audit.md` (which covers the 0.0.25 → HEAD
refactor and marks its 15 findings fixed); every finding below is a defect that is **still present**
on `main`.

**Method:** read the production code and traced the triggering input; each item quotes the current
source. Where a claim depends on behaviour outside the repo (real Anki packages, Robolectric/SQLite)
it is marked accordingly

**Confidence legend:**

- **Confirmed** — the defect is proven by reading the current code and the exact input.
- **Likely** — the code defect is real but one step of reachability depends on data shape.
- **Uncertain** — flagged with the specific doubt; verify before fixing.

**Baseline note:** this host is Linux/aarch64, so Robolectric-backed (SQLite) tests cannot run
locally; SQL behaviour is marked *CI-only* where relevant.

---

### Summary

| # | Severity | Area | Bug | Confidence |
|---|---|---|---|---|
| 1 | High | Editor | Adding an option can mint a duplicate option id | Confirmed |
| 2 | High | Import (JSON) | Streaming regenerates a category id per call for id-less categories | Confirmed |
| 3 | High | Import (Anki) | `notetypes.config` / `decks.kind` protobuf blobs parsed as JSON | Likely |
| 4 | High | Search | Paging source leaves a stale `nextKey`, duplicating the last window | Confirmed |
| 5 | Medium | Import | Image-only diffs trigger a refresh that never persists the image (repeats forever) | Confirmed |
| 6 | Medium | Library | `deleteCategory` orphans `card_state` / `review_log` rows | Confirmed |
| 7 | Medium | Import (Anki) | Cloze stem leaks the answer; cloze index uses list position, not cloze number | Confirmed |
| 8 | Medium | Import (DOCX) | Answer separator splits valid option-text answers and rejects the document | Confirmed |
| 9 | Medium | Export (DOCX) | Every field marker is written on its own paragraph | Confirmed |
| 10 | Medium | Import (JSON) | Streaming drops a new paper's empty categories | Confirmed |
| 11 | Medium | Export (PDF) | Answer key drops legacy explanations / omits such questions | Confirmed |
| 12 | Medium | FSRS | `intervalFor` narrows to `Int` before clamping | Likely |
| 13 | Medium | Results | Pre-submit row summary blank for formula/table options, shows markup | Confirmed |
| 14 | Medium | Import (DOCX) | VML `v:imagedata` reads `r:embed` instead of `r:id` → image skipped | Confirmed |
| 15 | Low | Export (HTML) | Top-level "loose" questions counted but not rendered | Confirmed |
| 16 | Low | Export (HTML) | Passage image dropped when the passage has no elements | Confirmed |
| 17 | Low | Export (PDF) | Unknown inline tags stripped, contradicting the KDoc | Confirmed |
| 18 | Low | Export | `MediaSpool` flattens `/`→`-`, aliasing two names to one file | Confirmed (latent) |
| 19 | Low | Export (DOCX) | `MathMlToOmml` mishandles `<tag />` and whitespace around `<math>` | Confirmed |
| 20 | Low | Import (Anki) | `AnkiHtml.decodeEntities`: uppercase hex entities left literal, double-decoding | Confirmed |
| 21 | Low | Export (DOCX) | `XmlEscapingAppendable.append(Char)` returns the delegate, not `this` | Confirmed (latent) |
| 22 | Low | Import | `difficulty` casing (`Medium` vs `medium`) causes a one-off spurious refresh | Confirmed |
| 23 | Low | Import | `withDownscaledImages` ignores images inside `elements` | Confirmed (doc mismatch) |
| 24 | Low | Test session | `TestSnapshot.reorder` does not de-duplicate saved ids | Uncertain |
| 25 | Low | Import | Import button disabled for a small all-duplicate *backup* | Uncertain |
| 26 | Low | Study/Test | Foregrounding can restart a hidden question's dwell clock | Uncertain |

---

### Findings

#### 1 · HIGH · Editor — adding an option can mint a duplicate option id · Confirmed

`app/src/main/java/com/mcqapp/ui/editor/EditorViewModel.kt:356-362`

```kotlin
fun addOption() = updateState {
    val nextLetter = ('a' + it.options.size).toString()
    it.copy(options = it.options + OptionEditorState(id = nextLetter, ...))
}
```

The new id is derived from `options.size`, not from the ids already present, and
`removeOption` (`:364-367`) never renumbers.

**Trigger.** Edit a question whose options are `a, b, c` (imported/exported ids), remove the
middle option `b` (allowed while `size > 2`) → options are `[a, c]`, `size == 2`. Tap **Add
option** → `'a' + 2 == 'c'`, so a second option with id `"c"` is created.

**Impact.** `toggleCorrect` (`:350`) maps over every option and toggles *both* `c`s; the move,
image and element helpers act on the first/both; `buildDto` writes two `OptionDto`s with the same
id and may emit `correctOptionIds = ["c"]`. The question's key becomes ambiguous and the duplicate
round-trips into the DB and exports.

**Fix.** Pick the first unused letter (`('a'..'z').first { l -> options.none { it.id == l.toString() } }`
or `generateSequence { ... }`), or renumber on removal.

#### 2 · HIGH · Import (JSON) — streaming regenerates a category id per call · Confirmed

`app/src/main/java/com/mcqapp/data/io/LegacyParser.kt:536-554`

```kotlin
fun categoryId(): String = id?.takeIf { it.isNotBlank() }
    ?: "cat-" + System.currentTimeMillis().toString(36) + "-" + (0..9999).random()
...
onFirst = { announce() },                       // announce() calls categoryId()
onBatch = { sink.onQuestions(categoryId(), it) } // a *second* categoryId() call
```

`categoryId()` is a function, so every call with a blank/absent `id` mints a **different** string.

**Trigger.** A category object with no `id` (or where `id` appears after `questions`, since JSON
member order is not fixed):

```json
{"papers":[{"id":"p","title":"P","categories":[{"title":"C",
  "questions":[{"id":"q1","text":"t","options":[{"id":"o1","text":"a"}],"correctOptionIds":["o1"]}]}]}]}
```

**Impact.** The category row is written under id A (from `announce()` /
`StreamingImportSession.onCategory`), but each batch is stamped B (`effectiveCatByRawId[B] ?: B`).
With the enabled FK `questions.categoryId → categories.id` (Entities.kt:55-60) the write fails with
`SQLiteConstraintException: FOREIGN KEY constraint failed` and the import aborts. The whole-file
reader (`readCategory`, LegacyParser.kt:862-864) calls the generator once, so the two paths
disagree.

**Fix.** Resolve the id once when the category object opens (or memoise it per category), and pass
the resolved id to both `announce()` and the batches.

#### 3 · HIGH · Import (Anki) — protobuf `config`/`kind` blobs are parsed as JSON · Likely

`app/src/main/java/com/mcqapp/data/anki/AnkiPackageReader.kt:619-639` and `:644-657`

```kotlin
val k = try {
    json.parseToJsonElement(String(c.getBlob(1) ?: ByteArray(0)))
        .jsonObject["kind"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
} catch (e: Exception) { 0 }
...
val isDynamic = try {
    json.parseToJsonElement(String(c.getBlob(2) ?: ByteArray(0)))
        .jsonObject.keys.contains("terms")
} catch (e: Exception) { false }
```

In the schema-15+ era Anki stores `notetypes.config` and `decks.kind` as **prost protobuf
blobs**, not JSON. The throw is swallowed, so a real package silently degrades:

- every cloze notetype gets `isCloze = false` → cloze notes import as single-option recall
  questions (`toQuestion` never calls `clozeQuestion`);
- every filtered (dynamic) deck gets `isDynamic = false` → imported as a real paper instead of
  being skipped (the schema-11 path at `:606` gets this right via `dyn`).

The rod test at `AnkiPackageReaderTest` seeds `config = x''` / `kind = x''`, so it never exercises a
real blob. *Reachability caveat:* schema 15–17 non-zstd `collection.anki21` packages from
2.1.28–2.1.49 (newer packages are zstd and rejected at `:77-82`). Verify against a real export
before/after the fix.

**Fix.** Decode the blob as protobuf (a tiny hand-rolled reader for field 1 `kind` varint, or a
schema detector), and fall back to JSON only when the bytes are valid JSON.

#### 4 · HIGH · Search — stale `nextKey` duplicates the last window · Confirmed

`app/src/main/java/com/mcqapp/data/repository/McqRepository.kt:796-808`

```kotlin
var nextKey: Int? = null
while (out.size < target) {
    val page = fetch(offset, window)
    out += page.hits
    val advanced = page.nextOffset ?: break   // breaks BEFORE nextKey is cleared
    offset = advanced
    nextKey = advanced
}
... Page(data = out, prevKey = null, nextKey = nextKey)
```

`searchPage` returns `next = if (raw.size == limit) offset + raw.size else null` (`:275`), i.e.
`nextOffset == null` means the candidate set is exhausted. When the loop consumes a terminal
window, it `break`s before `nextKey` is reset, so the **previous** window's offset stays.

**Trigger.** A query needing ≥ 2 candidate windows where the last window is terminal and yields
hits. Trace (`window = 200`, `loadSize = 30`): load #1 fetches offset 0 (next=200, `nextKey=200`)
then offset 200 (next=null, break) → returns the offset-200 hits with `nextKey = 200`. Load #2
re-fetches offset 200 and returns **the same hits again**.

**Impact.** Duplicate rows in the search list, and `SearchScreen` keys the `LazyColumn` by
`hits.itemKey { it.question.id }`, so a repeated key can also throw in Compose.

**Fix.** On `page.nextOffset == null` set `nextKey = null` before breaking (e.g.
`val advanced = page.nextOffset; nextKey = advanced; if (advanced == null) break`).

#### 5 · MEDIUM · Import — image-only diff causes a repeating refresh · Confirmed

`app/src/main/java/com/mcqapp/data/io/ContentHash.kt:175-177` vs
`app/src/main/java/com/mcqapp/data/local/Daos.kt:910-920`

```kotlin
return stored.image != dto.image || ... || stored.explanationImage != dto.explanationImage || ...
```
```sql
UPDATE questions SET explanation = :explanation, marks = :marks,
    difficulty = :difficulty, tags = :tags WHERE id = :id
```

`nonHashedFieldsDiffer` compares the images, but `updateNonHashedFields` updates neither. At the
call site (`Importer.kt:323`) `dto` is the **unscaled** file question while `stored.image` holds
the **downscaled** bytes.

**Trigger.** Import a file with an over-1280 px `data:` image, then re-import it with
`updateAnswersOnDuplicate = true`. The content hash matches (computed on the scaled twin,
`Importer.kt:294-299`), but `stored.image != dto.image` is permanently true.

**Impact.** `answersRefreshed++` and the answer-row delete/upsert run on **every** re-import,
even though nothing changed (non-idempotent; inflated counts).

**Fix.** Compare the scaled DTO (`scaledQuestion`), or drop `image`/`explanationImage` from the
diff (they are not updated here anyway).

#### 6 · MEDIUM · Library — `deleteCategory` orphans schedules and history · Confirmed

`app/src/main/java/com/mcqapp/data/repository/PaperStore.kt:170-189`

```kotlin
db.categoryDao().reparentChildren(...)
db.bookmarkDao().removeAllByCategory(categoryId)
db.categoryDao().deleteById(categoryId)   // cascades questions only
```

`questions` cascades from `categories`, and `options`/`correct_answers` from `questions`, but
`card_state` and `review_log` have FKs **only to `papers`** (Entities.kt:155-165, 206-215). The
sibling paths handle this explicitly (`QuestionStore.deleteQuestion:312-314`,
`deleteQuestions:325-331`, `moveQuestionsToCategory:372-376`), and there is no orphan sweep
anywhere.

**Trigger.** Study/grade a question, then delete its category (not the paper).

**Impact.** Orphaned `card_state`/`review_log` rows survive. Stats streams them unscoped
(`StatsStore.pageCardsInto`, `replayInto`), so the overview/cards distributions and
`deleteImpact.schedules` stay inflated; backups re-export them.

**Fix.** Collect the category's question ids (slim `SELECT id`) before the category delete and
sweep `card_state`/`review_log` for them in the same transaction.

#### 7 · MEDIUM · Import (Anki) — cloze stem leaks the answer; index is list position · Confirmed

`app/src/main/java/com/mcqapp/data/anki/AnkiPackageReader.kt:1002-1021`

```kotlin
val text = fieldToText(frontHtml, media).first        // raw field, markers intact
val deletions = Regex("\\{\\{c(\\d+)::(.*?)(?:::.*?)?\\}\\}", ...)
    .findAll(frontHtml).map { it.groupValues[1].toIntOrNull() ?: 0 to it.groupValues[2] }.toList()
val target = deletions.getOrNull(ord)
```

`AnkiHtml.toPlainText` strips HTML tags but not `{{cN::…}}`, so the stem keeps the markup **and the
answer**. And `ord` is Anki's `cloze_number − 1`, independent of where the deletion sits, so using
`deletions[ord]` picks the wrong deletion when cloze numbers are out of order/repeated (e.g.
`{{c2::a}} {{c1::b}}` with `ord 0` hides `b`, but `deletions[0]` is `a`), and can silently drop a
real card.

**Fix.** Replace matched `{{cN::x}}`/`{{cN::x::hint}}` with a blank in the stem text and select the
deletion whose number equals `ord + 1`.

#### 8 · MEDIUM · Import (DOCX) — answer separator rejects valid option-text answers · Confirmed

`app/src/main/java/com/mcqapp/data/docx/DocxParser.kt:652` and `:860-882`

```kotlin
private val answerSeparator = Regex("\\s*(?:,|;|&|\\band\\b)\\s*", RegexOption.IGNORE_CASE)
...
val tokens = answer.split(answerSeparator)...
else if (tokens.size > 1) fail("answer '$answer' mixes letters with other text; ...")
```

The parser documents an older form where `Ans.` holds the option's **own text** (`:879`), but any
such text containing `,` `;` `&` or ` and ` is split into multiple tokens, which are not single
letters, so the whole document is rejected.

**Trigger.** `Ans. Yes, definitely` where option `(c)` is `Yes, definitely`; also `Rock and roll`,
`A & B`. Single-token fallback at `:878-881` only saves answers with none of those characters.

**Fix.** Before failing, try matching the whole trimmed answer against an option's text first.

#### 9 · MEDIUM · Export (DOCX) — every field marker gets its own paragraph · Confirmed

`app/src/main/java/com/mcqapp/data/export/DocxPaperWriter.kt:680`, `:523`, `:739`

```kotlin
private var wroteAnything = prefix != null      // true before any content is added
...
fun hasContent(): Boolean = wroteAnything || runs.isNotEmpty()
...
if (item.breaksParagraph && current.hasContent()) flush()   // :523
```

`fieldBlocks` builds a fresh `ParaBuilder(markerPrefix)`; `wroteAnything` is already true, so
`hasContent()` is true and the first stacked element flushes immediately. `flowsInline` is
`elements.any { it is MathElement }` (`:476`), so a plain text option/stem has
`breaksParagraph = true` (`:480`).

**Trigger.** Any normal text field, e.g. option `text = "Alpha"` → output
`<w:p>(a) </w:p><w:p>Alpha</w:p>` instead of `<w:p>(a) Alpha</w:p>`. This contradicts the comment
at `:519-523` and the writer KDoc. Round-trip still recovers the text, so it is a layout defect.

**Fix.** Track content separately from the prefix (e.g. `wroteAnything` starts `false`; the prefix
sets `atStart=false` only), so the first element joins its marker.

#### 10 · MEDIUM · Import (JSON) — streaming drops a new paper's empty categories · Confirmed

`app/src/main/java/com/mcqapp/data/io/StreamingImportSession.kt:300-304` vs
`app/src/main/java/com/mcqapp/data/io/Importer.kt:176-177`

```kotlin
val skipped = isNewPaper && !needsWrite
if (skipped) { pendingCategories.clear() }
```
```kotlin
if (isNewPaper && incomingHashes.isNotEmpty() && incomingHashes.all { it in existingHashes }) {
```

The whole-file importer requires at least one question before skipping, so a paper holding only an
empty category is written; the streaming path clears the pending categories whenever no question
needed writing.

**Trigger.** `{"papers":[{"id":"p","title":"P","categories":[{"id":"c","title":"Empty"}]}]}` —
the paper shell is created but category `"c"` is lost. (A second cause: an all-duplicate paper whose
images were downscaled on storage also has no write but an existing category set.)

**Fix.** Match the whole-file condition (`questionsSeenInPaper > 0 && all duplicates`) or flush the
categories before deciding.

#### 11 · MEDIUM · Export (PDF) — answer key drops legacy explanations · Confirmed

`app/src/main/java/com/mcqapp/data/export/PdfPaperWriter.kt:181-190`

```kotlin
val hasEntry = answerText.isNotEmpty() || question.explanationElements.isNotEmpty()
...
w.elements(question.explanationElements, question.explanationImage)   // no legacy `explanation`
```

The inline path passes the legacy text (`:155-159`); the key path omits it and does not count it in
`hasEntry`.

**Trigger.** `QuestionDto(correctOptionIds=["b"], explanation="Because", explanationElements=[])`:
the key prints `Q1. B` but not the explanation. With `correctOptionIds=[]` and a legacy
explanation, `hasEntry` is false and the question disappears from the key entirely.

**Fix.** Pass `question.explanation` as the third arg and include it in `hasEntry`.

#### 12 · MEDIUM · FSRS — `intervalFor` narrows to `Int` before clamping · Likely

`app/src/main/java/com/mcqapp/domain/Fsrs.kt:258-263`

```kotlin
val days = (stability / factor) * (desiredRetention.pow(1.0 / decay) - 1.0)
return Math.round(days).toInt().coerceIn(1, config.maxIntervalDays)
```

`Math.round` returns a `Long`; `.toInt()` truncates **before** `coerceIn`, so `days ≥ 2^31` wraps to
an arbitrary value and the clamp then floors it to `1` or passes the wrapped value through. At the
default retention this is `stability` itself, and stability is never capped (only floored).

**Trigger.** `stability = 2_147_483_648.0` → `.toInt() = -2147483648` → clamp `1` (expected
`36500`); `stability = 4_294_967_396.0` → `.toInt() = 100` → `100`. Reachable via the tuner:
`FsrsOptimizer.LOWER_BOUNDS[9] = 0.0`, and `recallStability` uses `stability.pow(-w[9])`, so a
fitted `w[9] = 0` gives multiplicative growth that can cross 2^31.

**Fix.** `Math.round(days).coerceIn(1L, config.maxIntervalDays.toLong()).toInt()`.

#### 13 · MEDIUM · Results — pre-submit row summary blank / markup leak · Confirmed

`app/src/main/java/com/mcqapp/domain/AnswerReview.kt:36-47`

```kotlin
val byId = question.options.associate { it.id to it.text }
...
summary = if (selected.isEmpty()) "—" else selected.map { byId[it] ?: it }.joinToString(", ")
```

`QuestionOption.text` is `elements.textContent` (Models), which only collects `TextElement`s. So an
option made of a `MathElement`/`TableElement` yields `""` (blank row), and inline markup such as
`<b>2</b>` is shown verbatim. This is the same class of bug Browse/Search were fixed for with
`previewText`/`searchableText` (commit `64adab4`), not applied here.

**Fix.** Build the summary from `previewText` (markup-free, formula/table aware).

#### 14 · MEDIUM · Import (DOCX) — VML images read the wrong attribute · Confirmed

`app/src/main/java/com/mcqapp/data/docx/DocxReader.kt:419-422`

```kotlin
val q = it.getAttribute("r:embed")
if (q.isNotBlank()) q else it.getAttributeNS(REL_NS, "embed")
```

VML `<v:imagedata>` carries the reference in `r:id`, not `r:embed` (`a:blip` uses `r:embed`), so
`ref` is null for VML pictures → the image is dropped with a warning.

**Fix.** For `imagedata`, read `r:id` / `REL_NS "id"`.

#### 15 · LOW · Export (HTML) — loose questions counted but not rendered · Confirmed

`app/src/main/java/com/mcqapp/data/export/HtmlPaperWriter.kt:76`, `:84`

`body(...)` renders only `stream.walk(...)`, which by contract does not visit
`PaperStream.looseQuestions` (top-level `paper.questions`), yet the header uses
`stream.totalQuestions`, which includes them. DOCX (`DocxPaperWriter.kt:210-218`) and JSON
(`PaperJsonWriter.kt:40`) do render them. Narrow reach via the current UI, but the contract is
violated and the counts lie.

**Fix.** Render `looseQuestions` (e.g. before/after the categorized walk) or exclude them from the
header count.

#### 16 · LOW · Export (HTML) — passage image dropped when elements are empty · Confirmed

`app/src/main/java/com/mcqapp/data/export/HtmlPaperWriter.kt:174-177`

```kotlin
if (passage.elements.isNotEmpty()) {
    out.append(elementsToHtml(passage.elements, passage.image))
}
```

A passage with a legacy `image` but no `elements` prints only its title; PDF and DOCX both render
the legacy image. Narrow (the DB path usually yields `[TextElement("")]`), but inconsistent.

**Fix.** Call `elementsToHtml` unconditionally, or render `imageTag(passage.image)` in the else.

#### 17 · LOW · Export (PDF) — unknown inline tags silently stripped · Confirmed

`app/src/main/java/com/mcqapp/data/export/PdfPaperWriter.kt:496-521`

The KDoc promises "Unknown tag shapes and bare `&`/`<` stay visible as text", but no branch appends
an unknown tag (`:521` even comments "Unknown opens are stripped"). Legacy text like
`Pick <one> & "all"?` loses `<one>` in PDF while HTML/DOCX keep it.

**Fix.** Append `match.value` for unknown non-closing tags (or correct the KDoc if stripping is
intended).

#### 18 · LOW · Export — `MediaSpool` name flattening aliases entries · Confirmed (latent)

`app/src/main/java/com/mcqapp/data/export/MediaSpool.kt:56`

```kotlin
val file = File(directory, name.replace('/', '-'))
```

`add("a/b", X)` and `add("a-b", Y)` both write `<dir>/a-b`, so `copyTo("a/b")` returns `Y`. No
current caller produces colliding names, so latent.

**Fix.** Map the on-disk name injectively (percent-encode `/`) or use a counter.

#### 19 · LOW · Export (DOCX) — `MathMlToOmml` self-closing / whitespace edge cases · Confirmed

`app/src/main/java/com/mcqapp/data/docx/MathMlToOmml.kt:21`, `:45`, `:150-155`

```kotlin
private val mathTag = Regex("<(/?)([a-zA-Z][a-zA-Z0-9]*)(\\s[^<>]*)?(/?)>")
```

- `<mspace />`: the greedy `(\s[^<>]*)?` eats `" /"`, so `selfClosing == false`; `mspace` opens and
  its following sibling becomes a child that is then ignored (`convertNode("mspace")` drops
  children) — content lost.
- `" <math>…</math>"`: a whitespace `Txt` node makes `forest.size != 1`, so `top = forest` and
  `convertNode(El("math"))` hits `else -> null` → the equation is refused.
- The parser's own doc says whitespace-only text is dropped; `parseMathTree` does not drop it.

Narrow (the in-app sanitizer emits compact, non-self-closing MathML), but reachable from imported
MathML that is passed through verbatim.

**Fix.** Make group 3 not swallow the self-closing slash (e.g. `(\s[^<>/]*[^<>]*)?` or check
`match.value.endsWith("/>")`), drop blank `Txt` nodes in `parseMathTree`, and handle a `math`
element in `convertNode`.

#### 20 · LOW · Import (Anki) — entity decoding edge cases · Confirmed

`app/src/main/java/com/mcqapp/data/anki/AnkiHtml.kt:19`, `:67-84`

- `NUMERIC_ENTITY = Regex("&#(x?[0-9a-fA-F]+);")` — `x?` is case-sensitive, so `&#X2713;` is left
  literal (`MathMlToOmml` uses `[xX]`, so the two decoders disagree).
- The numeric pass runs before `.replace("&amp;", "&")`, so `&#38;amp;` becomes `&amp;` and then
  `&` — a double decode (`&amp;lt;` stays `&lt;`, so it is order-dependent).

Low impact, genuine correctness defects.

#### 21 · LOW · Export (DOCX) — `XmlEscapingAppendable.append(Char)` violates the contract · Confirmed (latent)

`app/src/main/java/com/mcqapp/data/export/DocxPaperWriter.kt:1013`

```kotlin
override fun append(c: Char): Appendable = out.append(escaped(c))
```

`Appendable.append(Char)` must return `this`; returning the wrapped `OutputStreamWriter` means a
chained `xml.append('x').append(str)` would bypass escaping. No current caller chains, so latent.

#### 22 · LOW · Import — `difficulty` casing causes a one-off spurious refresh · Confirmed

`app/src/main/java/com/mcqapp/data/io/QuestionDtoMapper.kt:32`

`difficulty = difficulty.label` writes the capitalized label (`Medium`), while the parser stores
the raw file value (`medium`, `LegacyParser`) and the whole-bank exporter passes the raw entity.
A `BookmarkExport`/`SelectionExport` re-import therefore sees `stored.difficulty != dto.difficulty`
once and refreshes. It converges after the first cycle, so it is a one-off false change, not a loop.

**Fix.** Compare case-insensitively, or emit the lower-case enum name from the mapper.

#### 23 · LOW · Import — `withDownscaledImages` ignores images inside `elements` · Confirmed (doc mismatch)

`app/src/main/java/com/mcqapp/data/io/Importer.kt:787-791`

```kotlin
/** Deep-copies the file with every question image downscaled ... */
internal fun QuestionDto.withDownscaledImages(): QuestionDto = copy(
    image = ..., explanationImage = ..., options = ... )
```

`QuestionDto.elements` / `explanationElements` / `OptionDto.elements` can hold
`ContentElement.ImageElement`, and `ContentHash.of` ignores those srcs, so oversized inline element
images stay full-size in the stored JSON (bloat), contrary to the doc. `OptionListJson`
(`JsonDto.kt:68-82`) also loses `OptionDto.text` when `elements` is empty (round-trips as empty);
the current write paths always populate elements, so that half is informational.

#### 24 · LOW · Test session — `TestSnapshot.reorder` does not de-duplicate · Uncertain

`app/src/main/java/com/mcqapp/domain/TestSnapshot.kt:116-123`

```kotlin
val ordered = savedIds.mapNotNull { byId[it] }
val seen = ordered.map { it.id }.toHashSet()
return ordered + loaded.filter { it.id !in seen }
```

`seen` only guards the appended tail; duplicates already inside `savedIds` survive, so
`loaded = [q1]`, `savedIds = ["q1","q1"]` yields `[q1, q1]` (the same question presented twice).
The seed path goes through `SessionOrder.reorderedTo`, which does guard, so only a malformed
seed-less snapshot can hit this. Verify whether such a snapshot is producible.

#### 25 · LOW · Import — button disabled for a small all-duplicate backup · Uncertain

`app/src/main/java/com/mcqapp/ui/importscreen/ImportScreen.kt:526-529`

```kotlin
enabled = state.paperTitle.isNotBlank() &&
    (state.previewTruncated || newQuestions.isNotEmpty() || changedQuestions.isNotEmpty()) &&
    !state.importing
```

`ImportViewModel.import()` deliberately restores a **backup** regardless of the first paper
(`:811-814`, `if (!backup && ...) return`), because papers 2+ and schedules still import. For a
small backup (`< 4 MB`, parsed whole → `previewTruncated == false`) whose paper 1 is entirely
duplicates, the button reads "Nothing new to import" and is disabled while `import()` would have
restored the rest. Confirm whether this is intended gating.

#### 26 · LOW · Study/Test — foregrounding can resume a hidden question's clock · Uncertain

`app/src/main/java/com/mcqapp/ui/study/StudyViewModel.kt:359-361`;
`app/src/main/java/com/mcqapp/ui/test/TestViewModel.kt:421-423`

```kotlin
fun onAppForegrounded() { if (!clockPausedByUser) resumeQuestionClock() }
```

`resumeQuestionClock()`/`setDwellActive(true)` only consult `clockCanRun`/`dwellShouldRun`, not
viewport visibility (`setQuestionVisible`) or a covering dialog (`questionVisible`). A user who
backgrounds the app while the card is scrolled out of view (or under a dialog) and returns resumes
charging dwell, because the visibility `LaunchedEffect` key is unchanged and does not re-fire.
Verify with a device/robolectric test.

---

### Areas reviewed and found clean

These were checked and no concrete defect was found; listed so effort is not duplicated:

- **FSRS core** (`Fsrs.kt` transitions, `FsrsOptimizer` clipping) apart from #12.
- **Browse predicates** — `pagingRefsByCategories` / `countBrowseIds` / `browseIdAtIndex` /
  `browseIndexBefore` filters are byte-identical and mutually consistent.
- **Study pull/count SQL** — `firstDueLearningForPaper`, `bestDueReviewForPaper`, `firstNewCard`,
  `dueForPaper`, `countsForPaper` agree with `Study.pools`.
- **Keyset paging** — `ReviewLogDao.pageAfterId`, `pageQuestionIdsAfter`, `CardStateDao.pageAfterRowId`,
  `AttemptDao.getLatestStandings` agree with their domain oracles.
- **Migrations v3→v17** (schema, indices, order columns) internally consistent.
- **`SafeZip` / zip readers** — no path traversal; entry names are keys only.
- **`Shuffle`, `Scoring`, `Mastery`, `Mistakes`, `DayBoundary`, `ExamTiming`, `TestTiming`,
  `PassageBlocks`, `PassageReorder`, `QuestionSearch`, `Drill`, `Reorder`, `Feedback`,
  `OptionLetter`** (bijective base-26 verified), and all `util/` files.
- **Navigation, `MainActivity`, `McqApplication`** — no defect found.

---

### Suggested priority

1. Fix #1, #2, #4 (data corruption / crash / duplicate UI) and #5, #6 (silent data staleness).
2. Validate #3 and #7–#11 against real `.apkg`/`.docx`/`.pdf` exports before touching them.
3. Treat #15–#26 as cleanup; #24–#26 need a reachability check first.


---

<a id="second"></a>

## New bug hunt — `main` @ f21afc7

**Scope:** all production Kotlin under `app/src/main/java/com/mcqapp/` (162 files), reviewed by
subsystem against the current tree. This is a **second pass**: every finding in `bugs.md`
(marked `main @ 12166c3`) is fixed at this commit and was excluded — and re-tested where
relevant. Everything below is **new**.

**Method:** six subsystem deep-reviews (import, Anki+backup, export/DOCX, UI, repository/DB,
settings/util) plus a manual core review of FSRS, the optimizer, and the session-order domain.
The FSRS-6 kernel (`Fsrs.kt`), `FsrsOptimizer`, `SessionOrder`/`PassageBlocks`/`PassageReorder`,
`QuestionSearch`, `DayBoundary`, and `Models.kt` were verified line-by-line against the
py-fsrs / fsrs-rs references — they are **clean**; see "Areas checked, clean" at the end.

**Confidence legend:** **Confirmed** = defect proven by reading the current code and the exact
input · **Likely** = code defect real, one reachability step depends on data shape ·
**Uncertain** = flagged with the specific doubt.

---

### Summary

| # | Sev | Area | Bug | Conf. |
|---|-----|------|-----|-------|
| 1 | Critical | Import (Anki) | revlog `time` (review *duration*) read as last-review time → all imported decks schedule from 1970 | Confirmed |
| 2 | High | Export (DOCX) | Marker guard covers `(a)`–`(j)` but reader matches `(a)`–`(z)` → exported file can fail its own re-import | Confirmed |
| 3 | High | Export (DOCX) | XML control characters written verbatim → one `\u0007` invalidates the whole `.docx` | Confirmed |
| 4 | High | Import (JSON) | `JsonPull.skipValue` recursion is unbounded → `StackOverflowError` on deep ignored keys | Confirmed |
| 5 | High | Test session | Fresh session's timer auto-submits through the open Resume dialog, destroying the saved session | Confirmed |
| 6 | Medium | Import (JSON) | Duplicate option ids accepted: second option silently overwrites the first, re-import loops forever | Confirmed |
| 7 | Medium | Export/Import | Selection exports mint positional category ids → changed selection re-import retitles and misfiles categories | Confirmed |
| 8 | Medium | Library | `question_stats` rows are never deleted with their question/paper/category → History's hardest/weakest show dead data forever | Confirmed |
| 9 | Medium | Search/Browse | SQL `LIKE` prefilter folds ASCII case only → non-ASCII case-insensitive hits silently dropped | Confirmed |
| 10 | Medium | Test session | Options/flags/navigating during "Saving result…" are accepted and silently discarded | Confirmed |
| 11 | Medium | Settings | "Export all (JSON)" serialises the whole bank and writes blocking IO on the Main thread → ANR | Confirmed |
| 12 | Medium | Images | EXIF orientation ignored everywhere an image is picked/decoded → sideways photos | Confirmed |
| 13 | Medium | UI (input) | Comma-stripping number filters turn `0,25` into `25.0` silent 25× marks in three screens | Confirmed |
| 14 | Medium | Export (PDF) | A block taller than one page is silently clipped; flow resumes one page down | Confirmed |
| 15 | Medium | Export (Anki) | Learning/Relearning cards export as graduated Review cards with `ivl=0` | Confirmed |
| 16 | Low-Med | Export (HTML) | Image-only explanation dropped from both quiz and answer keys | Confirmed |
| 17 | Low-Med | Export | HTML double-escapes entities the app/PDF/DOCX decode — same question shows three ways; PDF misses `&nbsp;` | Confirmed |
| 18 | Low-Med | Export (PDF) | Malformed `data:` URI dumps the whole (multi-MB) base64 into the PDF as text | Confirmed |
| 19 | Low-Med | Export (DOCX) | EMU width computed in `Int`: image ≥ 225,443 px overflows to a negative extent → invalid document | Confirmed |
| 20 | Low-Med | Export | Bookmark/Selection export streams carry no passages → passage members lose their context | Confirmed |
| 21 | Low-Med | Import (JSON) | Streaming drops the file's review progress when `scheduling` precedes `papers` | Likely |
| 22 | Low-Med | Import (Anki) | Image-only stems silently dropped on import; writer emits inconsistent `sfld` for them | Likely |
| 23 | Low-Med | Import (Anki) | Payload-less fallback misattributes *the passage's* image to the question | Likely |
| 24 | Low | Test progress | Header/rows saved non-atomically; racing saves can persist an older header last | Confirmed |
| 25 | Low | Backup | Restoring a corrupt `inProgressTest` document destroys the user's live resume snapshot | Confirmed |
| 26 | Low | History | Bookmarks page uses `LIMIT/OFFSET` → duplicates/skips when the set mutates mid-scroll | Confirmed |
| 27 | Low | Browse/library | `INSTR(csv)` category filter leaks any category id containing a comma | Confirmed (latent) |
| 28 | Low | Library | `PaperStore.savePaper`/`ensurePaperAndCategory` write via `INSERT OR REPLACE` — latent whole-paper cascade wipe | Confirmed (latent) |
| 29 | Low | Import (DOCX/Writer) | Stray close-tag rendering leaks a stalled style to end-of-text; writer's field-prefix fallback is dead code | Confirmed (cosmetic) |
| 30 | Low | Anki round trip | Writer emits schema/convention inaccuracies that degrade tooling: `col.mod` in ms, card id = next note id, U+001F passthrough into note fields | Confirmed |
| 31 | Low | Backup | Manifest/receipt counts ignore paper-level (`uncategorised`) questions | Confirmed |
| 32 | Low | Streaming | Post-error cleanup busy-loops to the end of a million-question file instead of stopping the parse | Confirmed |
| 33 | Low | Scale | `foldPassageStrays` loads every member's full row (elements JSON + images) to compare one column | Confirmed |
| 34 | Low | Study | Drill **Study again** replays the original schedule states captured at load → previews/chips lie, membership stale | Confirmed |
| 35 | Low | Stats | `openCard` drill-down has no generation guard → slow load shows the *previous* card | Confirmed |
| 36 | Low | Images | `ImageDownscale` decodes full-resolution first; on OOM it passes the oversized original through unfixed | Confirmed |
| 37 | Low | Logging | Crash trace may never reach the log file (async executor + synchronous process death); shared `SimpleDateFormat` is a race | Confirmed |
| 38 | Low | Dirty layouts | Comma-locale numeric settings field unusable; `String.format` default-locale latent crash; last x-axis label collision; mis-nested HTML leaks a style; percent/KB strings mix digit systems | Confirmed (bundle of small UI defects) |
| 39 | Uncertain / notes | mixed | ImportRunJournal mixed-run counts; `statsReviews()` dead path materialises whole log; bookmarks dialog hardcoded string; stale History top-lists; `dueNow` semantics | Uncertain |

---

### Findings

#### 1 · CRITICAL · Import (Anki) — the review *duration* is read as the last-review *timestamp*

`app/src/main/java/com/mcqapp/data/anki/AnkiPackageReader.kt:561` (streaming, the production
path) and `:795` (whole file), consumed at `AnkiScheduling.kt:108`:

```kotlin
"select cid, max(time) from revlog where cid in ($placeholders) group by cid"
...
out[c.getLong(0)] = c.getLong(1) * 1000L
```

```kotlin
val inferred = if (lastReviewedAtMillis > 0L) {
    lastReviewedAtMillis
}
```

In Anki's schema-11 `revlog`, `id` is the review timestamp (epoch **ms**) and `time` is the
**duration** of that review in ms — Anki's own stats code does `sum(time)/1000` for
"seconds studied". `max(time)` picks the *longest review of the card* (e.g. 32,500 ms), and the
`× 1000` multiplies it into `32,500,000` ms ≈ **1970-01-01 09:01**.

**Trigger.** Import any real `.apkg` that carries scheduling (the everyday "study an Anki deck
in this app" flow). Every card with a history gets `lastReviewedAt ≈ 1970`.

**Impact.** On the learner's next grade of that card:
- `Fsrs.elapsedDaysSinceLastReview` computes ~20,700 elapsed days → retrievability ≈ 0;
- a **Good** grade runs `recallStability` with R≈0, inflating stability ≈ tens-fold (S=10 →
  ~322 days) instead of ~2×, invokingState schedules off the FSRS model;
- Study serves the card at 0 % recall (`LOW_RECALL_RISK`) ahead of correctly-anchored peers,
  and the risk ordering compares 1970-dated cards across the board as equal-ancient.

The correct column is `max(id)` (and it is already in ms — the `* 1000` is wrong for a
seconds-duration column and meaningless for `id`). Both reader paths affected; nothing logs a
warning (the "no usable review log" warning is the *absent-log* path — this path silently
succeeds with garbage).

**Fix.** `select cid, max(id) from revlog …` and pass the value through **without** `× 1000`.

---

#### 2 · HIGH · Export (DOCX) — the writer's marker guard covers `(a)`–`(j)`, the reader matches `(a)`–`(z)`

`app/src/main/java/com/mcqapp/data/export/DocxPaperWriter.kt:672-675` vs
`app/src/main/java/com/mcqapp/data/docx/DocxParser.kt:654` and `:835-837`:

```kotlin
private val markerish = Regex("^(\\d{1,7}\\.\\)|\\([a-j]\\)|Ans\\.|Exp:|Passage:)", ...)
```
```kotlin
/** `(a)` … at the start of a line. Any letter is matched so an out-of-range one is caught. */
private val optionMarker = Regex("(?m)^\\(([a-z])\\)")
...
fail("option ($id) is not supported; use (a) to (${OPTION_LABELS.last()})")
```

The reader matches **any** single letter deliberately ("so an out-of-range one is caught"),
and fails when it finds one; the writer guards only `[a-j]`.

**Trigger.** Any stem/option/explanation whose stored text contains a newline followed by a
line starting `(k)` … `(z)` — e.g. `steps:\n(x) final step`, code-choice lists, character
labels. `ParaBuilder.markerish` only escapes `(a)`–`(j)`, so the line is written bare.

**Impact.** Re-importing the app's own export fails with
*"option (x) is not supported; use (a) to (j)"* — and the whole-file import aborts. The
document the app produced cannot round-trip through the parser it was written for.

**Fix.** Widen `markerish` to `[a-z]` (or to the exact reader grammar). One character.

---

#### 3 · HIGH · Export (DOCX) — control characters are written verbatim into `word/document.xml`

`app/src/main/java/com/mcqapp/data/docx/MathMlToOmml.kt:141-148` (the shared escaper, used by
the writer at `DocxPaperWriter.kt:230, 245, 575, 694, 777`):

```kotlin
internal fun escapeXmlText(s: String): String = buildString(s.length + 16) {
    for (c in s) when (c) {
        '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;")
        else -> append(c)                    // U+0000–U+0008, U+000B, U+000C, U+000E–U+001F pass through
    }
}
```

XML 1.0 forbids those code points entirely (only Tab, LF, CR are legal). No writer or importer
sanitises them.

**Trigger.** A question imported from JSON/Anki containing a control character (legal in JSON
and in the DB; e.g. `"\u0007"`, a form feed, or a code-dependent escape from an Anki field),
then exported as DOCX.

**Impact.** The raw byte lands in `document.xml`: Word ("unreadable content"), Google Docs and
the app's **own** `DocxReader` XML parser all reject the whole package — one `\u0007` makes the
entire exported bank unreadable. The app's DOCX import path cannot produce them, so this is
exclusively the DOCX *write* side.

**Fix.** Strip (or replace with a visible stand-in and warn) XML-illegal characters in
`escapeXmlText`.

---

#### 4 · HIGH · Import (JSON) — `skipValue()` recursion is unbounded

`app/src/main/java/com/mcqapp/data/io/JsonPull.kt:100-117`:

```kotlin
fun skipValue() {
    when (current) {
        Token.BEGIN_OBJECT -> { beginObject(); while (hasNext()) { if (peek() == Token.NAME) nextName(); skipValue() } ... }
        Token.BEGIN_ARRAY  -> { beginArray();  while (hasNext()) skipValue() ... }
```

`LegacyParser.kt` docstring promises "a hostile `[[[[…]]]]` cannot overflow the stack either;
the slice reader keeps its own MAX_NEXTING_DEPTH budget" — but that budget belongs to
`readElement` only. The **nine** `reader.skipValue()` call sites (unknown paper members,
`questions`-as-object typeMismatch, unknown category members, etc.) recurse with no depth
budget — once per nesting level.

**Trigger.** A syntactically valid bank with a deeply nested value under any key the parser
doesn't read: `{"metadata": [[[[ [… 10k levels deep ]]]]]}`.

**Impact.** `StackOverflowError`. On the streaming path it degrades to "Could not parse the
file" (fine); on the whole-file/preview path nothing catches `Error` — the import screen
takes an unmediated crash. (A run of `,,,,` inside an ignored array also nests frames, but
that needs malformed input; the deep *valid* file needs nothing but depth.)

**Fix.** Iterative `skipValue` (explicit stack), or a depth counter with a loud failure.

---

#### 5 · HIGH · Test session — the resume dialog races the fresh session's timer; auto-submit destroys the saved session

`app/src/main/java/com/mcqapp/ui/test/TestViewModel.kt:282-289` (load), `:305-328` (timer),
`:626-687` (submit):

```kotlin
checkResumeOffer(order)             // publishes resumeOffer — the dialog then waits for an explicit choice
...
if (totalSeconds > 0) startTimer()  // the FRESH session's countdown keeps ticking behind the modal
...
if (now <= 0) { submit(); break }   // at zero: saves an EMPTY attempt and clears the snapshot
...
clearSnapshot = { repository.clearTestProgress() }   // line 669 — the offer's data source is deleted
```

`questionVisible` pauses *dwell* behind dialogs; nothing gates `remainingSeconds`. The dialog
is `onDismissRequest = {}` (explicit choice required), so it can sit open through the whole
countdown.

**Trigger.** A timed paper (or ≥1-minute drill) with a saved resume offer; leave the dialog
open until the fresh timer hits 0.

**Impact.** A junk 0-answer attempt lands in history, `clearTestProgress()` deletes the
snapshot and its answer rows the dialog was offering, and the dialog (holding the stale
`resumeOffer` val) stays up. Tapping **Resume** afterwards rebuilds the order/answers while
`submitted = true` — `resume()` has no `submitted` guard — then navigates into a submitted
screen whose save is in flight.

**Fix.** In `startTimer`, break while `resumeOffer != null` (and re-run on dismissal), or
don't start the timer until the offer is resolved; and give `resume()` a `submitted/saving`
guard.

---

#### 6 · MEDIUM · Import (JSON) — duplicate option ids silently destroy an option and break idempotence forever

`app/src/main/java/com/mcqapp/data/io/LegacyParser.kt:1447`:

```kotlin
val oid = el["id"]?.jsonPrimitive?.contentOrNull ?: letterIds.getOrElse(index) { index.toString() }
```

Options are never de-duplicated or suffixed (unlike question ids, which have `uniqueIds`).
Entities.kt keys options on `(questionId, id)` with `REPLACE` upserts
(`StreamingImportSession.kt:472-473`, `Importer.kt:449`), and `ContentHash.kt:131` treats
*count* mismatch as a change:

```kotlin
if (storedOptions.size != dto.options.size) return false
```

**Trigger.** A bank with `{"options":[{"id":"a","text":"X"},{"id":"a","text":"Y"}]}` — a
trivially craftable foreign file.

**Impact.** The second `a` REPLACEs the first: option Y is silently lost, and if
`correctOptionIds` pointed at the first occurrence's payload the key now resolves to the wrong
text. Worse, `storedOptions.size(1) != dto.options.size(2)` makes the question *never* a
duplicate — `answersRefreshed++` and a full rewrite on **every** re-import (the idempotence
break already fixed for the image case, resurfacing from a different door).

**Fix.** Reject or magic-suffix duplicate option ids at the DTO boundary (warn, don't rewrite).

---

#### 7 · MEDIUM · Export/Import — Selection exports mint positional category ids

`app/src/main/java/com/mcqapp/data/io/SelectionExport.kt:26-31`, matched at
`Importer.kt:222-223` / `StreamingImportSession.kt:128-133`:

```kotlin
.entries.sortedBy { it.key }.mapIndexed { index, (categoryTitle, grouped) ->
    CategoryDto(id = "selection-$index", title = categoryTitle, ...)
...
val matchInPaper = catsInPaper.find { it.id == categoryDto.id } ?: catsInPaper.find { it.title == categoryDto.title }
```

The id comes out of **titlealphabetical position** in the *current* selection, and import
matches id-first.

**Trigger.** Import a selection of only Physics (creates `selection-0` = "Physics"). Later
import a changed selection `{Chemistry, Physics}` that omits ≥1 Physics question: titles sorted
give Chemistry `selection-0`, Physics `selection-1`.

**Impact.** The existing `selection-0` row is **retitled to Chemistry**, and any Physics
question the second selection omitted (unmatched by either batch, its categoryId never
rewritten) is now filed under the wrong category. BookmarkExport is immune (always a full set).

**Fix.** Derive the export id from the title (stable hash), or match title-first when ids are
prefixed `selection-`.

---

#### 8 · MEDIUM · Library — `question_stats` is never cleaned up

`app/src/main/java/com/mcqapp/data/repository/QuestionStore.kt:307-334`,
`PaperStore.kt:126-148/170-197`, entity at `Daos.kt:329-344`:

```kotlin
// deleteQuestion / deleteQuestions / deletePaper / deleteCategory all sweep:
db.questionDao().deleteById(...); db.cardStateDao()...; db.reviewLogDao()...; db.bookmarkDao()...
```

`QuestionStatsEntity` has **no foreign key and no cascade** (PK = `questionId` alone), and a
repo-wide grep shows no delete path for it — only `applyQuestionStats` and the two History
reads. The orphan-sweep work that fixed `card_state`/`review_log` stopped one table short.

**Trigger.** Finish an attempt (hardest-questions fills up), then delete a question /
category / paper.

**Impact.** `getHardestQuestions` / `getWeakestCategories` read `question_stats` directly (no
join), so History's "Hardest questions" keeps listing deleted questions indefinitely, and
"Weakest categories" counts deleted papers' buckets — the lifetime aggregate is supposed to be
the one honest all-time view. They also ride every subsequent backup. Drill-down on such a row
resolves nothing.

**Fix.** Sweep `question_stats` by the same question ids in each delete site (or an FK cascade
in a migration).

---

#### 9 · MEDIUM · Search/Browse — the SQL `LIKE` prefilter folds ASCII case only

`app/src/main/java/com/mcqapp/data/local/Daos.kt:1041-1057` (+ browse chain `:538-543`,
`:674-679`, `:710-715`), authority at `QuestionSearch.kt:66`:

```kotlin
"LIKE '%' || :like || '%' ESCAPE '\\'"       // SQLite default: case-folds A–Z only
...
val q = query.trim().lowercase()             // Kotlin lowercase(): full Unicode
```

The repository comment declares the contract: *"a superset, never a veto"*. For non-ASCII
text — accented Latin (`CAFÉ` vs `café`), Cyrillic, Greek — `LIKE` does not case-fold, so the
row is discarded **before** the in-memory filter that Case-folds properly ever runs.

**Trigger.** Search/browse (text filter, tags, options — every `LIKE` branch) with a query
whose case differs from the stored text on a non-ASCII letter.

**Impact.** Genuine hits silently missing; the documented prefilter contract is violated, and
the screens promise "case-insensitive".

**Fix.** `LIKE '%‌:lower%'` with both sides lowercased in SQL (`lower()` is also ASCII-only —
emit `lower(questions.searchText)` and bind the query pre-folded via Kotlin `lowercase()`, which
then matches the in-memory authority's behaviour) or use `COLLATE NOCASE` in the index-build.
(Actually, note SQLite's `lower()` is ASCII-only too — the correct pairing is to bind the
Kotlin-lowercased query and compare against a stored lowercased search-text column.)

---

#### 10 · MEDIUM · Test session — the screen stays interactive while the attempt saves; post-submit edits are silently dropped

`app/src/main/java/com/mcqapp/ui/test/TestViewModel.kt:451-473` (toggleOption), `:505-518`
(toggleFlag), `TestSessionScreen.kt:391-410` (progress card but no input lock):

```kotlin
fun toggleOption(optionId: String) {
    val question = _state.value.currentQuestion ?: return
    if (question.id in _state.value.revealed) return     // no submitted/saving guard anywhere in this path
```

`submit()` captured the selections before launching the save; `persistProgress` bails on
`submitted` (`:803`); the only disabled control while saving is the **Retry** button
(`:381`). The question body, option rows, flag and navigation all remain live underneath the
"Saving result… 40 000 of 169 000" card.

**Trigger.** Submit a large paper (save can run minutes — the progress card exists precisely
for that), then tap an option or flag.

**Impact.** The UI visibly marks the answer and grows `answeredCount`, but the attempt is
scored from the old map and the change is neither persisted for retry nor saved. What the user
last saw disagrees with the scored result, silently.

**Fix.** Short-circuit option/flag/`goTo` when `submitted || saving`; disable the question body
while saving.

---

#### 11 · MEDIUM · Settings — "Export all data (JSON)" runs on the Main thread

`app/src/main/java/com/mcqapp/ui/settings/SettingsViewModel.kt:269-289`; callee
`Exporter.exportAllTo` (`data/io/Exporter.kt:38`) is `suspend` but never hops:

```kotlin
fun exportAll(uri: Uri, onError: (String) -> Unit) {
    viewModelScope.launch {                       // Main.immediate
        outputStream.use { out ->
            Exporter(repository.db()).exportAllTo(
                java.io.BufferedWriter(java.io.OutputStreamWriter(out, Charsets.UTF_8), 1 shl 14))
        }
```

Every `json.encodeToString(...)` per category/question and every blocking `writer.write`
between Room hops executes on the calling dispatcher — the whole bank's serialization + file IO
on Main. The siblings the same file uses the same pattern for prove the intended convention:
`backupAll` (`:296`) and `restoreBackup` (`:375`) both wrap in `Dispatchers.IO`, and
`PaperExporter.exportStream` is `withContext(Dispatchers.IO)` — the JSON export is the sole
exception.

**Trigger.** Settings → Export all (JSON) on a few-thousand-question bank.

**Impact.** ANR/jank worst case on a big bank; guaranteed jank paragraph frames on a real one.

**Fix.** `withContext(Dispatchers.IO)` around the openOutputStream + export (matching
`backupAll`).

---

#### 12 · MEDIUM · Images — EXIF orientation is ignored on every pick/decode path

`app/src/main/java/com/mcqapp/util/ImageUtils.kt:15-31` (question/option image picker) and
`util/BitmapDecoder.kt:43-60`:

```kotlin
val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
val bitmap = decodeBounded(bytes, MAX_DIMENSION) ?: return null
```

No `ExifInterface` is ever consulted (repo-wide grep is empty; no dependency). Gallery/camera
pickers commonly deliver JPEGs with EXIF Orientation ≠ 1, which `BitmapFactory` ignores.

**Trigger.** Take a photo (one with orientation metadata — which the destination pickers don't
strip) and attach it to a question/option.

**Impact.** The photo stores and exports (Anki, PDF, DOCX) rotated 90 °/180 °; `readBytes()`
at `:17` also materialises a 40 MB pick in one ball before decode (bounded reads exist but
are not used here). Every consumer of the bank — including re-imports — carries the rotation.

**Fix.** Apply EXIF rotation post-decode (`ExifInterface` is in androidx-exif) before compress.

---

#### 13 · MEDIUM · UI input — comma-locales corrupt numeric entry in three screens (one silent 10–25×)

Three independent copies of the same filter:

```kotlin
// LibraryDialogs.kt:71, 85  (new-paper dialog)
negative = input.filter { it.isDigit() || it == '.' }
... negative.toDoubleOrNull() ?: 0.0

// ImportScreen.kt:280-281
val filtered = input.filter { it.isDigit() || it == '.' }
viewModel.updateNegativeMarking(filtered.toDoubleOrNull() ?: 0.0)

// SettingsScreen.kt:934-939 (scheduler numeric row) — KeyboardType.Decimal
val filtered = input.filter { it.isDigit() || it == '.' || (it == '-' && ...) }
```

**Trigger.** Any comma-decimal device (pt-BR, de-DE, hi-IN…): the Decimal keyboard's decimal
key inserts `,` and the filter deletes it.

- **LibraryDialogs:** typing `0,25` leaves `025` → saved `negativeMarking = 25.0` — every
  wrong answer costs 25 marks from then on, no range check, no error.
- **ImportScreen:** worse — because the view state is rewritten from the parsed value *every
  keystroke*, typing `"0"` immediately canonicalises the field back to empty (state 0.0 → the
  value clause renders `""`), so `0.5` is genuinely un-typeable and partial input resets the
  field from under the user.
- **SettingsScreen:** the candidate is range-checked, so nothing corrupt lands — but the
  comma-key decimals can never be typed, leaving those fields steppers-only on affected
  locales (and latent: `String.format("%.1f", value)` at `:963-964` default-locale would
  produce `10,5` for any future `step < 1` caller, marking a perfectly valid stored value red).

**Fix.** Normalise `,` → `.` before filtering (one shared helper for all three screens).

---

#### 14 · MEDIUM · Export (PDF) — a block taller than one page is silently clipped

`app/src/main/java/com/mcqapp/data/export/PdfPaperWriter.kt:255-281` (paragraph), `:429-451`
(table row):

```kotlin
if (y + layout.height > BOTTOM && y > MARGIN + spaceBefore) { advance(); y += spaceBefore }
...
layout.draw(canvas)                 // whole layout, no re-break, no height cap
y += layout.height + spaceAfter     // y now far past BOTTOM
```

One `advance()` protects the *start* only; a single paragraph or table row whose
`StaticLayout` height exceeds the printable height draws once from the top of the fresh page
and loses everything past the page bottom. No warning, no continuation, no scale-down.

**Trigger.** A ~60-line stem, a giant cell, or a huge explanation in any exported paper (a
300-word-question bank loses the tail of every such question).

**Impact.** Silent content loss inside the file; subsequent flow briefly shows near-blank
overflow pages, else pages after exchange clipped pages back-from-bottom.
**Fix.** Cap `layout.height` before drawing and re-break (`Canvas`-clip + `draw` chunks) or
write an oversized-block marker/warning.

---

#### 15 · MEDIUM · Export (Anki) — learning/relearning cards go out as graduated Review cards

`app/src/main/java/com/mcqapp/data/anki/AnkiScheduling.kt:196-208` (reached
`AnkiPackageWriter.kt:133-136`) — the only studied branch:

```kotlin
return AnkiCardSchedule(
    type = TYPE_REVIEW, queue = QUEUE_REVIEW, due = daysFromExport,
    ivl = max(0, state.intervalDays), ...)
```

An app card graded **Again** (→ LEARNING or RELEARNING, `intervalDays = 0`,
`dueAt = now + 10 min`) exports as `type=2, queue=2, due=today, ivl=0` — a *graduated review
card due today with no memory*. Anki then schedules its next grade by the full review interval
from `factor`; re-importing the same package round-trips as `REVIEW` with stability 0 — the
never-graduated/relearning distinction the file's own docstring insists on is crushed for
exactly the cards that have it. (`AnkiScheduling` already carries `TYPE_LEARN`/`QUEUE_LEARN`
on a timestamp `due` path — the reps>0 states simply never reach it.)

**Fix.** Emit the timer-step learning row for non-REVIEW kinds (the branch exists at
`:70-95` — extend the mapping to `cardState == LEARNING/RELEARNING`).

---

#### 16 · LOW-MED · Export (HTML) — image-only explanation block is dropped

`app/src/main/java/com/mcqapp/data/export/HtmlPaperWriter.kt:138` (answer-shown) and `:162`
(quiz):

```kotlin
if (question.explanationElements.isNotEmpty() || question.explanation.isNotBlank()) {
    out.append("<p class=\"expl\">Explanation: ")
```

`explanationHtml` itself handles `explanationImage`, but both **guards** omit it — a question
whose explanation is only an image (`explanationImage != null`, elements empty, legacy text
blank) gets no Explanation block at all. The PDF answer-key count includes the image case
(`question.explanationImage != null`, `PdfPaperWriter.kt:594-601`) and DOCX renders it —
HTML is the odd one out.

**Fix.** Add `|| question.explanationImage != null` to both guards.

---

#### 17 · LOW-MED · Export — entity decoding disagrees across four renderers

- `data/RichTextHtml.kt:15-39/107-112` — HTML **export** escapes without decoding: stored
  `&lt;` renders as the literal *characters* `&lt;` in every browser (in-app
  `util/InlineHtml.kt` decodes).
- `data/export/PdfMath.kt:49-67` — PDF decodes numeric/named refs **but not `&nbsp;`** →
  `&nbsp;` prints literally in PDFs (in-app and DOCX both produce the space).
- DOCX (`DocxPaperWriter.inlineRuns`) decodes fully.

**Trigger.** Any bank storing text with entity references (machine-escaped banks, hand
edits): `"5 &lt; 6"` renders `5 &lt; 6` in HTML export today, `5 < 6` in-app/PDF/DOCX.

**Impact.** Same question, three renderings; the exporters' fifteen hundred neighbours were
aligned and this family was not.

**Fix.** Decode entities once in `renderInlineHtml`/`escapeHtmlText`'s input (like
`InlineHtml`), and add `nbsp` to PDF's table (or reuse `InlineHtml`'s).

---

#### 18 · LOW-MED · Export (PDF) — malformed `data:` URI prints as megabytes of base64 text

`app/src/main/java/com/mcqapp/data/export/PdfPaperWriter.kt:292`:

```kotlin
val label = if (embedded == null) "[image: $src]" else "[unreadable image]"
```

A src shaped `data:…` that `parseDataUri` rejects (broken base64, missing `;base64`,
`data:image/png,<raw>`) is printed as its **entire literal self** — for a 2 MB failed image
that's hundreds of pages of base64 in the paper, plus zero indication it was an image.

**Fix.** Truncate the label (first ~64 chars) or special-case `data:` fails as
`"[unreadable image]"`.

---

#### 19 · LOW-MED · Export (DOCX) — EMU widths computed in `Int`; large-pixel headers overflow to a negative extent

`app/src/main/java/com/mcqapp/data/export/DocxPaperWriter.kt:825-828`, bounds at `:1090`
(`w in 1..1_000_000` accepted) and `EMU_PER_PX = 9525`:

```kotlin
var cx = w * EMU_PER_PX          // Int math: 225,443 × 9525 = 2,148,463,375 — right at the edge
var cy = h * EMU_PER_PX
if (cx > MAX_CX) { ... }
```

At `w ≥ 225,443` the multiply wraps; a negative `cx` fails the `> MAX_CX` guard (so it is
never rescaled) and is written straight into `<wp:extent cx="-…">`.

**Trigger.** A craftable/corrupt PNG/JPEG/BMP header (or a real monster scan) which the DOCX
import path encodes verbatim (no downscale there, unlike JSON import).

**Impact.** Word refuses the whole file ("unreadable content") or draws a nonsense frame.

**Fix.** Compute in Long, clamp to MAX before storing back to Int.

---

#### 20 · LOW-MED · Export — bookmark/selection streams carry no passages

`app/src/main/java/com/mcqapp/data/io/Exporter.kt:352-360` (`BookmarkStream.passages =
emptyList()`), `SelectionExport.paperDto` (no passages at all), while
`QuestionDtoMapper.kt:35` preserves `passageId`.

**Trigger.** Bookmark or multi-select export including passage-member questions, rendered to
HTML/PDF/DOCX.

**Impact.** Passages by provenance: PDF (`passagesById[passageId] → null → skip`) and HTML
(`?: return`) silently drop the block; DOCX falls through with no reset marker, so a member
glues onto some unrelated earlier passage on re-import. The question text ("as per the
passage above…") is unreadable.

**Fix.** Load the referenced passages into the stream (the same pattern `DatabaseStream`
already does with `passagesOf(categories)`).

---

#### 21 · LOW-MED · Import (JSON, streaming) — the file's review progress is dropped when `scheduling` precedes `papers`

`app/src/main/java/com/mcqapp/data/io/StreamingImportSession.kt:181-196` vs `:680-688`:

```kotlin
val targets = acceptedSchedules           // populated only as questions stream past (onQuestions :482)
...  if (target == null) { skippedSchedules++; continue }
```

Schedule pages are applied **at streaming time** against targets that fill only after their
questions arrive. A backup-style file whose member order puts `"scheduling"` (or interleaved
schedule pages) before `papers` silently discards everything: `restoredSchedules = 0`, every
entry counted as skipped, the real progress lost — while the same file through the whole-file
`Importer` (`ImportFilterer :81` reads scheduling after papers) restores fine. JSON member
order is otherwise explicitly not fixed in this parser.

**Fix.** Buffer schedule pages until the tail (`finish()` already re-applies), or apply them
after questions settle. *Likely:* the app's own writer puts scheduling last, so this needs a
foreign producer — the parser still advertises order-independence everywhere else.

---

#### 22 · LOW-MED · Import (Anki) — image-only stems are silently dropped

`app/src/main/java/com/mcqapp/data/anki/AnkiPackageReader.kt:826-828` (whole-cloze analog
`:1096-1101`), writer side `AnkiPackageWriter.kt:181`:

```kotlin
val front = fieldToText(frontHtml, media)   // strips <img> to plain text
if (front.first.isBlank()) return null      // image-only stem → note vanishes
```

A question whose stem is only an image exists fine in Anki (its field-0 content is the `<img>`
tag) but is dropped here with no counter or warning; `DeckScan` skips it so deck counts and
grouping drift. The app's own writer already writes `sfld = ""`/`csum = ""` for such notes
(`question.text` is textContent) — inconsistent metadata for the same shape.

**Fix.** Count and warn on dropped-empty stems; write the front's `<img>`-included form into
`sfld` (Anki computes its own csid).

---

#### 23 · LOW-MED · Import (Anki) — the payload fallback can attribute the *passage's* image to the question

`app/src/main/java/com/mcqapp/data/anki/AnkiPackageReader.kt:836, 845-846`:

```kotlin
val questionHtml = AnkiHtml.splitFront(media.rewrite(frontHtml).orEmpty()).first
...
image = media.rewrite(payload.image) ?: IMG_TAG.find(questionHtml)?.groupValues?.get(1),
```

`buildFront` embeds the passage (including its image) *before* the question in field 0, and
`splitFront`'s "question half" keeps the passage block. When the payload lacks the `image` key
(older/hand-edited payload), `IMG_TAG.find` picks the passage's picture as `question.image`.

**Impact.** A duplicate image: the passage is separately restored with its picture (also
persisted under `questions.image` at import), inflating the row and making the stem show the
image twice; any prompt branching on `question.image != null` misdirects.
**Fix.** Restrict the search to below the passage block, or only when the payload's `elements`
say the stem has no image (already available in the same payload).

---

#### 24 · LOW · Test progress — header/rows saves are not ordered or atomic

`app/src/main/java/com/mcqapp/data/repository/TestProgressStore.kt:49-52`:

```kotlin
suspend fun save(snapshot: TestSnapshot, changed: List<TestAnswer>) {
    if (changed.isNotEmpty()) writeRows(snapshot.paperId, changed)
    settings.saveTestProgress(snapshot.header().toJson())      // write AFTER rows, unserialized
}
```

Two `persistProgress` launches a few hundred ms apart can write the DataStore header out of
launch order (both suspend between), leaving the stored header one question behind what the
surrounding rows describe. On a crash in the gap, resume lands one question back with
seconds-stale timer. *Also:* TestProgressStore restores the resume slot from backups in two
statements (document replace + row cleanup) — see #25.

**Fix.** Serialize saves per paper (a Mutex in the store, or make the header-first), or fold
the header write to complete after the rows in the same job.

---

#### 25 · LOW · Backup — restoring a corrupt `inProgressTest` document destroys the live resume snapshot

`app/src/main/java/com/mcqapp/data/repository/TestProgressStore.kt:97-104`:

```kotlin
suspend fun restore(document: String?) {
    if (document == null) { clear(); return }
    TestSnapshot.fromJson(document)?.let { db.testProgressDao().deleteForPaper(it.paperId) }
    settings.saveTestProgress(document)     // unconditional overwrite of the slot
}
```

**Trigger.** User has a live session in progress; restores a backup whose stored
`inProgressTest` string is truncated/invalid.

**Impact.** The damaged string replaces the slot; the good header + its answer rows are
abandoned (row-cleanup skipped), and the next load logs "Discarding an unreadable resume
snapshot" and clears it — the in-progress test is silently gone. The parse guard protects
only the *row delete*, not the *replace*.

**Fix.** When `fromJson(document) == null`, refuse to adopt the slot (log; keep the live one).

---

#### 26 · LOW · History — the bookmarks list pages by OFFSET

`app/src/main/java/com/mcqapp/data/local/Daos.kt:269-275` with
`HistoryStore.kt:536-559` (`BookmarksPagingSource`, `nextKey = offset + loadSize`,
`getRefreshKey = null`):

```sql
ORDER BY bookmarks.createdAt DESC, bookmarks.questionId DESC LIMIT :limit OFFSET :offset
```

Every other paged read in the codebase is explicitly keyset to avoid exactly this: a toggle
that inserts/deletes a bookmark ahead of the offset shifts every following page by one → a
duplicated or silently skipped row while scrolling.

**Fix.** `(createdAt, questionId)` keyset like `pageBookmarkedQuestionsForTitle` next door.

---

#### 27 · LOW · Browse — the `INSTR(csv)` category filter leaks ids containing commas

`app/src/main/java/com/mcqapp/data/repository/McqRepository.kt:509-511`, consumed at
`Daos.kt:551-552` (+ `:687-688`, `:723-724`):

```kotlin
categoryFilterCsv = categoryFilterIds.orEmpty().joinToString(",", prefix = ",", postfix = ",")
...
"INSTR(:categoryFilterCsv, ',' || questions.categoryId || ',') > 0"
```

Category ids come straight from imported files (`categories.id` — arbitrary JSON strings). An
id of `a,b` makes its own row match when only its neighbours were selected (and leaks it into
every id-containing filter batch).

**Fix.** Chunked `IN (…)` with a bounded CSV, or reject comma ids at import (they pass through
today).

---

#### 28 · LOW · Library — `savePaper`/`ensurePaperAndCategory` write papers with `INSERT OR REPLACE`

`PaperStore.kt:96-107` (called at `McqRepository.kt:413` ← `LibraryViewModel.addPaper`;
`QuestionStore.kt:172` for `ensurePaperAndCategory`, no current UI caller), DAO at
`Daos.kt:32-33`:

```kotlin
@Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(paper: PaperEntity)
```

`categories → questions → card_state → review_log` all **cascade** from `papers`. Room fires
the declared cascades on the DELETE-part of REPLACE — the repo itself got burned this way once
(`renameCategory` was rebuilt with targeted `updateFields` for exactly this reason,
`PaperStore.kt:362-366`, and the importer does the same at `Importer.kt:149`).

**Trigger.** Today never (`paper-<millis>` is always fresh). Latent: any future "edit paper"
that routes through `savePaper` with an existing id — or a second device minting the same
millisecond id — deletes the paper row and cascades away every category, question,
schedule (and test progress snapshot) it held, leaving an empty-looking paper?

**Fix.** `IGNORE` + explicit `updateFields`, or `upsertInto` with the columns that really
change.

---

#### 29 · LOW · Writer detox — soft render/layout defects

- **InlineHtml style leak** (`util/InlineHtml.kt:90-96`): a mis-nested `<b>a<i>b</b>c</i>` —
  `</b>` does not pop (top is `i`), so everything after the misplaced close renders bold to
  end-of-text. The comment only rules out span-stealing, not leak. Common in hand-authored
  HTML. Fix: pop-until-found with a bound, or fixed-size tag stack.
- **HTML x-axis label collision** (`ui/stats/charts/LineChart.kt:149-158`,
  `StackedBarChart.kt:175-186`): the always drawn last label can butt against the previous
  stepped label (label count ≡ 1 mod step, widthy labels). Cosmetic.
- **Dead fallback branch** (`data/export/DocxPaperWriter.kt:504-541`): `pendingPrefix` inside
  the `is Tbl` case can never be armed (consumed by the constructor-time `newBuilder()`);
  unreachable either way, worth deleting before an edit that re-arms it silently breaks.

---

#### 30 · LOW · Export (Anki) — three writer-side schema/convention inaccuracies

All Confirmed at `AnkiPackageWriter.kt`:

- **`col.mod` in milliseconds** (`:205-209`): schema-11's `col.mod` is seconds (`intTime()` in
  Anki's code); the writer sends `nowMillis` (≈1.77e12, year ~53,000 as seconds). `scm` (below
  it) *is* ms, so it looks plausible but both columns travel together and mod is off by ×1000.
  Legacy import currently tolerates it; anything that reads `col.mod` shows a nonsense mod
  time. Fix: seconds.
- **Card id inside the note id range** (`:137-139, :167`): `AnkiCardRow(id = row.noteId + 1)`
  and the next note gets exactly that id (`idBase + index`). Notes and cards share one
  idspace in Anki (its `genId` checks across all separate tables so it stays unique across
  cards, notes, and revlog). SQLite PKs are per-table, so nothing trips; but the "id
  uniqueness across card-space" tools that scan collections break. Fix: offset ids
  (`idBase + noteCount + index`) or derive from a separately-advancing sequence.
- **Raw control characters pass into note fields** (`AnkiMediaPool.kt:122-135`): text
  containing U+001F passes verbatim into the fields blob, and U+001F is the field separator —
  a single occurrence splits the field blob into 4+ segments, moving the `mcqapp` payload
  from index 2 on. `payloadFromField` then refuses the payload — on the app's *own* next
  re-import. (0x1F rarely reaches user text except via CSV/DOCX imports.)

---

#### 31 · LOW · Backup — the manifest and restore receipt don't count paper-level questions

`app/src/main/java/com/mcqapp/data/backup/Backup.kt:633-636`:

```kotlin
questions = content.papers.sumOf { paper -> paper.categories.sumOf { it.questions.size } },
```

`PaperDto.questions` (top-level questions) exists and is produced by
`AnkiPackageReader.buildPapers:1166-1172` (a paper-deck's standalone questions) — these are
restored but simply not counted, so the persisted manifest and the "Restored N questions"
report undercount precisely the questions the restore actually wrote.

**Fix.** `sumOf { it.categories.sumOf { … } + it.questions.size }`.

---

#### 32 · LOW · Streaming — post-error cleanup busy-loops instead of stopping the parse

`app/src/main/java/com/mcqapp/data/io/StreamingImporter.kt:160-171`:

```kotlin
} catch (_: InterruptedException) { Thread.currentThread().interrupt() }
// ...and offer simply returns — the parser never learns it should stop.
```

When the consumer throws mid-import, `finally` runs `parser.interrupt()`; a pool in `queue.put`
then re-arms on every dispatch, every subsequent `put` throws again, and the parser continues
parsing the rest of a possibly million-question file spinning on failed offers. `join(2000)`
times out and a daemon thread burns CPU (and IO) on a finished import. A blocked `reader.read`
also stops nowhere.

**Fix.** Set a volatile `cancelled` on the parser thread and check it in the offer/parse loop,
or close the underlying stream to break the read.

---

#### 33 · LOW · Scale — `foldPassageStrays` materialises every member's full row for one column

`app/src/main/java/com/mcqapp/data/io/StreamingImportSession.kt:584-585` (+ same shape in
`Importer.kt:465-466`), DAO at `Daos.kt:956-960`:

```kotlin
val strays = db.questionDao().getMembersByPassage(passageId).filter { it.categoryId != passage.categoryId }
...
"SELECT * FROM questions WHERE passageId = :passageId ..."
```

Full `QuestionEntity` rows (stem JSON, base64 images, explanation JSON per row) load at the
import tail solely to compare `categoryId`. A "shared context" passage with tens of
thousands of members turns into an unbounded heap spike to move a few rows.

**Fix.** `SELECT id, categoryId FROM questions WHERE passageId = ?` and fetch orders per stray.

---

#### 34 · LOW · Study — drill **Study again** replays stale schedule states

`app/src/main/java/com/mcqapp/ui/study/StudyViewModel.kt:159-168, 656-698`:

```kotlin
private var drillCards: List<StudyCard>? = null     // built once at load()
fun restart() { ... drillIndex = 0; ... pullCard() } // never re-pulls the drill list
```

The fixed list replays with each card's *original pre-grading* state: the Again/Hard/Good/Easy
previews promise intervals the persisted scheduler no longer produces (they compute from
`state.current?.state`), a card that stopped being a leech stays in the drill, swatches/chips
can mislabel, and newly-created leeches never appear. Persisted scheduling is unaffected
(`recordStudyReview` re-reads `existing` from the DB); this is UI honesty, not corruption. Main
sessions restart clean — only the drill list is affected.

**Fix.** Re-pull the drill queue in `restart()` (like `mistakesOnly` restart does) or clear
`drillCards` to the pull path.

---

#### 35 · LOW · Stats — the card-detail sheet has no generation guard

`app/src/main/java/com/mcqapp/ui/stats/StatsViewModel.kt:97-126`:

```kotlin
fun openCard(paperId: String, questionId: String) {
    _cardDetail.value = CardDetail(paperId, questionId, null, null, null, emptyList())
    viewModelScope.launch {
        val question = repository.getQuestion(questionId)
        ...
        _cardDetail.value = CardDetail(paperId = paperId, questionId = questionId, ...)
```

Tap card A, tap card B before A's queries finish: the header shows B, then A's continuation
overwrites it — the sheet shows A's history under B's header. `loadVisible` guards with a
generation counter; this path was left bare.

**Fix.** `if (_cardDetail.value?.questionId != questionId) return@launch` before the final
assignment.

---

#### 36 · LOW · Images — `ImageDownscale` decodes at full size, and OOM silently passes the oversized file through

`app/src/main/java/com/mcqapp/data/io/ImageDownscale.kt:62-64`:

```kotlin
val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return src
```

Unlike `decodeBounded` (two-pass subsampling, used on the export/preview side), this decodes
the *full-resolution* bitmap first — an 8000×6000 import leg is ~185 MB on top of the file
before any downscale. On OOM (or any decode failure) the oversized original is passed through
unchanged — the bank keeps the multi-MB image the class exists to cut, and every re-import
re-attempts the huge decode. No crash, but the size guard is absent exactly for the biggest
inputs.

**Fix.** Use `decodeBounded(bytes, maxDimension)` (or `inSampleSize` before decode) here too.

---

#### 37 · LOW · Logging — crash traces may never reach the file; shared `SimpleDateFormat` is a data race

`app/src/main/java/com/mcqapp/util/Logger.kt:18, :103-146`; `McqApplication.kt:24-29`:

```kotlin
private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
...
io.execute { ... file.appendText(line) }        // writes happen async on the executor
```

- The uncaught-exception handler queues `CRASH/UNCAUGHT` + its stack onto the async executor,
  then hands off to the previous handler which kills the process — the daemon task routinely
  never runs, so the very trace "Export Logs" should carry is missing. The `recentErrors` ring
  is memory-only and dies too.
- `timeFormat` is shared mutable state: `d/i/w/e` build lines on the *caller's* thread while a
  rotation formats on the log thread — interleaved `SimpleDateFormat` calls can garble
  timestamps or throw internally from the not-supposed-to-fail path (caught, but the line is
  lost).

**Fix.** Synchronous/blocked write in `crash()`; `DateTimeFormatter` (thread-safe) or a
`ThreadLocal`/synchronized format.

---

#### 38 · LOW · Dirty UI details (bundle)

- **Settings numeric fields are comma-locale-unusable** (`SettingsScreen.kt:934-964`): the
  filter only accepts `.`, so a comma keyboard's decimal is void — desired-retention and
  interval-factor leave steppers-only on affected locales; latent `String.format` default
  locale.
- **Last x-axis label collision** (`LineChart.kt:149-158`, `StackedBarChart.kt:175-186`): the
  always-drawn last label with `mod step==1` for a multi-line label can butt against its
  neighbour (no spacing budget for the extra).
- **Mis-nested inline HTML leaks its outer style** (`InlineHtml.kt:90-96`): stray
  `</b>` under an `<i>` does not pop the bold span — everything after it renders bold.
  Typical hand-authored HTML banks.
- **Percent/size strings in other digit systems** (`HistoryScreen.kt:126,157-158,218,235`,
  `StorageInfo.kt:33-34`): `"%.0f%%".format()` / `"%.1f KB".format()` use default locale, so
  Devanagari/Arabic-Indic digits or comma decimals appear beside `Locale.ENGLISH`-formatted
  neighbours (`CardInfoSheet`, chart kit) — mixed digit systems on one screen; also breaks
  parse-anywhere habits. (Uncertain whether observed as "correct localisation" is intended.)
- **Bulk edit silently drops marks** (`BrowseViewModel.kt:626-631`): an unparsable marks
  value (`0,25`) just falls out (`marks = null`) and **difficulty/tags still apply** while the
  user believes they set marks — the only field that takes effect is not the one that failed.
  (Same comma family as #13.)
- **Hardcoded dialog title** (`BookmarksScreen.kt:224`): literal
  `"Export ${state.count} bookmarked questions"` bypasses localization while neighbours use
  `stringResource`.
- **History top-lists load once in `init`** (`HistoryViewModel.kt:31-43`): "hardest/weakest"
  never reload on `ON_RESUME`; finishing an attempt and returning shows stale lists for the
  backstack entry's lifetime. A trade-off the sibling Stats screen does not make.
- **Stepping from an invalid typed value jumps back** (`SettingsScreen.kt:968-976`):
  +/− hovers on `current` (view-state), not the typed text — a red-error candidate then a
  stepper press silently jumps the field to the last committed value.

---

#### 39 · Notes and unverified leaves

- **`ImportRunJournal` mixes runs** (`data/io/ImportRunJournal.kt:76-87`): the
  `interruptedImportNotice` compares the *current* `questionsSeen` against the journal's
  stored `totalQuestions`; a changed file between runs silently suppresses or warps the
  notice's numbers. *Uncertain; low impact.*
- **`McqRepository.statsReviews()` materialises whole `ReplayReview`s**
  (`data/repository/StatsStore.kt:52-60, :240-244`): the streamed accumulators exist because
  a million-row replay must not be held; this API does precisely that and has **no
  production caller** today — dead-lethal if anyone reconnects it.
- **Settings-restore ordering** (`RepositorySettings.kt:288-304` with
  `SettingsViewModel.kt:379-388`): the `in_progress_test` preference is part of a settings
  restore that runs *before* `restoreTestProgress`; the later call currently wins, but a
  settings-only restore would resurrect a stale resume snapshot slot. Low.
- **"Due now" excludes overdue** (`StatsAggregator.kt:396-397` vs `StudyStore.kt:315` /
  `Daos.kt:1273`): stats' *Due now* counts only cards due within today, while study badges
  count `dueAt <= now`. The comment marks it deliberate (Anki-like backlog separation) —
  recorded as a UX-debt description gap: no label explains the difference. *Not counted as a
  bug.*
- **View-model error handlers catch `Exception` only** (across the whole
  `LibraryViewModel`/`SettingsViewModel` family): any thrown `Error` escapes
  `viewModelScope`'s uncaught handler and kills the process. Confirmed by knock-on in
  `LibraryViewModelErrorTest` on aarch64, and reachable on-device via `OutOfMemoryError`
  inside a repository call *after* the DAO layer is past its own decode paths. Low; noted
  because the pattern is everywhere.

---

### Local test-run baseline (this ARM64 host)

`:app:testDebugUnitTest` completed **1298 tests, 326 failed, 7 skipped**. **Every failure is
the documented Robolectric-on-aarch64 native-runtime limitation** (AGENTS.md), in three
shapes — none is a logic failure:

1. `java.lang.AssertionError: The Robolectric native runtime is not supported on Linux
   (aarch64)` — 336 direct hits across the Room/SQLite suites.
2. `MigrationTest` (17): `setUp` dies at the first framework-SQLite open, then `teardown`
   trips `UninitializedPropertyAccessException: dbFile not initialized` — same root cause.
3. `LibraryViewModelErrorTest` (3): the native-runtime `AssertionError` escapes the
   ViewModel's `catch (Exception)`, so `exportError` stays null — the test sees a symptom
   (no error surfaced) of the host artifact, which is also insight #40 above.

Pure-JVM suites — the FSRS/vector tests, `FsrsOptimizerTest`, `SessionOrder`,
`PassageBlocks`, parser/export/backup/round-trip families, `NoDeadCodeTest`,
`StringResourceUsageTest`, etc. — all pass on this host (965 passed). CI (x86_64) remains
authoritative for the Robolectric suites.

---

### Areas checked and considered clean (so effort is not duplicated)

**FSRS / scheduling.** `domain/Fsrs.kt` — every formula validated against the py-fsrs
reference source: initial stability/difficulty, `nextDifficulty`'s linear damping
`(10−D)·Δ/9` and mean reversion `w7·D0(Easy) + (1−w7)(D+Δ)`, `recallStability` bonuses,
`forgetStability`'s `min(longTerm, S/e^(w17·w18))` cap, `shortTermStability` clamp ≥ 1 for
Hard+, `intervalFor` clamping in `Long` (the earlier bug, fix landed and re-checked), same-day
handling, leech/lapse counters, ease mirror. `FsrsOptimizer.kt` — outlier filter
(frequency-descending walk, quotas), ternary-search initial stability with Laplace smoothing,
`smoothAndFill`'s pair rules and {n−1} guard, Adam/beta correction, cosine schedule,
recency weights: matches fsrs-rs's own code paths; all deviations documented at the site.
`FsrsReplay`, `Study.pools` ↔ `countsForPaper` SQL equivalence, keyset cursors and their
unique tiebreaks — clean.

**Import machinery.** `JsonPull`'s state machine (besides #4), `SafeZip`, `BoundedRead`,
paper/category identity merging + id-foreign remap, duplicate-content hashing and
`scaled-twin` storage (the image-refresh loop and casing fixes hold), passage flow
(first-member filing, strays, sort preservation), attempts restore dedupe, `ImportPreview`
counters, streaming batch writes/resume. Findings here are the edges listed above (#6, #7,
#21, #33).

**Anki package reader/writer.** Entity decoding, `AnkiHtml`/marker back-parsing, schema-11
blobs, media layout/dedupe, writer batching/memory, id entropy (the CsId fold checks), Anki
guid/csid — clean apart from #1, #22, #23, #30 (**and the one Windows-para-body spelling of
`protobuf`** in the class list above). The reader's config/kind protobuf fix landed and holds.

**Backup.** Transaction/wipe semantics (wipe+stream+study in one transaction; failing paths
throw inside the transaction), stream row-count and ordering checks, `JsonValueStream`
(strings/escapes/braces), keyset page termination — clean besides #25, #31.

**Export writers.** `MediaSpool` the injective fix holds; `ZipPaperWriter` names/flush order;
`PaperJsonWriter` streaming shape; two-column PDF geometry; `OptionLetter` bijective base-26;
`DocxReader` XXE hardening and the VML `r:id` fix; `MathMlToOmml` ↔ `OmmlToMathMl` round
trip on the supported subset; `preferPayload` graft freshness; `PdfMath` parser recursion and
`MathSpan` clamps; HTML escaping/injection surface. Findings above are the edges (#2, #3,
#14, #16-20, #29).

**UI state machines.** study/test double-submit + double-grade guards, resume order rebuild
(seeds ↔ legacy id list), option prefetch races, dwell bank/pause/foreground gates (the
foreground fix verified), review-sheet keyed paging arithmetic, `QuestionPalette` grid keys,
`SessionClock` pausing on dialogs, `AutoAdvance`, exam mode, strict-mode fences, graded
previews from the same scheduler engine. Highlights above: #5, #10, #24.

**Stats screens.** generation-guarded viewmodels, tab/range switch, first-day cursors shared
by every retention window, heatmap 364-day window and weekday math, divide-by-zero guards in
every chart/donor (`coerceAtLeast`), streak counting in study-day — clean apart from #35,
#38 (label collision/locals), #39 (dueNow semantic note).

**Settings/Repository internals.** Every multi-key config write is one DataStore edit;
`setFsrsParameters` deliberately single-key; slider bounds match the sanitizer clamp; temp/
cache paths match cleaner namespaced prefixes; like-escaping and `ESCAPE '\'` correct in all
callers; no `!` hazards; `ChunkedQueries` in-clause math; `Logger` rotation bounds (besides
#37); `RepositorySettings.restore` type mismatch handling; migration internal consistency
(v6→v17 including the row-pick and batched backfills).

---

### Suggested priority

1. **#1** (Anki import scheduling corruption), **#4** (import crash on valid input), **#5**
   (destroys the user's saved session), **#2/#3** (whole-file export failures), **#6/#7**
   (silent data loss/misfile on normal import flows), **#11** (ANR on a headline flow).
2. Validate-on-device then fix: **#9** (search contract), **#10** (result/save match), **#12**
   (rotated photos), **#13** (silent marking corruption at three doors), **#14/#18/#19**
   (PDF/DOCX content loss on real banks), **#21-23** (Anki/JSON fidelity), **#15** (Anki
   round trip for in-progress cards).
3. Cleanup sweep: the remaining LOWs, starting with the latent escalators (#27, #28, #33,
   #39's `statsReviews`) and the UX-honesty items (#34, #26, #35, #24).


---

<a id="third"></a>

## Third bug hunt — findings against `main` @ HEAD

**Scope:** production Kotlin under `app/src/main/java/com/mcqapp/`, focused on the code that
changed since the second pass (`f21afc7..HEAD`) — category-scoped study/test/export, the
category picker and counts, the chunked category queries, the paper card, the browse
paging/filter changes — plus a re-check of the earlier docs' fixes and deferred items.

Every claim below was re-read against the current source in this pass; two candidate
findings drafted along the way were **dropped after checking the code** (noted at the end).
Severity/Certainty as in the previous docs.

**Verification pass (2026-10-22):** every finding and every 'clean' claim was re-checked
against the source independently and this doc was updated to match what was found:
finding #1 was rewritten (its central premise was contradicted by the code), #5's
mechanism note was corrected, #7 was sharpened, and several stale line references were
fixed. The 'clean' sections all held.

---

### Findings

#### 1 · Low · Study and Test card order can disagree — but only past the chunk wall

*(Rewritten by the verification pass. The original draft claimed Study serves the
selection in tree pre-order and scored this Medium. That premise is wrong; the code
facts it cited are all real, so the residual issue is kept at Low.)*

`StudyViewModel.kt:201-205` and `TestViewModel.kt:209-213` both expand the ticked ids with
`CategorySelection.orderedIds(...)` (`CategorySelection.kt:73-89`), which walks the tree in
**pre-order**. But the expansion only decides *which* categories are in scope, not the
order the cards come out in:

- **Study** (`StudyStore.getStudyQueue`, `StudyStore.kt:93-135`) feeds `dueCards` and
  `newQuestions` (`StudyStore.kt:204-230`), whose SQL orders rows by
  `(c.sortOrder, c.title, q.sortOrder, q.rowid)` inside each chunk
  (`Daos.kt:588-590` new, `Daos.kt:1552-1553` due), chunked at 500 ids
  (`ChunkedQueries.kt:15`).
- **Test** — `QuestionStore.getOrderForCategories` (`QuestionStore.kt:122-128`), backing
  `TestViewModel` → `repository.getSessionOrderForCategories` — re-sorts the passed list
  flat by `(sortOrder, title)` in Kotlin, then loads each category in that order: the
  same flat sort, end to end.

So for any scope of **≤ 500 in-scope categories both screens present the same relative
category order**, whatever the orderedIds expansion is. The expansion never reaches the
user as an order on Study's side except across chunk boundaries.

Divergence therefore needs a scope **over 500 categories** crossing a chunk wall where
cross-chunk pre-order differs from the flat sort — and the app cannot shape such a tree:
every tree-shaping edit (`moveCategory`, `nestCategory`, `promoteCategoryOutOfParent`,
`reparentCategory`) ends in `renumberPaperPreorder` (`PaperStore.kt:304-317`, called at
`:249`, `:333`, `:354`, `:394`), which rewrites `sortOrder` as the pre-order position, so
after any in-app edit the flat sort *equals* the tree order. Only an imported paper that
carries an arbitrary `(parent, sortOrder)` matrix and more than 500 in-scope categories
can diverge.

Implication for the fix: "expand with `orderedIds` in `getOrderForCategories`" alone is
**not** enough — Study would still re-sort each chunk by `(sortOrder, title)`, so the two
orders would only match at ≤ 500 categories. Unifying means one order contract for both:
chunked pre-order for both, or flat `(sortOrder, title)` for both with Study's chunk
boundaries aligned to the same sort.

#### 2 · Medium · Editor-attached images silently recompressed to opaque JPEG

`QuestionEditorScreen.kt:88-114` picks with `GetContent` and stores
`ImageUtils.encodeImageUri(context, uri)`; `ImageUtils.kt:17-38` decodes, EXIF-uprights, then
`compress(Bitmap.CompressFormat.JPEG, 80, …)` unconditionally.

Consequences for a picked PNG (a diagram, chart or screenshot — exactly what a question
author attaches):
- **transparency is lost** — a JPEG has no alpha channel, so transparent regions render
  black/dark on export, PDF and DOCX;
- **lossy re-encode on pick** — the pick's own format and quality are discarded, so a crisp
  PNG is recompressed at quality 80 whatever it was (`MAX_DIMENSION` down-sizing itself is
  reasonable; the format/quality choice is not).

The import paths (Anki, DOCX, JSON) store original bytes verbatim, so an editor-attached
image degrades relative to the same image imported with a paper. Fix at the format layer:
encode to the source's format (keep `PNG` when the source bytes say PNG), or store the
picked bytes as-is when they are already within the cap (`readBounded` gives you them) and
exif-neutral.

#### 3 · Low-Med · New paper-card surface is full of hardcoded user-facing strings

`LibraryPaperCard.kt:243-246` ("Study selection (N due…/… new)"), `:267` ("N tricky"),
`:299` ("Mistakes (N)"), and `:63-69` (the `${counts.leeches} tricky` badge word). All
verified at HEAD. Meanwhile other files in this very commit range do use resources for user-
visible copy (`stringResource(R.string.back)` in this same screen's flow,
`R.string.duration_minutes_0_untimed` in `LibraryDialogs.kt:64`). Sweep the card's copy
into resources, or the next localisation pass will miss it.

#### 4 · Low · `BrowseViewModel.filterState` initial-state duplication

`BrowseViewModel.kt:136-145` (the earlier draft's `97-103` is the `BrowseFilterState` data
class): `filters`' initial value and `filterState`'s
`SharingStarted.Eagerly` initial value both carry
`BrowseFilterState(categoryFilterIds = initialCategoryFilterIds.toSet())`. They must stay in
sync; today they are written adjoined within 6 lines, so it is a drift trap, not a bug. One
shared `val initialFilterState = …` would close it.

#### 5 · Low · `BrowseViewModel.loadContent`'s prune — no bug; the earlier mechanism note was wrong

`BrowseViewModel.kt:344-385`. **No bug confirmed here.** Correction from the verification
pass: the earlier note claimed an asymmetry — "only the right-hand `loaded.filterKeys` is
taken from the lambda's window and the left-hand filter uses the LIVE `windowIds`". That
is inaccurate: both `filterKeys { it in windowIds }` lambdas (left, pruning held cards;
right, filtering the rows this read loaded) read the live `windowIds` member each time the
`_state.update` lambda runs, so there is no stale snapshot on either side. There is no
"this very coroutine's windowIds" pitfall for a reader to fear. The source's own inline
comment ("only what the current window covers is kept…", "Pruned with the cards they
describe") remains the best description of the mechanism and the out-of-order-read
justification.

#### 6 · Low · `BrowseViewModel.pagingConfig` constant `jumpThreshold = BROWSE_PAGE * 3` evaluates to `180`

Cosmetic only (verified: `BROWSE_PAGE = 60` at `BrowseViewModel.kt:680`, the
`jumpThreshold = BROWSE_PAGE * 3` expression at `:714`): the compose diamond is
intentionally the same as the docstring's "three pages", but a future reader tuning
`BROWSE_PAGE` from 60 changes the jump threshold implicitly. Could be a named constant
pair if touched again.

#### 7 · Low · `statsReviews()` is a whole-log materialiser with no production caller

Unchanged from the carry-over, sharpened by the verification pass: `McqRepository.kt:128`
exposes `statsReviews()`, which loads the whole replay log via `StatsStore.loadReviews()`
(`StatsStore.kt:56`). **Nothing in production calls it** — its only callers anywhere are
three test files (`StatsTabsStreamTest`, `StatsRetentionStreamTest`,
`StatsOverviewStreamTest`), so the earlier draft's "any tab that still calls the old
accessor forces the full log into memory once per tab visit" was hypothetical: no such
tab exists at HEAD. The per-tab streamed variants (`statsRetention`, `statsOverview`,
`statsMemory`, `statsCards`, `statsWorkload`) are wired in the same object and cover every
tab. It is a dead production accessor kept alive by tests: prune it, or move those tests
onto a streamed variant if they still need the raw log.

#### 8 · Low · `PickedArchive.classifyPickedArchive(input)`'s count check `checked < DOCX_SCAN_ENTRIES` counts entries, not files

`PickedArchive.kt:44-104` (the earlier draft said `19-100`) — `DOCX_SCAN_ENTRIES = 12` is a
cap on archive entries examined,
and directories count toward it (`checked++` increments on every entry regardless of
`isDirectory`). A docx with a chunky `[Content_Types].xml`… `_rels/…`… docProps cluster
can push `word/document.xml` past entry 12 in a pathological-but-real re-zip, in which case
the file is classified as `OTHER` (i.e. handed to the Anki reader, which mis-answers with
an Anki-shaped error instead of the "this is a Word bank" message). Low-probability byte
layout, but the fix is free: count only non-directory entries.

---

### Clean at HEAD (earlier docs' fixes verified in place)

- `new_bugs.md` #1 (Anki `revlog.time` mis-read as timestamp): `AnkiScheduling.kt:97` and
  its caller no longer multiply a duration column; the ms `id` column is used directly.
- `new_bugs.md` #27 (`INSTR` on raw category ids leaking comma-containing ids):
  `CategoryDao.rowIdsByIds` (`Daos.kt:70-77`) + `McqRepository.categoryRowIdCsv`
  (`McqRepository.kt:555-560`) binds numeric rowids (chunked at 900); the SQL consumes them
  with `INSTR` on `,rowid,` (`Daos.kt:673`), which commas cannot spoof.
- Over-999 bind wall for scoped queries: `ChunkedQueries.kt:170-201` chunk-splits
  `firstDueLearning`, `countForCategories`, `firstNewQuestion`, `newQuestions`.
- 40 MB pick-at-once (photo import): `ImageUtils.encodeImageUri` now `readBounded`s the
  input and `uprightedBitmap`s it (EXIF fix kept, bitmap recycled).
- `PaperStore.reparentCategory` (`PaperStore.kt:367-398`) does validate cross-paper and
  cycles (`newParent.paperId != category.paperId`, `subtree(categoryId)` sweep) before
  writing — an earlier draft of this doc suspected it did not; the code is right.
- `CategorySelection.questionCount` (`CategorySelection.kt:100-116`) does not double-count a
  non-maximal ticked pair: `CategoryNode.questionCount` is the node's direct count
  (`Models.kt:297`), and the walk adds each in-scope node's direct count exactly once.

### Verified clean (this pass, no deltas)

- `PickedArchive.kt` exception-swallow-and-answer-from-prefix read (matches the byte-check
  path's documented behaviour, no regression).
- `Routes.kt` (whole file): every id is percent-encoded at build time, and
  `testRoute`'s empty `categories=`+`mistakes=true` combo round-trips as unscoped+ mistakes
  as intended.
- `AppDatabase`/`DaoModel` migration plan unchanged, `chunked` DAO inventory consistent
  with `McqRepository`'s imports.
- `CategoryCounts` roll-up (`CategoryCounts.kt`): post-order sum, pre-order re-emit,
  scope-total from maximal ticked ids — consistent with `CategorySelection.tickedIds`'
  documented contract; no double-count.

### Dropped after verification (recorded so later passes do not re-chase them)

- *Suspected:* `PaperStore.reparentCategory` accepting a cross-paper parent. Disproved:
  `PaperStore.kt:374` refuses it.
- *Suspected:* `questionCount` double-counting a non-maximal ticked input. Disproved:
  `CategoryNode.questionCount` is direct-only, so the walk is exact.


---

<a id="fourth"></a>

## Fourth bug report — scoped pass: FSRS scheduling and its UI

A scoped pass per the fourth-report plan: read deeply, not widely, over the FSRS
scheduler (`Fsrs.kt`, `SpacedRepetition.kt`, `DayBoundary.kt`), its store layer
(`StudyStore.kt`, `RepositorySettings.kt`, the study DAOs in `Daos.kt`), the
Anki warm-start coating (`AnkiScheduling.kt`), and the study screen
(`StudyScreen.kt`, `StudyViewModel.kt`). Candidates were chased only where the
code actually disagreed with its own documented contract; everything else went
into the verified-clean list. Severity/Certainty as in the previous docs.

---

### Findings

#### 1 · Medium · A failed `StudyViewModel.restart()` leaves the screen on a spinner no retry can reach *(fixed in this)*

`StudyViewModel.restart()` (`StudyViewModel.kt:724-786`) sets `loading = true`,
nulls `current`, and only then pulls (`pullCard()` → `getNextStudyCard` →
the card-state DAOs). Its catch block wrote `loadError` alone; it did not
clear `loading`. The screen's branch order is
`when { state.loading -> spinner; state.loadError != null -> error; ... }`
(`StudyScreen.kt:138-147`), so the error branch was unreachable: the learner
saw a spinner (over an already-nulled card) until they backed out. Contrast
`load()`'s catch (`StudyViewModel.kt:250-258`), which does set `loading =
false`, and `enableNewCards()`'s, which does the same.

Verified by injection: after a successful session, closing the repository's
database handle makes the restart's pull fail the way a locked or full disk
would. Fixed here, and pinned by the new
`StudyViewModelRestartFailureTest.aFailedRestartSurfacesItsErrorInsteadOfSpinning`
(Robolectric + the real database, CI-only).

#### 2 · Low · Grade/persist failures in a running session are silent, unlike every other failure surface

`grade()` and `next()` (`StudyViewModel.kt:589-653`) catch a failed
`recordStudyReview` and set only `grading = false` — no surfaced message. The
card stays on screen and another tap retries, so a transient failure
self-heals; but a persistent one (locked or full disk, the exact cases
`LibraryViewModelErrorTest` insists on surfacing elsewhere) retries forever
with no explanation while the schedule never moves and `lastResult`'s
promised interval never lands. Recording for the owner to adjudicate: the
minimal fix would be a small inline error strip in the footer, and the
full-screen `loadError` branch is deliberately *not* the right surface, since
it would erase the session's live state.

#### 3 · Low · "A preview can never lie about what grading will persist" is slightly over-strong

`refreshPreviews()` (`StudyViewModel.kt:703-722`) computes the four button
labels from `Study.previewDelays(scheduler, card, now)` at **pull time**, while
`recordStudyReview` (`StudyStore.kt:492-522`) re-runs `FsrsScheduler.next` at
**grade time** with a later `now`. With no fuzz the interval formula is
deterministic *given* `now`, but `next()`'s recall branch factors elapsed time
into retrievability, so a learner who dwells on a card — or holds the app
open in the background across a study-day boundary — can have the persisted
interval land one day away from the label when `intervalFor`'s rounding sits
on a boundary (`Fsrs.kt:249-266`). The Again branch is exact in practice
(`now + relearnMs`, minutes apart). The code is right to recompute at grade
time; the docstring's promise is what overstates. Suggested fix is doc-only:
"...so a preview can never lie about what grading would persist at the moment
it was drawn".

---

### Verified clean (this pass, no deltas)

- **The SQL retention ranking is the domain comparator, exactly.**
  `bestDueReviewForPaper` / `bestDueReviewForCategories` (`Daos.kt:1637-1689`)
  rank by `elapsed / stability` descending, mirroring
  `Study.pools`' `(recall asc, dueAt asc, authored)` comparator and
  `elapsedDaysSinceLastReview`'s no-timestamp branch (`Fsrs.kt:186-196`):
  `MAX(intervalDays, 1) / stability` in SQL is
  `intervalDays.toDouble().coerceAtLeast(1.0)` in Kotlin, and the
  `stability <= 0.0` head sorts where `retention()` returns `0.0`.
- **Config sanitization covers every path that constructs a scheduler.**
  `schedulerConfig()` sanitizes on read (`RepositorySettings.kt:213-229`),
  `setSchedulerConfig` on write, `setFsrsParameters` clips, and
  `resetSchedulerConfig` deliberately preserves the fitted weights
  (`RepositorySettings.kt:251-264`) as documented. `StudyStore.fsrs` builds
  from the sanitized config only.
- **The counts ↔ pull parity contract holds.** `countsForPaper` /
  `countsForCategories` / `categoryPopulations` (`Daos.kt:1419-1506`) reproduce
  `Study.pools`' and `Study.eligibility`'s classification predicates in SQL
  (the `NOT (reps = 0 AND dueAt = 0)` new-card shape, the hash-match gate,
  the leech split across `learning`/`due`/`leeches`), and `counts()`' fresh
  arithmetic `questions - (storedRows - newish)` matches `categoryCounts`'.
- **The interval math's documented edge cases are guarded where claimed.**
  `intervalFor` clamps in `Long` before narrowing (`Fsrs.kt:249-266`, with the
  optimizer-bounds overflow scenario spelled out); `shortTermStability` floors
  a success's growth at 1.0; `forgetStability` caps the long-term post-lapse
  value with the short-term one; `dueAfter` can only land a whole-day interval
  at or after the next study-day start (`DayBoundary.kt:27-51`).
-  **The check-path dwell ladder keeps Good reachable.**
  `check()` lifts both ends of the ladder by the same reading bonus
  (`StudyViewModel.kt:507-516`), which is exactly what `inferGrade`'s doc
  demands (`SpacedRepetition.kt:211-223`).
- **Anki warm-start anchors line up on both sides.** `fromAnki` day-counts due
  dates from the collection's `crt` day start and fabricates a real
  last-review timestamp (never relying on the `lastReviewedAt == 0` sentinel)
  when a package omits its review log (`AnkiScheduling.kt:52-120`);
  `toAnki` writes the learning-step row a failed card deserves rather than a
  graduated due-today review, and truncates `due` days from the export's day
  start so 23:00 exports do not shift cards by four hours
  (`AnkiScheduling.kt:170-245`).
- **The drill's fixed-list advance cannot stall on a deleted question.**
  `nextCard` increments `drillIndex` before the question lookup, so a vanished
  member is skipped, not retried (`StudyViewModel.kt:272-284`).
- **Restart semantics for the drill are as documented.** `restart()` re-pulls
  the drill list from the scheduler ("the world has moved on"), and the stale
  card is nulled before the gap (`StudyViewModel.kt:741-767`) — only finding
  #1's catch block was wrong, and it is fixed.

### Dropped after verification (recorded so later passes do not re-chase them)

- *Suspected:* `getStudyQueue`'s `poolsOf(config, dueCards, now)` mis-builds
  the fresh pool because it feeds only due rows into `Study.pools`. Disproved
  as harmless *and* unused: the input map only ever contains due rows (so
  `pools.fresh` is always empty), and the queue builds its fresh pool
  separately from `newQuestions` (`StudyStore.kt:102-144`).
- *Suspected:* `recordStudyReview` writing `contentHash = ""` for a question
  deleted mid-session creates a visible orphan. Disproved as invisible: every
  reader (`countsForPaper`, `dueForPaper`, `bestDueReview…`) inner-joins
  `questions`, so the row is never served or counted, and
  `deleteQuestion` removes card rows explicitly (`QuestionStore.kt:356-363`).
- *Suspected:* a stored REVIEW row with `stability = 0` (Anki `ivl = 0`) is
  served by the review pool with rank-head priority and breaks `next()`.
  Disproved: `hadMemory = state != NEW && stability > 0` routes the grade
  through the initial formulas, `lapses`/leech logic is reached, and the
  `stability <= 0.0` SQL head puts it first in the queue — degraded estimates,
  but no wrong behavior (`Fsrs.kt:80-84`, `Daos.kt:1657-1660`).


---

<a id="fifth"></a>

## Fifth bug report — scoped pass: the test session

A scoped pass per the find-first-mail plan over the **test session**: its view
model (`TestViewModel.kt`), its screen (`TestSessionScreen.kt`), the snapshot
model and store (`TestSnapshot.kt`, `TestProgressStore.kt`), the shared timing
and scoring helpers (`TestTiming.kt`, `SessionClock.kt`, `ExamTiming.kt`,
`Scoring.kt`, `Submission.kt`, `ExamMode.kt`, `Feedback.kt`, `AutoAdvance.kt`,
`SubmitSummary.kt`, `AnswerReview.kt`), the presented-order primitives
(`SessionOrder.kt`, `PassageBlocks.kt`), the paged pre-submit review
(`SessionReviewPagingSource.kt`), and the streamed result save
(`HistoryStore.saveAttemptStreamed`, via `McqRepository`).

**Status:** fixes for #1-3 were applied after this report was written; the
working-tree edits are the fix. This report remains the finding record. The
`AnswerReview` `—` placeholder is the only inventory item left as a bare
symbol.

**Confidence legend:** **Confirmed** = defect proven by reading the current code
and the exact input · **Likely** = code defect real, one reachability step
depends on data shape · **Uncertain** = flagged with the specific doubt.

---

### Summary

| # | Sev | Area | Bug | Conf. |
|---|-----|------|-----|-------|
| 1 | Medium | Test timer | Countdown is a tick-count decrement, not tied to the wall clock → long papers can auto-submit late | Confirmed |
| 2 | Low-Med | Test UI | Submission-dialog / palette / ungraded-dismiss flags are plain `remember` → a rotation resurrects dealt-with dialogs and re-pauses the dwell clock | Confirmed |
| 3 | Low | Test UI | Bundle of user-facing strings hardcoded in code (resume dialog, ungraded dialog, save progress, drill banner, review rows, question header, submit summary, palette semantics) | Confirmed |

---

### Findings

#### 1 · MEDIUM · TestViewModel — the countdown drifts against the wall clock

`app/src/main/java/com/mcqapp/ui/test/TestViewModel.kt:325` (`startTimer`):

```kotlin
while (true) {
    delay(1000)
    ...
    _state.update { it.copy(remainingSeconds = it.remainingSeconds - 1) }
```

The exhaustion counter is **a count of `delay(1000)` iterations**, not time.
Every iteration costs the delay's own scheduling slack (the dispatcher only
guarantees "at least" 1 s, and under load it runs late) plus the state update.
The drift is one-directional, so it accumulates without bound: an hour-long
paper can reach zero tens of seconds after the learner's own watch, and the
auto-submit fires late. Two aggravations:

- The drift reserve is never reconciled — there is no anchor anywhere in the
  loop connecting `remainingSeconds` to elapsed real time.
- The deadline that matters is `remainingSeconds == 0` auto-submit, i.e. the
  *grading* clock is what drifts, not a cosmetic readout.

Renewal note: the snapshot's `remainingSeconds` inherits whatever the drift has
left, so a resume also resumes the stolen time.

**Proposed fix (not applied):** anchor `startTimer` on
`SystemClock.elapsedRealtime()` + the remaining-at-anchor and let every tick
compute `remaining = anchorRemaining − (now − anchor) / 1000` (saturating at
zero, `target >= current → continue`). A pure `Countdown.remainingAt(...)` in
`TestTiming.kt` keeps it testable; warnings and auto-submit flow through the
existing `TimerWarnings.newlyDue(previous, target)` path unchanged.

#### 2 · LOW-MED · TestSessionScreen — contingency state does not survive rotation

`app/src/main/java/com/mcqapp/ui/test/TestSessionScreen.kt:142-145`:

```kotlin
var showPalette by remember { mutableStateOf(false) }
var showSubmitDialog by remember { mutableStateOf(false) }
var ungradedDismissed by remember { mutableStateOf(false) }
```

Plain `remember` is discarded on configuration change (rotation, dark-mode
flip, re-creation on a Chinese-localized resize, process death), and these
three are remembered *decisions with effects attached* — not just visibility:

- `ungradedDismissed` — the "questions without an answer key" dialog, after the
  user picked **Continue**, reappears on rotation, and (it is the gate in
  `questionVisible`, so while it is up) the dwell clock pauses again until the
  learner repeats the dialog on the same session start.
- `showSubmitDialog` reappears if it was open, fine — but if it was *annulled*,
  reopening on request at rotation does not cost nothing: the summary and the
  first-flagged jump are recomputed, no harm there. The palette and the submit
  dialog reset are haze; the ungraded artifact is the real one.

**Proposed fix (not applied):** `rememberSaveable` for all three.

#### 3 · LOW · User-facing strings hardcoded in code, whole-screen bundle

`stringResource` is the project's pattern everywhere else, and the doc comment
on `QuestionHeader` (`TestSessionScreen.kt:760-770`) even asserts "the visible
clock text renders via stringResource" while its `questionProgressLabel`
actually builds raw literals:

```kotlin
"Question ${index + 1} of $total" + " · ... mark/marks" + " · ... here"
```

Inventory of the literals on this screen (each `Text(...)` / semantics string
built by hand; line numbers at `HEAD`):

| Where | Lines | Sample |
|---|---|---|
| Resume dialog text | `TestSessionScreen.kt:115-121` | `"$answered question(s) answered"`, `", %02d:%02d left."`, `" Your progress was saved when the app closed."` |
| Ungraded dialog body | `:155-160` | "1 question has no answer key and won't be scored. ..." |
| Retry button label | `:381` | `"Retry"` — while `R.string.retry` *is used elsewhere on the same screen*, and `"Saving…"` neighbours it |
| Save progress card | `:405` | `"Saving result… ${saveDone} of ${saveTotal}"` |
| Drill banner | `:471` | "Drill — N questions on the clock" (while `drill_overshoot_shown` on the next block *is* a resource) |
| Palette: answered chip + status semantics | `:549-552, 971` | `"${answered}/${count} answered"`, `"Go to question N, current/answered/..."` |
| Review sheet rows | `:712-724` | `"Q${row.number}"`, `"answered"/"unanswered"/"not scored"`, `" · flagged"` |
| Question header text | `:795` | the progress label above |
| Explanation placeholder | `:908` | `"No explanation provided."` |
| ViewModel-side error strings | `TestViewModel.kt:314, 721` | `"Could not start the test: ..."`, `"Could not save your result: ..."` |
| Save fallback title | `TestViewModel.kt:738` | `paper?.title ?: "Test"` |
| Submit summary lines | `SubmitSummary.kt:15-21` | "Answered: x of y", "Unanswered: z — these score 0", "Longest on Qn: ..." |
| `TestProgressStore`/`AnswerReview` summary | `AnswerReview.kt:41-49` | `"—"` (the empty-summary placeholder) |

Same class of finding as `new_bugs.md` #39's "bookmarks dialog hardcoded
string", which was never broadened. Fixing it means adding ~15 plurals/
formatted resources and routing the two ViewModel-side error/resume strings
through a resource id (or `UiText`) — a mechanical but wide change; recording
before the fix pass so the sweep is not done piecemeal.

---

### Areas checked, clean (this hunt's "no bug found" notes)

These were chased to the relevant seam; each had a plausible suspicion and each
survived the check against the actual code:

1. **Resume offer vs. the fresh countdown** — withheld while the offer is up
   (`TestViewModel.kt:303-305`), started by whichever branch resolves the
   dialog; pinned by `TestSessionResumeTest`. The destructive-auto-submit race
   behind the dialog (finding #5 of `new_bugs.md`) is genuinely fixed.
2. **Snapshot / row pairing** — `TestProgressStore.save/clear/restore` share
   one `writeLock`, so the header no longer lands one write behind its rows
   (finding #24 of `new_bugs.md` is fixed); a crash between a save's row write
   and its header is cleaned by `discardStale` at the next load of the same
   paper; an unreadable document is dropped, a same-paper document is left
   alone.
3. **Post-save edit acceptance** — `toggleOption`/`toggleFlag`/`goTo`/
   `revealCurrent` all bail on `submitted || saving`; the screen disables the
   same controls. (#10 of `new_bugs.md` fixed.)
4. **An order replays itself** — seed replay (`TestSnapshot.orderOf`) shares
   `SessionOrder`'s block-aware Fisher-Yates (`PassageBlocks.shuffleBlocks`,
   drawn through `java.util.Random` to match `List.shuffled`), and a legacy
   snapshot keeps its id list *and* the null-seed forever, so a second resume
   can never revert to re-authored or re-shuffled order (pinned by
   `TestSessionResumeTest.aLegacyOrderSurvivesASecondResume`).
5. **Overflow / underflow of derived numbers** — `ExamTiming.secondsFrom` clamps
   to a week (a deliberate note, twice-guarded), drill `drillMinutes`/`duration`
   both saturate (`TestViewModel.kt:228-232`), `SessionClock` engages gates
   only through `clockMayRun`, and the countdown coercion holds the tempting
   `(now - nav)/1000` segment to a non-negative.
6. **Dwell bookkeeping** — `publishDwell`/`flushDwell`/`dwellCommitted` bank per
   navigation and never double-charge: a deliberate pause does not charge
   (`setDwellActive(false)` flushes inside `submit()` and `goTo`), the
   per-second tick does not rewrite the whole `dwellSeconds` map (use of the
   separate `_liveDwell` flow is exactly to keep the once-a-second recompose
   from invalidating the question body), and an in-flight `resolveCurrent`
   publish raced by a navigation is dropped (`resolveCurrent`/`prefetchNext`
   both re-check the still-current index).
7. **Paged pre-submit review** — `SessionReviewPagingSource` numbers rows by
   presented index (not `mapNotNull` sequence), pages via `prevKey`/`nextKey`
   correctly, and an entered-row tap's `goTo(row.number − 1)` round-trips the
   very same index; the sheet pauses dwell via `questionVisible`, and rows take
   their summaries via `searchableText` (a doc-noted fix).
8. **Streamed result save** — `HistoryStore.saveAttemptStreamed` keeps the
   *session* order (a category lookup memoises), batches 500 rows, tallies
   before `updateTally`, and holds all of it in one `db.withTransaction`; the
   progress callback is explicitly out-of-band. `submitAttempt` save-first /
   clear-second is honoured (`Submission.kt`), with `submit()`'s `onClearFailure`
   deliberately silent and the snapshot still readable when the save fails
   (Retry button exists and re-runs `submit()`).
9. **Scoring math** — `Scoring.scoreQuestion`/`summarize` skip empty keys
   (`ungraded`), half-marks negative marking as `−marks × negativeMarking`
   (consistent with `HistoryStore.scoreBatch`), and `selected == correct` is
   exact-set equality everywhere (`isCorrect`), including the stored row's
   `isCorrect` flag.
10. **Strict/practice gating** — the whole matrix routes through
   `ExamMode`/`Feedback` (reveal, flag, palette, live colours and explanation
   marker), and `Feedback.revealMarker` cannot tick a non-key option; the
   ungraded card disables its reveal colors outright (`Feedback.liveReveal`).
11. **Timer-inflight interactions** — the 5-min/1-min warnings are one-shot
    via `warnedThresholds` and re-armed on resume; the 1-minute warning's
    "auto-submits at zero" promise is kept by the last tick; `submit()` guards
    re-entry (`submitted || saving || questionCount == 0`) and cancels both
    jobs before starting the save.
12. **Comment-rot only (not bugs)** — `startDwellTicker` has two consecutive
    doc blocks (an editing leftover); `Dwell.add` now appears unused in the
    screen (the map has moved to `dwellCommitted`) — flag for the dead-code
    test if the codebase grows a `Dwell.add` reference check.


---

<a id="sixth"></a>

## Sixth bug report — scoped pass: DOCX import and export

A scoped pass over the DOCX path — **status:** fixes for #1-4 were applied
after this report; #5 remains by design. Files covered: the zip/XML reader
(`DocxReader.kt`), the marker→JSON parser
(`DocxParser.kt`, including the hidden-payload freshness machinery), the
writer (`DocxPaperWriter.kt`, `MediaSpool.kt`, `ExportImages.kt`), the OMML
converters' entry conditions, the XML helpers (`escapeXmlText`,
`decodeXmlEntities`), and the pick-routing classifier (`PickedArchive.kt`,
`isDocxArchive`).

**Confidence legend:** **Confirmed** = defect proven by reading the current code
and the exact input · **Likely** = code defect real, one reachability step
depends on data shape.

---

### Summary

| # | Sev | Area | Bug | Conf. |
|---|-----|------|-----|-------|
| 1 | Medium | Import | Unaccepted tracked-change insertions (`w:ins`, `w:moveTo`) are dropped silently; deletions (`w:del`) are kept as struck text | Confirmed |
| 2 | Low | Import routing | Byte-overload `isDocxArchive` still counts directory entries — the exact bug the streaming classifier fixed; a drift trap | Confirmed (latent) |
| 3 | Low | Export | `jpegSize` treats length-less JPEG markers as length-bearing (RST 0xD0–0xD7, TEM 0x01) and reads garbage lengths; SOS (0xDA) is read as a size segment | Confirmed (cosmetic) |
| 4 | Low | Export | A question's passage context is silently dropped when its `passageId` resolves to no passage in the stream (no warning, unlike every other uncarryable thing) | Likely |
| 5 | Low | Export | Category depth clamped to Heading 9: depth ≥ 9 (Heading 2 depth 7 +) nests wrong on a visible-only re-import; fresh payload keeps ids/parents | Confirmed (by design; noted) |

---

### Findings

#### 1 · MEDIUM · DocxReader — tracked-change insertions vanish

`app/src/main/java/com/mcqapp/data/docx/DocxReader.kt` (`BodyReader.paraHtml`,
the paragraph-child `when`): `w:del` is unwrapped and rendered as
`<del>…</del>`, but its insertion counterparts `w:ins` and `w:moveTo` fall into
the catch-all `else` branch ("no content") along with `w:fldChar` and friends —
so every run an unaccepted inserted revision carries is **dropped from the
imported text** with no warning.

A document edited in Word with Track Changes on — the ordinary way someone
fixes an option in a shared bank — then imported without accepting the
revisions, imports the pre-edit text while the screen shows no hint anything
was lost. Worse shape than the deleted-text case, which is at least kept
(strikingly visible) even though an unaccepted deletion arguably should not
carry into the bank either.

**Fix:** unwrap `w:ins`/`w:moveTo` like `w:del` but keep the runs as plain
content; refuse `w:del`/`w:moveFrom` content (dropped with the existing
warning style), so the bank agrees with what Word's *accepted* document would
hold. The producer side (`DocxPaperWriter`) cannot hit this: the app's
documents carry no revisions.

#### 2 · LOW · Import routing — the byte classifier drifted from the streamed one

`app/src/main/java/com/mcqapp/data/docx/DocxParser.kt:42` (`isDocxArchive`)
counts every non-null entry toward its 12-entry cap; the streaming classifier
in `PickedArchive.kt` counts only *files* (a directory-counting entry pushed a
real Word bank past the cap and mis-filed it as Anki — an earlier fix, pinned
by `PickedArchiveTest`). Production routes through the streamed classifier
only, so today this is latent: the byte overload is kept as a public decision
API (tests use it as an oracle), and it silently disagrees with the rule it is
supposed to mirror on a directory-padded archive.

**Fix:** count only non-directory entries in the byte overload, same rule.

#### 3 · LOW · Export — `jpegSize` walks the marker stream unsoundly

`DocxPaperWriter.kt` (`jpegSize`): the loop treats every marker outside
`0x01..0x09 | 0x64 | 0xD8` as length-bearing, but RSTs (`0xD0..0xD7`), EOI
(`0xD9`) and TEM (`0x01`) carry no length: the loop reads the next two payload
bytes as a length and desyncs. `SOS` (`0xDA`) — inside the `0xC0..0xCF` SOF
range test — is parsed as if it carried dimensions, yielding header junk that
normally fails the 1..1,000,000 sanity bound and degrades the drawing to the
320×240 default. On well-formed files the scan always reaches a real SOF
before either case, so the practical effect is cosmetic (wrong/absent sizing
on malformed JPEGs only) — recorded for completeness, fix is a marker-skipping
table.

#### 4 · LIKELY · Export — passage context lost without a warning

`DocxPaperWriter.writeBody`'s `passageAbove`: when `question.passageId` names
no passage in `stream.passages` (a passage deleted after the questions that
reference it, or a foreign/incomplete stream), the question is emitted without
any `Passage:` block — visible-only re-import loses its membership — with no
warning, unlike options, images and every other uncarryable artifact the
writer reports. A fresh payload preserves the id, which is why this stayed
latent. **Fix:** add the warning.

#### 5 · Confirmed, by design — Heading-9 depth clamp

`writeBody`'s `onCategory` clamps the heading level at 9; `headingLevelOf` maps
level 9 back to depth 7, so a category tree deeper than that (which the JSON
format is not bounded from carrying) re-imports flattened, one sibling at the
same depth, through the *visible markers only*. The fresh payload path
preserves ids and parents, so this only bites markers-only paths (edited
files). Recorded so the depth bound is a decision, not a surprise.

---

### Areas checked, clean

1. **Marker grammar ownership** — writer's line guard and the parser's grammar
   share one regex (`docxLineStartMarker`), so a round trip cannot reopen the
   `(a)`–`(z)` guard gap of `new_bugs.md` #2; option markers are capped at ten
   on both sides (`take(10)` in `canonQuestion`, `OPTION_LETTERS` in the
   writer) and out-of-range letters are refused at import, not silently
   renumbered.
2. **Payload freshness** — the subsequence walk keys `matchedVisible` by
   *payload position*, which grafting iterates in the same order; skipped
   (marker-uncarryable) payload questions land on the gap and are grafted
   unaffected. Title, passages (content-canonical, order-independent),
   option-answer resolution ("0" mirror for answerless questions), the
   ten-option cap, blank-field canons and image position markers all line up
   with the writer's stripping rules (`stripPayloadImages` mirrors
   `canonField`'s scalar-position marker).
3. **XML safety** — `escapeXmlText` strips control characters, lone
   surrogates and `\uFFFE/F`; `XmlEscapingAppendable` escapes chunk-wise
   without splitting an entity; the reader hardens its factory (best-effort
   per feature) and its entity resolver resolves nothing, so XXE is closed
   even when `disallow-doctype-decl` is unavailable (Android).
4. **Zip safety** — both archive decoders bound the decompression with
   `SafeZip`; the reader tells a too-large archive apart from a corrupt one.
5. **Media** — `MediaSpool` spools to temp files with an injective
   percent-encoding name map; the content-types Defaults cover every type
   `imageExtension` can emit (jpg/png/webp/gif only); dedupe keys are SHA-256s
   of the URI, not the URI.
6. **Image sizing** — the EMU math is long-based and clamped twice (the
   225,443-px overflow from `new_bugs.md` #19 is fixed); dims are bounded to
   1..1,000,000 per axis, with BMP's negative-height case taken as magnitude.
7. **OMML round trip** — `m:oMathPara` (Word's wrapper for display equations)
   recuses into its `m:oMath`, so native Word display equations are not
   degraded to linear text; paragraph-level and run-level `m:oMath` both reach
   the converter; failed conversions degrade to linear text *with* a warning
   on both directions.
8. **Sectioned import** — `Heading_20_1` LibreOffice spellings, `Title` as
   level 1, per-section passage-id qualification, the pre-heading
   "Uncategorized" section, and the paper-title-from-first-Heading-1 rule all
   round trip against the writer's emission (title at level 1, first category
   at Heading 2).
9. **Answer forms** — letters path first, whole-text match including the
   option text containing separators, mixed letters+text refused with the
   offending answer quoted, zero-based index kept for the legacy shape.
10. **Passage anchors** — the blanked-in-place rebuild keeps every character
    offset, so anchor positions stay valid in the rebuilt text; a bare
    `Passage:` reset is round-tripped by a bare reset paragraph in the writer.
11. **Streamed classification** — `classifyPickedArchive` counts files, not
    entries; a truncated archive answers from the prefix it read; the backup
    docx/manifest decision reflects the byte checks' order.


---
