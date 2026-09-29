# MCQ App

An offline-first Android app for practicing multiple-choice questions: build papers with
recursive categories, import question banks from JSON, take timed tests with negative
marking and per-question weights, study in practice/strict/mistakes modes, review
explanations, track mastery over time, keep questions fresh with SM-2 spaced repetition,
and export papers as JSON, ZIP, HTML, or PDF.

- **Stack:** Kotlin, Jetpack Compose (Material 3), Room, Navigation-Compose, DataStore, Coil, kotlinx.serialization
- **Requires:** Android 8.0+ (minSdk 26), targetSdk 36. Version 1.0.0.
- **Offline:** fully usable without network. The only thing that needs network is loading
  remote-URL images (see [Images](#images)).
- **Tests:** ~200 JVM unit tests + on-device critical-path UI tests; CI runs both on every push (see [Development](#development--ci)).

## Screens

| Screen | What it does |
|---|---|
| Library | Paper cards with Start / Browse / Export / Manage; **Mistakes (N)**, **Quick drill** and **Study (N due, N new)** buttons per paper; drawer with History, Bookmarks, Settings, per-paper category trees (reorderable), test-by-category, JSON import |
| Browse | Per-paper question list with text search + filters (All, No answer, No explanation, Uncategorized); edit, duplicate, delete; **selection mode** for bulk delete / move / copy (incl. cross-paper); manual up/down reorder |
| Test | Timed session with shuffle, practice/strict modes, per-question navigation, palette, flagging, bookmarking, mid-test reveal, smart submit dialog with answer review, auto-submit at zero, crash resume |
| Study | One question at a time: answer, reveal, self-grade Again/Hard/Good/Easy. SM-2 schedules the next review (see below) |
| Results / Review | Score breakdown, average time per question, per-question review with your vs correct answers, time spent, explanations, bookmark toggles, filters (All/Correct/Wrong/Skipped/Ungraded/Saved); review reachable later from History |
| History | Per-paper score trends, weakest categories, hardest questions, attempt list; delete attempts; open any attempt in review mode |
| Bookmarks | Bookmark toggles in test, review, and Browse; tap a bookmark to edit; export all bookmarks in any format |
| Editor | Edit everything about a question (incl. marks), with Prev/Next queue navigation (see below) |
| Import | Paste/file-pick JSON → validation warnings → preview split into New / Changed / Duplicates → edit in place → import with a result report (incl. restored history for backups) |
| Settings | Theme; Test options (shuffle, practice, strict, auto-advance); full backup; Storage breakdown; export diagnostic logs; about |

## Question model

A question has: text, optional question image, 2+ options (each with id, text, optional
image), a set of correct option ids (empty = no answer key, multiple = multi-correct),
**marks** (weight, default 1), explanation text, optional explanation image, difficulty,
tags, and an owning category.

Editor validation (shared by Save and Prev/Next): non-blank text, at least 2 options,
no blank option. Buttons stay disabled until the form is valid. Invalid marks fall back
to 1 (never blocks saving).

## Test modes & options (Settings → Test)

All default off; they compose, with strict winning contradictions:

- **Shuffle question order** — random question order per attempt (seed logged). **Shuffle
  options** — random option order. Review always shows your presented order; scoring is
  by option id, so shuffling never changes correctness.
- **Practice mode** — options color live as you tap and explanations auto-expand; the
  Show-answer button hides (nothing left to reveal). Selections stay changeable.
- **Strict exam mode** — hides Show-answer, flagging, and the question palette (a plain
  position counter remains); suppresses practice feedback. For a real exam feel.
- **Auto-advance** — jumps to the next question after answering (single-answer questions
  only; multi-correct needs several taps, the last question stays put).
- **Practice mistakes** (per-paper Library button) — a round built from questions you
  previously got wrong, most-recently-missed first. Appears only when mistakes exist.
- **Quick drills** (per-paper Library button) — a short round of `n` questions with a
  `m`-minute limit. `n` is capped at 1–50, `m` at 1–180, and both are remembered between
  openings. Drill results are not saved to History.
- **Study** (per-paper Library button) — spaced repetition, see below.

## Spaced repetition (Study)

Study is a separate mode from taking a test. It never marks, never scores, and never
writes an attempt: it only schedules *when you should see a question again*.

- Each paper's Library card shows a **Study** button with counts, e.g. `Study (3 due,
  12 new)`, plus a "N tricky" hint for leeches. A paper with nothing left just says
  `Study`.
- The screen shows **one question at a time**. Pick an answer, hit **Show answer** to see
  the correct one and its explanation, then self-grade with **Again / Hard / Good / Easy**.
  Grading is self-reported because you see the answer first — that is what makes the
  four buttons meaningful.
- **Scheduling is SM-2.** Good graduates a card to 1 day, then 6 days, then ~3× the
  previous interval; Easy jumps to 4 days on the first review. Hard grows the interval by
  the smaller of +1 day and +20%. Again drops the card to zero reps and re-queues it in
  **10 minutes**, so a failed card still comes back the same session.
- **Ease** starts at 2.5 and is clamped to 1.3–3.0. It drops on Again/Hard and rises on
  Easy, so easy questions stretch out and hard ones come back more often.
- **New-card limit** is 20 per paper per day; due and relearning cards are never counted
  against it.
- **Leeches** are cards failed 8 times or more. They are labelled `tricky` in the library
  and are surfaced first in the queue.
- **Editing a question resets its schedule.** An interval describes memory of *that*
  text, so changing the question text or options starts the card over. Bookmarks, history
  and results are untouched.
- An existing install starts warm: correct and wrong answers in past attempts are replayed
  through the scheduler to rebuild each card's position, so the first Study session is
  already scheduled rather than showing everything as new.

**Where the schedule lives.** A `card_state` row per question, added in Room schema v7.
Migration is automatic and preserves everything else. A card belongs to a paper, so
deleting a paper, or a question, removes its schedule; moving a question to another paper
starts it fresh.

**Roadmap.** The scheduler sits behind a `Scheduler` interface, so FSRS can be added
later as another implementation — it would slot in without changing storage or the study
UI.

## Taking a test

- **Palette** (answered-count button) jumps between questions; cells show current /
  answered / flagged states with TalkBack descriptions.
- **Flag** questions for later; **bookmark** any question (top bar) — bookmarks persist
  independently of the attempt.
- **Show answer** reveals correctness and locks the question (exam mode only; hidden in
  practice/strict). Ungraded questions never color — there is no key to check against.
- **Timer warnings** at 5 minutes and 1 minute (dismissible banners); auto-submit at zero.
  Per-question dwell time is tracked for timed and untimed papers alike.
- **Crash resume**: progress (order, selections, reveals, flags, position, timer, dwell)
  persists on every change; killing the app offers Resume / Start-fresh next launch for
  the same paper + categories.
- **Submit dialog** lists answered/unanswered (score-0 warning), flagged, not-scored, and
  longest-dwell counts, with Review-flagged and **Review answers** actions — the review
  lists every pick, tappable to jump, with a Back-to-submit return.

## Tests and scoring

- **All-or-nothing per question**: selected set must equal the correct set exactly.
- **Weighted**: correct = `+marks`; wrong = `−marks × negativeMarking`; skipped = 0.
  `maxScore` is the sum of graded marks (0-mark questions count as answered but add nothing).
- **Ungraded (no answer key)**: excluded entirely — no credit, no penalty, out of
  `maxScore` and counts. Skipping one is always safe; answering one is practice only.
- Single-correct questions single-select; multi-correct toggle independently.
- Duration 0 = untimed; otherwise the countdown **auto-submits at zero**.
- Attempts (with per-question snapshots of options/selections/correctness/explanations/
  dwell) persist to History and stay reviewable even if the paper later changes.
- **Study never touches any of this.** It runs outside the attempt path entirely, so a
  study session cannot add to History, change a score, or affect mastery stats.

## Results, History & mastery

- **Results header**: score, percentage bar, Correct/Wrong/Skipped (+Ungraded when present),
  time taken, and **average time per question**. Each card shows its own time spent.
- **Review filters**: All, Correct, Wrong, Skipped, Ungraded, Saved (bookmarked) — the
  last two appear only when applicable.
- **History**: per-paper **trend cards** (attempts, best, latest, ▲/▼/= delta), **weakest
  categories** with mastery bars, **hardest questions** with wrong/skip rates, then the
  attempt list. Ungraded rows never pollute difficulty signals.

## Browse: search, filters, bulk ops

- **Search** matches text, tags, and option texts (case-insensitive), composed with the
  attribute chips.
- **Uncategorized** chip shows top-level questions (kept in a category literally titled
  `Uncategorized`) plus any blank-`categoryId` rows (which imports never produce).
- **Select mode**: checkbox multiple questions, then Delete (with confirmation) or
  Move/Copy — the move dialog offers Move vs Copy chips, a paper picker, and that
  paper's categories. Cross-paper moves reuse import-grade id namespacing.
- **Reorder**: up/down arrows on the unfiltered list swap same-category neighbours
  (shown only where neighbours are real siblings); the observed list refreshes live.
- Per-card Edit (arms the Prev/Next queue), Duplicate (`-copy` ids), Delete.

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
Malformed rows are skipped, never fatal (a 1000-case seeded fuzzer pins this).

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
  }],
  "bookmarks": ["q-s1"],
  "attempts": [ /* full backups only; restored with history, see below */ ]
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
| correct answer | `correctOptionIds` (array or comma string) → `correctIndex` → `answer` / `correct` / `correctAnswer`, resolved as option id first, then option text (case-insensitive), then numeric index. Absent = no answer key. Dangling ids are dropped (question becomes ungraded). |
| marks | `marks`, `points`, `weight` (default `1`; invalid/negative → `1`). A correct answer scores `marks`; a wrong one deducts `marks × negativeMarking`. `maxScore` is the sum of graded marks. |

## Import semantics and nuances

Worth reading before you trust an import:

- **Validation warnings** flag what imports fine but misbehaves later: blank text, <2
  options, missing answer key, dangling key ids, 0 marks, >256 KB image payloads.
  They recompute live as preview rows are edited.
- **Duplicate = same content hash**, where the hash covers **question text + option texts + option images only**. Correct answers, explanations, difficulty, tags, marks, and category/paper assignment do **not** affect it.
  - Consequence 1: editing *only* the correct answer never counts as a change — re-importing it is a no-op for that question.
  - Consequence 2: image changes *do* count (option images are hashed), even though generated question ids ignore images.
- **Missing question ids are deterministic:** `SHA-256(text + options)` truncated. Re-parsing identical JSON yields identical ids, so re-imports line up; identical questions in one file get `-2`, `-3` suffixes. Explicit ids are always preserved.
- **Papers merge by id, then title** (oldest match wins). Re-importing the same file updates the existing paper instead of creating a same-named stub. Same for categories (id, then title within the paper); a category id owned by another paper is namespaced, never stolen.
- **New questions append** after existing ones (`max(sortOrder)+1`); **updates keep their position** — re-imports never reorder your list (use Browse arrows to reorder by hand).
- **A brand-new paper whose questions all already exist is skipped entirely** (no empty stub in the library).
- **Top-level `questions` are kept**: with no categories they form a category named after the paper; alongside categories they form an `Uncategorized` root category. (They used to be silently dropped in the second case.)
- **Writes are non-destructive**: papers/categories use insert-or-update, never `REPLACE` (which would cascade-delete questions). The duplicate-hash snapshot is taken before any write.
- **Import preview** splits the file into **New** (will be added), **Changed** (same id, new content — will update in place), and **Duplicates** (skipped), computed with the exact hash the importer uses. Editing preview rows updates their section live.
- **Preview edits are in-memory only** until you tap the Import button: the editor round-trips through a holder, and only `Import` writes to the database. Tapping Import shows a result dialog (`X new, Y updated, Z skipped`, plus restored history for backups) instead of silently navigating away.
- **Sort order survives editing**: saving a question never resets its position.
- **Tolerant by design**: empty option lists, missing answers/explanations/ids/difficulty/tags/marks all import (see the `Edge Cases` demo category). Blank text fields become `null` images on save; option/field text is trimmed. Corrupt files show an error dialog instead of hanging.
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

## Export formats (per paper, per category, bookmarks)

Tapping Export on a paper — or the share icon on a category row or the Bookmarks
screen — asks for one of six formats (Settings keeps a JSON full backup):

| Format | Contents |
|---|---|
| JSON (images inline) | Canonical schema, data-URI images in place. **Re-importable.** |
| ZIP (JSON + images) | `paper.json` (same schema) + `images/img001.jpg…` referenced by relative path; identical bytes deduped; remote URLs left as-is. Re-imports as questions, but the image files won't resolve back. |
| Web page (answers shown) | Self-contained `.html`: inline CSS, embedded images, correct options highlighted, answers + explanations visible. Non-default marks shown per question. |
| Web page (quiz mode) | Same page with answers hidden: per-question **Show answer** toggles plus Show/Hide-all, inline JS, no network needed (`file://` works). Suggested filename `*-quiz.html`. |
| PDF (answers inline) | A4 via framework `PdfDocument`: questions, ✓/○ options, answers under each question, embedded images scaled to page width, page numbers. |
| PDF (answer key at end) | Questions print unmarked; all answers + explanations move to an **Answer Key** section for self-testing. Suggested filename `*-answer-key.pdf`. |

Quirks: nested category trees render flat; remote-URL images render as an `[image: url]` line in PDF (no offline fetch); filenames are sanitized from the paper/category title. Category exports include descendants; bookmark exports group by source paper and keep original ids (re-import merges).

## Backup & restore

Settings → Data → **Export all data** writes one JSON file with every paper **plus
bookmarks and full attempt history**. Re-importing that file anywhere restores content
and history: bookmarks merge idempotently, attempts restore with fresh ids (re-imports
are deduped, never doubled), and the result dialog reports restored counts.
Settings → **Storage** shows database size, row counts, and per-paper question/image
weight with refresh.

## Demo data

`Load sample paper` imports `assets/sample_paper.json` — **General Knowledge Demo**, 27
questions in 9 categories: Science (+ nested Physics), History, Geography, Sports, Visual
Round (question/option images, a bar-chart interpretation), **Order Check** (sequentially
titled steps with varied `marks`, for verifying shuffle and weighting), and **Edge Cases**
(missing answer, missing explanation, missing id, empty options) plus a top-level Titanic
question landing in Uncategorized. Re-loading merges by stable ids — never duplicates.

## Data & storage

Room database `mcq.db`, **version 7** (`MIGRATION_3_4` adds `explanationImage` to
`questions` and `question_results`; `MIGRATION_4_5` adds `marks` to `questions`,
default `1.0`; `MIGRATION_5_6` adds `dwellSeconds` to `question_results`, default
`0`; `MIGRATION_6_7` adds the `card_state` table used by Study, starting empty so the
first session rebuilds schedules from attempt history; existing installs migrate in
place, and the 3→7 chain is covered by a migration test). Deleting a paper
deletes its categories; deleting a category deletes its questions (FK cascades) — options
go with their question. Deleting attempts, bookmarks, or papers never orphans history
snapshots (attempts embed their own copies).

Test progress snapshots live in DataStore (single `in_progress_test` key, cleared on
submit); test display options (shuffle, practice, strict, auto-advance) and theme are
DataStore preferences.

Card schedules live in Room, not DataStore, because they need to survive a paper edit,
be deleted along with the paper, and be rebuilt from attempt history. See
[Spaced repetition](#spaced-repetition-study).

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
- **The `Uncategorized` browse filter** shows top-level questions plus any
  blank-`categoryId` rows (which imports never produce).
- **Timer at 0 with duration set** auto-submits; untimed papers (duration 0) never count down.
- **Strict + practice contradict** — strict wins (no live feedback, no aids).
- **Auto-advance** never fires on multi-correct questions or the last question.
- **Resume** only offers for the exact same paper + category selection; submitting or
  reinstalling clears it.
- **Dwell times exist only for attempts finished after the v6 update**; old reviews show
  no per-question times.
- **Shuffle changes review order** — review shows your presented order, not library order,
  by design.
- **"Study (0 due)" does not mean there is nothing to do.** A brand-new paper shows all
  its questions as *new* instead, because nothing is due until a card has been studied.
- **A Study queue is frozen when you open it.** Cards you grade inside the session
  finish there; they come back on a later visit once their new date arrives. Grading the
  same card twice is not possible — the grade buttons lock until the write completes.
- **An edited question is forgotten by the scheduler on purpose**, so a rewritten option
  is studied from scratch even if you had it scheduled for next month.
- **Ungraded questions (no answer key) still appear in Study.** There is no correct answer
  to reveal, but you can still grade yourself on recall of the options.

## Logs & debugging

File logging with thread names to `logs/app.log` (external files dir, else internal;
never crashes its caller). In-app: Settings → **Export Logs** (shares the file).
Key tags: `IMPORTVM` (preview), `IMPORT` (database import), `EDITORVM`, `IMPORTSCREEN`,
`REPO`, `BROWSEVM`, `NAV`, `LIB`/`LIBVM`, `BOOKVM`, `RESULTVM`, `HISTVM`, `TESTVM`,
`EXPORT`, `SETTINGS`, `DOWNSCALE`, `STUDY`. Browse observation cancellations on navigation
are expected noise. Log rotation at 8 MB.

## Development & CI

Local builds need the Android SDK and a QEMU aapt2 wrapper on ARM64 hosts (see
`BUILDING.md` for the exact command). CI (`.github/workflows/build.yml`, x86_64) runs
on every push/PR:

- **build**: `assembleDebug` + full JVM unit test suite (~200 tests: scoring, parser +
  1000-case seeded fuzz, import/export writers, domain rules, SM-2 scheduling), uploads
  APK + results.
- **ui-test**: boots an API-34 emulator (KVM enabled) and runs the critical-path tests
  (library → start → answer/skip/wrong → submit → results score, and library → study →
  reveal → grade → schedule persisted).

Robolectric DB tests (`RepositoryTest`, `MigrationTest`) run on CI only — neither
Robolectric's native runtime nor Conscrypt ship Linux-ARM64 binaries, so they fail on
ARM64 hosts by environment, not by code. This is why Room migrations get raw-SQLite
tests driven from a hand-built old-version database rather than only through Room.
Stress banks: `tools/stress/gen_stress.py` generates large/degenerate papers
(`--questions`, `--options`, `--image-every`, `--img-dim`).
