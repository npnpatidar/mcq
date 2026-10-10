# Passage-Based Questions — Research & Design

**Status:** Resumed 2026-10-09 on `exp/passage-questions`. Supersedes the parked
entry in `ideas.md` (which kept the original decisions; this document re-verifies
them against the post-F1–F13 codebase and answers the open decisions).

**Requirement:** a passage with several questions (e.g. 5) asked from it. They must
be grouped together and occur together.

## Decisions carried over from the parked design (still valid)

- **Tests:** hard togetherness — members contiguous, passage visible while
  answering, shuffle moves the group as a block.
- **Study:** shared context only — each question keeps its own SM-2 schedule; the
  card renders the passage above it. Forcing co-occurrence would undo the
  scheduling-correctness work (Tracks A–D).
- **Data model:** `PassageEntity(id, categoryId, title, text, image, sortOrder)`
  + nullable `passageId` on questions. A passage lives in a category; members
  keep their own `categoryId`. Scheduling, bookmarks, attempts and stats stay
  per-question. `contentHash` includes `passageId` but not the passage text.
- **Rejected alternatives:** passage-as-special-question (pollutes every
  consumer), denormalised text (editing touches N rows), category hierarchy
  reuse (conflates organisation with grouping).

## Current-state facts (verified 2026-10-09)

| Area | Fact | Consequence |
|---|---|---|
| Schema | `AppDatabase` v8, manual migrations, last is `MIGRATION_7_8` (`AppDatabase.kt:93`) | New `MIGRATION_8_9`: `CREATE TABLE passages` + `ALTER TABLE questions ADD COLUMN passageId TEXT` |
| Questions | `QuestionEntity` (`Entities.kt:55`), FK `categoryId → categories` CASCADE | `passageId` is a plain nullable column (no FK, like `bookmarks.questionId`) |
| Content hash | `ContentHash.of(text, optionTexts, optionImages)` (`io/ContentHash.kt:17`) is the single source of truth; `QuestionContentMapper.computeContentHash` wraps it | Adding `passageId` to the hash = one signature change; import preview, importer and `card_state` seeding all flow through the wrapper |
| Card state | `StudyStore` seeds `card_state.contentHash` from `mapper.contentHashOf(question)` and resets a card when the hash differs (`StudyStore.kt:64,166`) | **Reassigning a passage resets that question's SM-2 progress** — same as editing its text. Acceptable and consistent, but must be a conscious decision (D6) |
| Import | `Importer.import` loops papers → categories → questions inside one transaction; dedup via content hash + `isSameContentAsAnyStored`; images downscaled before the transaction | Passages import per paper before questions; `passageId` rides on `QuestionDto`; passage text never enters the hash, so editing a passage never re-triggers dedup |
| JSON | `McqFileDto` (`JsonDto.kt:142`) has top-level `bookmarks`/`attempts`/`scheduling`; `QuestionDto` at `:31` | Add top-level `passages: List<PassageDto>` + `passageId` on `QuestionDto` |
| Export | `Exporter.exportPaperDto` → categories → `toDtoBulk()` (`Exporter.kt:128`) | Emit `passageId` per question + the paper's passages |
| DOCX | `questionSplit = Regex("(?m)^(\\d{1,7}\\.\\)")` (`DocxParser.kt:92`); blank-line collapse at `:69` keeps line starts | A `(?m)^Passage:` marker hooks into `extractQuestions`; stems always start with `N.)`, so no collision; `RawQuestion` gains `passageId`, `questionJson` emits it |
| APKG | `buildFront` (`AnkiPackageWriter.kt:149`) builds the front HTML; `payloadOf` (`AnkiMcqPayload.kt:83`) is the round-trip blob | Prepend passage text in `buildFront`; add `passageId` + passage text to the blob; `AnkiPackageReader.remapQuestion` parses it back |
| Test sessions | `TestViewModel.load()` (`TestViewModel.kt:99`): questions → `Shuffle.shuffleAttempt` (flat) → `Drill.sample` → state; `TestSessionScreen` navigates a flat index | Blocks: group after load, block-aware shuffle (new domain fn next to `Shuffle`), drill all-or-nothing; header above each member |
| Study | `StudyViewModel.load` → `repository.getStudyQueue` (per-question SM-2); `StudyScreen.kt:375` renders `ContentElements` | No queue change; context card above the question, passage fetched by `passageId` |
| Browse | `BrowseScreen.kt:324` `itemsIndexed` over a flat filtered list | Group: passage header rows + member rows |
| Search | DAO prefilter `LIKE` on `questions.text` (`Daos.kt:206`) + in-memory `QuestionSearch.filter` over `Question` objects | Passage text is not on `Question`; matching it needs a passages-table prefilter (new DAO query) unioned with question hits |
| Editor | `QuestionEditorScreen` + `EditorViewModel` (`saveQuestion`/`deleteQuestion`) | Passage CRUD + assign/unassign/reorder UI |
| Library | `observePapers` + study counts are question-based | Unaffected; counts stay question-based |
| Deletes | `PaperStore.deletePaper` (`:115`) cascades + manual cleanup; `deleteCategory` (`:153`) reparents children | With `PassageEntity` FK `categoryId → categories` CASCADE, both deletes clean passages automatically; `questions.passageId` needs explicit handling when a passage is deleted directly (D5) |

