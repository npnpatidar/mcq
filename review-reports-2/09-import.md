# Round-2 review — Import

**Scope:** `ui/importscreen/**`, `data/io/**` (Importer, preview, streaming session/journal, LegacyParser, image downscale), `data/docx/**`, `data/anki/**` reader + media pool, JSON/CSV/DOCX/.apkg handling, dedupe, preview, partial commits, warning classification, fallback titles.
**Branch:** `feature/review-round-2` @ `fbc8e15` (0.0.61). Read-only; no code/tests/gradle touched. Old `review-reports/10-import.md` treated as claims.

## Verdict

The audit's high findings are fixed and the write paths are genuinely atomic. Six residual defects remain; the most serious silently ungrades questions when the opt-in duplicate-answer refresh meets a bank with re-minted option ids.

## Findings

### I1 — High — Duplicate-answer refresh writes correct-option ids that no longer exist
`data/io/Importer.kt:427-455`; `data/io/StreamingImportSession.kt:489-511`; `data/io/ContentHash.kt:120-142,188-208`; `data/io/CardContent.kt:116-122`

`sameQuestionContent()` treats options by *content* and never compares option ids, while `nonHashedFieldsDiffer()` compares `storedCorrectIds != dto.correctOptionIds.toSet()` by raw id. A regenerated bank carrying a corrected key but fresh option ids (`a,b` → `o0,o1`) therefore qualifies for the refresh path, which deletes the stored `correct_answer` rows and reinserts `questionDto.correctOptionIds.map { CorrectAnswerEntity(sameContentId, it) }` — leaving the *stored option rows* with their old ids. The result is a question whose answer key references non-existent options: it imports cleanly, never grades, and `CardContent`'s positional fingerprint (deliberately id-insensitive) does not even flag a content change. Both writers share the defect; both are reached by the Settings toggle `updateAnswersOnDuplicate`.
**Why missed:** `UpdateAnswersOnDuplicateTest.kt` reuses ids `a,b` in every correction case, so the id-remint trigger is untested; the fixture passes and hides the corruption.
**Fix shape:** remap incoming correct ids onto stored option ids by option *position* (using the same positional rule as `CardContent.of`), and base the "differ" decision on positions, not raw ids; add a JVM/Room test with re-minted option ids asserting `getCorrectIds()` still resolves against stored options.

### I2 — Medium — Streamed backup with an all-duplicate first paper gets a disabled Import button
`ui/importscreen/ImportViewModel.kt:334-347,748-800`; `app/src/test/java/com/mcqapp/ImportButtonTest.kt:38-50`

`importButton()` detects a backup only via `state.originalFile`, but `loadLargeJson()` (the streaming path, files >4 MB or large URIs) sets `originalFile = null`. It does add a "Backup restore: N papers … will be restored" advisory notice, but when the sampled first paper has no new/changed questions the gate computes `backup=false, nothingToDo=true, previewTruncated=false` → button disabled, label "Nothing new to import", contradicting the notice. `import()` would still stream papers 2+ and their schedules/attempts. A >4 MB multi-paper backup whose preview has no new rows cannot be restored at all.
**Why missed:** every `ImportButtonTest` builds state with `originalFile = file`; the streaming seam is untested.
**Fix shape:** carry `isBackup`/`paperCount` on `ImportUiState` from `ImportPreview.isBackup` and gate on that flag rather than `originalFile`.

### I3 — Medium — "Streaming" is not memory-bounded for a studied backup
`data/io/StreamingImportSession.kt:118-146,225-240,898-911`; `data/io/ImportPreview.kt:79-84`

`bufferedSchedules` appends every `scheduling` page until `finish()`, and `acceptedSchedules` holds one `ScheduleTarget` per accepted question; for a 1M-card studied backup both are O(cards) on the heap. The preview deliberately avoids this ("does not build a million-entry schedule map just to show a notice"), and the streaming importer advertises bounded memory, but the actual import holds two full-size structures — the code comment itself concedes "the memory profile is the whole-file importer's". This is the huge-file OOM case the streaming design was meant to remove.
**Why missed:** scale tests use synthetic banks with no scheduling tail; the O(n) `bufferedSchedules` growth has no test.
**Fix shape:** spill schedule pages to a temp store and apply in a second bounded pass, or document an explicit cap with a friendly "too large to restore progress" notice. Owner call on which bound to accept.

### I4 — Low — Completion reports an empty paper shell as a "new paper"
`data/io/Importer.kt:147,232-259`; `data/io/StreamingImportSession.kt:361,367-372`

Both writers increment `newPapers` at paper start and then take the all-duplicates skip that writes no categories/questions. The only artifact is an empty paper shell, yet the report says "1 new paper". A re-import of an unchanged bank adds empty shells and the dialog still congratulates the user.
**Why missed:** counted before the skip, and no test asserts the completion text against shell-only imports.
**Fix shape:** decrement or suppress the new-paper count on the skip (and drop the shell, or disclose "empty paper added").

