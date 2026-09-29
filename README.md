# MCQ App

An offline-first Android app for practicing multiple-choice questions: build papers with
recursive categories, import question banks from JSON, take timed tests with negative
marking, review explanations, and export papers as JSON, ZIP, HTML, or PDF.

- **Stack:** Kotlin, Jetpack Compose (Material 3), Room, Navigation-Compose, DataStore, Coil, kotlinx.serialization
- **Requires:** Android 8.0+ (minSdk 26), targetSdk 36. Version 1.0.0.
- **Offline:** fully usable without network. The only thing that needs network is loading
  remote-URL images (see [Images](#images)).

## Screens

| Screen | What it does |
|---|---|
| Library | Paper cards with Start / Browse / Export / Manage; drawer with History, Bookmarks, Settings, per-paper category trees, test-by-category, JSON import |
| Browse | Per-paper question list with filters (All, No answer, No explanation, No category); edit, delete, reorder-safe saves |
| Test | Timed session with per-question navigation, flagging, mid-test reveal, submit confirmation, auto-submit at zero |
| Results / Review | Score breakdown plus per-question review with your vs correct answers and explanations (review is also reachable later from History) |
| History | Past attempts; delete attempts; open any attempt in review mode |
| Bookmarks | Toggle bookmarks from Browse; tap a bookmark to edit the question |
| Editor | Edit everything about a question, with Prev/Next queue navigation (see below) |
| Import | Paste/file-pick JSON → preview split into New / Changed / Duplicates → edit in place → import with a result report |
| Settings | Theme (system/light/dark), export-all-data JSON, export diagnostic logs, about |

## Question model

A question has: text, optional question image, 2+ options (each with id, text, optional
image), a set of correct option ids (empty = unanswered-key, multiple = multi-correct),
explanation text, optional explanation image, difficulty, tags, and an owning category.

Editor validation (shared by Save and Prev/Next): non-blank text, at least 2 options,
no blank option. Buttons stay disabled until the form is valid.

## Images

Images are stored as strings in one of two shapes:

- **Embedded:** `data:image/<type>;base64,...` data URIs. Displayed from bytes, work fully offline.
- **Remote URL:** loaded with Coil at view time. Needs network; shows nothing offline.

Picking an image from the device (question, option, or explanation image) re-encodes it
as JPEG, max 1024px on the long edge, quality 80. You can also paste a data URI or URL
directly into any image field. On save, blank image fields become `null`.

## Explanation images

Explanations support an image alongside the text, end to end:

- Editor has an explanation-image URL field + Pick button + live preview.
- Shown under the explanation text in Browse, test review, results/history review,
  bookmarks, and the import preview.
- Imported from `explanationImage` (also accepted: `explanation_image`, `explainImage`,
  `explanationImageUrl`); exported in all formats (inline in JSON/HTML, as a file in
  ZIP, drawn in PDF).

## Import: supported JSON

Three shapes are accepted. Missing ids are filled in; unknown fields are ignored.

**1. Bare array** (simplest — e.g. `simple_questions.json`):

```json
[
  { "question": "Which planet is known as the Red Planet?",
    "options": ["Venus", "Mars", "Jupiter", "Mercury"] }
]
```

**2. Full schema** (canonical — what export produces; `sample_paper.json` demos it):

```json
{
  "version": 1,
  "papers": [{
    "id": "paper-demo", "title": "General Knowledge Demo",
    "description": "...", "durationMinutes": 10, "negativeMarking": 0.25,
    "categories": [{
      "id": "cat-science", "title": "Science", "parentId": null,
      "questions": [{
        "id": "q-s1", "text": "...", "image": null,
        "options": [{ "id": "a", "text": "Venus" }],
        "correctOptionIds": ["b"],
        "explanation": "...", "explanationImage": null,
        "difficulty": "easy", "marks": 2, "tags": ["space"]
      }]
    }],
    "questions": [ /* optional top-level questions, kept in an "Uncategorized" category */ ]
  }]
}
```

**3. Legacy aliases.** Every level accepts common variants:

| Field | Aliases (first hit wins) |
|---|---|
| paper title | `title`, `name` (default `Untitled Paper`) |
| paper description | `description`, `desc` |
| duration | `durationMinutes`, `duration` (default `0` = untimed) |
| negative marking | `negativeMarking`, `negative` |
| categories | `categories`, `sections`, `subjects` |
| category title | `title`, `name` (default `Category`) |
| question text | `text`, `question` |
| question image | `image`, `imageUrl` |
| explanation | `explanation`, `explain`, `reason` |
| option text | `text`, `value` (plain strings also accepted as options) |
| tags | array, or comma-separated string |
| correct answer | `correctOptionIds` (array or comma string) → `correctIndex` → `answer` / `correct` / `correctAnswer`, resolved as option id first, then option text (case-insensitive), then numeric index. Absent = no answer key. |
| marks | `marks`, `points`, `weight` (default `1`; invalid/negative → `1`). A correct answer scores `marks`; a wrong one deducts `marks × negativeMarking`. `maxScore` is the sum of graded marks. |

## Import semantics and nuances

Worth reading before you trust an import:

- **Duplicate = same content hash**, where the hash covers **question text + option texts + option images only**. Correct answers, explanations, difficulty, tags, and category/paper assignment do **not** affect it.
  - Consequence 1: editing *only* the correct answer never counts as a change — re-importing it is a no-op for that question.
  - Consequence 2: image changes *do* count (option images are hashed), even though generated question ids ignore images.
- **Missing question ids are deterministic:** `SHA-256(text + options)` truncated. Re-parsing identical JSON yields identical ids, so re-imports line up; identical questions in one file get `-2`, `-3` suffixes. Explicit ids are always preserved.
- **Papers merge by id, then title** (oldest match wins). Re-importing the same file updates the existing paper instead of creating a same-named stub. Same for categories (id, then title within the paper); a category id owned by another paper is namespaced, never stolen.
- **New questions append** after existing ones (`max(sortOrder)+1`); **updates keep their position** — re-imports never reorder your list.
- **A brand-new paper whose questions all already exist is skipped entirely** (no empty stub in the library).
- **Top-level `questions` are kept**: with no categories they form a category named after the paper; alongside categories they form an `Uncategorized` root category. (They used to be silently dropped in the second case.)
- **Writes are non-destructive**: papers/categories use insert-or-update, never `REPLACE` (which would cascade-delete questions). The duplicate-hash snapshot is taken before any write.
- **Import preview** splits the file into **New** (will be added), **Changed** (same id, new content — will update in place), and **Duplicates** (skipped), computed with the exact hash the importer uses. Editing preview rows updates their section live.
- **Preview edits are in-memory only** until you tap the Import button: the editor round-trips through a holder, and only `Import` writes to the database. Tapping Import shows a result dialog (`X new, Y updated, Z skipped`) instead of silently navigating away.
- **Sort order survives editing**: saving a question never resets its position.
- **Tolerant by design**: empty option lists, missing answers/explanations/ids/difficulty/tags all import (see the `Edge Cases` demo category). Blank text fields become `null` images on save; option/field text is trimmed.
- **Embedded images shrink at import**: `data:` URIs over 1280px on the long edge are downscaled (format preserved, JPEG quality 85); unparseable images pass through untouched. Duplicate matching still uses the original bytes, so re-imports line up. Payloads over ~256 KB also raise a preview warning (see `tools/stress/gen_stress.py` for load testing).

## Editor Prev/Next

Opening an editor from Browse or Import arms a queue: the visible list, in order (Browse
respects the active filter; Import uses preview order). The top bar shows position
(`3/10`) with **Prev/Next**.

- Moving **saves the current question first** through the same channel as Save (database
  in Browse, preview list in Import) — then loads the next. No back-stack growth: system
  Back always returns to the originating list.
- **Unchanged moves write nothing**: the editor fingerprints the last persisted state, so
  just looking through questions is free. A move that does persist shows a short
  `Saved` toast; Save itself needs none (you asked for it).
- Prev/Next (like Save) stay disabled until the form is valid.
- Library, bookmarks, and new-question entries clear the queue, so the buttons only
  appear where a list exists.

## Export formats (per paper)

Tapping Export on a paper asks for one of six formats (Settings keeps a JSON export-all):

| Format | Contents |
|---|---|
| JSON (images inline) | Canonical schema, data-URI images in place. **Re-importable.** |
| ZIP (JSON + images) | `paper.json` (same schema) + `images/img001.jpg…` referenced by relative path; identical bytes deduped; remote URLs left as-is. Re-imports as questions, but the image files won't resolve back. |
| Web page (answers shown) | Self-contained `.html`: inline CSS, embedded images, correct options highlighted, answers + explanations visible. |
| Web page (quiz mode) | Same page with answers hidden: per-question **Show answer** toggles plus Show/Hide-all, inline JS, no network needed (`file://` works). Suggested filename `*-quiz.html`. |
| PDF (answers inline) | A4 via framework `PdfDocument`: questions, ✓/○ options, answers under each question, embedded images scaled to page width, page numbers. |
| PDF (answer key at end) | Questions print unmarked; all answers + explanations move to an **Answer Key** section for self-testing. Suggested filename `*-answer-key.pdf`. |

Quirks: nested category trees render flat; remote-URL images render as an `[image: url]` line in PDF (no offline fetch); filenames are sanitized from the paper title.

## Tests and scoring

- **All-or-nothing per question**: selected set must equal the correct set exactly. Correct = +1. Wrong = −negativeMarking. Skipped = 0. Max = number of questions.
- Single-correct questions single-select; multi-correct toggle independently.
- Duration 0 = untimed; otherwise the countdown **auto-submits at zero** (submit also asks for confirmation). Mid-test you can flag questions and reveal the current one to study its explanation.
- Attempts (with per-question snapshots of options/selections/correctness/explanations) persist to History and stay reviewable even if the paper later changes.

## Demo data

`Load sample paper` imports `assets/sample_paper.json` — **General Knowledge Demo**, 27
questions in 9 categories: Science (+ nested Physics), History, Geography, Sports, Visual
Round (question/option images, a bar-chart interpretation), **Order Check** (sequentially
titled steps with varied `marks`, for verifying shuffle and weighting), and **Edge Cases**
(missing answer, missing explanation, missing id, empty options) plus a top-level Titanic
question landing in Uncategorized. Re-loading merges by stable ids — never duplicates.

## Data & storage

Room database `mcq.db`, **version 5** (`MIGRATION_3_4` adds `explanationImage` to
`questions` and `question_results`; `MIGRATION_4_5` adds `marks` to `questions`,
default `1.0`; existing installs migrate in place). Deleting a paper
deletes its categories; deleting a category deletes its questions (FK cascades) — options
go with their question. Deleting attempts, bookmarks, or papers never orphans history
snapshots (attempts embed their own copies).

## Edge cases & gotchas (observed, not theoretical)

- **Answer-only edits don't re-import.** Hash excludes answers, so fixing just a key and
  re-importing changes nothing. Edit text/options, or fix it in the editor.
- **Importing an older file overwrites newer edits.** Same id + different hash = UPDATE in
  place. The preview's Changed section exists precisely to warn you.
- **"Nothing imported" after editing the file?** Check *which bytes the app received*:
  every file pick logs `picked import file: chars=… sha=…`. A document provider serving a
  stale/cached copy (or two same-named files on device) has bitten before — compare the
  `sha=` across picks before blaming the importer.
- **Bare-array imports get random paper ids** but constant title, so repeats merge by
  title. Missing category ids are random per parse; missing question ids are stable.
- **Whitespace is trimmed on save** (text, options, tags); blank images become `null`.
- **Correct-answer matching is forgiving**: id → text (case-insensitive) → numeric index.
- **The `Uncategorized` browse filter** shows top-level questions (kept in a category
  literally titled `Uncategorized`) plus any blank-`categoryId` rows, which imports
  never produce since every import assigns a category.
- **Timer at 0 with duration set** auto-submits; untimed papers (duration 0) never count down.

## Logs & debugging

File logging with thread names to `logs/app.log` (external files dir, else internal).
In-app: Settings → **Export Logs** (shares the file). Key tags: `IMPORTVM` (preview),
`IMPORT` (database import), `EDITORVM`, `IMPORTSCREEN`, `REPO`, `BROWSEVM`, `NAV`,
`LIB`/`LIBVM`, `TESTVM`, `EXPORT`. Log rot
...[truncated 1093 chars]