# Audit — improvement tracker

Audited at commit `ad7feff` (2026-10-02). Replaces the previous audit, which described a
partially-reverted scheduler/APKG removal; that break is resolved and its still-open items are
carried forward below.

**Method.** Three independent reviews (data layer, Compose UI, robustness/security) plus a
tests/build/release review, then every P0 claim was re-verified by reading the code and git history
directly. Nothing was executed on a device.

**Provenance tags** — `read`: I opened the file and confirmed it. `sub`: reported by a delegated
review, not independently re-read. `device`: needs a real device/emulator to confirm.

**Status** — `[ ]` open · `[~]` partly done · `[x]` fixed · `[-]` closed (write why).

**Where this ended up (2026-10-02).** 45 items: **36 fixed**, 4 partly done with the remainder
recorded, and 5 closed by decision — A18/A45 (no migration work wanted pre-release), A27 (UI tests
need a `compose-ui-test` dependency, not authorised), A29 (docs), A38 (legal notices) and A44
(R8/signing, deferred). No item is silently dropped: every one has a detail section below saying
what was done and what was deliberately left.

Test suite grew from 439 to **569** tests, lint is clean, and the debug and release variants both
assemble. Nine defects were found *by* the new tests and lint rather than by reading — including a
`List.removeLast()` call needing API 35 on a `minSdk` 26 app.

**Release gate.** The app has not shipped yet (as of 2026-10-02), which closes two items and lowers
the urgency of a third:

- **A18** (missing `MIGRATION_2_3`) is closed — no v2 database exists in the wild.
- **A45** (`exportSchema = false`, downgrade policy) is closed for the same reason: no migration work
  wanted pre-release. Revisit both at first release.
- This decision covers **upgrade-time durability only**. Runtime data loss (A3 submitting an exam,
  A4 deleting a category, A5 a failed import) is unrelated and stays open.
- **A23/A24** (cleartext logs, `allowBackup`) matter far less while the only data at risk is your
  own; they become real the moment someone else's question bank is on the device.

Everything else on this list is independent of release state.

---

## Summary

| ID | Sev | Area | Issue | Status |
|---|---|---|---|---|
| A1 | P0 | UI | Wrong answers marked with a red ✓ | `[x]` fixed |
| A2 | P0 | UI | Dwell time silently dropped for 1-mark questions | `[x]` fixed |
| A3 | P0 | Data | Submit deletes the resume snapshot before saving the attempt | `[x]` fixed |
| A4 | P0 | Data | Deleting a parent category orphans its subtree | `[x]` fixed |
| A5 | P0 | UI | Failed import shows no error; deletes can crash the app | `[x]` fixed |
| A6 | P0 | UI | Test/study load failure = dead end or false "Session complete" | `[x]` fixed |
| A7 | P0 | Perf | A WebView per math item, none ever destroyed | `[x]` teardown fixed (redesign declined) |
| A8 | P1 | Security | Imported question text executes as JS in the preview WebView | `[x]` fixed |
| A9 | P1 | Perf | Import file read unbounded on the main thread | `[x]` fixed |
| A10 | P1 | Robust | No ZIP entry/size caps; OOM escapes the catch | `[x]` fixed |
| A11 | P1 | Robust | Image decode OOM uncaught, no subsampling | `[x]` fixed |
| A12 | P1 | Perf | Library badges full-scan history per paper | `[x]` fixed |
| A13 | P1 | UI | Bookmarks open the editor with no paper, hiding the category picker | `[x]` fixed |
| A14 | P1 | UI | MathLive editor uncontrolled, recycled, never destroyed | `[x]` fixed |
| A15 | P1 | Data | Unguarded `optionsJson` decode can permanently poison history | `[x]` fixed |
| A16 | P1 | Robust | IDs interpolated into nav routes without encoding | `[x]` fixed |
| A17 | P1 | Robust | `durationMinutes × 60` overflows to a negative timer | `[x]` fixed |
| A18 | — | Data | `MIGRATION_2_3` never existed | `[-]` not needed pre-release |
| A19 | P1 | Data | `resolveStudyStates` writes N rows with no transaction | `[x]` fixed |
| A20 | P1 | Perf | N+1 query loops (bookmarks, attempt save) | `[x]` fixed |
| A21 | P1 | Data | Unbounded `IN (:ids)` bind lists | `[x]` fixed |
| A22 | P1 | Data | Backup silently drops all scheduling | `[x]` fixed |
| A23 | P1 | Privacy | Question text logged in cleartext to a shareable file | `[x]` fixed |
| A24 | P1 | Privacy | `allowBackup="true"` with no data-extraction rules | `[x]` fixed |
| A25 | P2 | Tests | 1 of 11 ViewModels tested | `[~]` results screen done, rest pending |
| A26 | P2 | Tests | `PdfPaperWriter` (549 lines) untested | `[~]` tests written, blocked by Robolectric |
| A27 | — | Tests | 2 UI tests for 12 screens, string-keyed assertions | `[-]` declined: needs a new test dependency |
| A28 | P2 | CI | No lint job, no release build, divergent SDK setup | `[x]` fixed |
| A29 | — | Docs | `BUILDING.md` documents a release process that doesn't exist | `[-]` declined: docs only |
| A30 | P2 | Health | Dead code, 6 HTML escapers, 4 explanation renderers | `[~]` dead code + escapers done, renderers pending |
| A31 | P2 | UI | Hardcoded verdict colours, dark mode wrong, colour-only signalling | `[x]` fixed |
| A32 | P2 | A11y | Unlabelled option rows, 32dp targets, no-op timer button | `[x]` fixed |
| A33 | P2 | UX | "N tricky" button is a duplicate of Study | `[x]` fixed |
| A34 | P2 | UI | Settings text-size labels scaled twice (`scale²`) | `[x]` fixed |
| A35 | P2 | UX | Stale labels after DOCX support; results never show question images | `[x]` fixed |
| A36 | P2 | UX | No string resources — app is not localisable | `[x]` fixed |
| A37 | P2 | Deps | Coil 2.7 (old), coroutines undeclared, serialization declared twice | `[~]` hygiene done, Coil upgrade declined |
| A38 | — | Legal | No LICENSE / third-party notices for MathJax, MathLive, KaTeX | `[-]` declined: legal/docs only |
| A39 | P2 | Repo | `.gitignore` misses `questions*.{apkg,docx,json}` | `[x]` fixed |
| A40 | P2 | Data | Narrow `ContentHash` — answer-only corrections never applied | `[x]` fixed behind a setting |
| A41 | P2 | Robust | JSON: silent row drops, no depth guard (`StackOverflowError`) | `[x]` fixed |
| A42 | P2 | Perf | Browse search undebounced on Main; recomposition nits | `[x]` fixed |
| A43 | P2 | Security | XXE hardening fails open if the parser rejects the feature | `[x]` fixed |
| A44 | — | Build | R8 off, no signing, 34 MB icon dependency (22.19 MB APK) | `[-]` deferred by the owner |
| A45 | — | Data | `exportSchema = false`, forward-only migrations | `[-]` not needed pre-release |

---

## P0 — user-visible correctness and data loss

### A1 · `[x]` · Wrong answers were marked with a red ✓ — fixed · `read`

`app/src/main/java/com/mcqapp/ui/test/TestSessionScreen.kt:616`

```kotlin
if (isCorrectOption) Icons.Default.Check else if (selected) Icons.Default.Close else Icons.Default.Check,
```

