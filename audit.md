# Code audit: `0.0.19` → `feature/backup-restore`

- **Date:** 2026-10-14
- **Range:** `0.0.19..HEAD` — 5 commits, 24 files, ~3,450 insertions
- **Commits:**
  - `801c081` feat(export): Word .docx export with categories, maths, images and round trip
  - `9ada0fa` fix(docx): inline layout, table grid, question spacing and a fidelity payload
  - `a7cf46d` feat(backup): full backup zip and exact-state restore
  - `5cea165` fix(backup): preserve paper creation times, read garbage files clearly
  - `c33735d` fix(backup): empty archives report as unreadable zips, not missing parts
- **Scope reviewed:** docx export (`DocxPaperWriter`, `MathMlToOmml`, `PaperExporter`, `ExportFormat`, `Exporter`), docx import overhaul (headings, hidden payload — `DocxParser`, `DocxReader`), full backup/restore (`Backup.kt`, `RepositorySettings.kt`, `Daos.kt`, `McqRepository.kt`, `SettingsViewModel`, `SettingsScreen`), import VM title handling, strings, README, and all 5 new test files (~1,284 lines).

## 1. Confirmed bug — fix before merge

### 1.1 Passage members after the 2nd passage bind to the wrong passage

**Severity:** data corruption on import (regression introduced in `9ada0fa`).

`extractPassages` records the `anchors` list at each `Passage:` marker using positions in the
**original cleaned text** (`app/src/main/java/com/mcqapp/data/docx/DocxParser.kt:447`), but it
returns a **rebuilt** text from which every passage body was removed. `extractQuestions` then
matches question positions in the rebuilt text against those anchors
(`DocxParser.kt:473-481`). Every anchor after the first removed body is offset by the
accumulated removed-body length, so questions belonging to passage 2..N are attributed to an
earlier passage (or to none).

**Reproduced empirically** with a scratch JVM test (since removed):

```
input:  Q1 / Passage: Alpha / body A / Q2 / Passage: Beta / body B / Q3
expected members: ["", "passage-1", "passage-2"]
actual members:   ["", "passage-1", "passage-1"]
```

The pre-change code read marker positions from the same rebuilt text it compared against
(consistent coordinates), so this is a regression, not a pre-existing flaw.

**Test gap:** the round-trip tests cover a single passage only
(`DocxRoundTripTest`, `DocxPaperWriterTest.passageBlockRidesAboveItsGroup`).

**Fix options:**

- recompute anchors in rebuilt coordinates (subtract the cumulative removed-body length for
  each anchor), or
- blank passage bodies with same-length spaces instead of removing them — coordinates never
  drift, and the title-leak fix that motivated the blanking is preserved.

Add a regression test with two passages and a question after the second.

## 2. High — silent data loss on re-import

### 2.1 Payload freshness check compares stems only

`preferPayload` (`DocxParser.kt:86-140`) decides between the hidden full-fidelity payload and
the visible markers by matching **question stem text only**. If the user edits in Word:

- an **option's text**,
- the **answer key** (`Ans.` line),
- the **explanation**, or
- just the **paper title**,

…the stems still match, the payload wins, and the edit is **silently reverted** on import.
The "hidden question data is out of date" warning only fires when a stem changed.

Consider comparing the full visible-marker parse against the payload **per question**
(options + answers, not just stems), so any body edit beyond reflow disables the payload.

**Edge case:** element-only stems (math/image, blank `text`) normalize to `""`, so two
different such questions can false-match; deleting or inserting one can make an edited file
pass the freshness check and resurrect stale payload content.

## 3. Medium

1. **Payload duplicates all images as base64.** The full DTO — including data-URI images —
   rides in `docProps/custom.xml` (`DocxPaperWriter.kt:53-56`, `customPropsXml` at :742-749)
   while the same images also ship as `word/media` entries. Image-heavy papers roughly double
   in size, and a multi-MB `vt:lpwstr` property is unverified against Word's limits (risk:
   Word may report unreadable content). Consider stripping images from the payload; re-import
   gets the bytes from `word/media`.
2. **webp media.** Written as `image/webp` defaults in `[Content_Types].xml`
   (`DocxPaperWriter.kt:711-718`); older Word builds do not render webp. No warning is emitted.
3. **Backup assembly is not transactional and not single-pass.** `SettingsViewModel.backupAll`
   (`SettingsViewModel.kt:221-236`) gathers manifest/content/study/settings in four separate
   reads; a concurrent study session can produce an inconsistent backup. `BackupSource.manifest`
   itself calls `content()` (`Backup.kt:223`), so the full export runs twice per backup. Pass
   one content snapshot into the manifest, or wrap the reads in a read transaction.
