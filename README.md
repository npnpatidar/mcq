# MCQ App

An offline-first Android app for practicing multiple-choice questions: build papers with
recursive categories, import question banks from **Word (.docx)** or **JSON**, take timed
tests with negative marking and per-question weights, study in practice/strict/mistakes
modes, review explanations, track mastery over time, keep questions fresh with SM-2
spaced repetition, and export papers as JSON, ZIP, HTML, or PDF.

- **Stack:** Kotlin, Jetpack Compose (Material 3), Room, Navigation-Compose, DataStore, Coil, kotlinx.serialization
- **Requires:** Android 8.0+ (minSdk 26), targetSdk 36. The version shown in Settings → About is
  derived from the git tag the build came from (`<major>.<minor>.<patch>`); an untagged local
  build reports `0.0.1-dev`.
- **Offline:** fully usable without network. The only thing that needs network is loading
  remote-URL images (see [Images](#images)).
- **Tests:** 580+ JVM unit tests + on-device critical-path UI tests; CI runs both on every push (see [Development](#development--ci)).

New here? The fastest way to get a bank in is to **type it in Word** — see
[Import: Word documents (.docx)](#import-word-documents-docx). JSON is the fully-featured
format (papers, categories, bookmarks, history) and is documented in
[Import: JSON reference](#import-json-reference).

## Screens

| Screen | What it does |
|---|---|
| Library | Paper cards with Start / Browse / Export / Manage; **Mistakes (N)**, **Quick drill** and **Study (N due, N new)** buttons per paper; drawer with History, Bookmarks, Settings, per-paper category trees (reorderable), test-by-category, JSON import |
| Browse | Per-paper question list with text search + filters (All, No answer, No explanation, Uncategorized); edit, duplicate, delete; **selection mode** for bulk edit / move / copy (incl. cross-paper) / delete / **export the selected questions**; manual up/down reorder |
| Test | Timed session with shuffle, practice/strict modes, per-question navigation, palette, flagging, bookmarking, mid-test reveal, smart submit dialog with answer review, auto-submit at zero, crash resume |
| Study | One question at a time: answer, reveal, self-grade Again/Hard/Good/Easy. SM-2 schedules the next review (see below) |
| Results / Review | Score breakdown, average time per question, per-question review with your vs correct answers, time spent, explanations, bookmark toggles, filters (All/Correct/Wrong/Skipped/Ungraded/Saved); review reachable later from History |
| History | Per-paper score trends, weakest categories, hardest questions, attempt list; delete attempts; open any attempt in review mode |
| Bookmarks | Bookmark toggles in test, review, and Browse; tap a bookmark to open it in Browse; export all bookmarks in any format. Shows the question only — no explanation, which would give the answer away with no options beside it |
| Editor | Edit everything about a question (incl. marks), with Prev/Next queue navigation (see below) |
| Import | Import a **Word** bank or **JSON** → validation warnings → preview split into New / Changed / Duplicates → edit in place → import with a result report (incl. restored history for backups). See [Word](#import-word-documents-docx) / [JSON](#import-json-reference) |
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

- **Per-question time** ticks once a second from its own flow, so the header updates
  without re-running the question body and its images. It works for untimed papers
  too, and the last question's time is banked before the attempt is saved.
- **Search** matches a question's whole visible content, its tags and its options —
  including text inside tables and formulas. Inline markup is not searchable, so
  `strong` does not match a question that merely contains a `<strong>` tag. Pictures
  have no text to match. Cross-paper search prefilters in SQL against the stored
  elements JSON, which contains the markup, so it widens that pass to the longest
  single word of the query — otherwise a phrase like `external force` would be
  discarded before the real filter ever saw it (case-insensitive), composed with the
  attribute chips.
- **Uncategorized** chip shows top-level questions (kept in a category literally titled
  `Uncategorized`) plus any blank-`categoryId` rows (which imports never produce).
- **Select mode**: checkbox multiple questions, then Edit (bulk marks/difficulty/tags),
  Move/Copy, Delete (with confirmation), or **Export** — the export dialog offers the
  same formats as a paper export and writes only the ticked questions, grouped under
  the categories they came from, with their ids intact so a re-import merges.
  The move dialog offers Move vs Copy chips, a paper picker, and that paper's
  categories. Cross-paper moves reuse import-grade id namespacing.
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

## Import: Word documents (.docx)

The fastest way to get a bank in: type it in Word (Google Docs and LibreOffice save the same
format) and pick the file. Nothing to install and no network needed.

A `.docx` is recognised by its **content**, not its name — any zip containing
`word/document.xml` near the front counts, so renaming it to `.zip` still works. Other files
fall through to the Anki (`.apkg`) or JSON paths below.

### The document format

One question per block, built from literal marker text at the **start of a line**:

| Line | Marker | Notes |
|---|---|---|
| Question | `1.)` | 1–7 digits, a period, then **`)`** — the closing bracket is required |
| Options | `(a)` … `(j)` | lowercase only, at least four, up to ten per question |
| Answer | `Ans.` | case-sensitive, trailing period included |
| Explanation | `Exp:` | case-sensitive, trailing colon included. **Optional** — see below |

The stem, every option and the answer are mandatory. A document missing any of them is
**rejected in full** with the offending question number, rather than silently losing
questions — so if one question is broken, nothing imports.

The explanation is the one field allowed to be absent. A paper written without `Exp:` lines
is still a usable paper, and refusing the whole document over it would lose every question in
it. Such a question imports with an empty explanation: the study and browse screens simply
show no explanation block, and a test session falls back to "No explanation provided."

**Several correct answers.** Write them together after one marker, separated by commas, `and`,
or `&` — all of these are the same question:

```
Ans. b, d
Ans. b and d
Ans. b & d
```

Every letter must match an option that exists, otherwise the import is refused. This matters:
before, `Ans. b, c` matched no single option, so the question imported **ungraded with no
warning** — it looked fine and scored nothing.

**More than four options.** `(e)` through `(j)` are read as real options, each keeping its own
text. Labels must run `(a)`, `(b)`, `(c)`, `(d)` … with no gaps, so a skipped `(d)` is reported
rather than silently renumbering everything after it. Before, only `(a)`–`(d)` were split off and
a six-option question imported *successfully* with `(e)` and `(f)` folded into option (d).

A missing option `(x)`, a repeated label, a label past `(j)`, and an answer naming an option the
question does not have are all refused with the question number.

A complete, valid document:

```
1.) Which gas do plants absorb during photosynthesis?
(a) Oxygen
(b) Carbon dioxide
(c) Nitrogen
(d) Hydrogen
Ans. b
Exp: Photosynthesis fixes carbon dioxide into glucose using light energy.
2.) What is the capital of France?
(a) Rome
(b) Madrid
(c) Paris
(d) Berlin
Ans. c
Exp: Paris has been the capital of France since 987.
```

Rules that follow from how the file is read:

- **Press Enter, not Shift+Enter**, between lines. A hard line break (Shift+Enter) inside a
  paragraph becomes a real newline, so a continued sentence can sprout a bogus marker. Ordinary
  soft-wrapping at the right margin is fine — it stays inside the paragraph.
- **Never indent with Tab.** A tab becomes a space, so `(a) Oxygen` turns into ` (a) Oxygen`
  and the marker is missed. (Indenting with the ruler or Paragraph dialog is fine — that is
  formatting, not text.)
- **Don't let Word auto-format the line into a list.** List numbers and bullets are
  formatting, so they never reach the parser and the marker disappears. Type `1.)` literally;
  if Word converts it, undo the automatic formatting.
- **Markers are lowercase/exact.** `(A)`, `Ans:`, `Ans -`, `answer:`, `Exp.` and `1)` are all
  *not* markers. In particular `1.` without the bracket is not a question marker.
- **Anything before the first `1.)` is ignored silently** — that is how a title page works.
- Options are split off from the end of the question, so a repeated `(b)` swallows the earlier
  one, and a continuation line beginning with `(a)`–`(d)` corrupts an option. Keep exactly one
  marker per line and one option per line.
- `Ans.` takes one or several options: `Ans. b`, or `Ans. b, d` for two right answers.
  `Ans. 1` means option **(b)** — numeric answers are zero-based — and naming an option's
  text (`Ans. Beta`) still works for a single answer.

### Formatting, images and equations

| In Word | Effect |
|---|---|
| Bold, italic, underline, strikethrough | preserved |
| Subscript, superscript | preserved |
| Text highlight | preserved |
| Tracked **deletions** | kept, marked as deleted |
| Tracked **insertions** | **silently dropped** — turn Track Changes off before saving |
| Tables | become a table element in the question |
| Inline pictures | embedded as base64, so they work offline |

**Pictures** must be pasted inline into a normal paragraph — in the question, an option, or the
`Exp:` paragraph. Pictures inside table cells or text boxes do not survive: a cell shows the
markup as text, and a text box loses its text and reports a skipped-picture warning. A picture
whose file is missing is skipped with a warning, never fatal. `.docx` images are stored at full
size and are **not** downscaled on import.

**Equations** (Word's equation editor) become MathML for the common cases: plain runs,
superscripts, subscripts, fractions, and square roots. Anything more elaborate — Σ or ∫,
auto-brackets, matrices, hats, combined sub-and-superscript — is kept as plain linear text and
the preview warns you:

> An equation uses unsupported constructs and was kept as plain text — check its question after import.

Check the import preview for that warning before shipping an equation-heavy bank.

### What a .docx import produces

A Word import always creates **one paper** titled `Imported Questions` holding **one category**
titled `Uncategorized`. Papers, categories, per-question marks, difficulty and tags cannot be
expressed in a Word document, so every question arrives with `marks` 1, difficulty `medium` and
no tags. Re-importing the same document merges into that paper by title, so edits update in
place instead of duplicating.

### If a .docx is rejected

| Message | Cause |
|---|---|
| `No questions found: the document is empty.` | nothing readable in the document body |
| `No questions found: expected 'N.)' numbered questions with '(a)..(d)', 'Ans.' and 'Exp:' markers.` | no `N.)` line was found — check for auto-numbering, leading tabs, or `N.` written without the bracket |
| `Malformed question 3.): missing answer.` … | that question is missing a required field; the number says which. The message ends by restating the expected layout. An explanation is **not** required — a question with no `Exp:` line imports with an empty one. |
| `That file is over the 64 MB import limit (at least N MB). …` | over the import cap. The message says how far over and notes that pictures, stored inline, add about a third |
| `Not a Word document: w:body is missing.` | the file uses Strict OOXML (`purl.oclc.org`); re-save it from Word in the normal format |

Limits: 64 MB per picked file, at most 4096 archive entries, 64 MB per decompressed part and
256 MB decompressed in total. The per-file cap is worth knowing about before you hit it:
pictures are inlined into the file as base64, which adds about a third, so a bank of
thousands of questions can reach 64 MB well before it looks large. There is no streaming
import — the file is read whole — so a bigger bank has to be split across several imports. `.doc` (old binary Word), `.rtf` and `.odt` are **not** supported
— save as `.docx`.

## Import: JSON reference

Three shapes are accepted. Missing ids are filled in; unknown fields are ignored.
Malformed rows are skipped, never fatal (a 1000-case seeded fuzzer pins this). Files nested
more than 200 levels deep are rejected.

**1. Bare array** (simplest — e.g. `simple_questions.json`):

```json
[
  { "question": "Which planet is known as the Red Planet?",
    "options": ["Venus", "Mars", "Jupiter", "Mercury"] }
]
```

A bare array carries no structure, so it is wrapped for you: one paper `Imported Questions`,
one category `Uncategorized`, and **no** bookmarks, attempts or schedules. Option ids are
assigned `a`, `b`, `c`, … in order. Use the full schema when you want papers, categories or
an answer key.

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
  "attempts": [ /* full backups only; restored with history, see below */ ],
  "scheduling": { /* optional: SM-2 card state keyed by question id */
    "q-s1": { "ease": 2.5, "intervalDays": 6, "dueAt": 1767225600000,
              "reps": 2, "lapses": 0, "leech": false,
              "lastReviewedAt": 1766601600000 }
  }
}
```

`bookmarks`, `attempts` and `scheduling` are optional and ignored by foreign files. Paper and
category ids are optional too — a missing paper id is generated, and a missing question id is
derived from the question's content so re-imports line up.

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
| correct answer | `correctOptionIds` (array or comma string) → `correctIndex` → `answer` / `correct` / `correctAnswer`, each resolved as option **id** first, then option **text** (case-insensitive), then a **zero-based** index. Absent = no answer key. Ids that match no option are dropped; if that empties the key, the next form is tried and failing that the question is ungraded (never unwinnable). |
| marks | `marks`, `points`, `weight` (default `1`; invalid/negative → `1`). A correct answer scores `marks`; a wrong one deducts `marks × negativeMarking`. `maxScore` is the sum of graded marks. |

**4. Rich content.** Where a plain string is accepted (`text`/`question`,
`explanation`/`explain`/`reason`, `options`), you can instead supply a **list of elements**.
These take precedence over the plain-string form, and they are what export writes and what the
[Word importer](#import-word-documents-docx) produces:

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

| `type` | `content` |
|---|---|
| `text` | a string; inline HTML (`<strong>`, `<em>`, `<u>`, `<del>`, `<mark>`, `<sub>`, `<sup>`) and `<math>…</math>` blocks are rendered |
| `math` | a `<math>…</math>` MathML fragment |
| `image` | an `<img src="…">` tag; the `src` is extracted |
| `table` | an array of rows, each row an array of cell strings |

In `options_elements` the object **keys are the option ids**, which is also what
`correctOptionIds` must then refer to. Only these four `type` values are read; anything else is
ignored rather than guessed at.

> **Careful:** every entry must be an element *object*. A bare string is dropped without any
> warning, so `{"a": ["Oxygen"]}` imports an option with **blank text**. Use
> `[{"type": "text", "content": "Oxygen"}]`, or just the simpler `"options": ["Oxygen"]`.

Image `src` values are portable when they are `data:` URIs or `http(s):`/`content:` links — a
local file path (`/storage/…`, `file://…`, a bare relative path) cannot travel with the JSON, so
the import warns you and the image will not display.

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

`Load sample paper` imports `assets/sample_paper.json` — **Sample Bank — English + हिन्दी**,
47 questions across 13 categories, covering everything the format can carry:

| Category | Shows off |
|---|---|
| Science (+ nested Chemistry, Physics) | states of matter, a molecule **picture**, an atom-number **table**, a **chemical equation** in MathML, a conservation-law question in Hindi |
| Physics | the `v = u + at` **formula**, an analogue **clock picture**, Planck's `E = hν` against a **spectrum picture** (with E = mc² as a distractor), a worked-values table in the explanation |
| Mathematics | the quadratic **formula**, a **bar-chart picture** read for two bars, a speed question whose explanation is a **table**, a right-triangle **diagram** plus Pythagoras, a Hindi place-value question, and four **harder** ones: differentiating a cubic (multi-correct, worked from a **table** of values), a **combinatorics** block count, an **arithmetic-series** sum, and a **probability** fraction read off a **table** of outcomes |
| Visual Reasoning | four **option pictures** (which two are quadrilaterals — multi-correct), a **pie-chart picture** with two equal sectors, a **number-line picture** |
| History | a **table of rulers as options** read for two correct pairs (multi-correct), British ascendancy in Hindi, the Titanic |
| Geography | a **dam table as options** deliberately left with **no answer key**, and the longest east-flowing river in Hindi |
| Sports | uses the legacy `name` alias for the category title |
| Multiple Correct Answers | three multi-correct questions, including one with **five options** and three right answers |
| Text Formatting | every inline tag the renderer accepts — `<strong>`, `<em>`, `<u>`, `<del>`, `<mark>`, `<sub>`, `<sup>` — used where the markup carries meaning: a retracted unit, a gas-law subscript, the emphasised right answer |
| Bilingual — English + हिन्दी | each question states itself in both languages |
| Order Check and Marks | ordered steps with `marks` of 2, 1 and 3 for verifying shuffle and weighting |
| Edge Cases | no answer key, no options, no explanation, only one option, and one question with no id that uses the older scalar `image` / `options[].image` / `explanationImage` fields |

Plus one top-level question that lands in **Uncategorized**, with a picture in its explanation.

Everything is regenerated by `tools/gen_sample_paper.py`, which draws the figures with a
built-in PNG encoder (no Pillow needed) and writes the asset. The asset is full of base64, so
edit the script rather than the JSON:

```bash
python3 tools/gen_sample_paper.py
```

Re-loading merges by stable ids — never duplicates.

## Data & storage

Room database `mcq.db`, **version 8** (`MIGRATION_3_4` adds `explanationImage` to
`questions` and `question_results`; `MIGRATION_4_5` adds `marks` to `questions`,
default `1.0`; `MIGRATION_5_6` adds `dwellSeconds` to `question_results`, default
`0`; `MIGRATION_6_7` adds the `card_state` table used by Study, starting empty so the
first session rebuilds schedules from attempt history; `MIGRATION_7_8` cascades
`correct_answers` off their question and drops already-orphaned answer-key rows;
existing installs migrate in place, and the 3→8 chain is covered by a migration
test). Deleting a paper deletes its categories; deleting a category deletes its
questions (FK cascades) — options and answer keys go with their question. Anything that
reached the paper only by id is deleted explicitly in the same transaction, because no
foreign key covers it: **bookmarks** for those questions, **history** (`attempts` for
that `paperId`, whose `question_results` cascade from them), and **SM-2 cards** in
`card_state` (already covered by a cascade, deleted anyway so the intent is visible and
does not depend on the foreign-key pragma). A single in-progress **test snapshot** in
DataStore is discarded too, but only when it belongs to the deleted paper, so another
paper's resumable session survives. Deleting attempts, bookmarks, or papers never
orphans history snapshots (attempts embed their own copies).

Test progress snapshots live in DataStore (single `in_progress_test` key, cleared on
submit); test display options (shuffle, practice, strict, auto-advance) and theme are
DataStore preferences.

Card schedules live in Room, not DataStore, because they need to survive a paper edit,
be deleted along with the paper, and be rebuilt from attempt history. See
[Spaced repetition](#spaced-repetition-study).

## Edge cases & gotchas (observed, not theoretical)

**Word imports**

- **One bad question rejects the whole document.** Unlike JSON, which skips only the broken
  row, a malformed marker anywhere in a `.docx` aborts the import with that question's number.
- **The `)` in `1.)` is not optional**, even though a stray `)` never leaks into the question
  text. `1.` alone yields "No questions found".
- **Word's automatic list numbering is invisible to the parser**, so a bank that looks perfect
  on screen can import as zero questions. This is the single most common Word failure.
- **A leading Tab destroys any marker**, including `1.)`. Ruler indentation is fine.
- **A `.docx` import cannot carry structure**: papers, categories, marks, difficulty and tags
  are all fixed (1 paper, 1 category, `marks` 1, `medium`, no tags).
- **Unresolvable answers import ungraded instead of failing** — `Ans. b, c` is not multi-answer.
- Tracked-change **insertions vanish**; turn Track Changes off before saving.

**JSON imports**

- **Answer-only edits do re-import.** The duplicate hash covers text and options, but a
  re-imported question kept under the same id with a fixed key, explanation, marks,
  difficulty, or tags updates that question instead of being skipped as a duplicate.
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

- **build**: `assembleDebug` + full JVM unit test suite (580+ tests: scoring, JSON + DOCX
  parsers + 1000-case seeded fuzz, import/export writers, domain rules, SM-2 scheduling),
  uploads
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