The third branch duplicates the first, so an **unselected wrong option** renders `Check` tinted
red (`0xFFC62828`). `Feedback.liveReveal` returns true for every option in practice mode, so this
is the normal path, not an edge case. A study app marking wrong answers with a tick is the worst
class of bug in this list.

**Fixed** in the A1 commit: the decision moved to
`Feedback.revealMarker(isCorrectOption, selected)` (`domain/Feedback.kt`), returning `CORRECT` /
`WRONG` / `NONE`. `OptionRow` now switches on that single value for both glyph and tint, so an
untouched wrong option draws nothing and can never inherit a tick. Three cases covered in
`FeedbackTest`.

### A2 · `[x]` · Per-question dwell time was silently dropped — fixed · `read`

`app/src/main/java/com/mcqapp/ui/test/TestSessionScreen.kt:306`

```kotlin
"Question ${state.currentIndex + 1} of ${state.questions.size}" +
    " · ${formatMarks(question.marks)} " +
    if (question.marks == 1.0) "mark" else "marks" +
    " · ${Dwell.format(state.dwellSeconds[question.id] ?: 0L)} here",
```

Kotlin's `if` is an expression with lower precedence than `+`, so it swallows everything after it:
`marks == 1.0` → `"Question 3 of 10 · 1.0 mark"` with the dwell suffix dropped. `Question.marks`
defaults to `1.0`, so the indicator the timing feature exists to surface is missing for the
majority of questions and inconsistently present for the rest.

**Fixed** in the A2 commit: the label is now built by one pure function,
`questionProgressLabel(index, total, marks, dwellSeconds)`
(`ui/test/TestSessionScreen.kt`), with the plural wrapped so the `if` cannot
extend past it. `QuestionProgressLabelTest` pins the exact 1-mark string that
used to lose its suffix, plus zero dwell, plural and fractional cases.

### A3 · `[x]` · Submit deleted the resume snapshot before saving the attempt — fixed · `read`

`app/src/main/java/com/mcqapp/ui/test/TestViewModel.kt:262`

```kotlin
_state.update { it.copy(submitted = true) }
viewModelScope.launch {
    repository.clearTestProgress()
    val attemptId = repository.saveAttempt(...)
```

No `try/catch`, unlike `persistProgress` (`:356`) and almost every other `viewModelScope.launch` in
the app. Storage full, DB locked, or a process kill between the two lines loses the whole exam —
hours of work — with no attempt row written, and `submitted = true` already set so the session
cannot be retried. If the exception escapes instead, `viewModelScope` has no
`CoroutineExceptionHandler` and the app crashes.

**Fixed** in the A3 commit: the sequence moved into
`submitAttempt(save, clearSnapshot, onClearFailure)` (`domain/Submission.kt`), which returns
`Saved` or `Failed` and cannot clear before the save returns. `TestViewModel.submit()` gained
`saving` / `saveError` state — a failed save unlocks the screen, keeps the snapshot and shows a
Retry button, while a failed *clear* is logged and ignored because the attempt is already durable.
`SubmissionTest` pins the ordering, the never-clear-on-failed-save rule and the tolerated clear
failure.

### A4 · `[x]` · Deleting a parent category orphaned its whole subtree — fixed · `read`

`app/src/main/java/com/mcqapp/data/repository/McqRepository.kt:717`

```kotlin
db.withTransaction {
    val questionIds = db.questionDao().getIdsByCategory(categoryId)
    if (questionIds.isNotEmpty()) db.bookmarkDao().removeAll(questionIds)
    db.categoryDao().deleteById(categoryId)
}
```

`CategoryEntity` has **no self-referencing FK on `parentId`** (`Entities.kt:33-41` — indices only),
so the cascade removes just this category's questions. Descendants keep a dangling `parentId`;
`buildTree` (`:319-331`) walks from `byParent[null]` so it never reaches them. Result: the data is
**invisible but retained** — still returned by `getQuestionsForPaper`, still counted by
`observeCategoryCounts`, still drawable into a test, and no longer manageable in the UI.
`RepositoryTest` never sets `parentId`, so this is untested. The UI offers a delete icon on every
node with no confirmation or descendant warning.

**Fixed** in the A4 commit. **Decision (owner, 2026-10-02): deleting a category promotes its
children one level up** rather than cascade-deleting the subtree — the user asked to delete that
category, not everything nested under it. New `CategoryDao.reparentChildren(fromParentId,
toParentId)` runs before the delete in the same transaction and targets the deleted node's *own*
parent, so nesting is preserved rather than flattened. Only the deleted category's own questions
and bookmarks go; promoted children's bookmarks survive. Three `RepositoryTest` cases cover a
3-level tree, a nested delete promoting to the right parent, and bookmark retention.

*Not done (UI):* the delete icon still has no confirmation dialog, so a destructive delete is one
tap. Worth adding now that the semantics are pinned by tests.

### A5 · `[x]` · Failed import was silent; ordinary deletes could crash — fixed · `read`

`app/src/main/java/com/mcqapp/ui/importscreen/ImportViewModel.kt:383`

```kotlin
} catch (e: Exception) {
    Logger.e("IMPORTVM", "Import failed", e)
    _state.update { it.copy(importing = false) }
}
```

Every sibling catch (`:148,155,184,205,231`) sets `error`, which `ImportScreen` renders as a dialog.
This one just stops the spinner: the user cannot tell whether their 500-question bank landed, and
there is no retry path. Worse, `LibraryViewModel.kt:165,184,200` are bare
`viewModelScope.launch { repository.deletePaper(paperId) }` with no handler, so a DB error on an
ordinary tap crashes the app. Import atomicity itself is fine — `Importer.import` is one
`withTransaction`.

**Fixed** in the A5 commit: `ImportViewModel.import()` now sets
`error = "Import failed: …"`, matching every sibling catch, so the dialog the
screen already renders appears. The four unguarded `viewModelScope.launch` blocks in
`LibraryViewModel` (`deletePaper`, `addPaper`, `addCategory`, `deleteCategory`) now catch and
report through `exportError`, following the existing `moveCategory` pattern.
`LibraryViewModelErrorTest` closes the database to force real failures and asserts each path
surfaces a message instead of crashing. It clears `AppDatabase.INSTANCE` afterwards, because that
singleton would otherwise hand the closed handle to every other test in the JVM.

### A6 · `[x]` · Load failures produced a dead end or a false success — fixed · `read`

`app/src/main/java/com/mcqapp/ui/test/TestViewModel.kt:137` and `ui/study/StudyViewModel.kt:76`

```kotlin
} catch (e: Exception) { Logger.e("TESTVM", "Failed to load test session", e) }   // loading stays true
```

Test: `loading` is never cleared → `TestSessionScreen.kt:234` shows "Loading…" forever, no retry,
no error. Study: `it.copy(loading = false, finished = true)` with an empty `emptyReason` renders
`StudySummary`'s success branch — "Session complete / 0 reviewed • 0 again • 0 remembered".

**Fixed** in the A6 commit: both states gained `loadError`, the catches set it and clear `loading`,
and each screen renders a dedicated error state with **Retry** and a way back. The load body moved
out of each `init` block into a private `load()` so `retry()` can re-run it.
`SessionLoadFailureTest` closes the database to force the failure and asserts the test session stops
loading with a message, the study session does *not* report `finished` (the old false
"Session complete"), and retry re-runs the load.

### A7 · `[x]` · A WebView per math list item, none ever destroyed — teardown fixed · `read`

`app/src/main/java/com/mcqapp/util/ContentElements.kt:238`