## Open decisions — answered

1. **Drill rule: all-or-nothing with overshoot.** `Drill.sample` shuffles and
   takes; the block-aware version shuffles whole blocks until the count is met
   (the last block may overshoot) and the UI shows "5 asked, 6 shown".
   Excluding a passage that doesn't fit could drop its easiest questions and
   break contiguity. `Drill.checkCount` already refuses counts above the paper
   size, so the pre-flight stays.
2. **Passage-internal order in shuffled tests: stable.** Block shuffle only;
   member order stays as authored. Deterministic, matches "occur together",
   and keeps the single-seed story in `Shuffle` intact.
3. **Study adjacency: shared context only.** Confirmed — no queue
   post-processing; the parked decision stands.
4. **DOCX marker: line-start `Passage:`.** Fits the existing `(?m)^` marker
   style; questions until the next marker (or end) belong to it; questions
   before any marker stay standalone; the `N.)` split is untouched.

## New decisions surfaced by the research

5. **Deleting a passage that still has members: refuse.** Error dialog naming
   the member count; unassign first, then delete. Matches `deleteCategory`'s
   careful philosophy (it reparents rather than orphans). Alternative
   (auto-detach) hides data movement from the user.
6. **`passageId` joins the question content hash — accept the card reset.**
   Reassigning a passage changes the hash, so that question's `card_state`
   resets (interval lost), exactly as if its text changed. Both writers go
   through `QuestionContentMapper.computeContentHash`, so import, export and
   study seeding stay in sync for free. The alternative (exclude `passageId`
   from the hash) would let a question change passages without the study
   layer noticing — worse.
7. **Passage text is searchable.** New DAO prefilter against `passages.text`,
   unioned with the existing question/option hits; `QuestionSearch.filter`
   stays the authority for question content.
8. **Export writers repeat the passage header on every member** (PDF, HTML,
   ZIP). Each question stays self-contained in exports, matching the
   "options on the front" philosophy; a block header would couple the writer
   to session logic.

## Sequencing (10 focused commits)

1. Data model: `PassageEntity`, `passageId`, `PassageDao`, `MIGRATION_8_9` +
   migration test (raw-SQLite `MigrationTest` pattern).
2. Store helpers: passage CRUD + `passageId` assignment/unassignment +
   repository tests.
3. JSON round-trip: `PassageDto`, importer, exporter + round-trip test.
4. Test sessions: block build + block shuffle + drill rule + sticky/repeated
   header + tests.
5. Study: context card + tests.
6. Browse grouping + passage search + tests.
7. DOCX `Passage:` marker + README format section + tests.
8. APKG: front prepend + blob fields + round-trip test.
9. Editor: passage CRUD + assign/unassign/reorder + tests.
10. Export writers (PDF/HTML/ZIP) passage headers + tests.

## Risks

- **Card reset on reassignment** (D6) — by design, but the editor should warn.
- **Import ordering** — passages must be written before member questions so
  `passageId` resolves; the importer's per-paper loop gives a natural place.
- **Search union** — passage hits and question hits need a stable merge
  (dedupe by question id; a question matching both ways appears once).
- **`exportSchema = false`** (audit F15) — no schema JSON to keep in sync;
  the migration test is the only guard.

## Progress log

2026-10-10 — all ten steps implemented in one WIP on `exp/passage-questions`
(schema v9 + `MIGRATION_8_9` + migration test, `PassageStore` CRUD/assign/
reorder, JSON round-trip incl. legacy aliases and the import preview's file
assembly, block build/shuffle/drill with overshoot UI, study context card,
browse grouping, DOCX `Passage:` marker + README format section, APKG front
prepend + payload fields, passage picker in the question editor, and passage
headers in the PDF/HTML/ZIP writers). Bugs found and fixed while reviewing:
`LegacyParser` never read a root-level `questions` array, so the DOCX
wrapper reported "No papers found"; `buildImportFile` dropped
`original.passages`, so the preview path imported members with dangling ids;
the in-memory `QuestionSearch.filter` vetoed the DAO's passage-union hits
(passages now flow into it as `passageBodies`); the DOCX passage split
dropped the newline after the marker line, so the first member's `N.)`
stopped being a line-start marker and was lost. Local verification: 807 unit
tests compile and run; every passage test passes; the remaining local
failures are the pre-existing Robolectric/aarch64 environmental ones (CI is
authoritative for those).