### I5 — Low — Warnings classifier still infers severity from the word "skipped"
`domain/ImportWarnings.kt:98-103`; `ui/importscreen/ImportViewModel.kt:126-127`; `data/docx/DocxReader.kt:72-75`

`typedParserWarnings()` still wraps every parser string with `severityOfParserWarning()` (substring "skipped"). `DocxReader` emits `"Ignoring an unsupported top-level element <…> — only paragraphs and tables are read"` when it drops content, which has no "skipped" and is shown in the neutral advisory panel. Conversely the DOCX payload-stale warning (metadata actually lost) is advisory. Severity must come from the producer, not the wording.
**Why missed:** the typed-warning refactor added explicit severity for VM-authored notices but left the legacy adapter for parser strings.
**Fix shape:** have `DocxReader`/`LegacyParser` emit `ParserWarning` with explicit severity; retire `severityOfParserWarning`.

### I6 — Low — Untranslated data-layer fallback strings leak to Hindi users
`data/docx/DocxParser.kt:508,616`; `data/anki/AnkiPackageReader.kt:194,502,1207`; `ui/importscreen/ImportViewModel.kt:490-494`

The canonical tokens `Uncategorized`/`Imported Questions` are now stable and localized via `fallbackTitleRes`/`displayTitle` (verified). Other stored fallbacks are raw English literals that bypass that map: blank DOCX heading → `"Category"`, blank passage → `"Passage"`, cloze explanation → `"Cloze card from Anki."`. They are persisted as data and rendered verbatim, so a Hindi user sees English.
**Why missed:** the token work covered only the two constants explicitly named in the old audit.
**Fix shape:** add token constants and locale mappings, or treat these as real user-entered content with the same localization rule.

## Re-verified fixed since 0.0.53

- H7 suggestion focus: `ImportScreen.kt:727` uses `PrimaryEditable` anchor.
- H8 `.apkg` drops/conversions: `AnkiApkgImporter.kt:105-108` carries `droppedAnkiStems`/`recallConverted`; both completion dialogs render them (`ImportScreen.kt:608-609`, `LibraryScreen.kt:687-700`) alongside the Anki memory notice.
- Single-shot button: `SingleShotGuard` CAS at `ImportViewModel.kt:81-87,997`.
- Atomic writes: whole run wrapped in one `db.withTransaction` (`Importer.kt:67-71,121`; `StreamingImporter.kt:46-52,88`; `AnkiApkgImporter.kt:59-65`); partial commits no longer possible.
- Completion copy: `importReportLines()` names restored schedules/papers (`ImportViewModel.kt:104-119`, `ImportScreen.kt:605`).
- Stable fallback tokens with localized display: `Importer.kt:35-37`, `fallbackTitleRes()`, `displayTitle()`.

## Checked, no defect

- Zip bombs / path traversal: `SafeZip.kt` counts decompressed bytes (64 MB/entry, 256 MB total, 4096 entries); `AnkiImportWork.extract` writes generated names (`eN`) and `createTempFile`, so no attacker-controlled path. OWASP Zip-Bomb guidance: https://owasp.org/www-community/attacks/Zip_Bomb.
- XXE: `DocxReader.parseXml` applies every supported hardening feature plus an empty entity resolver (https://owasp.org/www-community/vulnerabilities/XML_External_Entity_(XXE)_Processing).
- XSS via imported HTML/MathML: `renderInlineHtml`/`sanitizeMathml` re-emit allow-listed, attribute-free tags; WebView has CSP `default-src 'none'`, `allowFileAccess=false`, URL override, and only a typed `@JavascriptInterface` height callback (https://owasp.org/www-community/attacks/xss/).
- Anki schedule bridge: due-unit rule matches upstream `rslib/src/import_export/package/apkg/import/cards.rs` (https://github.com/ankitects/anki/blob/main/rslib/src/import_export/package/apkg/import/cards.rs); ignored FSRS `cards.data` is a documented known limitation, not a regression.
- Main-thread work: fingerprinting, parsing, downscaling, classification and DB writes all run on `Dispatchers.Default`/`IO`.
- Dedupe/content checks (`sameQuestionContent`, passage remap, id-ownership remap), schedule key resolution (`lookupCardSchedule`), Hindi plural coverage, per-import state reset in `Importer`, and journal "no partial changes" semantics.

## Owner decisions

1. Keep empty paper shells on all-duplicate imports? Fix I4 either way.
2. Accept the O(schedules) memory profile of streaming restores, or fund a bounded implementation for I3.
3. Define answer-key semantics when a duplicate refresh changes option ids (I1) — remap by position vs refuse the refresh.
4. Promote `Category`/`Passage`/cloze explanation to localized tokens (I6) or classify them as user data.