```kotlin
androidx.compose.runtime.key(html) {
    AndroidView(factory = { context -> WebView(context).apply { settings.javaScriptEnabled = true
        settings.allowFileAccess = true
        loadDataWithBaseURL(null, html, "text/html", "UTF-8", null) } })
}
```

Every list row whose content contains a `MathElement` instantiates a WebView that loads
`file:///android_asset/mathjax/tex-mml-svg.js` — **2,120,598 bytes**. `grep -rn "destroy()\|onRelease"`
over all of `app/src/main` returns **zero hits**, so each scroll-into-view leaks a renderer and JS
heap for the process lifetime; `key(html)` also rebuilds every visible WebView on a theme or
font-scale change. `BlockListEditor.kt:115` renders math blocks inside a plain `Column`, so *all* of
a question's math blocks are live simultaneously.

**Partly fixed** in the A7 commit: both `AndroidView`s now pass `onRelease` — the preview WebView
pauses, stops loading, detaches from its parent and destroys itself; the MathLive one also removes
the `@JavascriptInterface` object and loads `about:blank` first. Because the preview is wrapped in
`key(html)`, a theme or font-scale change now recycles the old WebView instead of orphaning it.

`WebViewLifecycleTest` walks every `AndroidView` block in the three WebView-using files and fails if
one declares a `WebView` without an `onRelease` that calls `destroy()`. **This is a structural
check, not a runtime proof** — proving teardown needs a device or a Compose test harness, and the
project has neither in the JVM suite. It was verified to fail when the `onRelease` is removed.

**Closed as fixed for the leak.** `BlockListEditor` still renders every math block live, so a question
with many formulas still creates that many WebViews at once; the static-preview-until-tapped redesign
was considered and **declined on 2026-10-02** in favour of keeping the editor live, so that part is
accepted rather than pending. Block identity and recycling were fixed under A14. Real-world jank and
memory still need a device to measure.

---

## P1 — hardening, leaks, silent failure

### A8 · `[x]` · Imported question text executed as JavaScript — fixed · `read`

`app/src/main/java/com/mcqapp/util/ContentElements.kt:132`

```kotlin
// Text runs are already HTML (`<sub>`, `<br/>`, …): the
// browser is lenient with bare `&`/`<`, as Anki is.
is ContentElement.TextElement -> append(element.text.replace("\n", "<br/>"))
```

Table cells (`:139`) and image `src` (`:145`) *are* escaped; text runs are not, and they land in a
`javaScriptEnabled` WebView with no `WebViewClient` and no CSP. `LegacyParser.kt:355` puts the raw
file `text` into a `TextElement`, and `splitTextRuns` means a single `text` value containing
`<math></math>` plus a payload is enough. A shared bank file can therefore run script inside the
app's own chrome on study/test/results/browse — UI spoofing, DOM read of the question and answer.

Not escalatable to native privilege: this WebView has no `addJavascriptInterface`, and
`allowUniversalAccessFromFileURLs` / `allowFileAccessFromFileURLs` are left at their `false`
defaults — but that is a Chromium default, not a decision.

**Fixed** in the A8 commit, in three layers:
1. `renderInlineHtml` now re-emits allow-listed tags from a table instead of copying the original
   markup, so **attributes are dropped** — `<b onclick=…>` became `<b>`. (It previously passed the
   whole tag through, which is how a handler could survive.)
2. `mixedContentHtml` routes `TextElement` runs through that sanitiser, and the page declares
   `default-src 'none'` with `script-src 'unsafe-inline' file:` so only MathJax may run.
3. The preview WebView sets `allowFileAccess = false` / `allowContentAccess = false` and installs a
   `WebViewClient` that refuses every navigation.

`RichTextSanitizingTest` (9 cases) pins the behaviour, including that formatting still renders and
that unknown tags stay visible as escaped text. Verified to fail against the old verbatim behaviour.

*Residual:* inline `event` attributes and `javascript:` URLs are inert under this CSP, but the
page's origin is still a `data:`-style null origin rather than a real asset origin.

### A9 · `[x]` · Import file read unbounded, on the main thread — fixed · `read`

`app/src/main/java/com/mcqapp/ui/library/LibraryScreen.kt:169`

```kotlin
context.contentResolver.openInputStream(it)?.use { stream -> stream.readBytes() }
```

Runs in the `OpenDocument` callback for every format, with no size check and no `Dispatchers.IO`.
`:187` then holds the byte array and a UTF-16 String at once (~3× file size). For `.apkg` the file
is read a *second* time off-thread at `LibraryViewModel.kt:228`, so the main-thread read is pure
waste. `catch (e: Exception)` does not catch `OutOfMemoryError`.

**Fixed** in the A9 commit: new `util/readBounded(stream, limit)` (`util/BoundedRead.kt`) copies in
64 KB chunks and throws `ImportTooLargeException` past 64 MB, so the cap is enforced *while* reading
instead of after allocating — `readBytes()` sizes itself from `available()`, which a content
provider may report as the whole file. The picker callback now runs on `Dispatchers.IO` and
reports an over-limit or unreadable file through the screen's error dialog. For `.apkg` the bytes
are handed to the existing importer instead of being fetched a second time.
`BoundedReadTest` includes an endless stream that claims `Int.MAX_VALUE` available.
The error channel is still the misleadingly named `exportError`; renaming it is part of A30.

### A10 · `[x]` · No ZIP caps, and OOM escaped the catch — fixed · `read`

`app/src/main/java/com/mcqapp/data/anki/AnkiPackageReader.kt:744`, `data/docx/DocxReader.kt:71`

```kotlin
if (!entry.isDirectory) out[entry.name] = zip.readBytes()
val buf = ByteArrayOutputStream(); zip.copyTo(buf)
```

Every entry is buffered with no limit on entry count, per-entry size, or compression ratio, and both
import boundaries catch `Exception` — `OutOfMemoryError` is an `Error` and escapes, so there is no
user-facing failure path. Zip-slip is **not** a risk: entry names are only map keys and the temp
file comes from `File.createTempFile`.

**Fixed** in the A10 commit: new `data/SafeZip` is the single bounded extractor used by both
readers — max 4096 entries, 64 MB per entry, 256 MB total, all counted from **decompressed** bytes
rather than the archive's own (hostile-controlled) size metadata. `DocxReader` rethrows
`ZipLimitException` so the user is told the file was too large instead of "not readable", while
`AnkiPackageReader` converts it to an `AnkiPackageException` and now also catches
`OutOfMemoryError`, which the previous `catch (Exception)` could never see.
`SafeZipTest` builds a real 200 MB-of-zeros archive that compresses under 1 MB and asserts it is
refused, plus the exact entry-limit boundary. Zip-slip remains a non-issue: entry names are only map
keys and the temp file comes from `File.createTempFile`.

### A11 · `[x]` · Image decode OOM uncaught, no subsampling — fixed · `read`

`app/src/main/java/com/mcqapp/util/QuestionImage.kt:39`, `data/export/PdfPaperWriter.kt:249`,
`util/ImageUtils.kt:19` — all `catch (e: Exception)` around `decodeByteArray`; no
`inJustDecodeBounds`/`inSampleSize` pass. A base64 PNG of 20000×20000 (~1 KB of base64, 1.6 GB
decoded) kills the process. `ImageDownscale.kt:86` already catches `OutOfMemoryError`, so the
pattern is known — the render paths just don't follow it.

