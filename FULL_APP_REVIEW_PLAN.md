# Full App Review Plan — MCQ

**Branch:** `feature/full-app-audit-plan` (cut from `main` at `ba79e24`, release `0.0.53`).
**Status:** plan only. No product code changes are made by this branch.
**Owner intent (verbatim spirit):** review the entire app — design, UI, accessibility,
features, and anything else that turns up — screen by screen, button by button, action by
action. Capture what happens when a control is pressed, what happens *behind* what the
user sees, what a new user cannot know, database integrity, and concrete improvements for
each feature/screen. Go as deep as possible and audit every line of code.

---

## 0. Charter and rules

1. **Depth over speed.** Every source file in scope is read and accounted for in a
   coverage ledger. "I skimmed it" is not a result; an unexamined file is an explicit gap.
2. **Evidence, not documents.** Owner rules in `AGENTS.md` / `PROJECT_NOTES.md` are
   authoritative *product decisions*, not proof that code is correct. Local audits
   (`question_*_audit.md`, deleted `audit.md`, `fsrs_audit.md`, `ChatGPT_*.md`) are hints
   only. Algorithms are checked against upstream sources (for FSRS-7: `fsrs-rs` v7.0.0,
   `ts-fsrs`, the FSRS wiki, Anki integration) and platform behaviour against official
   Android/Kotlin/Compose/Room/SQLite docs. **Cite the URL next to every external claim.**
3. **Product premises apply** (`PROJECT_NOTES.md`): honest user, normal-speed learners,
   no clock games. Findings that require gaming or sub-second tapping are not defects.
4. **Supervisor workflow** (`AGENTS.md`): the supervisor writes markdown, commits messages
   and PR text, and performs read-only inspection. Every code change goes to an
   implementation subagent with a scoped brief, and a **separate** reviewer verifies it.
   Up to 10 independent subagents may run in parallel; a reviewer still runs after its
   implementation. Quota deaths are retried, never downgraded.
5. **Owner decisions** (behaviour, user-visible copy, scope, release state) go to the
   owner. If the owner is unavailable and a decision is unavoidable, three decision
   subagents run in parallel with the identical question; adopt the convergent answer and
   report the split.
6. **PR per batch, draft until the owner says go.** `main` receives merged PRs only.

---

## 1. Scope inventory (what "every line" means here)

| Area | Files | Notes |
|---|---:|---|
| Production Kotlin | 217 | `app/src/main/java/**` — UI, navigation, ViewModels, domain, data, util |
| Resources | 8 XML | `res/values/**` — ~680 strings, plurals, themes |
| Unit tests | 277 | 167 pure-JVM, 105 Robolectric, 6 helpers; ~2,329 `@Test` methods |
| Instrumentation tests | 15 | 67 emulator tests across 16 classes (one file count discrepancy to resolve) |
| Workflows / build | 4+ | `build.yml`, `release.yml`, Gradle files, lint config |
| Schema | 29 JSON | `app/schemas/.../1.json … 28.json` |

Nothing in these sets is out of scope by default. Generated files and vendored binaries
are excluded, but every handwritten Kotlin/XML/YAML/Gradle/JSON file is either reviewed
or listed as an explicit gap in the ledger.

---

## 2. Review lenses (apply to every screen, action, and data path)

For each item below, record: **control → handler → immediate visible result → hidden side
effects → failure/empty/loading state → data written → a11y semantics → improvement idea**.

1. **Entry and exit:** how the user arrives (route, deep link, share intent, dialog), what
   arguments are passed, whether stale state survives, what Back/Up/system-gesture does,
   and what happens on process death / rotation.
2. **Every control:** button, icon, menu item, chip, toggle, text field, slider, drag/reorder,
   checkbox, dialog action, list row, swipe/scroll action. Is the label accurate? Is it
   enabled/disabled with a visible reason? Is a tap single-shot? Can it be tapped twice?
3. **Visible result:** navigation, dialog, toast/snackbar, state change, list refresh,
   progress indicator — or **no visible change** (a silent no-op is a finding candidate).
4. **Hidden behaviour:** writes, deletes, re-keys, cache invalidation, scheduler/config
   mutations, settings changes, background work, logging, side effects on other screens.
