# New bug hunt — `main` @ f21afc7

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

## Summary

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

## Findings

### 1 · CRITICAL · Import (Anki) — the review *duration* is read as the last-review *timestamp*

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

### 2 · HIGH · Export (DOCX) — the writer's marker guard covers `(a)`–`(j)`, the reader matches `(a)`–`(z)`

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

### 3 · HIGH · Export (DOCX) — control characters are written verbatim into `word/document.xml`

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

### 4 · HIGH · Import (JSON) — `skipValue()` recursion is unbounded

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

### 5 · HIGH · Test session — the resume dialog races the fresh session's timer; auto-submit destroys the saved session

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

### 6 · MEDIUM · Import (JSON) — duplicate option ids silently destroy an option and break idempotence forever

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

### 7 · MEDIUM · Export/Import — Selection exports mint positional category ids

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

### 8 · MEDIUM · Library — `question_stats` is never cleaned up

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

### 9 · MEDIUM · Search/Browse — the SQL `LIKE` prefilter folds ASCII case only

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

### 10 · MEDIUM · Test session — the screen stays interactive while the attempt saves; post-submit edits are silently dropped

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

### 11 · MEDIUM · Settings — "Export all data (JSON)" runs on the Main thread

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

### 12 · MEDIUM · Images — EXIF orientation is ignored on every pick/decode path

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

### 13 · MEDIUM · UI input — comma-locales corrupt numeric entry in three screens (one silent 10–25×)

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

### 14 · MEDIUM · Export (PDF) — a block taller than one page is silently clipped

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

### 15 · MEDIUM · Export (Anki) — learning/relearning cards go out as graduated Review cards

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

### 16 · LOW-MED · Export (HTML) — image-only explanation block is dropped

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

### 17 · LOW-MED · Export — entity decoding disagrees across four renderers

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

### 18 · LOW-MED · Export (PDF) — malformed `data:` URI prints as megabytes of base64 text

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

### 19 · LOW-MED · Export (DOCX) — EMU widths computed in `Int`; large-pixel headers overflow to a negative extent

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

### 20 · LOW-MED · Export — bookmark/selection streams carry no passages

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

### 21 · LOW-MED · Import (JSON, streaming) — the file's review progress is dropped when `scheduling` precedes `papers`

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

### 22 · LOW-MED · Import (Anki) — image-only stems are silently dropped

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

### 23 · LOW-MED · Import (Anki) — the payload fallback can attribute the *passage's* image to the question

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

### 24 · LOW · Test progress — header/rows saves are not ordered or atomic

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

### 25 · LOW · Backup — restoring a corrupt `inProgressTest` document destroys the live resume snapshot

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

### 26 · LOW · History — the bookmarks list pages by OFFSET

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

### 27 · LOW · Browse — the `INSTR(csv)` category filter leaks ids containing commas

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

### 28 · LOW · Library — `savePaper`/`ensurePaperAndCategory` write papers with `INSERT OR REPLACE`

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

### 29 · LOW · Writer detox — soft render/layout defects

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

### 30 · LOW · Export (Anki) — three writer-side schema/convention inaccuracies

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

### 31 · LOW · Backup — the manifest and restore receipt don't count paper-level questions

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

### 32 · LOW · Streaming — post-error cleanup busy-loops instead of stopping the parse

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

### 33 · LOW · Scale — `foldPassageStrays` materialises every member's full row for one column

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

### 34 · LOW · Study — drill **Study again** replays stale schedule states

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

### 35 · LOW · Stats — the card-detail sheet has no generation guard

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

### 36 · LOW · Images — `ImageDownscale` decodes at full size, and OOM silently passes the oversized file through

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

### 37 · LOW · Logging — crash traces may never reach the file; shared `SimpleDateFormat` is a data race

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

### 38 · LOW · Dirty UI details (bundle)

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

### 39 · Notes and unverified leaves

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

## Local test-run baseline (this ARM64 host)

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

## Areas checked and considered clean (so effort is not duplicated)

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

## Suggested priority

1. **#1** (Anki import scheduling corruption), **#4** (import crash on valid input), **#5**
   (destroys the user's saved session), **#2/#3** (whole-file export failures), **#6/#7**
   (silent data loss/misfile on normal import flows), **#11** (ANR on a headline flow).
2. Validate-on-device then fix: **#9** (search contract), **#10** (result/save match), **#12**
   (rotated photos), **#13** (silent marking corruption at three doors), **#14/#18/#19**
   (PDF/DOCX content loss on real banks), **#21-23** (Anki/JSON fidelity), **#15** (Anki
   round trip for in-progress cards).
3. Cleanup sweep: the remaining LOWs, starting with the latent escalators (#27, #28, #33,
   #39's `statsReviews`) and the UX-honesty items (#34, #26, #35, #24).