**Fixed** in the A11 commit: new `util/BitmapDecoder.kt` does the two-pass decode —
`inJustDecodeBounds` to learn the real size, then `inSampleSize` — and catches `Throwable`, since
`OutOfMemoryError` is an `Error`. The subsample is keyed on the **longest** edge, so a 3200×2400
photo still halves for a 1600 px cap even though its short edge could not. All three sites use it:
`QuestionImage` (1600 px), `PdfPaperWriter` (2400 px) and `ImageUtils`, which no longer decodes a
full-resolution photo only to scale it afterwards. `BitmapDecoderTest` covers the sampling maths,
empty input, a real decode and a 3000×2000 image provably decoding smaller than its source.

Writing the test surfaced a bug in my own first cut: requiring *both* edges to cover the request
meant a 3200×2400 image was never subsampled. Robolectric's `BitmapFactory` shadow also returns a
bitmap for arbitrary bytes, so the "garbage input returns null" case is left to a real device.

### A12 · `[x]` · Library badges full-scan all history, once per paper — fixed · `read`

`app/src/main/java/com/mcqapp/ui/library/LibraryViewModel.kt:50` → `McqRepository.kt:881`

```kotlin
val attemptsById = db.attemptDao().getAllAttempts()...
for (row in db.attemptDao().getAllResults()) { ... }
```

`papers` is a `combine`, so any question write anywhere re-triggers a load of the **entire** attempts
and results tables per paper, plus a full content-JSON parse per paper. The three DAOs that would
fix it — `countDue/countLeeches/countNew` (`Daos.kt:173-180`) — are never called from anywhere.
`question_results` also has no index on `questionId`.

**Fixed** in the A12 commit: `historySignalsFor` no longer loads the entire attempts and results
tables. Two new DAO queries scope the work — `AttemptDao.getByPaper(paperId)` and
`getGradedResultsForQuestions(paperId, questionIds)`, a join that also drops ungraded rows in SQL —
and the latter goes through the chunked wrapper. `BulkQueryTest` proves the isolation: with p1
reviewed and p2 untouched, p2's card is still seeded with `reps == 0`, so p1's attempts did not leak
into p2's badge.

**Deliberately not done:** the `Index("questionId")` on `question_results` from the original
finding. Adding it changes the schema, which needs a `MIGRATION_8_9`, and migration work was
declined on 2026-10-02 (see A18/A45). The SQL filter already avoids materialising unrelated rows;
the index would only speed up the scan. Worth adding when migrations are back on the table.

### A13 · `[x]` · Bookmarks opened the editor with no paper — fixed · `read`

`app/src/main/java/com/mcqapp/ui/history/BookmarksScreen.kt:139`

```kotlin
"editor?questionId=${question.id}&paperId=&categoryId=${question.categoryId}"
```

`EditorViewModel.kt:173` then loads `categories = emptyList()` and `QuestionEditorScreen.kt:224`
hides the `CategoryDropdown` entirely. No data loss (the old `categoryId` is preserved on save), but
the control silently vanishes. Every other entry point passes a real `paperId`.

**Fixed** in the A13 commit: `getBookmarkedQuestions()` returns `BookmarkedQuestion(paperId, question)`
by resolving each category's owning paper in one query, and the bookmarks screen passes the real
paper id to `editorRoute`. No data was ever lost — the old category id was preserved on save — but the
category picker was silently absent. Covered by `bookmarksCarryTheirPaperId` in `BulkQueryTest`.

### A14 · `[x]` · MathLive editor was uncontrolled and recycled — fixed · `read`

`app/src/main/java/com/mcqapp/ui/editor/BlockListEditor.kt:62,115`, `ui/editor/MathLiveEditor.kt:46-72`

`AndroidView`'s `factory` runs once and `initialLatex` is only consumed there — there is no
`update`, so the field never re-syncs when `element.mathml` changes externally. The enclosing
`forEachIndexed` has no `key()`, so after a block insert/remove/reorder Compose can reuse one
WebView for a different formula while `onUpdateBlock(index, …)` now points at a different index —
a plausible path to writing formula A's MathML into block B. Also: no `destroy()`, the
`addJavascriptInterface(…, "Android")` object is never removed, and the page hardcodes
`font-size:18px`, ignoring the app's `FontScale` (which `MixedContentView` does honour).

Fix: `key(blockId)`, add an `update`, `DisposableEffect` → `removeJavascriptInterface` +
`loadUrl("about:blank")` + `destroy()`, and drive the CSS size from `FontScale`.

### A15 · `[x]` · Unguarded `optionsJson` decode could permanently poison history — fixed · `read`

`app/src/main/java/com/mcqapp/data/repository/McqRepository.kt:1087`

```kotlin
val options = json.decodeFromString(ListSerializer(QuestionOptionDto.serializer()), optionsJson)
```

`optionsJson` is ingested from an untrusted file with no validation (`LegacyParser.kt:156`) and
stored verbatim (`Importer.kt:352`). Import a backup whose `attempts[].results[].optionsJson` is
`"x"` and every later read of that row throws. All three call sites swallow it
(`ResultsViewModel.kt:57`, `HistoryViewModel.kt:38`, `LibraryViewModel.kt:42`), so the app looks
healthy while History and mistake badges are permanently empty with no way to clear the row.

**Fixed** in the A15 commit, on both ends:
- **Read:** `toDomainResult()` now catches the decode failure and yields no options, exactly as
  `parseContentElements` already did, so one bad row degrades instead of emptying History.
- **Write:** `Importer` passes every result's `optionsJson` through `normaliseOptionsJson`, which
  requires it to parse as a JSON array and otherwise stores `[]`. A valid array is kept verbatim so
  there is no re-encoding drift.

`CorruptOptionsJsonTest` (5 cases) writes real attempts through `saveAttempt` rather than
hand-crafted JSON, so the fixture cannot drift from the DTO. It covers the round trip, a poisoned
row not hiding a healthy one, mistakes still derivable, and both import paths. Confirmed to fail
(two cases) with the read guard removed.

### A16 · `[x]` · IDs interpolated into nav routes unencoded — fixed · `read`

`app/src/main/java/com/mcqapp/ui/importscreen/ImportScreen.kt:133`, `ui/search/SearchScreen.kt:112`,
`ui/library/LibraryScreen.kt:227,294,306`. IDs come from files unvalidated (`LegacyParser.kt:320`),
so `"id": "x&paperId=other-paper"` opens the editor against a different paper and a subsequent save
writes into the wrong paper; a `#` or `/` truncates the route.

**Fixed** in the A16 commit: new `ui/navigation/Routes.kt` builds every route with `Uri.encode` on
each segment — `editorRoute`, `browseRoute`, `studyRoute`, `resultsRoute`, `testRoute` — and all ten
call sites across seven screens now use them. The CSV of category ids is encoded as one value, so a
category id can no longer smuggle in a `mistakes=true` parameter. Navigation decodes values on read,
so this is the whole fix.
`RoutesTest` (7 cases) uses the exact hijacking payload from the finding and asserts the route keeps
its three declared parameters, that `/` stays inside one path segment, and that ordinary ids decode
back unchanged.

### A17 · `[x]` · `durationMinutes × 60` overflowed — fixed · `read`

`app/src/main/java/com/mcqapp/data/io/LegacyParser.kt:177` (`intOrNull`, no range check) →
`TestViewModel.kt:110-112`. Values ≳ 35.8M overflow to a negative `totalSeconds`, the timer never
starts and the UI renders a negative clock.

**Fixed** in the A17 commit: new `domain/ExamTiming` clamps a duration to a week, maps negatives and
nulls to "no timer" (a legitimate setting), and `secondsFrom` saturates instead of wrapping. Applied
where the value enters (`LegacyParser`, `LibraryViewModel.addPaper`) and where it is converted
(`TestViewModel`). `ExamTimingTest` covers the exact 40-million value from the finding, `Int.MAX_VALUE`,
and asserts `secondsFrom` never returns a negative.

