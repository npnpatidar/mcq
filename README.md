# MCQ App

An offline-first Android app for practicing multiple-choice questions: build papers with
recursive categories, import question banks from **Word (.docx)** or **JSON**, take timed
tests with negative marking and per-question weights, study in practice/strict/mistakes
modes, review explanations, track mastery over time, keep questions fresh with FSRS-6
spaced repetition, and export papers as JSON, ZIP, HTML, PDF, Word (.docx), or Anki
(.apkg).

- **Stack:** Kotlin, Jetpack Compose (Material 3), Room, Navigation-Compose, DataStore,
  Coil, kotlinx.serialization
- **Requires:** Android 8.0+ (minSdk 26), targetSdk 36. The version shown in Settings →
  About is derived from the git tag the build came from (`<major>.<minor>.<patch>`); an
  untagged local build reports `0.0.1-dev`.
- **Offline:** fully usable without network. The only thing that needs network is loading
  remote-URL images (see [Images](#images)).
- **Tests:** 1,900+ JVM unit tests + on-device critical-path UI tests; CI runs both on
  every push (see [Development & CI](#development--ci)).
- **Source & issue tracker:** <https://github.com/npnpatidar/mcq>

---

## Quick Start

1. **Create a paper** — tap the `+` button on the Library screen.
2. **Add questions** — open the paper card → **Browse** → **Select** → **Edit** or use
   the `+` in Manage mode. You can also write questions in **Word** and import them.
3. **Import a bank** — Library drawer → **Import questions** → pick a `.docx` or `.json`
   file. The preview shows New / Changed / Duplicate counts.
4. **Take a test** — paper card → **Test** (FactCheck icon). The sheet lets you pick
   Random, Balanced, Mistakes, focus types, a Blueprint, shuffle, time, and length.
5. **Study** — paper card → **Study (N due, N new)**. Answer, reveal, grade Again/Hard/
   Good/Easy. FSRS schedules the next review automatically.
6. **Review results** — after a test, the Results screen shows score, margin, topics,
   time sinks, and per-question review with explanations.
7. **Track progress** — **Statistics** (drawer) has seven tabs: Overview, Retention,
   Workload, Memory, Cards, Guessing, Tests, Progress.
8. **Back up** — Settings → **Backup & data** → **Backup (zip)**. The zip restores the
   exact state (papers, schedules, attempts, settings, themes).

---

## Table of Contents

1. [Screens & Navigation](#screens--navigation)
2. [Question Model](#question-model)
3. [Reading Passages (Comprehension Sets)](#reading-passages-comprehension-sets)
4. [Test Modes & Setup Sheet](#test-modes--setup-sheet)
5. [Exam Blueprints](#exam-blueprints)
6. [In-Session Screen](#in-session-screen)
7. [Submitting, Scoring & Marking](#submitting-scoring--marking)
8. [Results Screen](#results-screen)
9. [History Screen](#history-screen)
10. [Study (Spaced Repetition)](#study-spaced-repetition)
11. [Statistics (Seven Tabs)](#statistics-seven-tabs)
12. [Browse, Search, Editor & Passages](#browse-search-editor--passages)
13. [Bookmarks](#bookmarks)
14. [Import: Word Documents (.docx)](#import-word-documents-docx)
15. [Import: JSON Reference](#import-json-reference)
16. [Import Semantics & Nuances](#import-semantics--nuances)
17. [Editor Prev/Next Queue](#editor-prevnext-queue)
18. [Export Formats](#export-formats-per-paper-per-category-bookmarks)
19. [Backup & Restore](#backup--restore)
20. [Settings — Every Control Explained](#settings--every-control-explained)
21. [Images](#images)
22. [Explanation Images](#explanation-images)
23. [Color Themes](#color-themes)
24. [Data & Storage](#data--storage)
25. [Edge Cases & Gotchas](#edge-cases--gotchas-observed-not-theoretical)
26. [Logs & Debugging](#logs--debugging)
27. [Development & CI](#development--ci)

---

## Screens & Navigation

| Screen | Route | Purpose |
|--------|-------|---------|
| **Library** | `library` | Home. Paper cards with Study, Test, Browse, Manage, Export. Drawer: History, Statistics, Bookmarks, Search, Passages, Settings, per-paper category trees (tri-state checkboxes), test/study by category, Import. FAB: new paper. |
| **Test Setup** | `test?...` | Bottom sheet titled **Start a test**. 17 type chips, breadth, scope, pool, length slider/chips (20–150, tens), minutes, fresh-sample note, preview, Start. |
| **Test Session** | `test?...` | Live sitting. Countdown (if timed), question clock, palette, flag, bookmark, prev/next, Show answer (unless practice/strict), Submit. Auto-submit at zero. Crash resume dialog. |
| **Results** | `results/{attemptId}` / `review/{attemptId}` | Score ring, accuracy, margin, trend, topics, time, What to do next, per-question review with filters, bookmark toggles. |
| **History** | `history` | Trends, weakest categories, hardest questions, attempt list (tappable → Results). No attempt delete. |
| **Statistics** | `stats` | Scope chip (All papers / one paper), range chips (7 d / 30 d / 90 d / 1 y / All). Seven tabs. |
| **Study** | `study/{paperId}?...` | One card at a time. Classic footer (Show answer → Again/Hard/Good/Easy) or Simplified (Guessing switch → Check → Change grade → Next). Clocks, passage header, summary on stop. |
| **Browse** | `browse/{paperId}?...` | Question list with search, 4 filter chips (All / No answer / No explanation / Uncategorized), category dropdown, selection mode (bulk edit/move/copy/delete/export), reorder arrows, passage headers. |
| **Editor** | `editor?...` | Full question editor: category, passage, BlockListEditor (text/image/table/math), options with checkboxes, marks, difficulty, tags, explanation, validation, Prev/Next over current filter, Save/Delete. |
| **Passages** | `passages` | List of passages with member counts. Create/Edit/Delete/Move. Tap member → opens in Browse. Delete guarded if members exist. |
| **Bookmarks** | `bookmarks` | Paged list. Tap → opens in Browse (reading list). Remove icon. Export (all bookmarks as pseudo-paper). |
| **Search** | `search` | Query field, 3 scope chips (All / Questions / Options). 300 ms debounce. Results: preview + paper title → tap opens in Browse. |
| **Settings** | `settings` | Five collapsible sections (all expanded by default): Appearance, Tests, Study/Spaced repetition, Backup & data, About. |

**Drawer order:** History → Statistics → Bookmarks → Search → Passages → Settings →
per-paper category trees (expandable, tri-state checkboxes, Study/Test buttons when
ticked) → Import questions.

**Back behaviour:** Library = exit app; Browse/Editor/Passages/Bookmarks/Search/
Settings = pop back stack. Editor back **does not** guard unsaved changes (only
Prev/Next do). Test session back opens the Submit dialog; a second back cancels the
dialog.

**Haptics:** Off by default. Settings → Appearance → **Haptic feedback** enables
Tick (input registered), ToggleOn/ToggleOff (state change), Confirm (correct),
Reject (wrong). No sound feedback.

---

## Question Model

A question has:

| Field | Type | Notes |
|-------|------|-------|
| **Text** | String / rich blocks | Legacy `text` field + `elements` (text, image, table, math). `elements` takes precedence. |
| **Question image** | Optional | Data URI, `http(s)` URL, or `content://`. Legacy scalar field + block images. |
| **Options** | 2+ | Each has id (a–j), text/blocks, optional image. Legacy scalar `image` field. |
| **Correct option ids** | Set<String> | Empty = no answer key (ungraded). Multiple = multi-correct (exact set equality). |
| **Marks** | Double (default 1.0) | Weight ≥ 0. Invalid → 1.0. Used in score = Σ(marks for correct) − Σ(marks × penalty for wrong). |
| **Explanation** | String / rich blocks | Legacy `explanation` + `explanationElements`. Shown after reveal. |
| **Explanation image** | Optional | Data URI / URL. |
| **Difficulty** | Enum | `easy` / `medium` / `hard` / `very_hard`. Default `medium`. |
| **Tags** | List<String> | Comma-separated on import/export. Searchable. |
| **Category** | Reference | Owning category (recursive tree). |
| **Passage** | Optional reference | Shared reading context (see below). |

**Validation (Save & Prev/Next):** non-blank text, ≥ 2 options, no blank option.
Buttons stay disabled until valid. Invalid marks fall back to 1 (never blocks saving).

---

## Reading Passages (Comprehension Sets)

A **passage** is a shared reading context (title, rich body, optional image) that
groups several questions. Members are ordinary questions pointing at the passage
by `passageId`.

**Where it matters:**
- **Test session:** members are kept contiguous; shuffling moves the whole block;
  a bounded test samples whole blocks (the header shows `N asked, M shown` when a
  block overshoots the requested count).
- **Study & Browse:** the passage renders as context above the question; each
  member keeps its own schedule, bookmarks, and stats.
- **Editor:** passage picker (Remove + passages in current category). Changing
  category drops the assignment if the passage isn't in the new category.
- **Passages screen:** create/edit/delete/reorder; expand to see paged member list;
  tap member → opens in Browse. Delete guarded if members exist.

---

## Test Modes & Setup Sheet

Open via paper card **Test** (FactCheck icon) or drawer **Test (N categories)**.
The sheet is titled **Start a test**; it is scrollable with the **Start** button
pinned at the end of content.

### 1. Test Type (17 chips, in order)

| Chip | Family | Fresh-sample? | What it does |
|------|--------|---------------|--------------|
| `Random` | Mock | ✅ | Uniform sample of scope; passage blocks move together. |
| `Balanced` | Mock | ✅ | Allocate slots proportionally per category (largest remainder, min 1), interleave round-robin. |
| `Blueprint` | Mock | ✅ | Runs a frozen section tree (see [Blueprints](#exam-blueprints)). |
| `Easiest questions` | Practice | ✅ | Rank by ease score (1−weakness, 1−difficulty, 1−memoryRisk, 1−lapse), exclude leeches/at-risk when ≥ 2N alternatives, take top 2N, shuffle, take N. Fill: authored Medium, then random. |
| `Mistakes` | Practice | ❌ | Previously missed questions in current scope. |
| `Weakest topics` | Practice | ❌ | Rank categories by composite weakness (0.5·(1−WilsonLower), 0.25·difficulty, 0.15·memoryRisk, 0.10·lapse), take top K (Breadth), allocate proportionally, record targets. |
| `Toughest questions` | Practice | ❌ | Rank by toughness (0.35·weakness + 0.20·difficulty + 0.20·memoryRisk + 0.15·lapse + 0.10·skip), 40% per-category cap (relaxes if needed), fill: authored Hard, then random. |
| `Tricky` | Practice | ❌ | Rank by trickiness (0.40·lapse + 0.25·guessRate + 0.20·skipRate + 0.15·weakness), 40% cap, fill: Toughest, then random. |
| `At risk` | Practice | ❌ | **Only cards due now** (`dueAt ≤ now`). Sorted by memoryRisk (1−retrievability), then due. Fill: remaining due, then unseen, then random. |
| `Unseen` | Practice | ❌ | No card / NEW card / stale content hash. Uniform shuffle. Fill: least recently reviewed, then random. |
| `Mixed` | Practice | ❌ | 40% Weakest + 25% Tricky + 20% At-risk + 15% Unseen (ceil), deduped, random fill. Records Weakest targets. |
| `Bookmarked` | Practice | ❌ | Current scope's bookmarks, shuffle, fill random. |
| `Tag` | Practice | ❌ | Exact folded tag, shuffle, fill random. |
| `Authored difficulty` | Practice | ❌ | Exact label (Easy/Medium/Hard), shuffle, fill from adjacent label, then random. |
| `Slowest` | Practice | ❌ | Eligible: ≥ 2 timed answers. Threshold: 75th percentile of median dwells, floor 30 s. Keep ≥ threshold, 40% cap, fill: Toughest, then random. |
| `Fixed panel` | Practice | ❌ | Frozen question set. No fill. Runs whole panel (length = panel size, not a 10-multiple). Whole paper only. |

**Representative (mock) types:** Random, Balanced, Blueprint. These feed the mock
trend, margin, and `rep*` counters. Everything else is **Practice** — never mixed
with mocks.

**Fresh sample:** If the type supports it (Random, Balanced, Easiest, Blueprint)
and Settings → Tests → **Avoid recently tested questions** is on (default), the
sheet shows a note that says "when enough others remain". At session load, whole
passage blocks containing any id from the last 3 comparable attempts are
excluded **only if** something was removed and the remaining pool ≥ 2× the
request. If applied, the attempt family is **Fresh sample** (compared only with
fresh samples); otherwise it stays **Mock**.

### 2. Breadth (Weakest topics / Mixed only)

| Chip | K (top categories to seed) |
|------|----------------------------|
| `Narrow · 1 topic` | 1 |
| `Medium · 3 topics` | 3 (default) |
| `Broad · 5 topics` | 5 |

The selector expands K in weakness order until the chosen categories can supply
the requested length. It is **not** "questions per topic".

### 3. Scope & Pool

- **Scope label:** `Whole paper` (nothing ticked) or `N categories` (ticked count,
  not expanded). For Blueprint: `Blueprint scope: the run asks only this
  blueprint's sections, whatever categories are ticked.`
- **Pool line:** `N questions` (scope count), `N mistakes` (Mistakes), `N in pool`
  (signal types — the selector's own pool, not the raw scope), or for a
  blueprint the **total terminal supply** (count-independent, so the length can
  grow past the current request).
- **Composition:** shown for signal types, e.g. `Composition: 12 weakest + 8 random`.
  Weakest/Mixed also list each target: `Polity · 40% (8/20)`.

### 4. Length

Allowed: 20, 30, …, 150 (tens). Default 20. Slider stops exactly at those
values; preset chips 20/50/80/100/120/150 (disabled when > pool). Max resolved:
`min(pool, 150)` rounded down to a ten. Exception: a **blueprint** rounds its
request **up** to the next ten (99 supply → request 100, run 99) and below 20
supply Start is refused instead of running a short test. Every other scope
< 20 → **short test** (slider hidden, label `Short test — 17 questions`).

### 5. Time

Field: `Minutes` (digits only, 6-char cap). Untouched → `Suggested: N min`
(1 minute per **resolved** question, minimum 1). `Use suggested` restores it.
**0 = untimed** (preview ends `· Untimed`). Clearing the field also means
untimed. Session caps at 10,080 min (1 week) — preview can say `999999 min`
while the clock runs a week.

### 6. Preview Line

`<Type>[ · <blueprint name>] · N questions · M min|Untimed · <comparability>`

Comparability: `comparable` (mock), `fresh sample if enough others remain ·
compared only with fresh samples`, `practice · not comparable`, `Short test ·
N questions`.

### 7. Start Button

Disabled while pool/mistakes/panel/blueprint loads, or blueprint supply < 20
(a short blueprint is refused, never started as a short test). Label:
`Start N-question test · M min` or `· untimed`. Disabled label: `Start test`.

### 8. Panels

Created only from signal-selected samples: **New panel…** under preview. Dialog:
`Panel name` (max 60 chars). Help: `Saves the N questions this preview selects
as a fixed set you can repeat.` On save → switches to Fixed panel with the new
panel selected. Delete: confirm `Delete “Name”? Its saved question set is lost;
the questions themselves are not.`

### 9. Blueprints from the Sheet

Picker: `Pick a blueprint` (chips `Name · sectionCount`). `New…` / `Edit
blueprint` / trash (confirm); **Edit** is disabled until the selected
blueprint's section views have loaded, so it never opens an empty tree. While
the section preview is still loading, the empty-scope warning is suppressed
rather than flashing an error about a half-read state.

Under picker: resolved tree `Title · N of M questions · P%`, `short by N` in
red, `section ignored` struck through. The tree is the real recursive
allocation at the current length; `N`, `M` and `P` roll up through
subcategories and top-level percentages add to 100. `N` is the **actual**
preview selection: when a whole passage pushes a section past its quota, the
extra members are included in `N` and `P`. Passage members count as individual
questions (a four-question passage adds 4 to its category's pool), never as
one block.

Passage selection is **all or none**: once any member is chosen for a section,
its whole passage is added (the last block may overshoot the quota), and the
fill stage does the same. A passage is never split between selected and
unselected questions. The fresh-sample exclusion also drops the whole passage
when any member is too recent.

### 10. Global / Per-Paper Settings That Change a Test

These are **not** in the sheet but materially change the sitting:

| Setting | Where | Default | Effect |
|---------|-------|---------|--------|
| `Shuffle question order` | Settings → Tests | off | Re-orders sampled ids with attempt seed; passages stay together. |
| `Shuffle options` | Settings → Tests | off | Option order shuffled per question with same seed. |
| `Practice mode` | Settings → Tests | off | Live correctness colours + explanation always shown; hides `Show answer`. |
| `Strict exam mode` | Settings → Tests | off | Hides flag, palette, `Show answer`; suppresses practice feedback (strict wins). |
| `Auto-advance` | Settings → Tests | off | After single-answer selection, go to next (no delay). Not in practice mode, where the live verdict needs reading. |
| `Avoid recently tested questions` | Settings → Tests | **on** | Fresh-sample rule above. |
| `Negative marking` | Add/Edit paper dialog | 0 | Penalty as fraction of marks; shown on card as `• -0.33 neg`. |
| `Paper duration` | Add/Edit paper | 0 = untimed | Legacy only; bounded test always uses sheet minutes. |

---

## Exam Blueprints

A **blueprint** is a named tree over the paper's existing categories. Each node
has integer **parts** (relative weights inside its sibling group). Created/edited
only from the setup sheet (Blueprint type → `New…` / `Edit blueprint`). No
separate manager.

**Scope ownership:** starting a blueprint clears the sheet's ticked categories
for that run; the run's scope is the union of the tree's terminal subtrees. The
library selection is untouched.

### Editor Controls

| Control | Detail |
|---------|--------|
| `Blueprint name` | Single-line, max 60 chars. |
| Help | `Parts weight each section inside its group; 0 leaves it out. A child implies its parent.` |
| Column legend | `Parts · share inside its group · share of the paper · questions at this length` |
| Every paper category | One indented row, paper's tree order, whether included or not. |
| `−` | Decrements; disabled at 0. Reaching 0 removes node **and its whole subtree**. |
| Value | Current parts, 0…999. |
| `+` | Increments to 999; if child, every ancestor forced to ≥ 1. |
| Share line (parts > 0) | `4 of 10 parts · 40% · 12% · 10 questions` (local %, global %, real quota). |
| Questions line (parts > 0) | `10 of 50 questions · 20% of the test` — planned quota, pool and percentage before Save; percentages roll up and passages count per member question. After Save the sheet switches to the **actual** selection, so a whole passage can raise `N` and change `P`. |
| Short line (parts > 0) | `Short by 7 at this length` when the uncapped ask exceeds the granted quota (pool cap). |
| Total line | `Total: 10 parts (History 40%, Polity 30%, Geography 30%)` |
| Validation | `Give the blueprint a name.` / `Add at least one section before saving.` (Save disabled). |
| Repository refusal | Exception message shown inline. Duplicate names are checked case-insensitively, scoped to the paper and excluding the row being renamed (the unique index is only the last resort); hierarchy/shape refusals also come back as sentences. |
| `Save` | Builds included nodes (parts > 0) recursively; selects blueprint; closes. A section whose category left the paper is warned about and removed on save. Saving the already-selected blueprint reloads its views, so the sheet preview and counts update immediately instead of staying on the pre-edit tree. |
| `Delete` | Only when editing; same confirm as sheet. |
| `Cancel` | Closes without saving. |

Setting a parent to 0 removes its children from the payload. Setting a child back
up re-creates ancestors at 1 part each.

### Quota Arithmetic (Exact)

At each sibling group:
1. Eligible = `available && parts > 0 && pool > 0`.
2. `exact_i = count × parts_i / Σparts`.
3. Assign `floor(exact_i)`, capped at pool.
4. If `count < eligible.size`: give 1 to top `count` nodes by `parts desc, pool desc, seed tiebreak`; stop.
5. Else distribute `count − Σassigned` one per pass over `remainder desc, parts desc, pool desc, categoryId asc`, skipping nodes at pool.
6. Every eligible 0 gets 1 by **stealing** from largest donor > 1 (while total = count and count ≥ eligible).
7. Remaining deficit redistributed in same remainder order.
8. Recurse into internal nodes with their assigned count. Terminal quota = `min(assigned, pool)`. Internal nodes report sum of descendants.
9. `achievable` = sum of terminal quotas. Unabsorbed deficit lowers `achievable`; run clamps to it.

**Worked Example A — Exact fit, length 100:**
```
History   40 parts ── Ancient 1, Medieval 1, Modern 2
Polity    30 parts
Geography 30 parts
```
Top level: exact 40/30/30 → 40, 30, 30. History recurses with 40 over 4 parts → 10/10/20. Terminals sum 100.

**Worked Example B — Short section, length 100, Polity pool 25:**
Floors 40/25/30 = 95; 5 remain. Remainders 0; order History (40), Geography (30), Polity (at pool). Passes: History +3, Geography +2 → History 43, Polity 25, Geography 32. History recurses 43 → 11/11/21. Result 100; Polity `short by 5`, card says `Not complete` despite full length.

**Worked Example C — Deficit nowhere to go, length 100:**
Pools: History 40, Polity 10, Geography 20. Floors 40/10/20 = 70; 30 remain but all at pool → `achievable = 70`. Run is 70; rows show Polity `short by 20`, Geography `short by 10`.

### Starting, Short Sections, Result Labelling

- The sheet's `available` is the blueprint's total terminal supply, independent of the current request. The length may round **up** to the next ten, so a 99-question supply is requested as 100 and still runs 99 (`resolve()` clamps to the exact achievable count).
- **Supply** < 20 → Start refused (`This blueprint can only supply N questions; at least 20 are needed to start.`). A blueprint never runs as a short test, even when the current request's achievable count is below 20 for some other reason.
- Terminal `quota > pool` → left short; nothing outside blueprint union used. `BLUEPRINT_FILL` only redistributes inside union.
- Attempt records the tree with each node's **requested** quota and label.
  Selection is passage-aware, so a whole block can make the **shown** count
  larger than the requested one (the session's `asked, shown` line says so).
- Results: `Blueprint · <label>` card → `Complete: every section asked its full quota.` or `Not complete: a short section asked fewer than its quota.` Rows: `asked/requested · accuracy% ±margin` (Wilson half-width, no finite-population correction). `small sample` under 5 asked. Unresolvable rows counted.
- Blueprint attempt = Mock (unless fresh sample applied) — never Practice.

---

## In-Session Screen

### Top Bar
| Element | Behaviour |
|---------|-----------|
| Title | Paper title (fallback `Test`). |
| Back arrow | a11y `Back`. **Does not leave** during a live sitting — opens Submit dialog. After submit/saving it pops. |
| Countdown | Only when timed. Timer icon a11y `Time remaining`; `mm:ss`; error colour ≤ 60 s. |
| Flag | a11y `Flag question`; hidden in Strict; tinted when flagged. |
| Bookmark | a11y `Bookmark question`; always shown; filled when bookmarked. |

### Question Clock (Dwell)
Text button under the question:
- Visible: `Question 3 of 20 · 1 mark · 0:42 here` (+ ` · Paused` when paused).
- a11y: `Question clock`, state `Running`/`Paused`.
- Tap toggles **deliberate** pause. Backgrounding pauses but doesn't change the
  deliberate flag; foregrounding resumes only if not deliberately paused. Dialogs
  / palette also pause (question covered).
- Cumulative per question across revisits. Banked on navigation and submit.
- Runs on untimed papers too. `Dwell.format` → `m:ss`.

### Practice / Strict Matrix

| Practice | Strict | Live colours | Explanation | `Show answer` | Flag | Palette |
|----------|--------|--------------|-------------|---------------|------|---------|
| off | off | no | after reveal | yes | yes | yes |
| on | off | yes | always | **no** | yes | yes |
| on | on | no | never | no | no | no |
| off | on | no | never | no | no | no |

Banners: `Practice — answers shown instantly` / `Strict exam — aids hidden until
submit`. Strict suppresses practice even if both on.

`Show answer` reveals key and **locks options** (`toggleOption` refuses a
revealed question). Ungraded questions show `No answer key — not scored`.

### Answering
- Single-answer: radio buttons. Multi-correct: checkboxes, hint `Multiple
  correct — select all that apply`.
- Tapping card/radio/checkbox toggles selection.
- Selected option highlighted (primary container); after reveal: correct = green,
  selected wrong = red.
- Answering writes progress immediately (navigation/background too).
- `Auto-advance`: on single-answer selection, immediate jump to next (no delay,
  no animation); never for multi-correct, last question, or practice mode
  (practice reveals the explanation on the same tap).

### Question Palette
- Open: tap bottom-centre progress text (`N/M answered`).
- Strict: palette unavailable; same position reads `N/M`.
- Grid: 44 dp adaptive, 48 dp cells. Colours: current = primary; answered = green;
  flagged = amber; untouched = surface variant. Answered tested first.
- a11y per cell: `Go to question 7, answered/current/flagged/unanswered`.
- Tap number → jump + close.

### Navigation Row
Bottom: `Previous` (disabled on first) | progress text | `Next` (or `Submit` on
last). Second row: `Show answer` (when allowed) + full-width `Submit test` (on
every question).

### Session Banners & "Asked vs Shown"
1. `Short test · N questions` (< 20).
2. Type banner: `Mock · <Type> · N` (a blueprint names itself as
   `Mock · Blueprint · <name> · N`), `Practice · <Type> · N`,
   `Practice · Mistakes · N`, or `Random · N`.
3. If shown > asked: `80 asked, 84 shown — a passage is kept whole`.
4. If shown < asked: `80 asked, 76 shown` (clamp/short scope/deleted).

Passages: shared reading card above each member's stem; members kept together.

### Time Warnings & Auto-Submit
- Anchored on `elapsedRealtime`; recomputes `anchorRemaining − elapsed` each tick.
- 300 s → `5 minutes left`; 60 s → `1 minute left — the test auto-submits at zero`
  (red card, `Dismiss`).
- At 0 → auto-submit (no confirmation).
- Countdown **never pauses** (not palette, dialogs, background). Only dwell clock pauses.

### Submit Dialog
Title: `Submit test?` Body (fixed order, only when applicable):
1. `Answered: 12 of 20`
2. `Unanswered: 8 — these score 0`
3. `Flagged for review: 3`
4. `Not scored (no answer key): 1`
5. `Longest on Q7: 2:14`

Buttons: `Review answers` → pre-submit review; `Submit`; dismiss = `Review
flagged` (if any flagged) else `Cancel`. Dialog dismissed before save; result
screen follows save completion. Saving shows `Saving result… 3 of 20` + progress.

### Pre-Submit Answer Review
Title `Your answers`. One row per question: `Q1` + selected options' text (`, `) or `—`. Status: `answered` / `unanswered` / `not scored` + ` · flagged`. Tap row → jump. Buttons: `Back to submit`, `Close`.

### Resume Dialogs
Offered at load for same session identity (paper, scope, type, count, exclusion,
targets, variant). Titles:
- `Resume previous attempt?` (normal)
- `Resume a short attempt?` (< 20 saved)
- `Resume a legacy attempt?` (pre-bounded metadata)

Body: `N questions answered. Your progress was saved when the app closed.` + time
if running. Short/legacy appends note about no margin/trend. Confirm: `Resume` /
`Resume with 12 questions`. Dismiss: `Start fresh` / `Start a new test`. Not
dismissible by outside tap. Restores selections, flags, bookmarks, index,
seconds, order (replaying seed), composition, dwell.

### Ungraded Dialog
On load if run has no-key questions: `Questions without an answer key` → `N
question(s) have no answer key and won't be scored. You can still answer them
for practice.` Buttons: `Continue` / `Go back`.

### Load/Save Failures
- Load error: error text, `Retry`, `Go back`.
- Save error: red card, message, `Retry` (or `Saving…`). Snapshot remains on disk.

---

## Submitting, Scoring & Marking

### What Is Stored
One `Attempt` + one result row per question. Attempt keeps: paper id/title, shown
count, correct/wrong/skipped, score, maxScore, duration, finished timestamp,
type, requested count, scope category ids, scope key, sampling version, seed,
exclusion mode, target ids, composition JSON, variant, **applied negative-marking
rate** (null on old/imported), blueprint trees.

### Marking Model
Per question with key:
- `marks` default 1.
- Selected == correct **as a set** and non-empty → `+marks`.
- Selected non-empty but not equal → `−marks × negativeMarking` (all-or-nothing;
  no partial credit for multi-correct).
- No selection → 0 (skips never penalised).
- No key → ungraded: excluded from **both** score and maxScore.

`maxScore = Σ marks` over graded questions. `percentage = score / maxScore × 100`.
`accuracy = correct / (correct + wrong)` ("accuracy on answered").
`accuracyAsked = correct / shown` ("accuracy on the whole test"; skips/ungraded
count as misses).

**Worked Example** — 20 questions, 1 mark each, negative marking 1/3, 1 ungraded,
14 correct, 4 wrong, 1 skipped:
```
graded        = 19
maxScore      = 19
score         = 14 − 4 × (1/3) = 12.6667
percentage    = 12.6667 / 19 × 100 = 66.67 → "Score 67%"
header        = "12.7 / 19"
accuracy      = 14 / 18 = 77.8% → "Accuracy on answered 78% (14/18)"
accuracyAsked = 14 / 20 = 70%   (used by margin & trend)
```
Same answers, no negative marking: score = 14, percentage = 73.7%. Skipping the
four wrong instead gives 14/19 = 73.7% with no penalty either way — difference
only in `accuracy` (14/14 vs 14/18).

Marking is derived from the attempt, not re-read from the paper. Editing a
paper's negative marking later does not rewrite an old attempt. Old rows without
stored rate reconstruct it as `(correctCount − score) / wrongCount` capped 0…1,
falling back to paper's current rate.

### Counts Shown Everywhere

| Field | Meaning |
|-------|---------|
| Correct | graded, answered, exact match |
| Wrong | graded, answered, not exact |
| Skipped | graded, no selection |
| Ungraded | no key (regardless of selection) |
| Answered | correct + wrong |
| Shown | result rows (or attempt total when rows absent) |
| Asked | requested count recorded on attempt |

**Note:** A graded skipped question is labelled `Skipped` on its review card,
matching the header count and the `Skipped` filter.

---

## Results Screen

### Header Card
- Attempt title (paper title).
- Date `dd MMM yyyy, HH:mm`.
- Header line: `<family> · <type> · <N of M questions>` + badge:
  - `Mock` → `comparable`
  - `Fresh sample` → `fresh sample · comparable to fresh samples only`
  - `Short test` → `no margin`
  - `Practice`/`Legacy` → `not comparable`
  - Legacy adds: `This was a full-scope attempt with many skipped questions. Its percentage is not comparable to a bounded test; use the question review.`
- Big score `%.1f / %.0f` (e.g. `12.7 / 19`).
- `Score 67%`.
- Progress bar at percentage.
- Stat columns: `Correct`, `Wrong`, `Skipped`, `Ungraded` (only when > 0).
- `Time taken: 04:12`.
- `80 asked, 76 shown` (only when differs).
- `This test did not change your Study schedule.` — always present.
- Screen title: `Results` or `Attempt review` (from History).
- Overflow menu (a11y `Export report`): `Export CSV` / `Export PDF` → share chooser `Share report`.

### Section Order (only cards with data shown)

1. Header
2. `What this result means`
3. `Guessing-corrected score (estimate)` — mock/fresh, no negative marking, ≥ 20 answered, all option counts known
4. `Targeted practice` — any practice type
5. `Study estimate vs test accuracy` — rows exist
6. `Coverage & progress` — rows exist (also carries mistakes)
7. `Comparable trend` — mock/fresh, trend-worthy
8. `Score interval` — negative marking > 0, ≥ 2 rows, ≥ 2 passage blocks
9. `Where the marks went` — any wrong/skip/ungraded/possible-guess > 0
10. `Blueprint · <label>` — blueprint composition exists
11. `Topics` — at least one row
12. `Time` — at least one timed question
13. `What to do next` — at least one recommendation (max 5)
14. `Questions` + filter chips + per-question cards

### 1. What This Result Means
- `Answered 18 of 20 · 14 correct · 4 wrong · 1 skipped`
- If `correct + wrong > 0`: `Accuracy on answered 78% (14/18)`
- `Score 67% · with 1/3 marking on wrong answers; skips are not penalised` or `Score 67% · no negative marking` (`1/3`, `1/4`, `1/2`, `1`, `33%`).
- If margin exists:
  - `95% margin ±18 points · 20-question sample (approximate)`
  - Previous comparable: `Last comparable: 62% (±19) · this result 67%`, then `+5 points, inside the margin` / `outside the margin`
  - First comparable: `This is your first comparable 20-question Random test.`
  - Not trend-worthy: `A single 20-question result is not a trend; 50 or more shown is needed for a line.`

**Important:** Margin is computed from **accuracyAsked** (correct / shown), while
score percentage can be mark-weighted with negative marking. The two can
legitimately disagree. Delta test compares `|delta|` with **this** attempt's
margin only (trend card compares with sum of both margins).

### 2. Guessing-Corrected Score (Estimate)
Mock/fresh, no negative marking, ≥ 20 answered, known option counts.
- `Corrected 47% · raw 60% · chance 25%`
- `Corrects the score for answers a uniform guess would have got right. It assumes no negative marking and is not a prediction.`
- `Estimate from 20 answered questions.`
- Formula: `(p − chance) / (1 − chance)`, clamped below at 0.
- `chance` = per-item uniform chance: `1/k` (single-correct), `1/C(k,c)` (multi-correct), averaged.

Example: 20 answered, 12 correct, all 4-option single-correct → raw 60%, chance
25%, corrected `(0.60 − 0.25) / 0.75 = 46.7%` → `Corrected 47% · raw 60% · chance 25%`.

### 3. Targeted Practice
For practice types (never Random/Balanced/Blueprint).
- Targets recorded: one row `Polity · 7/10 (70%) · 30 in pool`; note `Overall 55% on 18 answered; the target accuracy above is the headline.` + bias note `Practice questions are chosen because they are weak, so the overall score is expected to be lower than a random mock.`
- No targets: `This practice run recorded no target topics.`
- ≥ 2 runs same targets: `Target trend` → `Your targets across 3 runs: 40% → 55% → 70%`, `Change vs the previous run: +15 points`, `Same recorded target set; this line is never compared with a mock.`

### 4. Study Estimate vs Test Accuracy
FSRS quadrant counts:
- `Hard · wrong: genuine weakness (N)`
- `Hard · correct: fragile, retest soon (N)`
- `Easy · wrong: application or careless (N)`
- `Easy · correct: consolidated (N)`
- `N questions have no Study history.`
- `N of these questions are due in Study now.`
- `Mean predicted recall 74% over 18 studied cards.`
- `These are Study estimates, not grades.`

"Hard" = difficulty ≥ 5.5; missing/new/stale → "no Study history"; skipped outside both axes.

### 5. Coverage & Progress
- `12/200 questions tested (6%) · 3 repeats this test`
- `188 questions in scope are still unseen.`
- `4 of this test's questions are due in Study now.`
- `Mistakes: 4 added · 2 cleared · 12 remaining`

"Engaged" = answered or dwelled-on. "Tested" = scope's already-tested + newly
engaged. `remaining` reconciles prior mistakes with this attempt's adds/clears.

### 6. Comparable Trend
Mock/Fresh, trend-worthy (`shown ≥ 50` or `margin ≤ 10`; a 30-question census
margin 0 is trend-worthy).
- `N comparable points · last 67% · change −5 points`
- `The change is inside the margin, so it could be noise.` / `outside the margin.`
- Fresh sample: `Fresh samples exclude a different recent set, so this line is approximate.`
- Points = last 5, same comparability key, oldest→newest. `Inside` = `|delta| ≤ currentMargin + previousMargin`.

### 7. Score Interval
Negative marking > 0, ≥ 2 rows, ≥ 2 passage blocks.
- `95% bootstrap interval 62% to 74% (approximate)`
- `Resampled 3 passage blocks 1000 times.`
- Resamples whole passage blocks (standalone = own block) with seed from attempt;
  values: `+1` correct, `−marking` wrong, `0` skip/ungraded; 1000 resamples,
  2.5th/97.5th percentiles. Suppressed when marking = 0 (accuracy margin already
  describes score).

### 8. Where the Marks Went
- `Knowledge gap` always; `Rushed`, `Skipped`, `Ungraded`, `Possible guess` only when > 0.
- `Fewer than 10 timed questions; only absolute rules are applied.`

Thresholds (≥ 10 timed):
- Rushed wrong: answered wrong in `< max(5 s, median × 0.25)`
- Knowledge gap: every other wrong (incl. untimed, dwell 0)
- Slow-wrong list: wrong with dwell `≥ max(15 s, median)` (feeds "slowest wrong" evidence)
- Possible guess: correct with dwell 1–2 s (0 = not timed, excluded)

< 10 timed: rushed < 5 s, knowledge gap = rest, slow list uses 15 s.

### 9. Blueprint · \<label\>
See [Blueprints](#exam-blueprints). Extra: `± is the 95% margin for that section's sample, with no correction for the section quota. A section under 5 asked is a small sample, not a verdict.`

### 10. Topics
One row per category, accuracy ascending, then asked desc, then title:
- `4/10 · 40%`, `small sample` when < 5 asked
- `N topics in scope were not sampled: A, B`
- `N results no longer resolve to a category.`
- Topic accuracy = `correct / asked` (ungraded/skipped in denominator).

### 11. Time
- `04:12 total · median 0:12/question · target 1:00` (target = fixed 60 s/q)
- `Wrong answers averaged 1:14; correct answers 0:41.`
- `Time sinks: Q7 2:14, Q12 1:58, Q3 1:40` (up to 3, wrong at `≥ max(15 s, median)`)
- `Fewer than 10 timed questions; the pace comparison is a small sample.`

### 12. What to Do Next
Numbered 1–5, each with title, evidence line, button `Study` / `Review` / `Start
test`.

| Family | Rules (in order) |
|--------|------------------|
| Mock / Fresh | wrong ≥ 3 → `Review the N wrong questions`; `Study <weakest topic>`; skipped ≥ 3 → `Attempt every question next time` (no marking) / `Guess only when you can eliminate options` (marking); `Take a longer mock for a tighter estimate` when requested = 20 and margin ≥ 15; fallback `Keep going` |
| Practice | wrong ≥ 3 → review wrong; study weakest; `Repeat this focus test in 2–3 days` when targeted accuracy < 50%; `Practise the remaining N mistakes` when mistakes remain; `Take a random mock to check transfer` when target trend rose; `Continue this focus set` when < half target pool covered; `Review these questions in Study` for At-risk with due questions; fallback review-wrong |
| Short / Legacy | review wrong, then study weakest |

`Study <topic>` needs topic ≥ 5 asked and accuracy ≥ 10 pts below whole-scope
accuracyAsked. `Take a longer mock` only for 20-q request, margin ≥ 15, target 50
(or scope max).

### 13. Questions: Filters & Per-Question Cards

**Filter chips:** `All`, `Correct`, `Wrong`, `Skipped` always; `Ungraded` if any;
`Slow` if timing; `Saved` if bookmarks non-empty.

| Filter | Semantics |
|--------|-----------|
| `Correct` | graded, answered, exact |
| `Wrong` | graded, answered, not exact |
| `Skipped` | no selection **and** key exists |
| `Ungraded` | no key |
| `Slow` | `dwell ≥ max(60 s, 2 × median)` |
| `Saved` | question id in bookmark set (live) |

Empty state: `No <filter.lowercase()> questions` → `No all questions`, `No
correct questions`, etc.

**Per-question card** (numbered):
- Container: green correct, red wrong, grey ungraded. **Skipped graded = `Wrong` here.**
- Status word: `Correct` / `Wrong` / `Not scored` (literal, not resources).
- Bookmark icon: a11y `Remove bookmark` / `Bookmark question`; tap toggles. Last
  bookmark removed under `Saved` filter → resets to `All`.
- Tag: `Knowledge gap`, `Rushed`, `Skipped`, `Possible guess`, `Fragile correct`,
  `Solid correct`, `Not scored`.
- Study badge: `Study · difficulty 6.2/10 · 74% recall`, `· due now`, `· new card`,
  `· card is out of date (question changed)`, or `No Study history`.
- `Targeted · this question came from a practice target` when applicable.
- `Lifetime 12/15 (80%) · Mock attempts` or `· All attempts` (mock/fresh reads
  representative counters; practice reads all).
- `Item discrimination r = 0.31 · review this question` / `· weak
  discrimination` when same-family sample supports it.
- Options: `✓` correct, `✗` selected-wrong (not ungraded), `●` selected, `○` untouched.
- Explanation + lightbulb (a11y `Explanation`), `Time spent: 0:42` when dwell
  exists, explanation image (a11y `Explanation image`).

---

## History Screen

### Top Bar
- Title `History`; back a11y `Back`; action icon a11y `Statistics` (opens Stats).
- No search, filter, sort, **no delete**, no clear-history, no multi-select.
- Empty: `No attempts yet`.

### Trends Section
One card per `(paper, family, type, requestedCount, exclusionMode)` group,
excluding legacy. If any trend exists, `Trends` header + cards shown. The
`Attempts` header always renders before the attempt list, including when every
attempt is legacy.
Card: paper title; subtitle `<family>[ · <type>][ · N questions][ · fresh
sample]`; `${attempts} attempt[s] · best 82%`; right: latest %, arrow + absolute
change (`▲ 12 pts`, `▼ 8 pts`, `= 0 pts` grey). Delta = `latest − first` (not
slope, not margin-aware).

### Weakest Categories
Top 5 lifetime `(paper, category)` by lowest accuracy: title; `<paper> · 41% · 9/22`; progress bar.

### Hardest Questions
Top 5 by lifetime wrong rate: question text (2 lines); `64% wrong · 12% skipped · 25 attempts`. Graded attempts only.

### Attempts Cards
Each row tappable → Results/Attempt review:
- Title = **paper title** (no per-type attempt name).
- `<family> · <type line>` (tertiary): `Mock · Random`, `Mock · Balanced`,
  `Practice · Mistakes`, `Practice · Weakest topics`, `Mock · Blueprint`,
  `Short test · Random`, etc. Custom types append variant: `Practice · Tag ·
  polity`, `Practice · Authored difficulty · hard`, `Practice · Coverage ·
  exposure`.
- Timestamp `dd MMM yyyy, HH:mm`.
- Right: bold `67%` and `14/20 correct`.
- `14/20` uses `totalQuestions` (ungraded+skips included); percentage uses
  `score / maxScore` (ungraded excluded, negative marking applied). A
  negative-marked paper can show `70%` beside `18/20 correct`.

**No attempt delete control or confirmation.** (Deleting papers/categories/
questions/panels/blueprints has confirms; history rows do not.)

---

## Study (Spaced Repetition)

### How a Session Picks Questions
- **Live one-card-at-a-time** pull (no pre-fetch). Pools: **Learning** (new cards
  in learning steps), **Reviews** (due cards, interval ≥ 1 day), **Fresh** (new
  cards never seen).
- **No daily limits** (old caps removed; only on/off switch `Introduce new
  cards`).
- Ordering: due cards first (by due time), then new cards (by creation order),
  then tricky/leech drill (cards with `lapses ≥ leechThreshold`, default 8).
- **Scope:** whole paper or ticked categories (from Library/drawer).
- Library labels: `Study (X due, Y new)` / `Study selection (X due, Y new)` /
  `N due · N new · N tricky`.
- **Study again** re-pulls from the scheduler (fresh queue).

### Study Card Screen

**Sticky header** (always visible):
- Back arrow (a11y `Back`) → **stops the session** (same as Done for now), shows
  summary.
- Scope label (paper title or category list).
- Bookmark toggle.
- `Done for now` (TextButton) → stops, shows summary.
- Reviewed tally (e.g. `12 reviewed`).
- Session clock `m:ss` (wall time, runs while answer/explanation read).
- Minutes progress (question clock banked so far).
- Break strip (appears when question clock paused).

**Question card:** stem, options (radio/checkbox), passage header above if member.

**Classic footer** (default off): `Show answer` → reveals key + explanation,
then **Again / Hard / Good / Easy** (each shows delay preview: `10m`, `6h`,
`1d`, `7d` for a new card). No undo after tap.

**Simplified footer** (default on, `Simplified Anki` switch in Settings):
- `Guessing` switch (toggles per card; default off).
- `Check` → grades automatically: exact match + not guessed → `Good`; exact +
  guessed → `Again`; wrong → `Again`; empty → `Again`. Uses the absolute dwell
  cuts: `dwell < fastSeconds` (default 6 s) → `Easy`;
  `dwell >= slowSeconds` (default 60 s) → `Hard`; else `Good`. The clock starts
  when the card appears, so reading time counts against the cut and a long
  question buys no extra Easy time. With `Grade on correctness only` on, the
  clock is not consulted: a correct answer is always `Good`, so `Easy` and
  `Hard` only appear when the learner edits the grade.
- Result card shows grade, dwell, `Change grade` (re-opens Again/Hard/Good/Easy
  with same dwell), `Next` (persists the grade, pulls next card).

**Gestures/keys:** No keyboard shortcuts. Back = stop (not exit).
Scroll/background pauses question clock. Haptics: Tick (reveal/advance/grade),
Confirm (correct), Reject (wrong).

**Two clocks:**
- **Question clock:** starts when card visible; pauses on reveal/Check,
  scroll-away, background, tap. Banked in milliseconds in
  `pauseQuestionClock()`, displayed truncated, and rounded half-up once when
  Check freezes the graded dwell. Its icon and time are tinted from the same
  absolute cuts the check grades with: neutral for the first second (nothing
  banked yet), green below `fastSeconds`, amber through Good, red at or above
  `slowSeconds`. Tints are derived from the active
  theme and contrast-checked against its surface (≥ 4.5:1), so every palette
  stays legible in light and dark. Under `Grade on correctness only` the clock
  keeps the neutral tint while still counting and storing the dwell. Study
  only; the Test countdown is unchanged.
- **Session clock:** wall time, runs continuously (including answer/explanation).

### Session Summary

On stop (Back / Done for now / pull runs dry):

| Line | Computation |
|------|-------------|
| `N reviewed • M again • K remembered` | `reviewed` = grades persisted; `again` = Again grades; `remembered = reviewed − again` (Hard counts as remembered). Hard-coded English. |
| `Recalled P%` | `remembered * 100 / reviewed` (0 when reviewed = 0). |
| `Questions A:BC • Explanations D:EF • Total G:HI` | `questionSeconds` = banked question-clock segments; `sessionTotalSeconds` = frozen at finish; `explanationSeconds = max(0, sessionTotalSeconds − questionSeconds)`. Formatted `Dwell.format` (`m:ss`). |
| Buttons | `Study again` (re-pulls) / `Done` (leaves). |

**Stopped before any review** (`EmptyReason.StoppedEarly`):
- Title: `Stopped before reviewing a card.`
- Times shown (question/explanation/total).
- Buttons: `Study again` / `Done`.
- No fake tally (`0 reviewed` etc.).

**Simplified mode edge cases:**
- `Check` only **proposes** a grade (`pendingGrade`). `Next` persists it.
- Stop after `Check` **writes the proposal** exactly as `Next` would (same dwell,
  guess flag, correctness) then closes. Guarded by `stopped.compareAndSet`
  (one stop per sitting). Failed write → summary with `GradeError` banner; late
  write after stopped-early copy → flips reason back to tally.
- Classic mode: each grade button persists immediately; no pending grade.

### Scheduler (FSRS-6)

The app uses the **FSRS-6** algorithm (21 weights, Anki-compatible). Two numbers
per card:

| Parameter | Meaning | Range |
|-----------|---------|-------|
| **Stability (S)** | Days until recall probability falls to the target retention (default 90%). Bigger = longer intervals. |
| **Difficulty (D)** | How hard the card is for you, 1 (easy) → 10 (hard). Moves with grades, pulled slowly toward middle. |

**Retrievability (R)** = predicted recall chance right now:
`R = (1 + FACTOR × t/S)^DECAY`, clamped 0..1. `t` = days since last review.

**State machine:** NEW → (Good/Easy) → LEARNING steps (10 m, 6 h, 1 d, 7 d by
default) → REVIEW (intervals grow with S). Again on REVIEW → RELEARNING (delay
default 10 m) → REVIEW with reduced S. **Leech** flag when `lapses ≥
leechThreshold` (default 8, configurable 1..100).

**Study-day boundary:** 04:00 (configurable `anki_day_start_hour` 0–23). A
review at 01:00 belongs to the previous day. All streak/bucket arithmetic uses
this, never midnight.

**Scheduler Settings (Settings → Study / Spaced repetition):**

| Setting | Default | Range / Step | What it changes |
|---------|---------|--------------|-----------------|
| `Simplified Anki` | **on** | switch | Auto-grades from answers instead of Again/Hard/Good/Easy. |
| `Target retention` | 0.90 | slider 0.75–0.97 (21 steps, 0.01) | Higher → shorter intervals, more reviews. The percentage is shown above the slider and follows the thumb. |
| `Optimize FSRS parameters` | — | button (unlocks at 1000 reviews library-wide) | Fits 21 weights to your review log (Anki parity). Shows log-loss before/after. `Restore defaults` drops fitted weights. |
| `Ranking mode` | `Memory first` | chips: `Memory first` / `Explore new cards` | Memory first clears due before new. Explore surfaces a new card every 3 reviews (`EXPLORE_NEW_EVERY = 3`). |
| `Introduce new cards` | **on** | switch | Off = review-only sessions (no NEW pool). |
| `Grade on correctness only` | off | switch | On = correct → `Good`, wrong/guess/skip → `Again`, the clock never decides the grade or its colour. Hides both cut fields below and the Stats calibration card. Dwell is still recorded. |
| `Minimum easy answer threshold` | 6 s | 1–3600, step 1 | Auto-graded Easy below this. Absolute: reading time is inside the cut. Hidden while correctness-only grading is on. |
| `Hard answer threshold` | 60 s | 1–3600, step 1, ≥ fast | Auto-graded Hard at or above this. Hidden while correctness-only grading is on. |
| **Advanced** (collapsed) | | | |
| `Next day starts at` | 4 h | 0–23 | Study-day boundary. |
| `Maximum interval` | 36500 d | 1–36500 | Cap on any interval. |
| `Relearning delay` | 10 m | 1–1440 m, step 0.5 m | Delay after Again on REVIEW. |
| `Leech threshold` | 8 | 1–100 | Lapses before flagging tricky. |
| `Reset` | — | button (confirm dialog) | Resets every study setting — schedule knobs, target retention, ranking mode and new-card handling (not fitted weights). |

**FSRS Parameters (21 weights, official defaults):**
`w = [0.40, 0.60, 2.40, 5.80, 4.93, 0.94, 0.86, 0.01, 1.49, 0.14, 0.94,
2.18, 0.05, 0.34, 1.26, 0.29, 2.61, 0.00, 0.00, 0.00, 0.00]`

**Curve:** `R = (1 + t / (S * w[9]))^w[10]` (simplified; full uses all weights).

**Grade → Interval Examples (new card, default weights, retention 0.9):**

| Grade | Next interval |
|-------|---------------|
| Again | 10 minutes (same sitting) |
| Hard | 6 hours (or 5 h 40 m → snaps to 04:00 boundary) |
| Good | 1 day |
| Easy | 7 days |

**Graduated card (S=20, D=5, interval 14 d):**
- Again → 10 m (relearning)
- Hard → ~5 d
- Good → ~32 d
- Easy → ~51 d

**Retention vs Interval (default weights, D=5):**
| Retention | Interval for R=retention |
|-----------|--------------------------|
| 0.75 | 2.0 d |
| 0.80 | 4.3 d |
| 0.85 | 8.5 d |
| 0.90 | 16.1 d |
| 0.95 | 38.7 d |

**Retrievability decay (S=10 d):**
| Days since review | R |
|-------------------|---|
| 0 | 1.00 |
| 5 | 0.95 |
| 10 | 0.90 |
| 20 | 0.76 |
| 30 | 0.65 |
| 60 | 0.43 |

**Leech collapse:** A leech (lapses ≥ 8) gets its stability crushed → intervals
shrink drastically until it is no longer a leech.

**Worked life of q42:**
1. NEW → Good → LEARNING (10 m)
2. Good → 6 h
3. Good → 1 d
4. Good → 7 d (graduated to REVIEW, S≈10)
5. Again (lapse 1) → RELEARNING 10 m
6. Good → 1 d
7. Again (lapse 2) → RELEARNING 10 m
8. … after 8 lapses → `leech = true`, S crushed, intervals ~1 d until re-learned.

### Study Screen Numbers (Plain Language)

| Number | What it means |
|--------|---------------|
| `N due` | Cards whose due time has passed (including overdue). |
| `N new` | Cards never reviewed (no schedule row). |
| `N tricky` | Cards flagged as leeches (lapses ≥ 8 by default). |
| `Reviewed` | Cards you graded in this sitting. |
| `Again` | How many times you tapped Again (or auto-graded Again). |
| `Remembered` | `Reviewed − Again` (Hard/Good/Easy all count as remembered). |
| `Recalled P%` | `Remembered / Reviewed × 100` — your hit rate this sitting. |
| `Questions A:BC` | Time spent **reading the question** (clock paused on reveal/Check/scroll/background). |
| `Explanations D:EF` | `Total − Questions` — time spent reading the answer & explanation. |
| `Total G:HI` | Wall-clock length of the sitting (frozen at finish). |

---

## Statistics (Seven Tabs)

**Global controls (top):**
- **Scope chips:** `All papers` + one per paper (only when > 1 paper). Default
  `All papers`. Selecting a paper:
  - Retention tab: `Retention by paper` → `Retention by category`.
  - Cards/Memory tabs: per-paper cards disappear (only unscoped).
  - Tests tab: scope passed to SQL counters.
- **Range chips:** `7 d` / `30 d` / `90 d` / `1 y` / `All` (default `30 d`).
  Hidden on Tests tab (all-time). Window = study days (04:00 boundary), inclusive.

### Tab 1: Overview

| Card | What it shows |
|------|---------------|
| **Today** | 6 tiles: Reviews, Correct %, Time studied, Introduced (first-ever reviews today), Due (cards due today), Backlog (overdue). Then `Learning N · Review N · Relearning N`. |
| **Overview (KPIs)** | 12 tiles: Retention (first-of-day), Desired retention (setting), Mature retention, Current streak, Reviews in range, Study days, Time studied, Avg answer time, Est. knowledge (Σ retrievability), Avg interval (mean intervalDays > 0), Leeches (error colour if > 0), Longest streak. |
| **Test result families** | Static legend: Mock, Fresh sample, Practice, Short, Legacy (same labels used on Results/History). |
| **True retention** | 7-row table (Today / Yesterday / Last 7/30/90/365 / All time) × 3 cols (All / Young / Mature). First-of-day rule, mature = carried interval ≥ 21 d. |
| **Calendar** | GitHub-style 365-day heatmap (weeks columns, rows = day-of-week offset, no Mon–Sun header). 0 = empty; intensity = reviews / min(max, 4). Tap cell → `d MMM · N reviews`. Below: `Current streak: N days` (today 0 doesn't break), `Longest streak: N days`. |
| **Most forgotten** | Ranked list (≤ 15) by again-rate desc, then Again count desc. Guard: ≥ 3 reviews in range AND ≥ 1 Again. Row: question (2 lines) + `5× · 3× again · 60% · 12s`. Tap → card sheet. |
| **Scheduler** | Status text (fitted vs default), Desired retention, progress bar to 1000 reviews, `Open scheduler settings` button. |

### Tab 2: Retention

| Card | Chart / Table | Key details |
|------|---------------|-------------|
| **Retention** | Multi-series line: actual (filled area) + rolling pooled (tertiary, 7 buckets daily / 4 weekly/monthly) + dashed desired retention. y fixed 0–100%. Granularity: ≤ 31 d daily, ≤ 150 d weekly, else monthly. Empty buckets = gaps. |
| **Calibration** | Bubble scatter: x = mean predicted retention per 0.1 bin, y = observed pass rate. Diagonal = perfect. Bubble radius = `6dp × (1 + n/20)` max 16dp. Summary: `Predicted X% vs actual Y% over N reviews · Brier score Z (lower is better)`. Brier = mean `(p − outcome)²`. Guard: `Calibration needs reviews of cards that already had a memory state.` Grade evidence below it: per-grade pairs/cards/recall, `Easy vs Good` and `Hard vs Good` contrasts with their 90 % intervals, follow-up coverage and the readiness gate. Once those gates close, a read-only line names the pair to try next (`Suggested cuts: try 4 s / 60 s instead of 6 s / 60 s. Nothing is applied automatically`), one second per 5 pp outside the band, capped at 4 s, Easy cut only. The whole card is hidden while `Grade on correctness only` is on: with no Easy/Hard labels there is nothing to calibrate. |
| **Retention by paper / category** | Horizontal meter bars, weakest first, top 15. Unscoped = papers (deleted → `Unknown paper`); scoped = categories (unresolvable → `—`). |
| **Retention by interval** | Meter bars, fixed 6 bands: `First`, `1–7 d` (includes 0-day relearning), `8–21 d`, `22–60 d`, `61–180 d`, `181+ d`. |
| **Retention by tag** | Meter bars, weakest first, top 15. Multi-tag questions counted in each tag. |
| **Retention by category (all papers)** | Meter bars, weakest first, top 15. Only unscoped. |
| **Retention by weekday** | 7 meter bars Mon–Sun (study-day weekday = `floorMod(epochDay + 3, 7)`). First-of-day rule. |

### Tab 3: Workload

| Card | Chart | Key details |
|------|-------|-------------|
| **Forecast** | Stacked vertical bars (30 days, today = bar 0) + right-axis cumulative line. Segments: Learning (incl. Relearning) / Review. Overlay = cumulative if skipped (starts at overdue). Tooltip: bar total + overlay. Below: `Overdue: N` (error if >0), `Due today: N`, `Due tomorrow: N`, `Due this week: N`, `Daily load: X.X` (Σ 1/max(interval,1)). |
| **Review history** | Stacked bars (4 segments: Learning, Review, Relearning, Unknown) at same granularity as Retention trend. |
| **Grade mix over time** | Stacked bars (Again, Hard, Good, Easy). Corrupt ratings (outside 1..4) are dropped here, and by **Answer buttons** too, so the two charts agree. |
| **Time studied** | Single-segment bars = total seconds per bucket (y formatted as duration). Missing timing = 0 bar; no reviews = no bar. |
| **Answer buttons** | Numeric table: rows Learning / Young / Mature / Relearning (pre-state classification). Columns Again / Hard / Good / Easy / Pass. Unknown pre-state rows dropped. Again counts tinted error. |
| **Study sessions** | Text stats: Sittings (gap > 30 min or negative), Mean reviews, Mean length, Longest, Seconds per review (timed). Histogram: bins `1`, `2–4`, `5–9`, `10–19`, `20–49`, `50+`. |
| **Hourly breakdown** | 24 bars + right-axis pass rate line (null hours break line). |
| **Response time** | Histogram (edges 5/10/20/30/60/120 s, labels `0–5s`…`2m+`), only timed rows. Then `Again 7s · 12×` per grade with timed reviews. Fast failures: `N failures were answered in under 5 seconds — worth a slower look.` Trend line (mean seconds per bucket, duration y-axis). Slowest cards (top 10, mean timed seconds, ≥ 3 timed reviews, tap → card sheet). |

### Tab 4: Memory

| Card | Chart / Table | Key details |
|------|---------------|-------------|
| **Stability** | Histogram (edges 1/3/7/14/21/45/90/180/365 d, labels `<1d`…`>1y`), cards with S > 0. Mean/median line. |
| **Difficulty** | Histogram 10 bins (1…10, `floor(d)−1`, 10.0 → bin 10). Mean/median. |
| **Retrievability** | Histogram 10 bands `0–10%`…`90–100%` of `R(card, now)` for S > 0. Mean/median + `Estimated knowledge: N cards you are likely to remember right now`. |
| **Memory by paper** | Text table (unscoped only): `Title · N cards · S X.X · D X.X · R YY%`. S/D one decimal, R percentage. |
| **Intervals** | Histogram (edges 2/4/8/16/32/64/128/256/512, labels `1d`…`17m+`) + right-axis cumulative share (0–100%). Mean/median. |
| **Memory growth** | Two line charts: Mean stability by review number (1…15, 15+ pools rest) + Mean stability by month (`MMM yy`). All history, not range-filtered. |

### Tab 5: Cards

| Card | What it shows |
|------|---------------|
| **Coverage** | `%1$d studied · %2$d unseen · %3$d bookmarked of %4$d`. Meter = studied/questions. Unseen = max(questions−studied,0). Studied = any `card_state` row (even NEW). Guard: `No questions in this scope.` |
| **Card counts** | Donut (150 dp, 22 dp ring): Learning / Relearning / Young (REVIEW interval < 21 d) / Mature (REVIEW interval ≥ 21 d). NEW excluded. Total = four states. Leeches note if > 0. |
| **Overdue age** | Histogram bins `<1d`, `1–2d`, `2–4d`, `4–7d`, `1–2w`, `2w+` (age = floor((now−dueAt)/1d), min 0). Summary: `Mean age X.X d`, `Oldest N d`. |
| **Cards per paper** | Meter bars (unscoped only), one per paper, total desc, cap 50, `N more papers not shown.` |
| **Not started per paper** | Rows `Title · N unseen of N total`, unseen desc, top 15. |
| **Leeches** | Ranked list (≤ 15), lapse count desc, same `5× · 3× again · 60% · 12s` summary. Lifetime counts (range ignored). Tap → card sheet. |

### Tab 6: Guessing

All figures count **only rows carrying v19 flags** (`wasGuess`, `wasCorrect`).
Rows without flags counted separately in coverage line.

| Card | What it shows |
|------|---------------|
| **Guessing at a glance** | 6 KPIs: Guess rate, Guessed right (hit rate), Random chance (weighted mean `1/C(options,correct)`), Lift (hit − chance, signed pts), Raw accuracy ((earned+lucky)/flagged), Graded pass (earned/flagged). Coverage line: `All N in-range reviews carry the guess flag.` or `Counting N of M in-range reviews; older rows predate guess tracking.` |
| **Outcomes** | 2×2 grid: Earned (knew it) / Lapse (said wrong) / Lucky (guessed right) / Didn't know (guessed wrong). `Raw accuracy minus graded pass is the lucky share: +X pts.` |
| **Guess rate over time** | 2-line chart (filled guess rate + hit rate) + dashed chance. Same granularity rule. |
| **By card maturity** | Stacked bars (Guessed / Not guessed) + right-axis hit rate. Bands: Fresh/unknown, Learning, Relearning, Young, Mature. |
| **By sitting position** | Stacked bars + hit rate. x labels `1`, `2`, `3`, `4`, `5+` (capped at 5). |
| **By hour of day** | 24 stacked bars + right-axis guess rate. |
| **Time on the card** | `Guessed: Xs · N timed`, `Earned: Ys · N timed`, comparison with ±0.5 s dead band. |
| **What lucky picks cost** | `+N days of interval lost` (sum over guessed-correct of `max(0, goodInterval − actualInterval)`). `N reviews; Good would have averaged X.X d, the card kept Y.Y d.` Only rows with replayable counterfactual. |
| **Fragile topics** | Meter bars by category (fallback paper), top 8, guessed desc, hit rate asc. Row: `N reviews · N guessed · X% hit`. |
| **Shaky cards** | Ranked list (≤ 10), guessed desc, hit rate asc, recency desc. Guard: ≥ 2 guesses. Row: `N guesses of M reviews · X% hit`. Tap → card sheet. |

### Tab 7: Tests

No range chips. Sections in order:

| Section | What it shows |
|---------|---------------|
| **Exam** | Group chips: `Mock strict`, `Mock fresh`, `Practice` (default first with attempts). Profile chips (≤ 8, latest first) when group has > 1 comparison key. Body: key line, scope, `N comparable attempts · N questions shown`, 2-col KPIs (Latest, Best, Average, Change, Attempts, Total time, Skip rate, Sec/question), source line (lifetime accuracy + rep*/all), trend line (filled, y 0–100%), `Approx. 95% margin ±X.X points · n=N`, `The latest change is inside the margin: treat it as noise.` (when `|delta| ≤ currentMargin + firstMargin`), Per paper (if ≥ 2 papers), Category accuracy (meter bars, weakest first, ≤ 15), Timing/skips. Empty: `No comparable attempts in this group yet.` |
| **Recent attempts** | 5 most recent all families: `family · correct/shown · percent · dd MMM yy`. Tap → Results. |
| **Test families** | Rows per family present: `Family · N attempts · latest X% · average Y%` (unweighted mean of percentages). Families: Mock, Fresh sample, Practice, Short, Legacy. |
| **Reliability (estimate)** | `r = X.XX · SEM Y.Y questions`. Same-form families use the across-attempt split-half (`splitHalfAcrossAttempts`, Spearman–Brown `2r/(1+r)`); different-form families fall back to the mean per-attempt split-half. Optional `KR-20 X.XX` (same-form check). Sample: MOCK + FRESH_SAMPLE, largest group by (paper, type, scope, count), latest 30 attempts, each attempt's graded rows as 1/0, n = first attempt's rows (≥ 20), min 5 per half. SEM is on the raw question-count scale and labelled `questions`. Guard: `Sample too small to estimate reliability.` |
| **Practice progress** | Last 3 focus-practice runs: `Type · target X/Y (Z%) · N mistakes cleared`. Mistakes cleared = graded/answered/correct with prior standing = mistake. |
| **Weakest practiced topics** | Top 5 by target accuracy asc: `Category · X/Y (Z%)`. Targets with 0 asked skipped. `withoutTargets` runs skipped. |
| **Fixed panels** | Rows `Name · N questions · N runs · latest X% · average Y%` (most recent run first). Only attempts with `variant = panel id`. |
| **Current scope** | `N questions · N unseen · N leeches · N at risk`. Unseen = no card / NEW / stale hash. At risk = due now, not NEW, stability > 0. |

### Tab 8: Progress

| Card | What it shows |
|------|---------------|
| **Progress** | 6 KPIs: Reviews (+Δ), Introduced, Graduated (pass from NEW/LEARNING/RELEARNING), Relapsed (Again on REVIEW), Again rate (+Δ points, negated pass-rate delta), Lapse rate. |
| **Against previous period** | Equal-length window immediately before selected range. `Reviews vs previous`, `Pass rate vs previous`, `Avg answer vs previous`, `Study days vs previous`. All range has `The All range has no previous period to compare.` |
| **Card flow** | 3-line chart (Introduced, Graduated, Relapsed) at granularity rule. |
| **Again rate** | Filled line (y 0–100%, error colour). `Again X% · lapses Y`. `Pass rate change Z pts against the previous period.` |

### Card Sheet (from every ranked list)

Modal bottom sheet (Anki's Card Info):
1. Question text (or id).
2. If no schedule: `This card has never been scheduled.` Else: State (New/Learning/Review/Relearning), Interval (`N d`), Due (`d MMM yy, HH:mm` or `Never`), Stability (`X.X d` or `—`), Difficulty (`X.X` or `—`), Predicted recall (`XX%` or `—`), Lapses, Reviews.
3. History summary: Again rate, Mean answer time (timed).
4. Full log newest first: `d MMM yy, HH:mm`, grade word (Again/Hard/Good/Easy, tinted), detail `6s · S 4.2 · D 5.0 · I 10d` (elapsed · stability after · difficulty after · interval after — omitted when not recorded).
5. Empty: `This card has no logged reviews yet.`

---

## Browse, Search, Editor & Passages

### Browse Screen

**App Bar:**
- Normal: title = paper title; back → pop; **Select** (icon) → enters selection mode.
- Selection: title = `N selected`; back → `Close` (exits selection, clears); **Select All** / **Edit** / **Move** / **Export** / **Delete**.

**Search Field:** `OutlinedTextField`, label `Search text, tags, options`, single line, 300 ms debounce.

**Filter Chips (4):** `All` / `No answer` / `No explanation` / `Uncategorized`. Changing filter drops selection.

**Category Dropdown:** `All categories` / single category (depth-indented). Single pick only.

**Selection Mode:**
- Row checkbox toggles `selectedIds`.
- Bulk actions (enabled when non-empty):
  - **Edit** → dialog: Marks (optional, non-negative decimal), Difficulty (Keep / Easy/Medium/Hard/Very Hard), Tags (comma-separated, blank = keep). Apply → clears selection, exits.
  - **Move** → Move/Copy dialog: Move/Copy toggle, target paper picker, target category list (flattened, depth-indented, shows count). Success → clears selection, exits.
  - **Export** → Export Format dialog (captures `exportIds`).
  - **Delete** → confirm `Delete N questions?` + `This cannot be undone.` → deletes.

**Reorder Controls** (only when unfiltered, unsearched):
- **Question arrows** (per row): up/down. Passage-aware: standalone facing block jumps whole block; member swaps within run; block moves via passage header arrows.
- **Passage header arrows:** move whole block up/down.
- **Category section arrows** (first/last row of category, completely unfiltered): double-arrow up/down moves whole category.

**Empty States:** `Loading…` / `No questions in this paper` / `No questions match this filter`.

**Per-Row Actions (non-selection):** Tap → opens in Browse (deep-link focus); Edit → Editor with Prev/Next queue; Delete (immediate, no confirm); Duplicate.

**Passage Groups:** Header card before first member: title, member count, block arrows, content preview (4 lines), `Show/Hide questions (N)` toggles paged member list (50/page). Subsequent members: `In passage: {title}` label.

### Editor Screen

**App Bar:** Title `New question` / `Edit question`; back → pop (no unsaved guard on back); Prev/Next counter + buttons (only if queue exists). Prev/Next **never gated on form validity** — only block if dirty AND invalid. If dirty & valid → auto-saves, shows `Saved`, then navigates.

**Category Dropdown** (if paper has categories): flattened, depth-indented. Changing category drops passage if not in new category.

**Passage Dropdown** (if category has passages or question assigned): `Remove from passage` + passages in current category.

**Question Body — BlockListEditor:** Label `Question`. Blocks: Text, Image, Table, Formula (MathML/LaTeX via MathLiveEditor). Per-block: add/move/remove/edit. Images: gallery picker → `data:` URI. Tables: cell add/remove row/col, edit cell.

**Legacy Question Image** (read-only): if pre-block image exists → label + image + Remove.

**Preview Card:** read-only render of blocks.

**Options Section:** Label `Options`, hint `Tick the correct answer(s)`. Per option: checkbox (correct), title `Option (a)`, move up/down, Remove (only if > 2), BlockListEditor (no table cell editing), legacy image row. **Add Option** button.

**Difficulty Dropdown:** Easy / Medium / Hard / Very Hard.

**Marks Field:** `Marks (default 1)`, single line, non-negative decimal.

**Explanation Section:** Label `Explanation`, BlockListEditor (tables read-only), legacy image row.

**Tags:** Existing tags as `InputChip` (tap → remove). Add tag: field + `Add` button.

**Validation Warnings:** `No answer is marked correct — this question will not be graded.` (error colour). Blank text / < 2 options / blank option → Save/Prev/Next disabled.

**Save / Delete Row:** `Save` (enabled when valid & not saving) → saves, pops, `Saved` toast. `Delete` (not new, not from import) → deletes, pops.

**EditorSession (Prev/Next Queue):** Seeded from Browse filter via `repository.browseFilteredIdAt` (streams, no materialisation). Holds size, index, prevId, nextId, hasQueue.

### Passages Screen

**App Bar:** Title `Passages`; back → pop. FAB `Add` → New Passage dialog.

**List:** Empty `No passages yet`. Per row: title (or `Passage`), paper title, member count, body preview (4 lines), `Show/Hide questions` toggle, Move up/down, Edit, Delete (guarded if members → refusal dialog `This passage still has N question(s). Remove them from the passage first.`).

**Expanded Member List:** Paged (50/page), index + stem (2 lines). Tap → opens in Browse focused.

**New/Edit Passage Dialog:** Title (required), Text (multi-line, 160 dp; rich elements preserved if text unchanged), Category picker (all categories, no depth indent, required). Save enabled when title+category non-blank.

**Passage Picker (Editor):** `Remove from passage` + passages in current category. Changing category drops assignment if passage not in new category.

---

## Bookmarks

**App Bar:** Title `Bookmarks`; back → pop; Export icon (only if count > 0) → Export Format dialog (pseudo-paper `Bookmarked questions`).

**List:** Paged (50/page). Empty `No bookmarked questions`. Per row: tap → opens in Browse focused (reading list, not Editor). Content: question preview (3 lines, bodyMedium, images included, **no explanation**). Remove icon → idempotent delete.

**Export:** Same format dialog, exports all bookmarks.

---

## Search Screen

**App Bar:** Title `Search all papers`; back → pop.

**Search Field:** `Search text or tags`, single line, 300 ms debounce (whole `SearchRequest` debounced).

**Scope Chips (3):** `All` (question+tags+options+passage) / `Questions` (question+tags+passage) / `Options` (options only).

**Results:** Paged (30/page). `Searching…` / `No matches` / `N match(es)` (+ `+` if possibly more). Per hit: preview (2 lines) + paper title (primary). Tap → Browse focused.

**Admission Rules:** `searchableText` (flattens blocks, joins with space, strips markup; tables & formulas included). Prefilter key = longest alphanumeric run (len ≥ 2) escaped for SQL `LIKE`. Blank query → all questions (UI shows empty until non-blank). Cross-paper via `paperByCategoryId`; deleted papers/categories dropped.

---

## Import: Word Documents (.docx)

### Document Grammar (Line-Start Markers)

| Marker | Regex | Case | Required | Notes |
|--------|-------|------|----------|-------|
| **Question** | `^\d{1,7}\)` | exact | Yes | `1.)` at line start. |
| **Option** | `^\([a-z]\)` | exact | Yes | `(a)`..`(j)`; must start at `(a)`, consecutive without gaps. |
| **Answer** | `^Ans\.` | exact | Yes | `Ans. b` or `Ans. b, d` or `Ans. b and d`. |
| **Explanation** | `^Exp:` | exact | No | `Exp: <text>`. |
| **Passage** | `^(?i)Passage:` | **case-insensitive** | No | `Passage: <title>`; blank line = end-of-passage reset. |

Markers are line-anchored (`(?m)^`). DOCX writer prefixes such lines with a space
to escape them.

### Full Worked Example

```
1.) What is the capital of France?
(a) London
(b) Paris
(c) Berlin
Ans. b
Exp: Paris is the capital city of France.

Passage: Newton's Laws
A body remains at rest or in uniform motion unless acted upon by a force.

2.) Which law describes inertia?
(a) First
(b) Second
(c) Third
Ans. a
Exp: Newton's First Law.

Passage:
3.) What is F=ma?
(a) First Law
(b) Second Law
(c) Third Law
Ans. b
```

### Formatting Support

| Feature | Support | Details |
|---------|---------|---------|
| Bold/Italic/Underline/Strike | Yes | `<strong>`, `<em>`, `<u>`, `<del>` in HTML round-trip. |
| Subscript/Superscript | Yes | `<sub>`, `<sup>`. |
| Highlight | Yes | `<mark>`. |
| Math (OMML) | Yes | Converted to MathML (`<math>...</math>`); unsupported → linear text + warning. |
| Tables | Yes | Word tables → `TableElement`; nested flattened. |
| Embedded images | Yes | `word/media/` → `data:` URIs. |
| Remote image URLs | No | Left as `http(s)://` src; warning: `An image that is not embedded … was left out`. |
| VML (legacy Word art) | Yes | `urn:schemas-microsoft-com:vml` namespace. |
| Track Changes | Applied | Deletions applied; insertions unwrapped; warning emitted. |
| Hyperlinks | Unwrapped | Runs inside hyperlink kept as content. |
| Automatic numbering | Ignored | Question/option numbers must be literal text. |

### Hidden Payload (Round-Trip Fidelity)

App's own DOCX exports embed a **custom document property** (`mcqapp` in
`docProps/custom.xml`) with full `McqFileDto` JSON. On re-import:
1. Payload parsed and fingerprint-matched against visible markers (per-question
   canon: stem, options, answer indices, explanation, passage membership).
2. **If every visible question matches a payload question in order** (subsequence
   walk; payload-only questions allowed between), payload wins — full fidelity.
3. **If any mismatch** (edited in Word, Google Docs stripped payload, older
   version), payload ignored; visible markers used; image srcs grafted
   positionally.
4. Warning: `The document's hidden question data is out of date (the file was edited outside the app), so marks, difficulty and tags were restored from the visible text only.`

### Every Rejection Message

| Message | Trigger |
|---------|---------|
| `missing option (a). Expected format 'N.) question (a) .. (b) .. (c) .. (d) .. Ans. .. Exp: ..'.` | No `(a)` marker. |
| `missing option (a). The first marker found was (x), but options must start at (a).` | First option not `(a)`. |
| `option (x) appears more than once.` | Duplicate option letter. |
| `option (x) is not supported; use (a) to (j).` | Letter outside a–j. |
| `option (x) is missing; labels must run (a), (b), (c), (d) … without gaps, but found (abce).` | Gap in sequence. |
| `missing question stem` / `missing option (x)` / `missing answer` | Required field blank. |
| `answer names option (x), which this question does not have. It has (a) (b) (c).` | Answer letter not in options. |
| `answer '...' mixes letters with other text; write several answers as 'Ans. b, d' so each one can be matched to an option.` | Multiple tokens, not all single letters. |

---

## Import: JSON Reference

### Bare array

The simplest shape — e.g. `simple_questions.json`:

```json
[
  { "question": "Which planet is known as the Red Planet?",
    "options": ["Venus", "Mars", "Jupiter", "Mercury"] }
]
```

A bare array carries no structure, so it is wrapped for you: one paper
`Imported Questions`, one category `Uncategorized`, and no bookmarks, attempts or
schedules. Option ids are assigned `a`, `b`, `c`, … in order.

### Full Schema (`McqFileDto`)

```kotlin
@Serializable
data class McqFileDto(
    val version: Int = 1,
    val papers: List<PaperDto> = emptyList(),
    val bookmarks: List<String> = emptyList(),
    val attempts: List<AttemptDto> = emptyList(),
    val warnings: List<String> = emptyList(),
    val scheduling: Map<String, CardScheduleDto> = emptyMap(),
    val passages: List<PassageDto> = emptyList()
)
```

### Paper Schema (`PaperDto`)

| Field | Required | Default |
|-------|----------|---------|
| `id` | Yes | ephemeral `paper-import-<base36-ms>` |
| `title` | Yes | — |
| `description` | No | `""` |
| `durationMinutes` | No | `0` (untimed) |
| `negativeMarking` | No | `0.0` (sanitized ≥ 0) |
| `categories` | No | `[]` |
| `questions` | No | `[]` (folded into Uncategorized) |
| `passages` | No | `[]` |

### Category Schema (`CategoryDto`)

`id`, `title`, `parentId?`, `questions: List<QuestionDto>`.

### Passage Schema (`PassageDto`)

`id`, `title`, `elements: List<ContentElement>`, `image?`, `categoryId?`.

### Question Schema (`QuestionDto`)

| Field | Required | Default / Notes |
|-------|----------|-----------------|
| `id` | Yes | stable SHA-256 of text+options if absent |
| `text` | No | `""` (legacy; ignored when `elements` present) |
| `elements` | No | `[]` (rich content: text/image/table/math) |
| `image` | No | `null` (legacy scalar) |
| `options` | No | `[]` (List<OptionDto>) |
| `correctOptionIds` | No | `[]` (must reference existing option ids) |
| `explanation` | No | `""` |
| `explanationElements` | No | `[]` |
| `explanationImage` | No | `null` |
| `difficulty` | No | `"medium"` (case-insensitive; invalid → medium) |
| `marks` | No | `1.0` (≥ 0; else 1.0) |
| `tags` | No | `[]` (CSV on import; folded lowercase for search) |
| `passageId` | No | `null` |

### Option Schema (`OptionDto`)

`id` (required, a–j on DOCX import), `text`, `elements`, `image?`.

### Content Elements (Rich Content)

Wire format: `{"type": "...", "content": ...}`

| Type | Content | Notes |
|------|---------|-------|
| `text` | String | Inline HTML: `<b><i><u><del><sub><sup><mark><br>`; unknown tags literal. |
| `image` | String (src) | `data:image/...;base64,`, `http(s)://`, `content://`. |
| `table` | `List<List<String>>` | Rows of cells; inline markup preserved. |
| `math` | String (MathML) | `<math>...</math>` blocks; attributes & line breaks kept. |

### Attempt Schema (`AttemptDto`)

| Field | Notes |
|-------|-------|
| `paperId`, `title`, `totalQuestions`, `correctCount`, `wrongCount`, `skippedCount`, `score`, `maxScore`, `durationSeconds`, `finishedAt` (epoch ms) | Core attempt data |
| `testType?` | `RANDOM` \| `MISTAKES` \| `WEAKEST` \| …; null = legacy |
| `requestedCount`, `scopeCategoryIds` (CSV), `scopeKey` (`paper:<id>` \| `categories:<csv>`), `samplingVersion`, `seed`, `exclusionMode` (`NONE` \| `RECENT` \| `RECENT_SIMILAR`) | Selection metadata |
| `targetTopicIds` (CSV), `compositionJson`, `variant?` (panel/blueprint id) | Practice/blueprint data |
| `negativeMarking?` | Penalty rate applied; null on pre-v24 |
| `results` | List<AttemptResultDto> |

### Attempt Result Schema (`AttemptResultDto`)

`questionId`, `categoryTitle`, `text`, `optionsJson` (serialized options at attempt time), `correctOptionIds` (CSV), `selectedOptionIds` (CSV), `isCorrect`, `explanation`, `explanationImage?`, `dwellSeconds`.

### Card Schedule Schema (`CardScheduleDto`)

`ease`, `intervalDays`, `dueAt` (epoch ms), `reps`, `lapses`, `leech`, `lastReviewedAt`, `difficulty`, `stability`, `cardState` (`NEW`\|`LEARNING`\|`RELEARNING`\|`REVIEW`), `reviewCount`. Only written for studied cards (`reps > 0 || dueAt > 0`).

### Rich content

Where a plain string is accepted (`text`/`question`, `explanation`/`explain`/`reason`,
`options`), you can instead supply a **list of elements**. They take precedence over
the plain-string form and are what export writes:

```json
{
  "question_elements": [
    { "type": "text",  "content": "Which expression equals " },
    { "type": "math",  "content": "<math><mfrac>…</math>" },
    { "type": "image", "content": "<img src=\"data:image/png;base64,iVBORw0…\">" },
    { "type": "table", "content": [["Ruler", "Year"], ["Akbar", "1556"]] }
  ],
  "options_elements": {
    "a": [{ "type": "text", "content": "Akbar — 1556" }],
    "b": [{ "type": "text", "content": "Akbar — 1605" }]
  },
  "explanation_elements": [{ "type": "text", "content": "He acceded in 1556." }]
}
```

In `options_elements` the object **keys are the option ids**, which is what
`correctOptionIds` must then refer to. Only the `text`, `math`, `image` and `table`
types are read; every entry must be an element object, because a bare string is
dropped without warning.

### Complete Annotated Example

```json
{
  "version": 1,
  "papers": [
    {
      "id": "paper-physics-1",
      "title": "Physics Mechanics",
      "description": "First-year mechanics questions",
      "durationMinutes": 60,
      "negativeMarking": 0.33,
      "categories": [
        {
          "id": "cat-kinematics",
          "title": "Kinematics",
          "parentId": null,
          "questions": [
            {
              "id": "q-001",
              "text": "A car accelerates from rest at 2 m/s². How far in 5 s?",
              "elements": [{"type": "text", "content": "A car accelerates from rest at 2 m/s². How far in 5 s?"}],
              "image": null,
              "options": [
                {"id": "a", "text": "10 m", "elements": [{"type": "text", "content": "10 m"}], "image": null},
                {"id": "b", "text": "25 m", "elements": [{"type": "text", "content": "25 m"}], "image": null},
                {"id": "c", "text": "50 m", "elements": [{"type": "text", "content": "50 m"}], "image": null}
              ],
              "correctOptionIds": ["b"],
              "explanation": "s = ½at² = ½×2×25 = 25 m",
              "explanationElements": [{"type": "text", "content": "s = ½at² = ½×2×25 = 25 m"}],
              "explanationImage": null,
              "difficulty": "medium",
              "marks": 2.0,
              "tags": ["kinematics", "suvat"],
              "passageId": null
            }
          ]
        },
        {
          "id": "cat-dynamics",
          "title": "Dynamics",
          "parentId": "cat-kinematics",
          "questions": []
        }
      ],
      "questions": [],
      "passages": [
        {
          "id": "passage-1",
          "title": "Projectile Motion Context",
          "elements": [
            {"type": "text", "content": "A ball is thrown at 30° with 20 m/s."},
            {"type": "math", "content": "<math><mi>v</mi><mo>=</mo><mn>20</mn><mi>m</mi><mo>/</mo><mi>s</mi></math>"}
          ],
          "image": null,
          "categoryId": "cat-kinematics"
        }
      ]
    }
  ],
  "bookmarks": ["q-001"],
  "attempts": [
    {
      "paperId": "paper-physics-1",
      "title": "Physics Mechanics",
      "totalQuestions": 1,
      "correctCount": 1,
      "wrongCount": 0,
      "skippedCount": 0,
      "score": 2.0,
      "maxScore": 2.0,
      "durationSeconds": 45,
      "finishedAt": 1700000000000,
      "testType": "RANDOM",
      "requestedCount": 10,
      "scopeCategoryIds": "cat-kinematics",
      "scopeKey": "categories:cat-kinematics",
      "samplingVersion": 2,
      "seed": 42,
      "exclusionMode": "RECENT",
      "targetTopicIds": "",
      "compositionJson": "",
      "variant": null,
      "negativeMarking": 0.33,
      "results": [
        {
          "questionId": "q-001",
          "categoryTitle": "Kinematics",
          "text": "A car accelerates from rest at 2 m/s². How far in 5 s?",
          "optionsJson": "[{\"id\":\"a\",\"text\":\"10 m\"},{\"id\":\"b\",\"text\":\"25 m\"},{\"id\":\"c\",\"text\":\"50 m\"}]",
          "correctOptionIds": "b",
          "selectedOptionIds": "b",
          "isCorrect": true,
          "explanation": "s = ½at² = ½×2×25 = 25 m",
          "explanationImage": null,
          "dwellSeconds": 12
        }
      ]
    }
  ],
  "warnings": [],
  "scheduling": {
    "q-001": {
      "ease": 2.31,
      "intervalDays": 9,
      "dueAt": 1800000000000,
      "reps": 7,
      "lapses": 1,
      "leech": false,
      "lastReviewedAt": 1750000000000,
      "difficulty": 5.5,
      "stability": 21.0,
      "cardState": "REVIEW",
      "reviewCount": 7
    }
  },
  "passages": []
}
```

### Optional Fields & Defaults

| Field | Default | Notes |
|-------|---------|-------|
| `version` | `1` | Only version; future bumps this. |
| `PaperDto.description` | `""` | |
| `PaperDto.durationMinutes` | `0` | Untimed. |
| `PaperDto.negativeMarking` | `0.0` | Sanitized ≥ 0. |
| `QuestionDto.text` | `""` | Ignored when `elements` non-empty. |
| `QuestionDto.elements` | `[]` | Rich content takes precedence. |
| `QuestionDto.image` | `null` | Legacy scalar. |
| `QuestionDto.explanation` | `""` | |
| `QuestionDto.explanationElements` | `[]` | |
| `QuestionDto.explanationImage` | `null` | |
| `QuestionDto.difficulty` | `"medium"` | Case-insensitive; invalid → medium. |
| `QuestionDto.marks` | `1.0` | < 0 or non-finite → 1.0. |
| `QuestionDto.tags` | `[]` | CSV on import; folded lowercase for search. |
| `QuestionDto.passageId` | `null` | |
| `OptionDto.text` | `""` | Ignored when `elements` non-empty. |
| `OptionDto.elements` | `[]` | |
| `OptionDto.image` | `null` | |
| `AttemptDto.testType` | `null` | Null = legacy; never inferred as RANDOM. |
| `AttemptDto.negativeMarking` | `null` | Null on pre-v24. |
| `CardScheduleDto.*` | zeros/`false`/`"NEW"` | Only for studied cards. |

### Unknown Fields

**Treatment:** `ignoreUnknownKeys = true` on all JSON readers. Extra fields silently
dropped. **No validation warning for unknown keys.**

### Validation Warnings (Advisory, Non-Fatal)

Generated by `ImportWarnings.forQuestion()`, shown under **Warnings — imports fine,
but check these**:

| Warning | Condition |
|---------|-----------|
| `Blank text` | `elements` empty AND `text` blank |
| `No options` | `options.isEmpty()` |
| `Only one option` | `options.size == 1` |
| `No answer key` | `correctOptionIds.isEmpty()` |
| `Answer key references missing option: <id>` | `correctOptionIds` contains id not in `options` |
| `Worth 0 marks` | `marks == 0.0` |
| `Image is <N> KB` | Data URI payload > `LARGE_IMAGE_CHARS` (≈ 100 KB base64) |

Parser warnings (from `LegacyParser`) split into:
- **Skipped (red)** — content dropped: malformed questions, missing passages, unreadable images.
- **Notes (neutral)** — nothing dropped: equation fallback, remote-image warning, payload-stale notice.

### Import Preview: New / Changed / Duplicate

Classifies every incoming question by **content hash** (SHA-256 of text + option
texts + passage membership + images as stored):

| Bucket | Condition | User Action |
|--------|-----------|-------------|
| **New** | Hash not in DB, no same-id row with different content | Will be inserted |
| **Changed** | Same `id` exists but `ContentHash.nonHashedFieldsDiffer` (answer key, explanation, marks, difficulty, tags, images) | Will UPDATE; **schedule reset** if content changed |
| **Duplicate** | Hash matches existing (text+options identical) AND no answer/metadata change | **Skipped** |

**Dedupe rule:** Hash covers stem textContent, all options, explanation, image
positions. **Tables and MathML invisible to hash** — hash collision triggers full
`ContentHash.sameQuestionContent` (element-by-element). If content truly matches
→ duplicate; if only answer/metadata differs → Changed.

**Same-id remapping:** If a question `id` in the file belongs to a different
paper in DB, remapped to `"<paperId>-<originalId>"` to avoid FK cascade wipe.

### "Restored History" (Bookmarks & Attempts)

- **Bookmarks:** Restored by question `id` (harmless if missing). Count in
  `ImportReport.restoredBookmarks`.
- **Attempts:** Restored with **fresh attempt IDs**; results remapped. Dedupe
  key: `(paperId, finishedAt, score)` — same backup re-imported twice does not
  double history. `QuestionStats` recomputed. Count in
  `ImportReport.restoredAttempts`.
- **Review schedules:** From `McqFileDto.scheduling` (app's own exports) or
  explicit Anki import map. Applied **only to accepted questions** (not
  duplicates, not content-changed). Stored with stored `contentHash` so later
  re-import detects no change. Count in `ImportReport.restoredSchedules`.

### Import Size Limits & Chunking

| Limit | Value | Enforcement |
|-------|-------|-------------|
| Max import file size | **64 MB** | `MAX_IMPORT_BYTES` in `BoundedRead.kt`; stream-enforced (`copyBounded`/`boundedStream`). |
| Max manifest/settings part | **8 MB** | `MAX_PART_BYTES` in `McqBackupFormat`. |
| JSON nesting depth | **200** | `MAX_NESTING_DEPTH` in `LegacyParser`; streaming reader aborts deeper. |
| Export page size | **500** questions | `EXPORT_PAGE` in `Exporter`; one keyset page per batch. |
| Import batch size | **2000** questions | `IMPORT_BATCH_SIZE` in `LegacyParser`; streaming callback size. |
| Study row batch | **2000** rows | `PAGE` / `BATCH` in `BackupSource` / `BackupRestorer`. |

**Progress:** Logger emits one line per 500 questions + heap MB. Preview shows
`LinearProgressIndicator` when truncated, fraction = `importedQuestions /
totalQuestionCount`.

**Cancel:** `CancellationException` propagates from streaming parse — whole import
in DB transaction → atomic rollback. User sees `Import cancelled`.

**Failure messages:**
- `That file is over the 64 MB import limit (at least X MB). Import the bank in parts, or drop the pictures — they are stored inside the file and add about a third to its size.`
- `Could not parse the file: <exception>`
- `No papers found`
- `Not a readable zip file: <reason>`
- Anki: `This is a zstd-compressed .colpkg, which needs Anki 2.1.50+ to unpack. Export the deck as .apkg from Anki and import that instead.`

---

## Import Semantics & Nuances

1. **JSON import ignores unknown keys silently** — foreign files can carry
   arbitrary extra fields.
2. **Content hash excludes tables and MathML formulas** — two questions differing
   only in a table/formula share a hash; full `sameQuestionContent` comparison
   catches this but only on hash collision.
3. **DOCX import prefers hidden payload** when fingerprint matches visible text;
   Google Docs strips it → falls back to markers with warning.
4. **Backup always REPLACE** (wipe + replay), no merge option; schedules only
   restored if contentHash matches (content unchanged).
5. **Anki export uses schema 11 (legacy)** deliberately — no `meta` entry → Anki
   upgrades on import; maximum compatibility.
6. **Remote image URLs in JSON/DOCX/Anki are kept in markup but CSP blocks
   loading** in HTML exports; privacy setting controls display in-app.
7. **Import preview shows "Skipped" (red) vs "Notes" (neutral)** — advisory
   warnings (equation fallback, payload stale) don't count as dropped content.
8. **Max import 64 MB counts base64 image payload (~33% overhead)** — a "small"
   bank with many pictures can trip it.
9. **Streaming import/export never holds full bank in memory** — one
   paper/category/batch at a time; temp files for media (`MediaSpool`,
   `AnkiTempFiles`).
10. **Legacy JSON shapes accepted:** bare array `[{...}]`, object with `papers`,
    object with `questions` (DOCX wrapper). Per-field fallbacks: `question`/`text`,
    `options`, `answer`/`correct`/`correctIndex`, `explanation`/`explain`/`reason`,
    `image`/`imageUrl`, `points`/`weight`, `tags`. Deterministic SHA-256 IDs.
    Option letters by position. Answer resolution chain.

---

## Editor Prev/Next Queue

When opening Editor from Browse (tap Edit), an `EditorSession.Cursor` is seeded
with the current Browse filter. It holds `size`, `index`, `prevId`, `nextId`,
`hasQueue`. `getIdAtOffset` calls `repository.browseFilteredIdAt(offset, scope)`
— **streams from current filter without materialising IDs** (works for 250k
questions).

**Navigation:** Prev/Next buttons in app bar. **Never gated on form validity** —
only blocks if dirty AND invalid. If dirty & valid → auto-saves (`Saved` toast),
then navigates. Back button **does not** guard unsaved changes (dirty state lost).

---

## Export Formats (Per Paper, Per Category, Bookmarks)

All single-paper exports share the same `PaperStream` walk (database-backed, one
category at a time). File naming: `baseName(title) + suffix + extension`,
`baseName` = title sanitized (remove `\/:*?"<>|`, collapse spaces to `-`, trim
to 80 chars, fallback `paper`).

**Format dialog** (Library paper, Browse selection, Bookmarks): choose a format
row, then one action — **Save to file** or **Share** — or **Cancel**. There is
no separate Save/Share mode chip: the action button is the choice, so sharing
or saving is one tap after picking the format.

### 1. JSON Inline (`ExportFormat.JSON_INLINE`)
- **MIME:** `application/json` | **Ext:** `.json` | **File:** `<base>.json`
- **Contains:** Full `McqFileDto` envelope for one paper: `{version, papers:[paper], passages:[...]}`.
- **Images:** Inline as `data:` URIs in `image` fields and `ImageElement.src`.
- **Re-importable:** Yes — identical to import format.
- **Lost:** Nothing (full fidelity).
- **Writer:** `PaperJsonWriter.writePaperJson()` streaming.

### 2. ZIP (`ExportFormat.ZIP`)
- **MIME:** `application/zip` | **Ext:** `.zip` | **File:** `<base>.zip`
- **Contains:** `paper.json` (same JSON, image srcs rewritten to relative paths) + `images/img001.png`…
- **Images:** Data URIs decoded once, spooled to temp (`MediaSpool`), written as `images/imgNNN.<ext>`; remote URLs left untouched in JSON.
- **Re-importable:** Yes — JSON reader resolves relative `images/...` paths.
- **Lost:** Nothing.
- **Writer:** `ZipPaperWriter.paperToZip()`.

### 3. HTML (Answers Shown) (`ExportFormat.HTML`)
- **MIME:** `text/html` | **Ext:** `.html` | **File:** `<base>.html`
- **Contains:** Self-contained HTML: paper title, description, meta (duration, negative marking, question count), categories as `<h2>`, questions numbered, options with ✓/○, correct answers & explanations visible.
- **Images:** Data URIs embedded; remote URLs kept in markup but **CSP blocks loading** (`img-src data:`).
- **Math:** MathML re-emitted through sanitizer.
- **Tables:** `<table border="1">`.
- **Quiz mode:** No (see 4).
- **Writer:** `HtmlPaperWriter.writeHtml()`.

### 4. HTML Quiz Mode (`ExportFormat.HTML_QUIZ`)
- **MIME:** `text/html` | **Ext:** `.html` | **File:** `<base>-quiz.html`
- **Contains:** Same as HTML but correct answers & explanations **hidden**; "Show answer" button per question; "Show all answers" / "Hide all answers" at top.
- **CSP:** `script-src 'unsafe-inline'` for toggle JS.
- **Writer:** `HtmlPaperWriter.writeQuizHtml()`.

### 5. PDF (Answers Inline) (`ExportFormat.PDF`)
- **MIME:** `application/pdf` | **Ext:** `.pdf` | **File:** `<base>.pdf`
- **Contains:** A4 (595×842 pt), 40 pt margins; paper title (20pt bold), meta (10pt gray), categories (14pt bold), questions flowed with `StaticLayout`; options indented 12pt with ✓/○; correct answers & explanations inline (green).
- **Two-column:** Optional (`twoColumnPdf=true`): left column → right column → next page. Read at export time (Settings → Backup & data → `Two-column layout`).
- **Images:** Data URIs decoded, subsampled to `MAX_EXPORT_DIMENSION`; unreadable → `[unreadable image]`; remote URLs → `[image: <url>]`.
- **Tables:** Real bordered grids, auto-fit columns, paginated by row.
- **Math:** MathML → canvas via `MathSpan` (TeX annotation preferred, else tag-stripped linear).
- **Oversized blocks:** Banded across pages/columns (clipped draw).
- **Writer:** `PdfPaperWriter.writePdf(answersAtEnd=false)`.

### 6. PDF Answer Key (`ExportFormat.PDF_ANSWER_KEY`)
- **MIME:** `application/pdf` | **Ext:** `.pdf` | **File:** `<base>-answer-key.pdf`
- **Contains:** Questions **without** correct marks or explanations; then **Answer Key** section at end listing only questions with answer/explanation: `Q7. B` or `Q7. —` + explanation.
- **Writer:** `PdfPaperWriter.writePdf(answersAtEnd=true)`.

### 7. Anki `.apkg` (`ExportFormat.APKG`)
- **MIME:** `application/octet-stream` | **Ext:** `.apkg` | **File:** `<base>.apkg`
- **Contains:** Anki schema-11 package: `collection.anki2` (SQLite), `media` (manifest), `0,1,2...` (media files).
- **Note type:** Basic (3 fields: **Front**, **Back**, **mcqapp**).
- **Front:** Passage (if any) + question + options; `Select all that apply.` for multi-correct.
- **Back:** ✓-marked correct options + explanation.
- **mcqapp field:** JSON payload `{elements, options, correct, explanation, difficulty, marks, tags, passageId, passageTitle, passageElements, passageImage}` — exact round-trip.
- **Deck hierarchy:** `Paper::Category::Subcategory` (separator `::`); titles containing `::` rewritten to `: `.
- **Tags:** `mcqapp mcqapp-difficulty-<label> <user-tags...>` (space-delimited, leading/trailing space).
- **Scheduling:** If `schedulingFor` provides `CardScheduleDto`, written as Anki `cards` rows (ivl, factor, reps, lapses, due, queue, type); else new cards (`queue=0, due=position`).
- **Media:** Data URIs → `word/media` entries; manifest maps numeric zip name → original filename (`mcqapp-0.png`); Anki rewrites `<img src="...">` by filename.
- **GUID:** Stable SHA-1 of `"paperId/questionId"` → base91; re-import updates notes in place.
- **Schema:** **11** (no `meta` entry → Anki treats as `Legacy1`, upgrades on import).
- **Writer:** `AnkiPackageWriter.writeTo()` (streaming: temp SQLite → zip in chunks).
- **Round-trip:** Importing the `.apkg` back via `AnkiPackageReader` recovers **options, correct answers, difficulty, marks, tags, passages** from the `mcqapp` payload. Foreign decks (no payload) parsed from Front/Back HTML (✓/✗ markers, `Answer:`, `Exp:`).

### 8. Word `.docx` (`ExportFormat.DOCX`)
- **MIME:** `application/vnd.openxmlformats-officedocument.wordprocessingml.document`
- **Ext:** `.docx` | **File:** `<base>.docx`
- **Contains:** OOXML: `word/document.xml`, `word/styles.xml`, `word/media/`, `docProps/custom.xml` (hidden payload), `[Content_Types].xml`, `_rels/.rels`.
- **Structure:** Paper title = Heading 1; categories = Heading (depth+1); questions = marker grammar (`N.)`, `(a)`, `Ans.`, `Exp:`, `Passage:`).
- **Math:** Native OMML (`m:oMath`) — `mathMlToOmml()`.
- **Images:** `word/media/imageN.<ext>`; relationships in `word/_rels/document.xml.rels`.
- **Tables:** Word tables (`w:tbl`) with borders, auto-fit.
- **Inline formatting:** Runs with `w:b`, `w:i`, `w:u`, `w:strike`, `w:vertAlign`, `w:highlight`.
- **Marker escaping:** Lines starting with any marker prefixed with one space.
- **Option limit:** **10** (`(a)`..`(j)`); excess dropped with warning.
- **Hidden payload:** Full `McqFileDto` in `docProps/custom.xml` property `mcqapp` (images stripped to empty strings — positions kept for graft-back).
- **Re-importable:** Yes — parser prefers payload when fresh; falls back to visible markers.
- **Lost:** Marks, difficulty, tags, correct answers **only in visible markers** (payload carries them).
- **Writer:** `DocxPaperWriter.writeDocx()` (streaming: body → payload → media).

### 9. CSV Report (Result Screen → Share → CSV)
- **Trigger:** Share → "CSV report" from result screen.
- **Contains:** Header comment block (`# mcq report export`, `# attemptId=...`, `# paper=...`, `# type=...`, `# family=...`, `# exportedAt=...`, `# guessingCorrected=...%`), then RFC 4180 columns: `number,questionId,category,result,dwellSeconds,tags`.
- **Result values:** `"Correct"`, `"Wrong"`, `"Skipped"`.

---

## Backup & Restore

### Full Backup (`.zip`)

**File name:** `mcqapp-backup-<timestamp>.zip` (timestamp = `System.currentTimeMillis()`).

**Archive layout (4 entries, written in order):**

| Entry | Content | Size Cap |
|-------|---------|----------|
| `manifest.json` | `Manifest` (format, version, dbVersion, appVersion, exportedAt, counts) | 8 MB |
| `content.json` | Full `McqFileDto` (all papers, questions, passages, bookmarks, attempts) | Streamed |
| `study.json` | `StudyFile` (card_state rows **with contentHash**, review_log, paperCreated, panels, blueprints, inProgressTest) | Streamed |
| `settings.json` | `SettingsFile` (Map<String, PreferenceEntry<type, value>>) | 8 MB |

**Images:** Stay inline as data URIs in `content.json` (extracting would break content-hash-keyed schedules).

### Manifest Schema

```json
{
  "format": "mcqapp-backup",
  "version": 1,
  "dbVersion": 42,
  "appVersion": "1.2.3",
  "exportedAt": 1700000000000,
  "papers": 3,
  "questions": 1250,
  "attempts": 17,
  "reviews": 8432
}
```

### Study File Schema

```json
{
  "cardState": [
    {"paperId":"...","questionId":"...","ease":2.5,"intervalDays":10,"dueAt":1800000000000,"reps":5,"lapses":0,"leech":false,"lastReviewedAt":1750000000000,"contentHash":"abc123...","difficulty":5.2,"stability":18.7,"cardState":"REVIEW","reviewCount":5}
  ],
  "reviewLog": [
    {"id":1750000000123,"paperId":"...","questionId":"...","rating":3,"reviewedAt":1750000000123,"elapsedSeconds":12,"stateBefore":"REVIEW","stabilityAfter":18.7,"difficultyAfter":5.2,"intervalDaysAfter":10,"wasGuess":false,"wasCorrect":true}
  ],
  "paperCreated": [
    {"id":"paper-1","createdAt":1690000000000,"sortOrder":-1}
  ],
  "panels": [
    {"id":1,"paperId":"paper-1","name":"Half","createdAt":1700000000500,"questionIds":["q-2","q-1"]}
  ],
  "blueprints": [
    {"id":1,"paperId":"paper-1","name":"UPSC","createdAt":1700000001000,"sections":[{"categoryId":"cat-1","parts":5,"children":[]}]}
  ],
  "inProgressTest": "{\"paperId\":\"paper-1\",\"selections\":[\"q-1\"],...}"  // or null
}
```

### Export Flow (Settings → Backup)

1. `BackupSource.collect(appVersion, inProgressTest)` in **one transaction**:
   - `Exporter.exportAllDto(withScheduling=false)` → `content.json` (scheduling omitted; rides in `study.json`)
   - `cardStateDao.getAll()`, `reviewLogDao.getAll()`, `paperDao.getAll()` → `study.json`
   - Panels & blueprints read (small, not paged)
   - Manifest counts from same snapshot
2. `McqBackupFormat.writeTo()` streams each entry to zip:
   - Manifest & settings: held whole (≤ 8 MB), encoded once
   - Content: `Exporter.exportAllTo(writer, withScheduling=false)` streams paper by paper
   - Study: `writeStudyRows()` emits batches of 2000
3. Zip bytes → Share sheet (Save / Share).

### Restore Flow (Settings → Restore Backup)

**Entry point:** `BackupRestorer.restoreStreaming(inputStream, ...)` (streaming) or `restore(backup, ...)` (decoded).

**Atomicity:** Wipe + restore share **one DB transaction** (`db.withTransaction`). Failure at any point rolls back — library unchanged.

**Steps (streaming):**
1. `McqBackupFormat.readStreaming()` unzips, validates manifest (`format == "mcqapp-backup"`, `version <= 1`), enforces **content before study** (else `This backup holds its study rows before its content — mcqapp cannot read a backup re-packed in that order.`).
2. `wipeAllTables()` — deletes **every user table** (excludes `sqlite_%`, `android_%`, `room_%`); FK CASCADE handles children.
3. `StreamingImporter.importInTransaction()` → replays `content.json` through importer (same dedupe, same ID remapping).
4. `cardState` rows upserted in batches of 2000 (with original `contentHash`).
5. `reviewLog` rows inserted in batches of 2000.
6. `paperCreated` → `paperDao.updateCreatedAt()` + `updateSortOrder()` (sortOrder null on pre-v20 backups → left alone).
7. Panels & blueprints restored with **original IDs** (attempts reference them by `variant`).
8. Transaction commits.
9. Caller applies `settings` (preferences) + `inProgressTest` (resume snapshot) — failures reported as **partial restore** (library itself succeeded).

### Dialogs & Exact Copy

| Dialog | Title | Message | Buttons |
|--------|-------|---------|---------|
| **Restore confirmation** | (system alert) | `This wipes the current library and every setting, then restores the backup. Continue?` | `Replace everything` / Cancel |
| **Restore success** | `Import complete` | `Restored X papers, Y questions, Z attempts, N schedules, M reviews.` + newer-app note + partial note | OK |
| **Restore failure** | `Restore failed` | Exception message | OK |
| **Backup missing part** | `Not an mcqapp backup` | `This backup is missing <part> — it may be truncated.` | OK |
| **Wrong format** | `Not an mcqapp backup` | `Not an mcqapp backup (found format 'X').` | OK |
| **Newer app version** | `Not an mcqapp backup` | `This backup was made by a newer app version (format X vs 1 here). Update the app and try again.` | OK |
| **Repacked order** | `Not an mcqapp backup` | `This backup holds its study rows before its content — mcqapp cannot read a backup re-packed in that order.` | OK |

### Merge vs Replace Semantics

**Always REPLACE (wipe + replay).** No merge option. The backup is a **point-in-time
snapshot**; restoring it must reproduce that exact state.

**What happens to settings/themes/schedules on restore:**
- **Settings** (`settings.json`): Applied via caller's `applySettings()` — all
  preferences restored (theme, font scale, scheduling params, privacy, haptics).
  Failure → `The library itself came back; settings were not restored (<error>)`.
- **Themes** (custom): Carried in `SettingsFile` as JSON preference; re-imported with settings.
- **Schedules** (`card_state`): Restored **verbatim with original `contentHash`** — a
  card's schedule survives only if its question's content hash matches (content
  unchanged). If content changed on import, schedule reset (logged:
  `Content changed for id=..., schedule reset`).
- **Review history** (`review_log`): Restored verbatim (ids, timestamps, ratings).
- **Panels & Blueprints**: Restored with **original IDs** (attempt `variant` references them).
- **In-progress test**: Resume snapshot restored via `applyInProgress()`.

---

## Settings — Every Control Explained

Route: `settings` (drawer row **Settings**). Screen: vertical scroll, 16 dp
padding, 12 dp between sections. Five collapsible `Card` sections, **all
expanded by default** (`rememberSaveable { mutableStateOf(true) }`). Header row
= chevron + title, `toggleable(Role.Button)` (TalkBack exposes expanded state).

### Section Order & Test Tags

| # | Title (exact) | Test tag |
|---|---------------|----------|
| 1 | `Appearance` | `settings-section-appearance` |
| 2 | `Tests` | `settings-section-tests` |
| 3 | `Study / Spaced repetition` | `settings-section-study` |
| 4 | `Backup & data` | `settings-section-backup-data` |
| 5 | `About` | `settings-section-about` |

---

### 1. Appearance

#### 1.1 Theme (Radio Group)
**Key:** `theme_mode` | **Default:** `system`

| Option | Stored value | Meaning |
|--------|--------------|---------|
| `System default` | `system` | Follows `isSystemInDarkTheme()` |
| `Light` | `light` | Forces light mode |
| `Dark` | `dark` | Forces dark mode |

Read by `McqTheme` in `MainActivity` (whole app, immediately — no restart).

#### 1.2 Color Theme (Picker)
**Key:** `theme_palette` | **Default:** `material` | **Tag:** `theme-picker`

- `ExposedDropdownMenuBox` with read-only `OutlinedTextField`, label `Color theme`, 5-dot swatch strip.
- Menu: **15 built-in themes first**, then imported custom themes (stored order).
- Each item: name + summary, swatch strip, check when selected.
- Below field: one line = selected theme's summary, or `Imported` for custom.
- **`Import theme…`** (tag `theme-import`) → `OpenDocument` (JSON/text/octet-stream).
- **`Remove this theme`** (conditional: only when selected theme is custom) → confirm dialog `Remove this theme` / theme name / `Delete` / `Cancel`. On delete, falls back to `material`.
- Helper: `Pick a theme .json file. Imported themes are added to the list and kept in backups.`

**Built-in themes (exact names & summaries):**

| Name | Summary |
|------|---------|
| `Material` | `Modern, colorful` |
| `Nord` | `Cool blue, minimal` |
| `Dracula` | `Dark purple, vibrant` |
| `Tokyo Night` | `Dark blue, neon accents` |
| `Catppuccin` | `Pastel, modern` |
| `Gruvbox` | `Retro, warm` |
| `One Dark Pro` | `Balanced dark` |
| `Monokai` | `Colorful, high contrast` |
| `Solarized` | `Carefully balanced colors` |
| `Everforest` | `Natural green` |
| `Rosé Pine` | `Muted, elegant` |
| `Ayu` | `Clean, warm` |
| `Night Owl` | `Deep blue` |
| `Kanagawa` | `Japanese-inspired` |
| `GitHub` | `Clean, familiar` |

**Import rules:** max 64 KB file, max 20 stored themes, same-name (case-insensitive) replaces, successful import selects the theme, no success toast, failure dialogs with exact messages (not a theme file, name 1–40 chars, control chars, 20 max, too large, could not open, hex colour format). JSON shape: `name`, `primary`, `secondary`, `tertiary`, `error`, `light{background,surface,surfaceVariant,onBackground}`, `dark{same}`.

#### 1.3 Text Size (Radio Group)
**Key:** `font_scale` | **Default:** `1.0` (`Default`) | **Snap:** `FontScale.coerce()` to nearest option.

| Option | Value |
|--------|-------|
| `Small` | 0.85 |
| `Default` | 1.0 |
| `Large` | 1.15 |
| `Extra large` | 1.3 |

**Effect:** `McqNavHost` provides `LocalDensity` with `fontScale` at nav root → **every sp-sized text in the app scales immediately**, no restart. Preview line always shown: `Preview: the quick brown fox jumps over 13 lazy dogs.`

#### 1.4 Privacy (Switch)
**Key:** `load_remote_images` | **Default:** `false` (off) | **Tag:** `setting-load-remote-images`

| Field | Text |
|-------|------|
| Label | `Load remote images` |
| Help | `When off, pictures from the web stay hidden so whoever authored the bank can't see when you open a question. Pictures saved inside the bank always show.` |

**Effect:** `QuestionImage` reads `LocalLoadRemoteImages`. When off, remote `http(s)` images render placeholder `Remote image hidden. Turn on Load remote images in Settings.` Data URIs unaffected. Immediate; open screens recompose.

#### 1.5 Feedback (Switch)
**Key:** `haptics_enabled` | **Default:** `true` (on) | **Tag:** `setting-haptics`

| Field | Text |
|-------|------|
| Label | `Haptic feedback` |
| Help | `Vibrate briefly when you select an answer, reveal it or grade a card.` |

**Effect:** `MainActivity` provides `LocalHapticsEnabled`; `Haptics` (Tick/ToggleOn/ToggleOff/Confirm/Reject) no-op when off. Switching on fires one `ToggleOn` sample immediately (bypasses app gate; platform touch-feedback still applies). No `VIBRATE` permission.

---

### 2. Tests

Six switch rows (all default **off** except #6), 8 dp spacing, footnote under group.

| # | Label | Help | Default | Key | Tag |
|---|-------|------|---------|-----|-----|
| 1 | `Shuffle question order` | `Present questions in random order each attempt` | off | `shuffle_questions` | `setting-shuffle-questions` |
| 2 | `Shuffle options` | `Present answer options in random order` | off | `shuffle_options` | `setting-shuffle-options` |
| 3 | `Practice mode` | `Show correct answers and explanations instantly` | off | `practice_mode` | `setting-practice-mode` |
| 4 | `Strict exam mode` | `Hides answers, flags and the question palette` | off | `strict_mode` | `setting-strict-mode` |
| 5 | `Auto-advance` | `Move on as soon as you choose an answer, in single-answer tests. Practice mode stays on the question so you can read the explanation.` | off | `auto_advance` | `setting-auto-advance` |
| 6 | `Avoid recently tested questions` | `A Random, Balanced, Easiest or Blueprint test skips questions asked in your last three comparable tests of this length, when enough others remain. Such a test is a fresh sample, compared only with another fresh sample.` | **on** | `avoid_recent_questions` | `setting-avoid-recent` |

Footnote: `Review always shows the order you were given. Scoring is unaffected: answers are matched by option, not position.`

**Enforcement / Future-only:**

- Shuffle question/order / options: read once per test session (`TestViewModel.load()`) and per study sitting (`StudyViewModel.optionShuffleEnabled`). Passage blocks move together. ⇒ **next attempt/sitting only**.
- Practice mode: read at test start; `ExamMode` mediates (`canReveal = !practice && !strict`, `effectivePractice = practice && !strict`). **Strict overrides practice**. ⇒ next attempt only.
- Strict exam mode: read at test start; `canFlag`, `canOpenPalette` = `!strict`; reveal blocked. ⇒ next attempt only.
- Auto-advance: read at test start; `AutoAdvance.nextIndex` called from `toggleOption` — advances **on selection**, single-answer only, not on last question, guess still advances. ⇒ next attempt only.
- Avoid recently tested: `LibraryViewModel.avoidRecentQuestions` feeds sheet; when on and type supports fresh sample, plan uses `ExclusionMode.RECENT_3`. Sheet prints fresh-sample note and blueprint warning. ⇒ tests started after change; started attempt records rule used.

---

### 3. Study / Spaced Repetition

**Body order:** Heading `Spaced repetition` + `Reset` (right) → Description paragraph → `Simplified Anki` switch → `Study` label → `Target retention` slider → `Optimize FSRS parameters` block → `Ranking mode` chips → `Introduce new cards` switch → `Minimum easy answer threshold` / `Hard answer threshold` number rows → Response-time footnote → `Advanced Anki settings` (collapsible, collapsed by default) → `Study day` group (2 rows) + `Lapses` group (2 rows).

#### 3.1 Simplified Anki
**Key:** `simplified_study` | **Default:** **on** (only setting in section defaulting on) | **Tag:** `setting-simplified-anki`

| Field | Text |
|-------|------|
| Label | `Simplified Anki` |
| Help | `Grade study cards automatically from your answers instead of picking Again, Hard, Good or Easy after each card.` |

**Effect:** `StudyViewModel.load()` snapshots into `state.simplified` per session. When on, `check()` calls `Study.autoGrade(...)` (exact match + not guessed → `Good`; exact + guessed → `Again`; wrong → `Again`; empty → `Again`). Uses the absolute dwell cuts: `dwell < fastSeconds` → `Easy`; `dwell >= slowSeconds` → `Hard`; else `Good`. Nothing is added for reading time. Scheduler underneath unchanged.

#### 3.2 Target Retention
**Key:** `fsrs_desired_retention` | **Default:** `0.9` | **Slider:** 0.75–0.97, 21 steps (0.01 apart). **Commit:** one write on drag end (`onValueChangeFinished`). The percentage is shown above the slider and follows the thumb while dragging. Help: `How reliably you want to recall cards when they come due. Higher asks for shorter intervals and more reviews.` Read per pull/grade ⇒ next review onward; already-scheduled dates untouched.

#### 3.3 Optimize FSRS Parameters
Static text + help `Fits the scheduling weights to your own review history, the way Anki's Optimize does. Unlocks after 1000 reviews.` (`FsrsOptimizer.MIN_REVIEWS = 1000`).

Button `Optimize FSRS parameters`:
- Disabled when `fsrsReviewCount < 1000` or fit running.
- Running: label `Optimizing…` → `Optimizing… X/Y` + circular progress.
- Under threshold: `Reviews logged: X of 1000 needed.`
- Success: `Fitted on X review points from Y cards. Log loss vs your current weights: A → B.` (loss `%.4f`, `Locale.US`) + `Restore defaults` / `OK`.
  - `Restore defaults` → confirmation dialog, then `repository.restoreFsrsDefaults()` (drops fitted 21 weights for official set), clears state.
  - `OK` → dismiss message.
- Failure: error-coloured `Optimization failed: <msg>` + `OK` (clears state; nothing persisted).
- Cancelling/leaving Settings cancels silently.
- Weights persisted even when re-fit loss is worse (Anki parity); message makes regression visible.
- Key: `fsrs_parameters` (CSV of 21 doubles) via `FsrsTuner`.

#### 3.4 Ranking Mode
**Key:** `study_mode` | **Default:** `MEMORY_FIRST` | Chips: `Memory first` / `Explore new cards`. Help: `Memory first clears what is due before showing anything new. Explore surfaces a new card every few reviews.` (`Study.EXPLORE_NEW_EVERY = 3`). Read per pull ⇒ next review onward.

#### 3.5 Introduce New Cards
**Key:** `introduce_new_cards` | **Default:** `true` | **Tag:** `setting-introduce-new-cards`

Help: `Switch off for review-only sessions. Many new cards increase future review load.` Effect: `StudyStore.getNextStudyCard`/`getStudyQueue` read it per pull (off ⇒ no NEW pool). `StudyViewModel` snapshots into `state.introduceNewCards` for empty-state hint.

#### Grade on Correctness Only (switch)
**Key:** `correctness_only` | **Default:** off | **Control:** switch (tag `setting-grade-correctness-only`).

On: `Study.inferGrade` returns `Good` for every correct, unguessed answer and `Again` for wrong/guess/skip; the cuts are not read. The question clock keeps running and `review_log.elapsedSeconds` is still written, so the dwell history stays analysable. The switch also starts a new grading version (`reason = rule`, `grading_versions.correctnessOnly = 1`), so rows graded with the clock are never pooled with rows graded without it. Both threshold fields below disappear while it is on; `Reset` turns it off.

#### 3.6 Minimum Easy Answer Threshold
**Key:** `anki_fast_seconds` | **Default:** `6` s | **Control:** stepper (`−`/`+`) + 88 dp field (decimal keyboard, suffix `s`). **Range:** 1–3600, step 1. **Domain clamp:** `coerceIn(1, 3600)`. Hidden while `Grade on correctness only` is on (the value is kept as the fallback rule).

Absolute: a correct answer faster than this is `Easy` and nothing is added for reading the question. A cut pair stored by a pre-v27 build is ignored until a cut is saved under this rule (`grading_rules_version`).

#### 3.7 Hard Answer Threshold
**Key:** `anki_slow_seconds` | **Default:** `60` s | Same stepper/field. **Cross-field rule:** `value >= fastSeconds`. Validation: typing outside range or violating cross-field rule → field error, never commits, both steppers disabled.

**Footnote (always shown):**
`The response-time thresholds drive Simplified study's automatic grading. With Simplified study off you grade every card yourself.`

#### 3.8 Advanced Anki Settings (Collapsible, Collapsed by Default)
**Tag:** `advanced-anki-settings`

**Study day group:**
| Label | Help | Default | Range/Step | Suffix | Key |
|-------|------|---------|------------|--------|-----|
| `Next day starts at` | `Hour of the day a new study day begins, on this device's clock` | 4 h | 0–23, step 1 | `h` | `anki_day_start_hour` |
| `Maximum interval` | `Longest interval any review can get` | 36500 d | 1–36500, step 1 | `d` | `anki_max_interval` |

**Lapses group:**
| Label | Help | Default | Range/Step | Suffix | Key |
|-------|------|---------|------------|--------|-----|
| `Relearning delay` | `How long until a failed card returns` | 10 m (600000 ms) | 1–1440, step 0.5 | `m` | `anki_relearn_ms` |
| `Leech threshold` | `Failures before a card is flagged tricky` | 8 | 1–100, step 1 | — | `anki_leech_threshold` |

#### 3.9 Reset (Button)
**Label:** `Reset` (TextButton, right of `Spaced repetition` heading). Asks for confirmation because it resets more than the schedule knobs. `Restore defaults` under Optimize also asks before discarding fitted weights.

Does: `repository.resetSchedulerConfig()` — rewrites **pacing knobs only**: max interval, relearn delay, leech threshold, day-start hour, easy threshold, hard threshold, target retention, ranking mode, introduce-new-cards. **Deliberately does NOT touch fitted FSRS weights** (`fsrs_parameters`); `Restore defaults` in optimizer block is the only way to drop those.

Data-flow: `SettingsViewModel.updateSchedulerConfig(config)` sanitises, then re-reads stored config and copies its `fsrsParameters` back in before writing, so a stale snapshot can never revert a fresh fit.

---

### 4. Backup & Data

**Body order:** Heading `Data` → 5 full-width `Button`s and `Export Logs` (all `enabled = !busy`) interleaved with 3 description lines → Heading `Storage` + `Refresh` → Heading `PDF export` + 2 switches.

#### 4.1 Buttons

| # | Label | Enabled | Action | System Dialog / Result |
|---|-------|---------|--------|------------------------|
| 1 | `Export all data (JSON)` | `!busy` | `CreateDocument("application/json")`, name `mcq-export.json` → streams `Exporter.exportAllTo` (includes papers, categories, questions, passages, bookmarks, attempts, **and** `scheduling` map) | System save dialog; success **silent** |
| 2 | `Share all data (JSON)` | `!busy` | Renders JSON to `cacheDir/shared/mcq-export.json`, then `ACTION_SEND` chooser `Share file` | Chooser |
| 3 | `Backup (zip)` | `!busy` | `CreateDocument("application/zip")`, name `mcqapp-backup.zip` → full zip: `manifest` (dbVersion, appVersion, exportedAt, counts), `content` (bank JSON without scheduling), `study.json` (card states, review log, paper createdAt+sortOrder, in-progress test, panels, blueprints), `settings.json` (all DataStore prefs except `in_progress_test`) | System save dialog; success **silent** |
| 4 | `Share backup (zip)` | `!busy` | Same zip to `cacheDir/shared/mcqapp-backup.zip`, then `Share file` chooser | Chooser |
| 5 | `Restore backup` | `!busy` | `OpenDocument("application/zip", "application/octet-stream")` → **confirmation dialog first** | See below |
| 6 | `Export Logs` | `!busy` | Shares app's log file via `FileProvider` + `ACTION_SEND` (`text/plain`), chooser title `Export Logs` | See failures |

**Restore Confirmation Dialog:**
- Title: `Restore backup`
- Body: `This wipes the current library and every setting, then restores the backup. Continue?`
- Confirm: `Replace everything` | Dismiss: `Cancel`

**Restore Execution + Feedback:**
- One atomic DB transaction (wipe by schema enumeration, then streamed import); settings applied after DB commit — settings failure = partial restore.
- Pre-restore value of `Refresh answers on re-import` used during import.
- Success dialog: title `Restore backup`, body = report string:
  `Restored <N> papers, <M> questions[, <A> attempts], <S> schedules, <R> reviews.`
  Optionally + ` Note: the backup was taken on a newer app (database v<X>, v<Y> here); its study rows may use fields this version ignores.`
  Optionally + ` The library itself came back; settings were not restored (<msg>) and/or the in-progress test was not restored (<msg>).`
- Storage report refreshed after success.
- Failures: over 256 MB cap → `That backup is over the 256 MB restore limit — it may be corrupt.`; else `Restore failed: <exception>`; unreadable → `Restore failed: Could not read the file.`

**Export/Backup/Share Failure Bodies** (all in shared `Export failed` dialog — see §4.5):
- `Could not open file for writing`
- `Export failed: <exception>`
- `Backup failed: <exception>`
- Share: `Could not prepare the file to share.` / `No app can share this file.`
- Export Logs: `No log file exists yet.` / `No app can handle this share.`

#### 4.2 Description/Help Lines (Exact, Render Order)

| Position | Text |
|----------|------|
| under `Backup (zip)` | `Everything — papers, questions, schedules, review history, attempts, bookmarks and settings — in one readable zip. Restores exactly the state it was taken from.` |
| under `Restore backup` | `Replaces the whole library and all settings with the backup's contents. Anything added since the backup is gone.` |
| under `Export all data (JSON)` | `Backs up every paper, category, question, passage, bookmark, attempt and card schedule to one JSON file. Re-import it anywhere to restore content and history. Settings and the review log are only in the zip backup.` |

#### 4.3 Storage

- Heading `Storage` + `Refresh` button → `refreshStorage()` re-runs `repository.storageReport()`. Also loaded once in ViewModel constructor.
- While `storage == null`: `Measuring…`
- Summary line: `Database: %1$s · %2$d papers · %3$d questions · %4$d attempts · %5$d bookmarks`
  - `%1$s` = `StorageInfo.formatBytes(dbBytes)` of `context.getDatabasePath("mcq.db")` plus its `-wal` and `-shm` files, when present; `?` if the paths throw; formats `<1024` as `N B`, `<1 MiB` as `%.1f KB`, else `%.1f MB`.
  - `%2$d` papers = papers table row count.
  - `%3$d` questions = `countQuestions()`.
  - `%4$d` attempts = `attemptDao().countAll()` (finished attempts).
  - `%5$d` bookmarks = `bookmarkDao().countAll()`.
- Per-paper rows (sorted by image weight desc, base64 char sum ≈ bytes):
  - Left: title (`bodySmall`, 1 line ellipsised).
  - Right: `%1$d q · %2$s` (e.g. `42 q · 1.2 MB`).
  - Collapsed cap: **10** rows (`STORAGE_PAPER_PREVIEW_LIMIT`).
- Expand toggle (only when > 10 papers): `Show all N papers` / `Show fewer`.
- Footnote: `Image weight counts embedded pictures; large banks shrink automatically at import.`

#### 4.4 PDF Export Switches

| # | Label | Help | Default | Key | Tag |
|---|-------|------|---------|-----|-----|
| 1 | `Two-column layout` | `Flow questions down the left column, then the right` | off | `pdf_two_column` | `setting-pdf-two-column` |
| 2 | `Refresh answers on re-import` | `When a file repeats a question's text and options but corrects its answer key, update the stored answer instead of reporting it as a duplicate.` | off | `update_answers_on_duplicate` | `setting-update-answers` |

- `Two-column layout`: read at export time (`LibraryViewModel`, `BookmarksViewModel`, `BrowseViewModel` call `repository.pdfTwoColumn().first()` per export) → applies to **subsequent exports** from any screen.
- `Refresh answers on re-import`: read at import time (`LibraryViewModel`, `ImportViewModel`, `Importer`, `BackupRestorer`) → affects **future imports/restores only**.

#### 4.5 Failure-Dialog Title Bug

**Every failure dialog is titled `Export failed`.** `SettingsScreen` tracks intended title in `exportErrorTitleRes` (assigned in 5 places) but dialog renders `stringResource(R.string.export_failed)` unconditionally. A `Backup failed`, `Restore failed`, `Theme import failed` or `Export Logs` failure appears under the wrong heading. The field is written and never read (dead state).

---

### 5. About

Every line, in render order:

1. `bodyMedium` paragraph:
   `MCQ App turns your own multiple-choice papers into practice, fully offline. Import a JSON question bank, then study it with spaced repetition or sit a timed test with negative marking and a full result breakdown. Every answer comes with its explanation, and your history, accuracy and weakest categories stay on the device.`
2. 12 dp spacer.
3. `Made by Naresh` (`bodyMedium`, `FontWeight.Medium`).
4. 4 dp spacer.
5. `Version %1$s` (`labelMedium`, `onSurfaceVariant`) where `%1$s` = `BuildConfig.VERSION_NAME` (e.g. `Version 0.0.47` or `0.0.1-dev` on untagged build). Never hard-coded.

---

## Images

- **Embedded images** (data URIs): stored inside the bank (JSON/DOCX/Anki/Backup). Always
  render, work offline.
- **Remote images** (`http(s)://` URLs): kept in markup but **only load when
  Settings → Appearance → Load remote images is on**. When off: placeholder text
  `Remote image hidden. Turn on Load remote images in Settings.` (privacy:
  prevents author from knowing when you open a question).
- **Images in HTML exports:** data URIs embedded; remote URLs kept but **CSP
  `img-src data:` blocks loading** in the browser.
- **Images in PDF exports:** data URIs decoded, subsampled; unreadable →
  `[unreadable image]`; remote URLs → `[image: <url>]`.
- **Import size limit:** 64 MB counts base64 payload (~33% overhead). A "small"
  bank with many pictures can trip it.
- **Large image warning:** `Image is <N> KB` in import preview when data URI >
  ≈ 100 KB base64.
- **Legacy scalar image fields** (`image` on question/option/explanation) shown
  as read-only rows with Remove button in Editor; no blank image fields
  anywhere.

---

## Explanation Images

Explanation images are a first-class field (`explanationImage` / `explanationElements`
with image blocks). They render **inside the explanation** on:
- Test session (after reveal, unless practice/strict).
- Results per-question card.
- Study card (after Check/reveal).
- Editor preview.
- Exports (HTML/PDF/DOCX/Anki carry them; Anki `mcqapp` payload includes them).
- Bookmarks screen: **not shown** (would give the answer away with no options
  beside it).

---

## Color Themes

**Built-in (15):** Material, Nord, Dracula, Tokyo Night, Catppuccin, Gruvbox,
One Dark Pro, Monokai, Solarized, Everforest, Rosé Pine, Ayu, Night Owl,
Kanagawa, GitHub. Each has light & dark palettes (4 accents + background/
surface/surfaceVariant/onBackground).

**Custom themes:** Import `.json` (Settings → Appearance → `Import theme…`).
Max 64 KB, max 20 stored. Same-name import replaces. Successful import selects
the theme. Appears in picker with `Imported` subtitle. Removable via `Remove
this theme` (only when selected). Kept in backups (`settings.json`).

**Theme JSON shape:**
```json
{
  "name": "My Theme",
  "primary": "#7AA2F7",
  "secondary": "#BB9AF7",
  "tertiary": "#7DCFFF",
  "error": "#F7768E",
  "light": {
    "background": "#FFFFFF",
    "surface": "#F7F7F7",
    "surfaceVariant": "#E0E0E0",
    "onBackground": "#1A1A1A"
  },
  "dark": {
    "background": "#1A1B26",
    "surface": "#24283B",
    "surfaceVariant": "#414868",
    "onBackground": "#E0E0E0"
  }
}
```
All hex colours must be 6-digit (`#RRGGBB`).

---

## Data & Storage

### DataStore (Settings)
Single file `settings`. `SettingsStore.snapshot()` captures **all** preferences
generically (unknown types skipped; `in_progress_test` deliberately excluded).
Every Settings control round-trips through `Backup (zip)`. `restore()` **replaces
the whole preference set** — a setting changed since the backup does not survive
a restore.

### Room Database (`mcq.db`)
Tables: papers, categories, questions, options, passages, attempts, attempt
results, bookmarks, card_state, review_log, panels, blueprints, in_progress_test,
question_stats, etc.

### Storage Screen Numbers

| Label | Source | Definition |
|-------|--------|------------|
| **Database** | `formatBytes(dbBytes)` | SQLite main file plus `mcq.db-wal` and `mcq.db-shm` when present. |
| **Papers** | `report.papers` | `paperDao.getAll().size`. |
| **Questions** | `report.questions` | Sum of questions across all papers (category + paper-level). |
| **Attempts** | `report.attempts` | `attemptDao.countAll()`. |
| **Bookmarks** | `report.bookmarks` | `bookmarkDao.getAll().size`. |
| **Per-paper usage** | `report.perPaper` | For each paper: title, question count, **image weight** (sum of base64 char length of all embedded images in that paper's questions, options, explanations, passages). |

Expand toggle at > 5 papers. Footnote: `Image weight counts embedded pictures; large banks shrink automatically at import.`

---

## Edge Cases & Gotchas (Observed, Not Theoretical)

1. **No attempt delete in History.** No control, no confirmation. (Panels/blueprints
   have confirms; asymmetry looks accidental.)
2. **Practice/Strict are global Settings toggles**, not on the setup sheet. The
   sheet looks identical for a strict mock and an instant-feedback practice run.
3. **Fixed — fresh-sample note matches the rule.** It reads "when enough others
   remain"; the attempt still records `NONE` (Mock) if exclusion could not apply.
4. **Fixed — `Balanced` help describes the real allocation:** proportional per
   category, interleaved by topic.
5. **Fixed — `At risk` help says due now;** `atRisk = dueAt ≤ now`.
6. **Fixed — `Unseen` help names the real rule:** no card / NEW / stale hash.
7. **Fixed — the blueprint editor's "N questions" is the real quota**
   (`BlueprintQuotas.allocate`, the same allocation Start uses).
8. **A blueprint can be "short by 5" and still run the full length.** Deficit
   redistributed to siblings with room; "Not complete" is about quotas, not total.
9. **Fixed — the banner matches the family.** Balanced reads
   `Mock · Balanced · N questions`; Blueprint additionally names itself:
   `Mock · Blueprint · <name> · N questions`; only practice types read
   `Practice · …`.
10. **Short scope announced three times:** `Short test — 17 questions`, preview
    `Random · 17 questions · 17 min · Short test · 17 questions`, session
    `Short test · 17 questions`.
11. **Fixed — a graded skipped question reads `Skipped` on the review card**,
    matching the header count and the `Skipped` filter.
12. **`No all questions`.** Empty state built by lowercasing filter constant.
13. **Delta-in-margin uses two yardsticks.** `What this result means` compares
    `|delta|` with this attempt's margin; `Comparable trend` compares with sum of
    both margins. Same change can be "outside" on one card and "inside" on the
    other.
14. **Empty minutes field = untimed, not suggested.** Clearing produces
    `Fixed(0)`; only `Use suggested` restores suggested duration.
15. **Six digits of minutes accepted but session caps at 10080 min (1 week).**
    Preview can say `999999 min` while clock runs 10080.
16. **Fixed panel under category scope has no error line.** Start disabled; only
    whole-paper sentence above hidden picker; generic refusal suppressed.
17. **No way to leave a live sitting without submitting.** Back arrow opens
    submit dialog; `Cancel` returns to question. Killing app = only "abandon"
    (leaves resumable snapshot).
18. **Countdown never pauses, but dwell clock does.** Auto-submit can fire while
    submit dialog/palette/answer review open; background time still counts.
19. **Multi-correct is all-or-nothing.** 2 of 3 correct = wrong, full penalty;
    no partial credit.
20. **Score interval almost always absent.** Requires negative marking > 0, ≥ 2
    rows, ≥ 2 passage blocks.
21. **"Possible guess" = 1–2 seconds, not "under 3".** Dwell 0 = "untimed",
    excluded; instant answer on untimed paper not flagged.
22. **Fixed — the listed Results/Browse strings moved to resources:** screen
    titles, stat labels, per-question status, bookmark content descriptions and
    `Uncategorized`.
23. **`No per-question timing was recorded` unreachable** — Time card renders
    only when `hasDwell` true.
24. **Panel cannot be created from Random or Mistakes sample.** `New panel…`
    only for signal-selected types.
25. **Editor's parts can be 999 but sheet's length ≤ 150.** Parts are weights;
    harmless but "999" reads like a question count.
26. **Blueprint never counts as short test.** Below 20 total supply → refuses
    to start instead of running a labelled short test.
27. **History row's `correct/total` and percentage use different denominators**
    (ungraded/skips/negative marking).
28. **Trend cards group by requested count, not shown count** — 80-asked/84-shown
    and 80/80 same line; 90 and 80 different.
29. **Fixed — the `Attempts` header always renders**, even when every attempt
    is legacy.
30. **`Slow` filter can hide everything on a fast paper.** Threshold
    `max(60 s, 2 × median)`; median 10 s still requires 60 s.

---

## Logs & Debugging

**Diagnostic Logs Export:** Settings → Backup & data → **Export Logs**.
- Source: `Logger.logFilePath()` (internal rotating log, ~1 MB). If null:
  `No log file exists yet.`
- Share: `FileProvider` URI, `ACTION_SEND`, `text/plain`, `FLAG_GRANT_READ_URI_PERMISSION`. Chooser title: `Export Logs`.
- Failures: toast `No log file exists yet.` / `No app can handle this share.`

**Log content:** app lifecycle, import/export progress (per 500 Qs + heap),
errors, sync events, scheduler operations, test session milestones.

---

## Development & CI

### Build (Termux / ARM64 host)

```bash
. /sdcard/repo/apk-build/env.sh   # exports JAVA_HOME, ANDROID_HOME, MCQ_AAPT2_OVERRIDE
cd "$MCQ_REPO"
sh gradlew :app:assembleDebug :app:testDebugUnitTest $MCQ_AAPT2_OVERRIDE --no-daemon
```

- `aapt2FromMavenOverride` only needed on Termux (ARM64); `env.sh` exports it
  (`MCQ_AAPT2`, default `/opt/android-aapt2/aapt2`). GitHub Actions uses bundled
  `aapt2` and passes no override.
- Pure-JVM tests run locally. Robolectric/SQLite tests fail on ARM64 ("The
  Robolectric native runtime is not supported on Linux (aarch64)"); CI (x86_64)
  is authoritative.
- `run-jvm-tests.sh` selects classes by `^import org.robolectric` (not word
  match), so a comment cannot hide a class. Full local pass ≈ 124 classes.

### Build (NixOS ARM64 host, repo at `/home/naresh/repo/mcq`)

```bash
. apk-build/env.sh   # manages /tmp/mcq-apk-env (jdk17, qemu, x86_64 sysroot, aapt2 wrapper)
sh gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin \
  $MCQ_AAPT2_OVERRIDE --dependency-verification=lenient --no-daemon
```

### CI (`.github/workflows/build.yml`)

Jobs: `wrapper-validation` → `build` (unit tests + lint) → `release-build` →
`ui-test` (emulator). APKs uploaded as artifacts (`if: always()`).

**After every push:** download both APKs to `/sdcard/Download` (or
`/home/naresh/Download` on NixOS) with exact names:
```bash
gh run download <run-id> --name app-debug   -D /sdcard/Download   # -> app-debug.apk
gh run download <run-id> --name app-release -D /sdcard/Download   # -> app-release.apk
```
Debug variant has `applicationIdSuffix = ".debug"` → installs as
`com.mcqapp.debug` beside release (`com.mcqapp`) with own data directory and
FileProvider authority; both can coexist.

### Branching (Owner Rule)

- Everything on a feature branch cut from `main` (`feature/<topic>`). `main`
  receives merged PRs only — never commit directly, not even docs.
- Open a PR for every branch (draft until owner says go); owner decides when it
  merges and when a release is cut.
- Releases tag the merge commit on `main` as `0.0.N`, asset
  `mcq-<tag>-signed.apk` + `.sha256`, notes auto-generated with PR links.

### Test Dependencies

Never add a test dependency without asking; the project deliberately uses no
SQLite driver library and calls `android.database.sqlite` under Robolectric
instead.

### Anki Interoperability

- Export writes legacy schema-11 `.apkg` with no `meta`; Anki upgrades on import
  (officially supported path for old clients).
- Notetype: 3 fields — Front, Back, `mcqapp` (JSON copy of question). Card
  template renders only Front/Back; third field recovers options/correct answers
  exactly on re-import (readable Back cannot represent option boundaries).
- Options on Front; correct answer(s) + explanation on Back.
- Media referenced by **filename** (`mcqapp-0.png`), never numeric zip entry.
  Anki keys import media map by filename.

---

## Repository

**Source & issues:** <https://github.com/npnpatidar/mcq>

**Made by Naresh**