5. **Data integrity:** transaction boundaries, FK/cascade expectations, orphan risk,
   stale derived data, content hashes, version keys, migration compatibility.
6. **State coverage:** loading, empty, error, offline, partial data, zero/one/many items,
   boundary values, stale cache, concurrent operation, cancelled operation.
7. **Design/UX:** consistency with Material 3 and the app's palettes, typography, spacing,
   dark mode, font scale, RTL, layout at small/large screens, action discoverability.
8. **Accessibility:** labels, roles, stateDescription, click-target size, focus order,
   dialog focus, screen-reader announcements, WebView content, charts, error delivery.
9. **New-user clarity:** what the app does *without saying so* (defaults, grading policy,
   what is/isn't saved, what Export actually contains, why a grade is proposed).
10. **Improvement backlog:** smallest useful change, who benefits, severity, and whether it
    needs an owner decision. Record even when no defect exists.

---

## 3. Method and evidence handling

### 3.1 Coverage ledger
Create `REVIEW_LEDGER.md` on this branch in batch 0 and maintain it:
```
| File / area | Reviewed in batch | Reviewer | Result | Follow-up issue |
```
A file is "done" only when a reviewer has signed off and any findings are filed.

### 3.2 Batch structure
Each batch: (1) scoped brief + inventory slice, (2) read-only reviewer(s) — design/domain,
implementation/integration, tests/docs as appropriate, (3) consolidated findings table,
(4) owner decisions requested, (5) only then an implementation batch for agreed fixes,
(6) separate reviewer of the fixes, (7) CI confirmation. Batch size: one coherent area,
not one screen at a time unless the screen is large (Editor, Study, Test, Library, Settings).

### 3.3 Finding format
`Severity | Area | File:line | Evidence (quoted) | User impact | Hidden side effect |
Recommendation | Needs owner decision?`. Severity: Critical/High/Medium/Low/Nit.
A "no defect" result must list what was checked and cleared, not just a verdict.

### 3.4 External claims
Every algorithm/platform claim cites an official source URL next to it. When upstream and
a local document disagree, report the disagreement; do not assume the local document wins.

---

## 4. Phase A — Baseline, instrumentation, and current-state truth

- Confirm clean baseline: compile `:app:compileDebugKotlin`, unit + androidTest Kotlin,
  lint, full local pure-JVM suite; record CI run ids and baseline failures.
- Resolve inventory discrepancies (15 vs 16 androidTest files; UI test count).
- Build the screen/control/data map from the five inventories already produced, then verify
  it against source rather than trusting it.
- Identify dead code and unreachable destinations (e.g. `review/{attemptId}` has no caller;
  `ImportDataHolder` statics; unused resources, permissions, build variants).
- Record the existing structural guards (`NoDeadCodeTest`, `StringResourceUsageTest`,
  `UiAccessibilityTest`, `WebViewLifecycleTest`, `ReliabilityTest`) and what they *do not*
  check.

**Deliverable:** `REVIEW_LEDGER.md` + baseline report with verified counts, dead-code list,
and a prioritised risk map.

---

## 5. Phase B — Screen-by-screen walkthrough (every control, every action)

For each screen: enumerate entry points, all controls, each handler, each visible result,
hidden writes, failure states, and improvements. Use the lenses in §2 verbatim.

| Screen | Entry points | Control families to audit | Specific deep questions from the inventory |
|---|---|---|---|
| Library + drawer | start destination, import-complete | FAB/new paper, load sample, per-paper Study/Test/Mistakes/leeches/Export/Browse, category ⋮ menu, reorder, drawer destinations, tri-state category scope | Do delete-impact counts match cascades? Blueprint scope consistent between preview, route, session? Can nested dialogs stack and lose state on rotation? |
| Study | Library card/drawer, Results action | pause, bookmark, answer options, Show answer, Guess, Check, Change grade, Next, grade buttons, break strip, summary actions | `gradedAndStuck` retry safety; dwell clock on background/scroll; 3rd-exposure `RELEARNING` with positive interval shown coherently; empty-scope exit |
| Test session | Library setup, Results action | back/submit confirm, flag, bookmark, pause, Prev/Next/Submit, Show answer, palette, resume dialogs, answer review | Back during save; resume warnings after schema change; question visibility pausing for overlays; filter shrinking to zero |
| Results / Review | submit, History, Stats TESTS | export menu, filter chips, per-card bookmark, Study/Test/Review actions, paged rows | Unreachable `review/{attemptId}` route; scope metadata loss in Study/Test actions; paging error recovery |
| Browse | Library card, drawer, Search, Bookmarks, Passages | select/select-all, bulk edit/move/export/delete, search debounce, filters, category dropdown, row actions, reorder, move/copy dialog | Selection dropped on filter change; passage-expanded counts vs toast; focus deep link vs paging rebuild |
| Stats (9 tabs) | drawer, History, Settings | scope/range chips, tab sections, CardInfoSheet, retry | All nine empty/loading slices; scope+range zero data; stability units; TESTS tab missing range selector |
| Sessions | drawer | range chips, grouped cards, topic expand, retry | Day-boundary/DST grouping; resume refresh; empty vs error |
| History | drawer | attempt rows, stats icon, weakest/hardest cards | Scoping and refresh of derived lists; legacy attempts |
| Bookmarks | drawer | export/share, row focus, delete, paging errors | Export URI across Activity recreation; last-item empty state |
| Search | drawer | query, scope chips, hits, retry, count | Debounce/sync race; "N+" honesty; blank query |
| Settings + 5 sections | drawer, Stats | every toggle/slider/stepper, theme import/remove, reset, Optimize, export/backup/restore, storage, logs | Concurrent export/backup/restore; restore warning accuracy; settings-key ownership; one-shot notices after restore |
| Question editor | Library add/edit, Browse, Import edit | Back/dirty dialog, Prev/Next, category/passage/difficulty, body/option/explanation blocks, tables, image picks, marks, tags, Save, Delete confirm | Invalid/dirty Save reasons; import-source fail-closed; image target across rotation; passage removal resetting progress |
| Passages | drawer | new/edit/delete/move, member expansion, cross-paper confirm | Cached category re-verification; rich passage preservation on no-op save; pager state on rotation |
| Import | file picker / share intent | source fields, preview edit/delete, warnings/skipped, Import, progress, complete dialog | DTO write-back; truncated preview disclosure; backup picked for import redirected clearly |
| Navigation host | app root | route args, deep links, share router, BackHandlers | Route encoding for special ids; back-stack after test submit; two entry paths to `ImportDataHolder` |

---

## 6. Phase C — Feature/domain deep dives

1. **Study & grading:** modes, pull-based queue, pacing, 0 s dwell semantics, simplified vs
   classic grade proposal, `gradeEdited` provenance, leech/tricky logic, timer segments,
   atomic review write, dropped-card skip, session stop/restart. Verify against upstream
   FSRS/Anki docs where the model is involved.
2. **Scheduling & settings:** 34 weights, `Sf == 0` sentinel, no short-term branch,
   interval solve, day boundary, Fast/Slow cuts and grading versions, desired retention,
   Optimize/tuner storage gate, FSRS-7 upgrade/rebuild, one-shot notices, restore rebuild.
3. **Statistics/calibration:** nine tabs and their definitions, streaming accumulators,
   generation guard, calibration cache key/invalidation, guessing 2×2, epoch resets.
4. **Import/export/backup:** all eight formats, preview warnings vs skipped, dedupe by hash
   and its blind spots, JSON v1/v2 key precedence, backup wipe-and-replay, partial restore,
   Anki SM-2 bridge and ignored `cards.data`, PDF byte output (currently ignored tests).
5. **Library management:** paper/category trees, question CRUD/bulk ops, copy/move/collision
   rules, passages and group moves, panels, blueprints, tags, bookmarks.
6. **Hidden defaults:** correctness-only on for new installs, retention 0.9, day start 4,
   leech 8, fast/slow 6/60, max interval 36500; identify which of these a user must discover
   the hard way and whether the UI explains them.

---

## 7. Phase D — Database integrity, migrations, and derived data

- Table-by-table audit of PK/FK/index choices in `Entities.kt` and `28.json`, including
  invariants *not* enforced: `question_stats` has no FK, `review_log` FK is paper-only,
  `bookmarks` and `passageId` have no FK, `categories.parentId` has no self-FK,
  `gradingVersion` has no FK, no CHECK constraints.
- Migration chain 3→…→28, especially the dual-shape v27 guard; what data is irrecoverably
  discarded; post-migration validation; backfill transactions.
- Every writer that deletes/re-keys/upserts: question save/delete/duplicate/move,
  paper/category delete, passage reassignment, bulk edits, restore, stats writes.
- Derived/cached data: calibration cache, `question_stats`, exposure counts from
  `review_log`, content hashes (including scaled-image twin), FSRS rebuild CAS,
  tuner storage flag, commit→invalidate windows.
- Backup/format keys: v2 composite key + bare alias, version branches, what each export
  format omits (especially schedules vs full backup).

---

## 8. Phase E — UI, design, accessibility, and string audit

- Design system: palettes and contrast bisection, fixed M3 roles left at stock purple,
  white launch window vs forced dark, typography overrides absent, raw `dp` spacing,
  font scale 1.3/landscape, RTL icons that are not `AutoMirrored`.
- Strings: find every user-visible literal outside `strings.xml` (Compose literals,
  ViewModel `state.error`, Toasts, a11y state strings, import bullets); check plurals,
  format args, stale names (`sm_2_*`, `simplified_anki`), duplicate/trailing-space values,
  and extend `StringResourceUsageTest` only with owner approval for scope changes.
- Accessibility: MathLive and preview WebViews unlabelled, charts using test tags as
  content descriptions, 22 `contentDescription = null` sites, focus order, dialog focus,
  errors via Toast only, hardcoded English state strings, click targets `clickable` rows.
- Compose internals: keys in ~15 lazy lists, 36 `LaunchedEffect`s, side-effect cancellation,
  WebView teardown/JS bridge, `key(html)` remount, CompositionLocal scope.

---

## 9. Phase F — The "not visible to a new user" pass

A dedicated cross-cutting pass that asks, for every feature: *what would a first-time user
wrongly assume?* Seed list from the inventories:
- correctness-only grading by default and a decorative clock; why "Easy" may never appear;
- editing a question silently resets its study schedule;
- plain JSON paper export does not carry progress; only full backup does;
- Anki deck's own FSRS state is ignored on import;
- cross-paper move carries history and changes the destination paper's statistics (now
  warned, but is the collision case clear?);
