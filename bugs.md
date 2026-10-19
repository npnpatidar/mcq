# Bug hunt — `main` @ 12166c3

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

## Summary

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

## Findings

### 1 · HIGH · Editor — adding an option can mint a duplicate option id · Confirmed

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

### 2 · HIGH · Import (JSON) — streaming regenerates a category id per call · Confirmed

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

### 3 · HIGH · Import (Anki) — protobuf `config`/`kind` blobs are parsed as JSON · Likely

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

### 4 · HIGH · Search — stale `nextKey` duplicates the last window · Confirmed

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

### 5 · MEDIUM · Import — image-only diff causes a repeating refresh · Confirmed

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

### 6 · MEDIUM · Library — `deleteCategory` orphans schedules and history · Confirmed

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

### 7 · MEDIUM · Import (Anki) — cloze stem leaks the answer; index is list position · Confirmed

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

### 8 · MEDIUM · Import (DOCX) — answer separator rejects valid option-text answers · Confirmed

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

### 9 · MEDIUM · Export (DOCX) — every field marker gets its own paragraph · Confirmed

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

### 10 · MEDIUM · Import (JSON) — streaming drops a new paper's empty categories · Confirmed

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

### 11 · MEDIUM · Export (PDF) — answer key drops legacy explanations · Confirmed

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

### 12 · MEDIUM · FSRS — `intervalFor` narrows to `Int` before clamping · Likely

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

### 13 · MEDIUM · Results — pre-submit row summary blank / markup leak · Confirmed

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

### 14 · MEDIUM · Import (DOCX) — VML images read the wrong attribute · Confirmed

`app/src/main/java/com/mcqapp/data/docx/DocxReader.kt:419-422`

```kotlin
val q = it.getAttribute("r:embed")
if (q.isNotBlank()) q else it.getAttributeNS(REL_NS, "embed")
```

VML `<v:imagedata>` carries the reference in `r:id`, not `r:embed` (`a:blip` uses `r:embed`), so
`ref` is null for VML pictures → the image is dropped with a warning.

**Fix.** For `imagedata`, read `r:id` / `REL_NS "id"`.

### 15 · LOW · Export (HTML) — loose questions counted but not rendered · Confirmed

`app/src/main/java/com/mcqapp/data/export/HtmlPaperWriter.kt:76`, `:84`

`body(...)` renders only `stream.walk(...)`, which by contract does not visit
`PaperStream.looseQuestions` (top-level `paper.questions`), yet the header uses
`stream.totalQuestions`, which includes them. DOCX (`DocxPaperWriter.kt:210-218`) and JSON
(`PaperJsonWriter.kt:40`) do render them. Narrow reach via the current UI, but the contract is
violated and the counts lie.

**Fix.** Render `looseQuestions` (e.g. before/after the categorized walk) or exclude them from the
header count.

### 16 · LOW · Export (HTML) — passage image dropped when elements are empty · Confirmed

`app/src/main/java/com/mcqapp/data/export/HtmlPaperWriter.kt:174-177`

```kotlin
if (passage.elements.isNotEmpty()) {
    out.append(elementsToHtml(passage.elements, passage.image))
}
```

A passage with a legacy `image` but no `elements` prints only its title; PDF and DOCX both render
the legacy image. Narrow (the DB path usually yields `[TextElement("")]`), but inconsistent.

**Fix.** Call `elementsToHtml` unconditionally, or render `imageTag(passage.image)` in the else.

### 17 · LOW · Export (PDF) — unknown inline tags silently stripped · Confirmed

`app/src/main/java/com/mcqapp/data/export/PdfPaperWriter.kt:496-521`

The KDoc promises "Unknown tag shapes and bare `&`/`<` stay visible as text", but no branch appends
an unknown tag (`:521` even comments "Unknown opens are stripped"). Legacy text like
`Pick <one> & "all"?` loses `<one>` in PDF while HTML/DOCX keep it.

**Fix.** Append `match.value` for unknown non-closing tags (or correct the KDoc if stripping is
intended).

### 18 · LOW · Export — `MediaSpool` name flattening aliases entries · Confirmed (latent)

`app/src/main/java/com/mcqapp/data/export/MediaSpool.kt:56`

```kotlin
val file = File(directory, name.replace('/', '-'))
```

`add("a/b", X)` and `add("a-b", Y)` both write `<dir>/a-b`, so `copyTo("a/b")` returns `Y`. No
current caller produces colliding names, so latent.

**Fix.** Map the on-disk name injectively (percent-encode `/`) or use a counter.

### 19 · LOW · Export (DOCX) — `MathMlToOmml` self-closing / whitespace edge cases · Confirmed

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

### 20 · LOW · Import (Anki) — entity decoding edge cases · Confirmed

`app/src/main/java/com/mcqapp/data/anki/AnkiHtml.kt:19`, `:67-84`

- `NUMERIC_ENTITY = Regex("&#(x?[0-9a-fA-F]+);")` — `x?` is case-sensitive, so `&#X2713;` is left
  literal (`MathMlToOmml` uses `[xX]`, so the two decoders disagree).
- The numeric pass runs before `.replace("&amp;", "&")`, so `&#38;amp;` becomes `&amp;` and then
  `&` — a double decode (`&amp;lt;` stays `&lt;`, so it is order-dependent).

Low impact, genuine correctness defects.

### 21 · LOW · Export (DOCX) — `XmlEscapingAppendable.append(Char)` violates the contract · Confirmed (latent)

`app/src/main/java/com/mcqapp/data/export/DocxPaperWriter.kt:1013`

```kotlin
override fun append(c: Char): Appendable = out.append(escaped(c))
```

`Appendable.append(Char)` must return `this`; returning the wrapped `OutputStreamWriter` means a
chained `xml.append('x').append(str)` would bypass escaping. No current caller chains, so latent.

### 22 · LOW · Import — `difficulty` casing causes a one-off spurious refresh · Confirmed

`app/src/main/java/com/mcqapp/data/io/QuestionDtoMapper.kt:32`

`difficulty = difficulty.label` writes the capitalized label (`Medium`), while the parser stores
the raw file value (`medium`, `LegacyParser`) and the whole-bank exporter passes the raw entity.
A `BookmarkExport`/`SelectionExport` re-import therefore sees `stored.difficulty != dto.difficulty`
once and refreshes. It converges after the first cycle, so it is a one-off false change, not a loop.

**Fix.** Compare case-insensitively, or emit the lower-case enum name from the mapper.

### 23 · LOW · Import — `withDownscaledImages` ignores images inside `elements` · Confirmed (doc mismatch)

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

### 24 · LOW · Test session — `TestSnapshot.reorder` does not de-duplicate · Uncertain

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

### 25 · LOW · Import — button disabled for a small all-duplicate backup · Uncertain

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

### 26 · LOW · Study/Test — foregrounding can resume a hidden question's clock · Uncertain

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

## Areas reviewed and found clean

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

## Suggested priority

1. Fix #1, #2, #4 (data corruption / crash / duplicate UI) and #5, #6 (silent data staleness).
2. Validate #3 and #7–#11 against real `.apkg`/`.docx`/`.pdf` exports before touching them.
3. Treat #15–#26 as cleanup; #24–#26 need a reachability check first.