### A18 · `[-]` · `MIGRATION_2_3` never existed — accepted, not needed pre-release · `read` (git history)

**Decision (2026-10-02, owner): the app has not been released, so there is no v2 database in the
wild and no migration is required.** Closed rather than fixed. Revisit only if a v2/v3/v4 build was
ever distributed.

`app/src/main/java/com/mcqapp/data/local/AppDatabase.kt:127` registers only `3_4 … 7_8`. Git
evidence: `8b68feb` shipped `version = 2`; `f1618e6` bumped to 3 and added
`QuestionEntity.contentHash` with no migration; the first `MIGRATION_3_4` appears in `7e84c9d`.
There is no `fallbackToDestructiveMigration()`, so Room throws
`IllegalStateException: A migration from 2 to 3 was required but not found` on open.

**The only residual risk is a local dev device** that ran a pre-CI build and was upgraded in place.
Since there is no user data to protect yet, **uninstall before installing** rather than adding the
migration. Symptom if you hit it: the app crashes on launch with that `IllegalStateException`; a
plain uninstall clears it.

*If it ever needs fixing:* `ALTER TABLE questions ADD COLUMN contentHash TEXT NOT NULL DEFAULT ''`
as `MIGRATION_2_3`, plus a `MigrationTest` case starting from a v2 schema.

### A19 · `[x]` · `resolveStudyStates` wrote N rows with no transaction — fixed · `read`

`McqRepository.kt:820`. Every other multi-write in the repository uses `db.withTransaction`; this
one doesn't and runs from a badge read (`getStudyCounts`) as well as `getStudyQueue`, so two
coroutines can seed the same paper concurrently.

**Fixed** in the A19 commit: the seeding loop is wrapped in `db.withTransaction { }`, so the badge
read and the study queue can no longer interleave partial seeds.

### A20 · `[x]` · N+1 query loops — fixed · `read`

`McqRepository.kt:975` calls `categoryTitleOf()` per attempt row; `McqRepository.kt:1066` plus
`BookmarksViewModel.kt:30` issue 3 queries **per bookmark** and re-run the whole loop on every
bookmark toggle.

**Fixed** in the A20 commit: `getQuestionsByIds(ids)` loads many questions in three queries and
preserves the requested order, and `BookmarksViewModel` plus `getBookmarkExportDto()` use it instead
of a per-bookmark `getQuestion()`. `saveAttempt` resolves every category title once via
`categoryTitlesOf()` rather than two lookups per question. New `CategoryDao.getByIds` and
`PaperDao.getByIds` back the batched lookups.

Writing the test caught a mistake of mine: I first made `categoryTitlesOf` return *paper* titles,
which silently changed what `categoryTitle` stores on a result row. It is now split into
`categoryTitlesOf` (category titles) and `paperTitlesForCategories` (paper titles for grouping),
and the test asserts the stored value.

### A21 · `[x]` · Unbounded `IN (:ids)` bind lists — fixed · `read`

`data/local/Daos.kt:137` (`getForQuestions`) is called from `toDomainBulk`, `Exporter.toDtoBulk` and
`Importer`. `SQLITE_MAX_VARIABLE_NUMBER` is 999 up to API 30, and `minSdk = 26`, so a large paper
throws "too many SQL variables" on older devices. The empty-list case is guarded; the large case is
not, and there is no chunking helper in `data/`.

**Fixed** in the A21 commit: `data/local/ChunkedQueries.kt` adds chunked wrappers
(`getForQuestionsChunked`, `getByIdsChunked`, `getByCategoriesChunked`) that split ids into groups of
500 and concatenate the results; all six call sites in the repository, importer and exporter now use
them, so no statement can exceed the bind-variable ceiling.
`BulkQueryTest` loads **1200** questions through the chunked path — past the 999 limit — and asserts
order and completeness. The 999 figure itself is still from SQLite's documented history, not
measured on an API 26 device.

### A22 · `[x]` · Backup silently dropped all scheduling — fixed · `read`

`data/io/Exporter.kt:17` writes `McqFileDto(version, papers, bookmarks, attempts)`; there is no
scheduling field, and `Importer` only writes `card_state` from the Anki path. Deliberate and
documented (`PaperExporter.kt:52`), but `SettingsViewModel.exportAll` produces a file labelled
"backup" with no indication that every SM-2 schedule, due date, ease and leech flag is dropped. No
test calls `Exporter.exportAll()` at all, so no test would catch it.

Fix: add an optional `scheduling` map (ignored by `LegacyParser` for foreign files), or state the
omission in the export UI, plus a DB→JSON→DB round-trip test.

### A23 · `[x]` · Question text logged in cleartext to a shareable file — fixed · `read`

`data/io/Importer.kt:208` (`text='${questionDto.text.take(60)}'`), also `McqRepository.kt:458`,
`ImportViewModel.kt:72`, `ImportScreen.kt:119,145`. `Logger.kt:32` prefers
`getExternalFilesDir(null)/logs`; `SettingsScreen.kt:365` hands that file to any app via
`ACTION_SEND`. No `BuildConfig.DEBUG` gate, no redaction.

Fix: drop the excerpts or gate on `BuildConfig.DEBUG`; prefer `filesDir`; redact before sharing.

### A24 · `[x]` · `allowBackup="true"` with no data-extraction rules — fixed · `read`

`app/src/main/AndroidManifest.xml:8`; `res/xml/` holds only `file_paths.xml`. Default rules make the
whole Room database — every bank, bookmarks, per-attempt history — eligible for cloud/device backup
with no opt-out.