4. **Backup zip written on Main.** `outputStream.use { it.write(bytes) }` runs on the
   `viewModelScope` (Main) dispatcher (`SettingsViewModel.kt:238-241`) — jank/ANR risk for
   large backups. Move the write into `withContext(Dispatchers.IO)`.
5. **Partial-restore reporting.** `applySettings`/`applyInProgress` run after the DB
   transaction commits (`Backup.kt:314-315`). If settings restore throws, the user sees
   "Restore failed" although the database was fully wiped and replaced. Report partial success.
6. **64 MB read cap on restore.** `restoreBackup` reads via `readBounded` with the default
   `MAX_IMPORT_BYTES` (64 MB). A legitimate backup with inline data-URI images can exceed
   that, and the error text ("import the bank in parts") does not apply to backups. Consider a
   backup-specific (larger) limit and message.

## 4. Low / notes

- **Hardcoded wipe-table list.** `BackupRestorer.restore` (`Backup.kt:295-301`) deletes from 11
  hardcoded table names — complete today, but a future table would silently survive restore.
  Consider deriving the list from `sqlite_master` or a shared constant. The child-before-parent
  ordering is redundant: every FK in the schema is `onDelete = CASCADE`.
- **`manifest.dbVersion` is recorded but never checked.** Restoring a backup taken on a newer
  DB schema into an older app is not warned about. Content is importer-validated and study
  rows are fixed-shape, so the risk is mostly theoretical.
- **Cross-section passage reuse round-trip gap.** The writer spans one `Passage:` block across
  category sections (`emitPassageAbove`, `DocxPaperWriter.kt:80-98`), but the sectioned parser
  binds passages within a section only — member questions in a later section lose the passage.
  Uncommon shape; worth a note or a writer-side re-emit.
- **Duplicate-content questions on restore.** The importer dedups identical content within one
  import (`sameContentId`); schedules for the collapsed-away id then dangle (`card_state` /
  `review_log` have no FK to questions) and are silently dropped. Only reachable with banks
  containing identical stems.
- **Wasted scheduling build.** `BackupSource.content()` builds the whole scheduling map and
  then drops it via `.copy(scheduling = emptyMap())` (`Backup.kt:205`). Trivial cost; skip it.
- **Leading blank runs.** `ParaBuilder.add` drops a `<w:br/>` at the very start of a paragraph
  (`DocxPaperWriter.kt:506`), so leading newlines in stored text are lost on export. Minor
  fidelity, arguably intended.

## 5. What is good

- **Restore atomicity is real.** The wipe, the importer (which joins the outer transaction via
  `db.withTransaction`, `Importer.kt:96`), the verbatim `card_state`/`review_log` writes and
  the `createdAt` restoration all land in one transaction; a failed restore leaves the library
  untouched.
- **Strong round-trip tests.** `BackupRestoreRoundTripTest` asserts a full row-dump fixpoint
  across every table, the second-backup-is-same-state invariant, manifest counts, and refusal
  messages for garbage/truncated/foreign archives (including the empty-archive case from
  `c33735d`).
- **Bounded, defensive backup reading.** `SafeZip` caps entry count, per-entry size and total
  expansion with actual-decompressed counts; format id and forward-version checks give clear
  user-facing errors.
- **Writer hygiene.** Marker-looking continuation lines are guarded with a leading space,
  category flattening is cycle-safe, XML is escaped, unknown inline tags stay literal, media
  entries are deduped and referenced by filename (relationship ids), option-less questions are
  excluded with a warning instead of producing an unimportable document, and the JPEG header
  scan handles SOF markers correctly.
- **`MathMlToOmml` policy.** A clean, symmetric subset with `null`-on-unsupported and a
  warned plain-text fallback — no silent mangling in either direction.
- **UI wiring.** Restore requires an explicit confirmation dialog with honest copy
  ("Anything added since the backup is gone"), strings are user-tested phrasing, launchers
  follow the existing per-format pattern in all three export screens.
- **Import title handling.** A document's own `Title`/`Heading 1` now wins over the file name,
  editable in the preview (`ImportViewModel`).
- **Docs.** README's import/export and backup sections match the shipped behavior, including
  the heading→category mapping and the payload's guarantees.

## 6. Recommendation

Fix **1.1** (confirmed import corruption) and decide on **2.1** (silent loss of user edits)
before merging. Items 3-6 are cheap follow-ups; the low section is note-level.
