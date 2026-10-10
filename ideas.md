# Ideas — parked, not planned

Features explored but deliberately **not** built yet. Each entry records *why* it is
parked and what was already decided, so a future session can resume without
re-litigating the design.

---

## Passage-based questions (reading comprehension) — RESUMED

**Status:** Explored 2026-10-05. Resumed 2026-10-09 on `exp/passage-questions`;
the full research and design (with the open decisions answered against the
current codebase) now lives in **`PASSAGE-QUESTIONS.md`**. The decisions below
still stand.

**Requirement:** a passage with several questions (e.g. 5) asked from it. They must be
grouped together and occur together.

### The core tension (decided)

Togetherness means different things in the two places questions appear:

- **In a test, togetherness is a correctness requirement.** A question cannot be
  answered without the passage in front of it. So passage questions must be
  **contiguous**, the passage must be **visible** while answering each one, and
  **shuffle must move them as a block**.
- **In study (spaced repetition), forced togetherness breaks the scheduler.**
  Cards are per-question with individual due dates. Serving a whole group when one
  member is due means early reviews for the rest, which distorts their intervals
  (the same reason Anki buries siblings instead of grouping them).

**Decision:** hard togetherness in tests, shared context in study. In study each
question keeps its own schedule but its card always renders the passage. Forcing
co-occurrence in study would undo the scheduling-correctness work (Tracks A–D).

### Data model (decided)

New `PassageEntity(id, categoryId, title, text, image, sortOrder)` plus a nullable
`passageId` on questions. A passage lives in a category; member questions keep their
own `categoryId`. Scheduling (`card_state`), bookmarks, attempts and stats stay
per-question, untouched. `contentHash` includes `passageId` but not the passage text.

Rejected: passage-as-special-question (pollutes every consumer), denormalised text
(editing means touching N rows), reusing the category hierarchy (conflates
organisation with grouping; breaks drill-by-category and weak-category stats).

Migration: one new table + one nullable column, following the manual-migration pattern.

### Per-surface changes (planned, not built)

- **Test sessions:** build the session as blocks; render a passage header (sticky or
  repeated) above member questions; block shuffle (blocks shuffle, member order inside
  stays stable); drill needs an all-or-nothing rule (see open decisions).
- **Study sessions:** no queue changes; render a passage context card above the
  question. Members appear when individually due.
- **Browse:** group by passage (one header + questions beneath); search matching
  passage text surfaces the group.
- **Library:** show passage info; counts stay question-based.
- **Editor:** passage CRUD + assign/unassign + reorder (largest UI chunk after tests).

### Import / export (planned, not built)

- **JSON:** top-level `passages` array + `passageId` on questions (mirrors categories).
- **DOCX:** proposed new line-start marker `Passage:`; questions until the next marker
  (or end) belong to it; questions before any marker stay standalone. `N.)` split
  untouched.
- **APKG:** Anki has no passage concept. Display: prepend passage text to each
  member's Front with a separator. Round-trip: the existing `mcqapp` JSON blob carries
  `passageId` + passage text. Foreign apkg files have no passages.

### Sequencing (if resumed)

Roughly 8–10 focused commits: data model + migration → repository helpers → JSON
round-trip → test-session blocks + header + block shuffle → study context card →
browse grouping + search → DOCX marker + README + tests → APKG prepend + blob →
editor CRUD → drill rule.

### Open decisions (unanswered)

1. Drill rule: all-or-nothing with overshoot display, or exclude passages that
   don't fit?
2. Passage-internal order in shuffled tests: always stable, or shuffle within block?
3. Study adjacency: is shared context enough, or soft grouping (members adjacent
   when individually eligible)?
4. DOCX marker: is `Passage:` acceptable, or do source documents use a convention
   to match?