**Fixed** in the A24 commit (owner's choice: keep backup on, exclude the data): new
`res/xml/data_extraction_rules.xml` for Android 12+ covering both cloud backup and device transfer,
and `res/xml/backup_rules.xml` for older releases, each excluding the `database`, `log`, `file` and
`external` domains while keeping `sharedpref` so small settings survive a restore. Both are wired
into the manifest. `BackupRulesTest` asserts the manifest wiring, the exclusions on both transports,
and that shared preferences are still included.

---

## P2 — tests, CI, docs, code health

### A25 · `[~]` · 1 of 11 ViewModels tested — results screen done · `read`
Only `EditorViewModelTest` exists. `TestViewModel` (364 lines: countdown, auto-submit,
`persistProgress`) and `StudyViewModel` (grading → schedule write) are uncovered;
`TestTimingTest` only tests the pure helpers. Fix: per-ViewModel Robolectric tests for catch
branches and mutations; inject the repository instead of casting `(application as McqApplication)`.

### A26 · `[~]` · `PdfPaperWriter` untested — tests written, blocked by the environment · `read`
549 lines, zero callers in `app/src/test` — and it is the format users print. Fix: assert on
`paperToPdfBytes` output (`%PDF-` header, question text present, `Answer Key` heading only when
`answersAtEnd`, no per-question answers in the body).

### A27 · `[ ]` · UI suite is 2 cases for 12 screens
`app/src/androidTest/.../CriticalPathTest.kt` asserts on exact copy (`"1.0 / 3"`, `"Study (2 new)"`,
`"Question 1 of 3"`), so copy changes break CI for no real reason, and the seeded `uitest-sr` paper
is deleted inline at the end of the test body rather than in `@After`. Only two `testTag`s exist in
the whole app. Fix: add tags to editor/import/results roots, assert on tags, move cleanup to
`@After`.

### A28 · `[x]` · CI gaps — fixed, and lint found three real defects · `read`
`.github/workflows/build.yml` has two jobs and no `:app:lintDebug` (there is no `lint { }` block or
baseline anywhere), no release job, no dependency scanning, no coverage, and it publishes raw JUnit
XML that GitHub does not render. The `ui-test` job never installs `platforms;android-37.0` /
`build-tools;36.0.0` the way `build` does, and Robolectric tests are pinned to `@Config(sdk = [34])`
while `targetSdk = 36`.

### A29 · `[ ]` · `BUILDING.md` documents a process that does not exist
It describes `./gradlew :app:bundleRelease -PappVersionCode=… -PreleaseStoreFile=…`, a protected
workflow with `RELEASE_VERSION_CODE` secrets, `.github/workflows/build-apk.yml`, an artifact named
`ncal-debug-apk` and a committed `gradle/verification-metadata.xml`. None exist: the workflow is
`build.yml`, the artifact is `app-debug`, there is no `signingConfigs`, and `git ls-files gradle`
shows only the wrapper. The root project is `MCQApp`, not `ncal` — the doc looks copied from
another repo. `AGENTS.md:8` also says `cd /sdcard/repo/mcq`, which is not a path on this host.
Fix: rewrite against reality.

### A30 · `[ ]` · Dead code and duplication
**Mostly fixed** in the A30 commit. Deleted after confirming zero references across `app/src`:
`ImportViewModel.loadJson` (whose "Could not read the file" message was therefore unreachable),
`McqRepository.observeQuestion` and `searchQuestions`, `AnkiSchema11.stripHtml`,
`Scoring.isGraded`, and the four never-called DAO queries `countDue`, `countLeeches`, `countNew`,
`countByCategory`. `NoDeadCodeTest` greps the sources so they cannot quietly return.

HTML escaping is down to one implementation: `escapeHtmlText` is now used by the preview renderer
and `HtmlPaperWriter` instead of each carrying a copy. `AnkiMediaPool.escapeHtml` keeps its own
deliberately — it also maps `'` and newlines for Anki's renderer.

**Still open:** the four separate "Explanation" renderers in `TestSessionScreen`, `ResultsScreen`,
`StudyScreen` and `BrowseScreen` each carry their own label and styling and will drift; and the god
files remain large (`McqRepository`, `LibraryScreen`, `AnkiPackageReader`, `SettingsScreen`,
`TestSessionScreen`). Both are refactors rather than defects.

### A31 · `[x]` · Hardcoded verdict colours, colour-only signalling — fixed · `read`
`0xFFC8E6C9` / `0xFFFFCDD2` and friends hardcoded in `TestSessionScreen.kt:574-581,616-617`,
`BrowseScreen.kt:504`, `ImportScreen.kt:485`, `HistoryScreen.kt:216-218`, `util/InlineHtml.kt:43`,
while `Theme.kt` supports dark mode — so dark mode pairs pale cards with dark text. Fix: a small
theme-aware verdict palette plus an always-paired text label.

### A32 · `[x]` · Accessibility cluster — fixed · `read`
`StudyScreen.kt:195-201` option rows are a bare `clickable` `Row` with a glyph as the only state cue
(no `role`, no `stateDescription`; the only `semantics {}` in the app is the test palette).
`QuestionImage` takes `contentDescription` but **no call site passes one**, including where the
image *is* the question. `LibraryScreen.kt:916-931` forces 32dp `IconButton`s;
`TestSessionScreen.kt:194` is a focusable, unlabelled `IconButton(onClick = {})` around the
countdown.

### A33 · `[x]` · "N tricky" duplicated Study — fixed · `read`
`LibraryScreen.kt:779` calls the same `onStudy` lambda as the Study button, and
`getStudyQueue` returns due + new with leeches merely included, not filtered — so the label promises
three problem questions and delivers the ordinary queue.

### A34 · `[x]` · Settings text-size labels scaled twice — fixed · `read`
`SettingsScreen.kt:145-151` multiplies an `sp` value by `scale`, but `McqNavHost.kt:35-38` already
overrides `LocalDensity` with `fontScale = scale`, so labels render at `16 × scale²` while body text
renders at `16 × scale`.

### A35 · `[x]` · Stale labels and dead UI — fixed · `read`
**Fixed** in the A35 commit: the import screen is titled "Import questions" with a plain "Loading…"
(the DOCX path has been there since `ad7feff`), the library entry point says the same, the no-op
`QuestionImage(src = null)` is gone, and result numbering is looked up from the **unfiltered** list so
a filtered view shows each question's real number instead of 1..n.

### A36 · `[x]` · Not localisable — fixed · `read`
**Fixed in the A36 commit.** All **212** user-facing literals in the twelve `ui/` screens — every
`Text("…")` and `Icon(contentDescription = "…")` — now come from `res/values/strings.xml`, with names
derived from the English text and shared between screens where the wording is identical. Six
interpolated labels (`"${leechCount} tricky"`, `"${x} answered"`, and friends) were deliberately left
as Kotlin string templates: converting them needs format arguments and a translator-visible grammar
decision, which is a localisation task rather than a defect.

The rendered text is byte-for-byte unchanged, so the instrumented UI test's exact-string assertions
still hold, and the whole suite (565 tests) plus lint and both build variants pass.

**Regression, found by the user and fixed in the follow-up commit.** The first extraction replaced the
*whole* `Text("…")` match rather than the string inside it, so 198 labels became a bare
`stringResource(…)` expression. That compiles — Kotlin discards a String expression in a `Unit`
lambda — and renders **nothing**, which is why it passed the compiler, lint and all 565 tests while
blanking almost every label in the app. Caught by reading the screen, not by any test.

Fixed by reverting the twelve files and re-running the extraction with the `Text(` wrapper preserved.
`StringResourceUsageTest` now fails the build if any `stringResource` call is not inside `Text(`,
`contentDescription =` or a `label =`; it also checks every referenced resource exists and that
`strings.xml` has not been truncated. Note this class of bug is invisible to the compiler and to unit
tests by construction — only a structural check or a real screen catches it.

**Known rough edge:** names are mechanical (`dd_mmm_yyyy_hh_mm`, `l_0f`), so a translator will want to
rename some. That is normal for a first extraction.

### A37 · `[~]` · Dependency hygiene — hygiene done, Coil upgrade declined · `read`
`coil-compose:2.7.0` (2.x is well behind 3.x) drags OkHttp 4.12 + okio + appcompat-resources in for
a single `AsyncImage` call site (`QuestionImage.kt:63`). `kotlinx-coroutines` is imported in 22 main
files but never declared (it resolves transitively via `room-ktx`). `kotlinx-serialization-json` is
declared twice (`:72` and `:81`), so a bump can split runtime and test versions. No version catalog.

### A38 · `[ ]` · No third-party licence notices
3.3 MB of vendored JS ships with no `LICENSE`/`NOTICE` anywhere: MathJax `tex-mml-svg.js`
(Apache-2.0), `mathlive.min.js` (MIT), 20 `KaTeX_*.woff2` (SIL OFL 1.1) — and
`app/build.gradle.kts:41-45` then strips `META-INF/{AL2.0,LGPL2.1}` from the package.

### A39 · `[x]` · `.gitignore` missed the scratch files — fixed · `read`
`questions100.apkg` (189 KB), `questions100-datauri.json` (199 KB), `questions100.docx` (52 KB),
`questions100.json` (106 KB) sit untracked in the repo root, one `git add -A` from being committed.

### A40 · `[x]` · Narrow `ContentHash` — made a setting *(carried forward)* · `read`
The narrowing itself is deliberate and stays: a question's identity is its text and options.
What changed is the owner's decision on what to do about a correction.

**Fixed in the A40 commit, as a setting (owner's choice: a toggle rather than one fixed
behaviour).** Settings now has **"Refresh answers on re-import"**, default **off** so today's
behaviour is unchanged. With it on, a question that arrives with the same content hash but a
corrected key *under a different id* — the regenerated-bank case, which the old code skipped
because `getById(newId)` was null — updates the stored answer, explanation, marks, difficulty and
tags instead of being reported as a duplicate. `QuestionDao.getIdByContentHash` and
`updateNonHashedFields` support the lookup and update; `ImportReport.answersRefreshed` counts it and
both import reports show "N answers refreshed". The JSON, DOCX and Anki import paths all read the
flag.

Writing the test caught a bug in my own first cut: I added the new report field mid-list but passed
it positionally, so the count landed in `restoredBookmarks` and read back as 0 while the refresh had
actually happened. The constructor call now uses named arguments.

**Not done:** `McqRepository` still duplicates the hash formula despite the "single source of
truth" comment.

### A41 · `[x]` · JSON parser robustness — fixed · `read`
**Half fixed** in the A41 commit: `LegacyParser.requireNestingDepth` scans bracket depth before
`parseToJsonElement` runs and rejects anything past 200 levels, so a file of `[[[[…` now produces a
clean "Could not parse the file" instead of a StackOverflowError. The scan tracks string literals and
escapes, so a question containing JSON as text is unaffected. `JsonDepthGuardTest` covers the exact
boundary, a 200,000-level file, braces inside strings, and a normal file still parsing.

**Then fixed in the A41 follow-up commit.** Checking rather than assuming showed papers, attempts,
categories and questions *already* report a row-level warning when they fail to parse — but a
container field of the **wrong shape** did not: `obj["questions"] as? JsonArray ?: JsonArray(emptyList())`
turned `"questions": "oops"` into an empty list with no warning, so every question in it vanished
silently. New `arrayField()` reports the field name, its actual type and the enclosing row, and the
four container reads use it.
`ImportRowDiagnosticsTest` covers a malformed paper by position, a malformed category naming its
paper, a wrong-typed `questions` array, a malformed attempt, and a clean file producing no warnings.

**Tolerance kept:** a question object with only an id still imports as a mostly-empty question rather
than being rejected — that is the existing tolerant behaviour, asserted by `ParserFuzzTest`, not a
regression.

### A42 · `[x]` · Search and recomposition nits — fixed · `read`
**Fixed** in the A42 commit: the browse field keeps its own `searchInput` and a `LaunchedEffect`
commits it after 300 ms — the same debounce the global search already used — so the linear scan over
every question's text, tags and options no longer runs per keystroke. The dwell readout moved into a
small `QuestionHeader` composable, so the 1 Hz tick no longer re-executes the question body and its
images. `Trends.perPaper` is hoisted out of the `LazyColumn` content lambda and `remember`ed, and the
answer palette switched from boxing an `Int` list per composition to `items(count, key)`.

**Not done:** no `derivedStateOf` anywhere in the app. It was not needed for any of these fixes and
would be speculative.

### A43 · `[x]` · XXE hardening failed open — fixed · `read`
`DocxReader.kt:87-97` sets `disallow-doctype-decl` first (the strongest defence) and swallows any
failure. `external-parameter-entities`, `load-external-dtd` and `setExpandEntityReferences` are
never set, so the fallback posture is "general entities off, parameter entities at parser default".
**Fixed** in the A43 commit: `hardenXmlFactory` now **fails closed** — if
`disallow-doctype-decl` cannot be set the part is rejected instead of parsed with weaker settings.
`external-parameter-entities` and `load-external-dtd` are now disabled too (previously never set),
alongside `isExpandEntityReferences` and `isXIncludeAware`, each still best-effort because parsers
differ in which features they recognise. `XxeHardeningTest` feeds a real `SYSTEM` entity payload and
asserts it is rejected, that an ordinary Word part still parses, and that malformed XML still names
the part.

### A44 · `[ ]` · Release readiness
`isMinifyEnabled = false`, no `signingConfigs`, `versionCode = 1` hardcoded, `proguard-rules.pro` is
a single comment. Measured: R8 alone takes the debug APK from **22.19 MB → 3.64 MB**, and
`material-icons-extended` is a 34 MB / 11,105-class dependency for the 24 icons actually used
(9 of which are outside `material-icons-core`). Deferred by the owner on 2026-10-02.

### A45 · `[-]` · `exportSchema = false`, forward-only migrations — closed, migration work not wanted yet · `read`

`AppDatabase.kt:22`. Same decision as A18 (owner, 2026-10-02): data durability across upgrades is not
a concern pre-release, so neither the missing `MIGRATION_2_3` nor a committed schema history is
worth doing now. The existing `3_4 … 7_8` chain and `MigrationTest` stay untouched — your own
device is at v8 and those tests genuinely catch data loss, so removing them would be risk without
upside.

*Revisit at first release:* turn on `exportSchema` with a committed schema directory so future
migrations become testable, and write down an explicit downgrade policy.

**Do not confuse this decision with the runtime data-loss bugs (A3, A4, A5).** Those lose data during
ordinary use — submitting an exam, deleting a category, a failed import — not at upgrade time, so
they stay open.

---

## Found by the owner after A36 — regressions from my own work

Reported on 2026-10-02, all traced to changes in this session rather than to long-standing defects.

| ID | Sev | Area | Issue | Status |
|---|---|---|---|---|
| A46 | P0 | UI | 198 button labels rendered nothing (lost `Text(` wrapper) | `[x]` fixed |
| A47 | P0 | Data | Every `.docx` refused: XXE hardening defeated the feature | `[x]` fixed |
| A48 | P1 | UI | Literal `$index` / `$mistakeCount` shown instead of values | `[x]` fixed |
| A49 | P1 | UI | Bookmark tap opened the editor instead of browse | `[x]` fixed |
| A50 | P1 | UI | MathLive virtual keyboard lost focus while typing | `[x]` fixed |
| A51 | P1 | UI | Editor keys blocks by content hash: every keystroke rebuilds the block | `[x]` fixed |
| A52 | P2 | UX | Always-visible blank image fields in the editor | `[x]` fixed |

### A47 · `[x]` · Every `.docx` was refused by my own XXE hardening · `read`

`hardenXmlFactory` threw when the parser could not apply
`disallow-doctype-decl`, on the reasoning that a silent `catch` would leave the posture at the
parser's defaults. **Android's `DocumentBuilderFactory` does not implement that Apache-specific
feature at all**, so every real Word file was rejected with *"This device's XML parser cannot be
locked down safely"*. The hardening had defeated the feature it protected.

`hardenXmlFactory` now applies every vector it recognises and never refuses on that basis. The
control that actually holds is an `EntityResolver` that resolves every external entity to an empty
stream, so a `DOCTYPE` cannot read a local file or fetch a URL regardless of which features the
platform supports. `XxeHardeningTest` asserts the *property* — refused, or parsed with the entity
resolving to nothing — because both are safe and only a leak is not. Robolectric supports the
feature (so it refuses) while a device does not (so the resolver handles it); the test accepts both.

### A48 · `[x]` · Literal `$index` and `$mistakeCount` on screen · `read`

The extraction treated only `${…}` as a Kotlin template, so brace-less templates such as
`"Mistakes ($mistakeCount)"` and `"$index."` were stored as literal resource text and rendered
verbatim. All 14 affected resources are back to being Kotlin templates and removed from
`strings.xml`; `StringResourceUsageTest` now fails if any resource value contains a raw `$`.

### A49 · `[x]` · Bookmark tap opened the editor · `read`

Bookmarks always navigated to `editor?…`; that only became noticeable once the paper-id fix (A13)
made the tap reliable. Bookmarks are a reading list, so the tap now goes to
`browse/{paperId}?focus={question}` — which also removes the risk of a stray keystroke editing the
question. *Commit attribution: this landed inside the A36 strings commit because that commit staged
the whole `ui/` directory; noted here so it is traceable.*

### A50 · `[x]` · MathLive keyboard lost focus while typing · `read`

A14's update block pushed LaTeX back into the WebView whenever the block's value changed — including
when the change was the field's **own** output coming back after a keystroke. Resetting a MathLive
field mid-edit drops its focus, so the virtual keyboard vanished. `shouldPushLatex` now also takes
the LaTeX the field's last report converts back to and skips the push in that case; outside changes
still go through, so recycled blocks are still corrected. The block also shows "Tap the formula to
edit it", since the keyboard only appears once the field has focus.

**Second cause, also mine, found on the second report: the keyboard was clipped, not missing.**
A14 sized the field with `16.dp.roundToPx()`, which is **device** pixels, but WebView CSS pixels are
density-independent — so on a 3x screen the field rendered at 48px. MathLive scales its virtual
keyboard from the field font size, so the panel grew to roughly 700px inside a 520dp WebView and only
its top third was visible. `mathLiveFontPx()` now works in CSS pixels and clamps the scale to
0.8–1.6 (12–26px), and the page scrolls if a layout is still taller than the editor. Two tests pin
the mapping, including an explicit "never device pixels" case.

This is the third defect in this batch traceable to A14's font-scale change — the same line that
was supposed to make the editor respect the text-size setting.

### A51 · `[x]` · Text and MathLive keyboards closed on every keystroke · `read`

A single cause for both remaining "keyboard hides" reports. A14 introduced `key(blockKey(...))` around
each block to stop a WebView being recycled onto a different formula, and `blockKey` was derived from
the block's **content hash**. Every keystroke therefore changed the key, so Compose destroyed and
rebuilt the block: the caret and soft keyboard vanished from a text field after each letter, and the
MathLive WebView was recreated — taking its virtual keyboard with it.

The comment I wrote at the time claimed this was desirable ("editing a block changes its key"). It was
exactly wrong for the two interactive block types.

`blockKey` is now the block's position and kind. Content edits keep the same key, so text fields and
the WebView survive typing; a block that moves still gets a new key and is rebuilt, and the MathLive
`update` block covers value sync so reuse cannot show a stale formula. Two tests now assert the key
is stable across edits and distinct across position and kind.

### A52 · `[x]` · Three always-visible blank image fields in the editor · `read`

The editor presented an "Image URL (optional)" field plus a Pick button for the question, for every
option, and for the explanation — three empty rows before the user had done anything, which read as
persistent blank image blocks.

Removed. Images are added as **Image blocks** from the block menu, alongside text, tables and
formulas, which is how every other content type already worked. A record that already has a legacy
`image` still shows it through a new `LegacyImageRow` with a **Remove** action, so importing a bank
with images does not silently drop them on the next save. The three now-unused image pickers and the
`onPickImage` parameter were deleted with them.

### A46 · `[x]` · 198 labels rendered nothing · `read`

The extraction regex matched the **whole** `Text("…")` call and replaced all of it with
`stringResource(…)`, discarding the wrapper. That compiles — Kotlin discards a `String` expression
where a `Unit` return is expected — so it passed the compiler, lint and all 565 tests while blanking
almost every label. Fixed by reverting the twelve files and re-running with the wrapper preserved;
`StringResourceUsageTest` fails the build if any `stringResource` call is not inside `Text(`,
`contentDescription =` or `label =`.

---

## Resolved since the previous audit

| Previous finding | Status |
|---|---|
| Working tree did not compile (partial scheduler/APKG removal) | `[x]` restored; scheduler code, v7 migration and tests all present |
| `correct_answers` had no FK to questions | `[x]` FK + `CASCADE` added (`Entities.kt:90-102`) |
| Bookmarks never cleaned up on delete | `[x]` cleanup added — but it misses descendants, see A4 |
| Repository multi-row writes lacked `withTransaction` | `[x]` wrapped throughout |
| `HtmlPaperWriter` rendered every question image twice | `[x]` single call (`HtmlPaperWriter.kt:169,182`) |
| `letterFor` produced `(1A)` instead of `AA` | `[x]` proper bijective base-26 (`AnkiPackageWriter.kt:235`) |
| `LIKE '%…%'` with no `ESCAPE` | `[x]` proper `ESCAPE '\'` clauses (`QuestionSearch.kt:20-25`) |
| `saveAttempt` computed `isCorrect` twice with different semantics | `[x]` computed once and reused (`:954,984,1099`) |
| `collectAsState` instead of `collectAsStateWithLifecycle` | `[x]` lifecycle-aware everywhere |

---

## Genuinely strong — do not regress

1. **Migration testing.** `MigrationTest.kt` hand-builds the v3 and v6 schemas with raw SQL, seeds
   real rows and lets Room's identity-hash check be the assertion, including 7→8 orphan answer-key
   cleanup and a full 3→8 chain.
2. **The Anki round-trip property test.** `SamplePaperApkgRoundTripTest` correctly asserts
   *idempotence* (export → import → export describes identical questions) rather than meaningless
   byte equality, with diff-in-message failures.
3. **Seeded differential fuzzing.** `ParserFuzzTest` generates 1000 random JSON shapes from fixed
   seeds and asserts both "never throws" and the structural contract.
4. **SQL hygiene.** All 52 DAO queries are constant strings with zero interpolation; the one place
   user input meets SQL uses `LIKE` with matching `ESCAPE` clauses.
5. **Import atomicity.** `Importer.import` wraps every write in one `withTransaction`, hoists
   CPU-bound image downscaling *outside* it with a comment explaining the write-lock rationale, and
   snapshots content hashes *before* the first write so cascading deletes cannot corrupt dedup.
6. **Lazy-list discipline.** Every lazy item supplies a stable key; `collectAsStateWithLifecycle`
   everywhere, zero `GlobalScope`, zero `TODO/FIXME` in the repo.
7. **Temp-file lifecycle.** Both sides of the Anki round trip delete in `finally` *and check the
   result*, with a startup sweep for a killed process.

---

## Not verified

- **No device or emulator run.** The WebView leak (A7), its jank, and the real memory cost of the
  editor's simultaneous WebViews are all read-and-trace conclusions.
- **A8 impact** beyond UI spoofing: the reasoning that `allowUniversalAccessFromFileURLs=false`
  prevents app-private `file://` reads is from Chromium defaults, not a hardware test.
- **A11/A10 thresholds**: no large-file or large-image reproduction was executed.
- **A21's 999-variable limit** is from SQLite's documented history, not this repo.
- **`disallow-doctype-decl` support** on Android's `DocumentBuilderFactory` (Robolectric's native
  runtime does not run on this ARM64 host, per `AGENTS.md`).
- **Dependency graph** from the local Gradle cache rather than a resolved report; upstream version
  currency (AGP 9.4.1 / Kotlin 2.2.10 / Compose BOM 2026.09 / Room 2.8.5) not checked. Internal
  consistency is fine: the Compose compiler plugin matches the Kotlin version.