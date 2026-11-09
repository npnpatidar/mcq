# Phase 3 implementation plan: custom tests, reliability and export

Status: **implemented on `feature/phase3-custom` (cut from `origin/main`; PR #52, draft).** Phase 2 is merged to `main` (PR #51).

**Implemented (slices 1–5 + fixed panels):**
- **Slice 1 (data foundation):** `question_tags` index + schema v22 migration/backfill (`MIGRATION_21_22`), nullable `attempts.variant`, tag normalization (`Tags.kt`), write-path tag-index sync (QuestionStore/Importer/StreamingImportSession/bulk), exact scoped tag/bookmark/dwell reads. Generated schema `22.json`. `MigrationTest.ALL_MIGRATIONS` includes `21_22`, with a v21→v22 fixture/assertions.
- **Slice 2 (custom selectors):** Bookmarked/Tag/Authored difficulty/Slowest startable (practice, not `isFocus`), `variant` threaded through plan/attempt/snapshot/route/session, custom selector logic + preview (`TestSelector`, `LibraryViewModel`, `McqRepository` scoped reads), setup-sheet tag/difficulty pickers and variant-aware Start.
- **Slice 3 (tag editing/round-trip):** editor tag-chip add/remove; tags/index sync on save paths; import/export continue to use `questions.tags` CSV as canonical.
- **Slice 4 (reliability/item analysis):** pure domain `domain/stats/Reliability.kt` (split-half odd/even + Spearman–Brown, SEM, KR-20, point-biserial across attempts, guessing correction, NaN/zero-SD/small-sample guards) with `ReliabilityTest`. The Tests tab shows a family-scoped Reliability card (`TestProgressData.reliability`). Item discrimination is keyed by question id across same-key attempts (`≥ 5` attempts that asked it), shown on the results review row; guessing correction uses each item's own chance level (`1/C(k,c)`), is suppressed for practice/negative marking/unknown keys/below 20 answered, and appears as a result card, in the CSV header and in the PDF. Both are estimates and are labelled as such.
- **Slice 5 (report export):** `ReportCsv` (UTF-8, RFC 4180, header comment block, stable columns) + `ReportExportCsvTest`, simple `ReportPdf` (v1 table/text, no charts), Results export/share actions (CSV/PDF) via cache `reports/` + `FileProvider`. The CSV/PDF include the guessing-corrected line when the report has one.
- **Fixed panels (stats.md §11.15):** schema v23 adds `panels`/`panel_questions` (`MIGRATION_22_23`, generated `23.json`), a panel picker + "New panel…" in the setup sheet (saves the current preview's sample, then switches to it), a trash action per panel (with confirmation), `TestType.FIXED_PANEL` (practice, no fill from outside the panel), panel id in `variant`, and a panel-progress card in the Tests tab. A panel is a whole-paper set: it runs every member (even a size like 47 after a deletion) and is not offered under a category scope. A deleted question shrinks the panel instead of substituting another. Panels travel in `study.json` (device state, not shareable content) with their ids and frozen order, because an attempt's `variant` names its panel by id.

**Deferred by plan prerequisites (not implemented):** Slice 6 adaptive next test/staged testing (needs a calibrated item bank; only a rule-based cross-attempt card is feasible with one user's data) and Slice 7 exam blueprint (needs owner decisions; changes plan shape). Both are documented with their open questions in [`ideas.md`](ideas.md).

**Verification (local, ARM64):** `:app:compileDebugKotlin` + `:app:compileDebugUnitTestKotlin` pass; the full pure-JVM suite passes (142 classes, 1245 tests, 0 failures, 2 skipped). Robolectric/SQLite + instrumented tests not run locally (CI x86_64 authoritative).

It is the working plan for the phase-3 items in [`stats.md`](stats.md)
§8.10–§8.13, §11.15 (phase 3), §12.4 and §13 (phase 3). When a slice ships,
this document and `stats.md` are updated together; a deferred item stays
visibly deferred here.

## 1. Goal and exit criteria

Phase 3 answers the questions phase 2 leaves open:

1. **Can I practise a set the category tree cannot express?** — bookmarked
   questions, a tag, an authored difficulty, or the questions that eat time.
2. **Is my score stable, and are individual questions behaving?** — split-half
   reliability, standard error of measurement and item discrimination.
3. **Can I get the evidence out of the app?** — a shareable report.
4. **What should the next test be?** — an adaptive next-test recommendation and
   an exam blueprint (both optional within phase 3; see §9).

Exit criteria:

- Bookmarked, Tag, Authored difficulty and Slowest are startable from the setup
  sheet, show their pool/composition preview, record their composition and
  resume without re-ranking. They never claim mock comparability.
- Exact tag filtering is indexed, not a `LIKE '%tag%'` scan, and the tag index
  survives import, edit and delete without drifting from `questions.tags`.
- The Tests tab shows reliability and item-analysis cards only when the sample
  can support them, and says so when it cannot.
- A result can be exported as CSV and PDF with the same numbers the screen
  shows.
- Pure-JVM tests cover every rule; Robolectric/SQLite and instrumented tests run
  on CI x86_64 (the ARM64 host cannot run either).

## 2. What already exists

| Area | Shipped today | File |
|---|---|---|
| Ten startable types | Random, Balanced, Mistakes, Weakest, Toughest, Easiest, Tricky, At risk, Unseen, Mixed | `domain/TestSetup.kt` |
| Selector toolkit | `cappedTop`, `interleave`, `allocate`, `fillWith`, composition codec | `domain/signals/TestSelector.kt` |
| Signal read | Chunked projections over `question_stats` + `card_state` | `data/local/ChunkedQueries.kt`, `SignalStore` |
| Bookmarks | `bookmarks` table, toggle and paged browse | `data/local/Daos.kt`, `HistoryStore` |
| Tags | `questions.tags` CSV plus `tagsFolded` for search only | `data/local/Entities.kt` |
| Authored difficulty | `questions.difficulty` (`EASY`/`MEDIUM`/`HARD`/null) | `domain/Models.kt` |
| Dwell | `question_results.dwellSeconds`, median in the report | `data/local/Entities.kt`, `TestReportBuilder` |
| Schema | v21 | `data/local/AppDatabase.kt` |
| Mixed composition | 40% weakest / 25% tricky / 20% at risk / 15% unseen + random fill | `TestSelector.mixed` |

`stats.md` phase-3 item 3 (Mixed practice composition) is already done in
phase 2; it is removed from the phase-3 checklist.

## 3. Constraints and non-goals

- No new test dependency: Robolectric + `android.database.sqlite` remain the
  database test tools; no SQLite driver library.
- One schema migration policy: bump `AppDatabase.version` once per shipped
  slice, add the migration to `MIGRATIONS`, and prove it in `MigrationTest`
  from the previous version.
- A custom selector is practice: `isRepresentative == false`, no margin, no
  mock trend, all-attempt `question_stats` counters.
- The session must keep the phase-1 promise: a bounded, resumable plan whose
  selection is stored as ids and never re-ranked on resume.
- Non-goals for the first phase-3 branch: adaptive/staged testing (needs a
  calibrated item bank) and an exam blueprint editor (needs an owner decision
  on scope, §9). Both stay marked deferred.

## 4. PR slices

Slices 1–3 ship together (custom tests need the tag index); reliability and
export follow as separate branches/PRs so each is reviewable on its own.

### Slice 1 — data foundation (`question_tags`)

- Add `question_tags(questionId, tagFolded, tag)` with an index on
  `(tagFolded, questionId)`, FK to `questions` with `ON DELETE CASCADE`.
- Bump the schema to v22 and backfill from `questions.tags` in the migration:
  split on `,`, trim, lowercase into `tagFolded`, dedupe.
- Keep `questions.tags` as the canonical CSV: export/Anki/JSON continue to
  read and write it. The new table is an index, never a second source of truth.
- Maintain the index in the same write paths that set `tags`:
  `QuestionStore` save/update, `Importer`, `StreamingImportSession`, backup
  restore and the bulk tag editor. Add one helper so no caller re-implements
  the split/lowercase rule.
- Queries: distinct tags with counts in scope; question ids for an exact tag
  (or an `IN` list of tags); no `LIKE`.
- Tests: migration backfill (mixed case, spaces, duplicates, empty labels),
  tag index after save/edit/delete, and equality with `tagsFolded` for a
  sampled bank. Robolectric, CI x86_64.

### Slice 2 — custom selectors

Add to `TestType`/`TestSetup.STARTABLE_TYPES`:

| Type | Pool | Selection | Fill |
|---|---|---|---|
| Bookmarked | `bookmarks` ∩ scope | random, seeded | Random from scope |
| Authored difficulty | `questions.difficulty = chosen` | random within the label | adjacent label, then Random |
| Tag | exact tag(s) ∩ scope | random | Random from scope |
| Slowest | ≥ 2 dwell samples, median ≥ scope p75 | highest median dwell first | Toughest, then Random |

All four `isFocus`? No: they are practice but not study-signal selectors, so
keep them out of `isFocus` (which drives Statistics practice runs today) and
add `usesSignalSelector` only where they read signals. Slowest reads dwell,
not `Signals`; decide in §9 whether it joins the signal selector or has its own
pre-ranked list.

Required plumbing:

- **Variant.** Tag and authored difficulty need a chosen value. Add an optional
  `variant: String?` to `TestPlan`, `AttemptMetadata`/`attempts` (nullable
  column in the same v22 migration), `TestSnapshot` and `testRoute(...)`. The
  report and History label read it (`Custom · tag: polity`). Bookmarked and
  Slowest leave it null.
- **Repository reads.** `bookmarkedQuestionIdsInScope`, `questionsWithTag`,
  `questionsWithAuthoredDifficulty`, and a chunked median-dwell read. Return
  ids (and counts for the preview), never full question content.
- **Selector.** Pure functions alongside the existing ones, with a
  `SelectionPreview` so the sheet shows pool, composition and (for a tag) the
  chosen tag. No targets: a custom type covers the whole scope, so the report
  should say "custom set" rather than invent a target list.
- **Setup sheet.** Four more chips; a tag picker (single-select from the
  distinct tags in scope, with counts); an authored-difficulty picker
  (Easy/Medium/Hard); disable Start when the chosen pool is empty.
- **Labels.** `Custom · bookmarked`, `Custom · tag: X`, `Custom · hard
  (authored)`, `Custom · slowest`, each `· not comparable` in the results
  header. Add them to `ui/common/AttemptLabels.kt`/`TestTypeLabels.kt`.

### Slice 3 — tag editing and round-trip

- Editor: tags as chips with add/remove; save rewrites `questions.tags`,
  `tagsFolded` and the index atomically.
- Import (JSON, DOCX, Anki) and backup restore write the index; export and
  Anki write only the CSV, so an imported deck round-trips byte-compatible
  with phase 2.
- Search stays on `tagsFolded`; only the Tag test reads the index. This avoids
  changing the search relevance that ships today.

### Slice 4 — reliability and item analysis (separate PR)

Pure domain in `domain/stats/Reliability.kt`:

- Split-half odd/even with Spearman–Brown correction; require ≥ 20 attempted
  questions and at least 5 in each half, otherwise say "sample too small".
- SEM = `SD × sqrt(1 − r)` in score points and as a percentage; show it only
  when `r` is positive and the sample passes the gate.
- KR-20 (binary items with marking = 1) as a cross-check; it and split-half
  must agree within a stated tolerance in tests.
- Per-question discrimination: point-biserial between the item (correct = 1)
  and the rest-of-test score. Flag `< 0.0` as "review this question" and
  `> 0.0` but small as "weak discrimination"; never call an item "bad".
- Guessing correction for 4-option items: `(p − 1/k) / (1 − 1/k)`, labelled as
  an estimate and suppressed below 20 questions.
- Statistics: a **Reliability** card inside the Tests tab (family-scoped, mock
  and practice separate) plus a per-question sheet row for discrimination.
  No practice run enters a reliability figure for a mock.

Tests: hand-computed fixtures for split-half/Spearman–Brown, SEM, KR-20 and
point-biserial; minimum-sample guards; a perfect score (zero SD) must not
produce NaN; discrimination direction; guessing correction boundaries.

### Slice 5 — report export (separate PR)

- CSV: one row per question with number, question id, category, result, dwell,
  tags, and the report-level columns; UTF-8, RFC 4180 quoting; a header
  comment block with attempt id, type, family and export time.
- PDF: reuse `data/export/PaperExporter`'s two-column renderer where possible;
  otherwise add a small report layout. Include the header, score/accuracy,
  error mix, topics, practice/custom headline, recommendations and the
  "sample too small" notes. No chart is required in v1; a table is honest.
- Share through the existing `FileProvider` and `<provider>` authority, with a
  `shareReport` action on the results screen. File name
  `mcq-report-<paper>-<attemptId>.csv|pdf`; the attempt id keeps it unique.
- Tests: CSV quoting (comma, quote, newline, non-ASCII), column order stability,
  PDF page-count/layout smoke under Robolectric where feasible, and a golden
  text extraction of the CSV. Instrumented: share sheet opens (CI emulator).

### Slice 6 — adaptive next test (deferred within phase 3)

- A "next test" recommendation that picks type, length and scope from the last
  attempt and the current signals. It must cite its evidence and never claim a
  score will improve.
- Staged/adaptive testing needs calibrated difficulty and stays out until there
  is enough item data; the CAT literature's exposure and calibration
  requirements are not met by this bank today.

### Slice 7 — exam blueprint (needs an owner decision)

- Per-paper sections with counts, optional weights and optional per-section
  time; a Blueprint test is a representative mock built from those quotas.
- Why it waits: it changes the meaning of `TestPlan` (sections vs one flat
  count), needs an editor, and a paper without a blueprint must keep working.
  Decide in §9 before any code.

## 5. Domain design notes

### Custom selection and composition

Reuse the phase-2 contracts: a pure selector returns `Selection(ids, targets,
composition)`, records segments through `Composition.of`, and never invents a
count. A custom type has no targets; its composition names the pool source and
the fill:

```text
Bookmarked   -> BOOKMARKED 12 + RANDOM 8
Tag          -> TAG 12 + RANDOM 8
Authored     -> AUTHORED_HARD 12 + AUTHORED_MEDIUM 4 + RANDOM 4
Slowest      -> SLOWEST 12 + TOUGHEST_QUESTIONS 8
```

Add the new `FillSource` values (`BOOKMARKED`, `TAG`, `AUTHORED_HARD`,
`AUTHORED_MEDIUM`, `AUTHORED_EASY`, `SLOWEST`) with display labels; unknown
sources still fall back to "random" in the setup preview.

### Slowest definition

- A question qualifies with at least **2** dwell samples (> 0) in the scope.
- Its score is the median of those samples.
- The threshold is the paper's **75th percentile** of per-question medians,
  with a floor of 30 s so a fast paper does not brand ordinary questions slow.
- Rank by median descending; ties break by question id for determinism.

### Tag normalisation

One function: `split(',') → trim → drop blanks → lowercase → distinct`. The CSV
keeps the user's original case; `tagFolded` and the index use the folded value.
A tag that differs only by case or surrounding spaces is the same tag.

## 6. Schema and migration

v22 adds:
```sql
CREATE TABLE question_tags (
  questionId TEXT NOT NULL,
  tag TEXT NOT NULL,
  tagFolded TEXT NOT NULL,
  PRIMARY KEY (questionId, tagFolded),
  FOREIGN KEY (questionId) REFERENCES questions(id) ON DELETE CASCADE
);
CREATE INDEX index_question_tags_tagFolded ON question_tags(tagFolded, questionId);
ALTER TABLE attempts ADD COLUMN variant TEXT;
```

Backfill reads `questions.tags` once in the migration and inserts the folded
pairs. `MigrationTest` asserts: a v21 database with commas, spaces, duplicates
and mixed case migrates to the expected rows; a v22 fresh create has the table
and index; deleting a question removes its rows; a backup restore repopulates
them.

v23 adds the fixed panels (stats.md §11.15):

```sql
CREATE TABLE panels (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  paperId TEXT NOT NULL,
  name TEXT NOT NULL,
  createdAt INTEGER NOT NULL,
  FOREIGN KEY (paperId) REFERENCES papers(id) ON DELETE CASCADE
);
CREATE INDEX index_panels_paperId_name ON panels(paperId, name);

CREATE TABLE panel_questions (
  panelId INTEGER NOT NULL,
  questionId TEXT NOT NULL,
  position INTEGER NOT NULL,
  PRIMARY KEY (panelId, questionId),
  FOREIGN KEY (panelId) REFERENCES panels(id) ON DELETE CASCADE,
  FOREIGN KEY (questionId) REFERENCES questions(id) ON DELETE CASCADE
);
CREATE INDEX index_panel_questions_panelId_position ON panel_questions(panelId, position);
CREATE INDEX index_panel_questions_questionId ON panel_questions(questionId);
```

`MigrationTest` proves 22→23 on a genuine v22 file, that the panel size is a
count (not a read of every id), that deleting a question shrinks the panel,
and that deleting the panel removes its rows. `23.json` is generated by the
build and committed.

## 7. Tests

### Pure JVM (local, authoritative)

- `TestSelectorTest`: custom pools, seeded determinism, fill ladders, the
  slowest median/percentile rule, empty selected tags and empty authored pools.
- `TagNormalizerTest`: split/trim/lowercase/dedupe and the CSV round-trip.
- `ReliabilityTest`: split-half/Spearman–Brown, SEM, KR-20, point-biserial,
  zero-variance and small-sample guards, guessing correction.
- `ReportExportCsvTest`: quoting, header, and column order.
- `TestReportBuilderTest`: custom labels, no targets and no mock comparability.

### Robolectric / SQLite (CI x86_64)

- v21 → v22 migration and the fresh-create schema.
- Tag index equals `tagsFolded` after save, edit, bulk edit, import and delete.
- Exact tag query in scope returns only whole tags (`java` does not match
  `javascript`).
- Bookmarked ∩ scope count; authored difficulty count; slowest median sample
  and percentile threshold; all ids-only reads (no question content loaded).

### Instrumented (CI emulator)

- The four custom chips appear, a tag can be chosen, Start runs the type, and
  the session header and result header say `Custom · …`.
- A tag edited in the editor appears in the Tag picker in the same session.
- The share action opens the chooser for a report.

## 8. Risks and mitigations

- **Tag drift** between `questions.tags` and the index. Mitigation: one
  normalisation helper, all writes through `QuestionStore`, and an assertion
  test that compares the two for a sampled bank after each write path.
- **Migration size** on a large bank. Mitigation: set-based backfill in SQL
  where possible; the Kotlin path is chunked and measured in `MigrationTest`
  with a large fixture.
- **False precision** from reliability on a small test. Mitigation: hard
  sample gates and "sample too small" copy; never show `r` for a 20-question
  practice run with half the items skipped.
- **Slowest is not hardest.** Mitigation: the label says "slowest", the
  explanation states that dwell also reflects length and wording, and the
  selector never feeds a mock.
- **PDF layout scope creep.** Mitigation: v1 tables only, reuse the existing
  export renderer, and no chart requirement.
- **Resume drift** when a custom type's pool changes. Mitigation: resume
  replays recorded ids exactly like the phase-2 focus types.

## 9. Decisions needed from the owner

1. **Tag storage:** add the `question_tags` index (recommended) or keep the CSV
   and accept a bounded-size bank for the Tag type?
2. **Variant persistence:** add the nullable `attempts.variant` column
   (recommended, so History/report labels survive) or derive the label from
   `compositionJson` only?
3. **Slowest thresholds:** 2 dwell samples, p75 with a 30 s floor (proposed) —
   or a fixed 60 s threshold?
4. **Custom targets:** no recorded targets (proposed, "custom set" label) or
   the scope's category ids?
5. **Reliability gates:** ≥ 20 attempted questions and ≥ 5 per half (proposed)
   — the owner may want a lower bar for short tests.
6. **Exam blueprint:** ship in phase 3 or defer to phase 4? It changes the plan
   shape and needs a per-paper editor.
7. **Export formats:** CSV + PDF (proposed) or CSV first, PDF after feedback?

## 10. Shipped order

1. Slice 1: `question_tags` + v22 migration. **Implemented on branch** (v21→v22 Robolectric fixture/assertions included).
2. Slice 2: four custom selectors and the setup-sheet plumbing. **Implemented on branch.**
3. Slice 3: tag editing and round-trip. **Implemented on branch.**
4. Slice 4: reliability and item analysis. **Implemented** (domain/tests, reliability card, item discrimination on the review row, guessing correction card/CSV/PDF).
5. Slice 5: CSV/PDF export. **Implemented on branch** (CSV/PDF + share action).
6. Fixed panels: `panels`/`panel_questions` + v23 migration, picker (with create and delete), selector, progress card. **Implemented on branch.**
7. Slice 6/7: adaptive next test and exam blueprint. **Deferred** (calibrated item bank / owner decision); details and open questions in [`ideas.md`](ideas.md).