- "Optimize" can legitimately keep current weights;
- calibration needs evidence and silent epoch resets;
- enabling new cards from an empty state is a persistent global setting;
- import dedupe can collapse identical copies; skipped vs advisory notes distinction.

Each item becomes either a copy/UX improvement proposal or a documented intentional choice.

---

## 10. Phase G — Tests, CI, and verification hardening

- Map the 167 pure-JVM / 105 Robolectric / 16 instrumentation classes to features; flag
  ignored tests (`PdfPaperWriterTest`, one PDF test in `ExportWritersTest`) and weak
  threshold-only assertions (~135) and the `assertTrue(true)` placeholder.
- Screens with no instrumentation coverage: History, Bookmarks, Import, Library, Search,
  Sessions, Stats, full TestSession. Decide which need emulator tests.
- CI: jobs, SDK pins, artifacts (`app-debug`, `app-release`, `test-results`, `ui-test-results`,
  `lint-report`), emulator flakiness history (three failed runs before the UI test fixes),
  release workflow and manual user-facing notes step.
- No new test dependency without owner approval; Robolectric/SQLite tests only on x86_64 CI.
- Update `PROJECT_NOTES.md` where it has drifted (e.g. current release tag `0.0.53`).

---

## 11. Deliverables

Per batch: a short audit report (findings + cleared areas + owner decisions), updated ledger,
and — only for agreed fixes — separate implementation PRs with tests and CI green.
At the end: a consolidated `FULL_APP_REVIEW_FINDINGS.md` with a severity-ranked backlog,
a screen × lens matrix, an integrity-risk register, and a "no defect found" appendix so
future agents know what was actually checked.

No release is cut from this branch. Any user-visible fix follows the owner's normal PR →
merge → release process, with plain-language release notes.
