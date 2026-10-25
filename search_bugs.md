# Eighth bug report — scoped pass: the search feature

A scoped pass over search — **status:** report only, nothing fixed yet.
Files covered: the cross-paper search screen and its ViewModel
(`SearchScreen.kt`, `SearchViewModel.kt`), the domain filter
(`QuestionSearch.kt`), the repository pipeline (`McqRepository.kt`:
`searchPage` / `searchGlobal` / `SearchPagingSource` / `BrowseScope`),
the DAO queries (`Daos.kt`: `search`, `searchIncludingOptions`,
`pagingRefsByCategories` and its count/cursor twins), the text shapes the
search matches (`Models.kt`: `searchableText`, `passageSearchText`,
`previewText`, `stripMarkup`), every write path that maintains the search
columns (`QuestionStore.kt`, `Importer.kt`, `QuestionContentMapper.kt`),
the migrations/backfills that backfill them (`AppDatabase.kt`
14→15, 17→18), and Browse's search-field wiring (`BrowseScreen.kt`,
`BrowseViewModel.kt`). Tests read: `QuestionSearchTest`,
`GlobalSearchPrefilterTest`, `PassageRepositoryTest`.

Verified locally before writing: the pure-JVM search suite
(`QuestionSearchTest`) passes on this host; Robolectric/SQLite classes
(`GlobalSearchPrefilterTest`, `PassageRepositoryTest`) cannot run on
aarch64 — CI remains authoritative for anything marked "needs CI".

Already-known findings are not repeated: the folded-column migration work
(the `CAFÉ` veto, bugs in the 17→18 series), the `updateNonHashedFields`
stale-`tagsFolded` bug and its fix, and the prefilter-against-JSON-`text`
bug the `prefilterKey` fragment exists for. What follows is new.

**Confidence legend:** **Confirmed** = defect proven by reading the current
code and tracing both sides of the inconsistency end to end; **Likely** =
code defect real, one reachability step depends on data shape or timing.

---

## Summary

| # | Sev | Area | Bug | Conf. |
|---|-----|------|-----|-------|
| 1 | Medium | Repository | Global search's passage post-filter string is built `title + "\n" + body`; the stored column (and Browse) use `title + " " + body` — a phrase straddling the title→body boundary is found by SQL, vetoed by the authority, and two search surfaces disagree | Confirmed |
| 2 | Medium | DAO/Repository | `Scope.OPTIONS` reuses the options+text+tags+passage prefilter while the authority accepts only option matches — a query that matches stems but no options walks every candidate window to exhaustion | Confirmed |
| 3 | Low | ViewModel | `committed` debounces only the query; a scope tap mid-typing commits the stale query immediately, and the debounce timer is not restarted | Confirmed |
| 4 | Low | Screen | `LoadState.Error` matches no branch of the status `when`: a failed search renders a blank area — no message, no retry (refresh *and* append) | Confirmed |
| 5 | Low | Browse screen | The two-way search-input sync can revert a keystroke typed in the frame after the debounced commit lands | Confirmed (narrow) |
| 6 | Nit | Domain | Orphaned KDoc above `prefilterKey`: `escapeLike`'s doc is detached from its function | Confirmed (cosmetic) |
| 7 | Nit | Screen | `SearchScreen`'s `repository` parameter is unused; a dead trailing `Spacer(Modifier.width(8.dp))` | Confirmed (cosmetic) |
| 8 | Nit | Paging | `getRefreshKey()` returns `null`: every invalidation refreshes from offset 0 and drops the scroll position; LIMIT/OFFSET windows over a concurrently-mutated candidate set can transiently duplicate a row (duplicate LazyColumn key) until Room's invalidation lands | Confirmed (design note) |

---

## Findings

### 1 · MEDIUM · Repository — the passage post-filter string disagrees with the stored column

**The two strings.** A passage's searchable text is written by
`passageSearchText` (Models.kt:158-159):

```kotlin
fun passageSearchText(title: String, elements: List<ContentElement>): String =
    (title + " " + elements.searchableText).trim()
```

Every writer stores it folded (`QuestionContentMapper.kt:102-103`,
`Importer.kt:892-893`, the 14→15 backfill `AppDatabase.kt:588-590`), and the
`PassageEntity.searchText` doc (PassageEntity.kt:39-43) calls this "the string
global search matches so a member can be found through its shared context".
Global search's SQL prefilter honours that: both DAO queries match
`passages.searchTextFolded LIKE '%' || :query || '%'` (Daos.kt:1214-1215,
1232-1233).

But the in-memory authority never sees that string. `McqRepository.searchPage`
builds its `passageBodies` map with a different shape (McqRepository.kt:274-278):

```kotlin
passage.id to (passage.title + "\n" +
    passage.text.parseContentElements(mapper.json).searchableText)
```

— a **newline** joiner, and no `trim()`. `QuestionSearch.filter` then vetoes
any candidate whose match lives only in `passageBodies`
(QuestionSearch.kt:78-80).

**The failure, end to end.** Title `"The water cycle"`, body
`"Rain fills rivers."` (the exact passage of
`PassageRepositoryTest.globalSearchFindsAMemberThroughItsPassageText`):

- stored column (folded): `the water cycle rain fills rivers.`
- authority string: `the water cycle\nrain fills rivers.`

Query `"cycle rain"`: the DAO's passage branch matches the column
(`cycle rain` is literally present), the member question becomes a candidate —
and the post-filter rejects it (`cycle rain` does not occur across the `\n`).
Global search shows "no matches" for a phrase that is in the app's own
searchable string.

**Two surfaces disagree.** Browse's SQL-only query matches the same folded
column for its passage branch (`Daos.kt:663-664`), with no post-filter to
second-guess it — so Browse *finds* the question for `"cycle rain"` while
global search does not, on identical data. That asymmetry is what makes this a
defect rather than a taste question: the prefilter/authority contract
("prefilter is a superset, filter is the authority") silently becomes
"two different definitions of the passage text" depending on which screen
asks.

**Why the tests miss it.** The existing coverage only exercises phrases fully
inside the title ("water cycle") or fully inside the body ("fills rivers")
(PassageRepositoryTest.kt:269-272); the domain test builds its own
`passageBodies` with the same `\n` joiner (QuestionSearchTest.kt:101), so it
pins the repository's shape rather than the column's. No test compares the
post-filter string against what the importer actually stores.

**Fix sketch.** Build the map with the one canonical builder:

```kotlin
passage.id to passageSearchText(
    passage.title, passage.text.parseContentElements(mapper.json)
)
```

(`filter` lowercases the value itself, matching the current behaviour.) The
domain test's own map stays as it is — it tests `filter`, not the repository —
but a Robolectric assertion belongs in
`globalSearchFindsAMemberThroughItsPassageText`:
`searchGlobal("cycle rain")` must return `q1` (needs CI on x86_64).

### 2 · MEDIUM · DAO/Repository — `Scope.OPTIONS` walks every candidate to exhaustion

The scope dispatch (McqRepository.kt:264-269) sends QUESTION to the
text+tags+passage prefilter and **everything else** — OPTIONS included — to
`searchIncludingOptions`, whose candidate set is the union of question text,
tags, option text and passage text (Daos.kt:1225-1241). The authority for
OPTIONS, though, accepts **only** option matches
(`QuestionSearch.kt:81-85`: `Scope.OPTIONS -> inOptions`).

So for `Scope.OPTIONS`, every question-text-only candidate is fetched,
parsed (`toDomainBulk` reads the elements JSON and loads every option row)
and then discarded. The pager's loop (`SearchPagingSource.load`,
McqRepository.kt:868-895) keeps pulling 200-candidate windows until it has 30
post-filtered hits or the candidates run out. A query that matches many stems
but no option text — any common word on a real bank ("water", "which",
"correct") — is the pathological case: tens of thousands of candidates,
hundreds of window queries, and every question in the bank parsed with its
options, all to end at "no matches". The QUESTION scope's DAO is correctly
scoped (no options branch, Daos.kt:1207-1223); OPTIONS is the one that
over-fetches.

This is performance, not correctness — the final answer is right — but the
cost lands on the exact query shape users type while poking at the Options
chip.

**Fix sketch.** An options-only prefilter for the OPTIONS scope
(`options.searchTextFolded LIKE :query OR LIKE :fragment`, joined back to
questions), keeping the two-phase pipeline unchanged. Scope.ALL genuinely
needs the question-text branches, so `searchIncludingOptions` stays for it.

### 3 · LOW · ViewModel — scope changes commit a stale query

`committed` (SearchViewModel.kt:51-55) is
`combine(query.debounce(300), scope)`. `debounce` gates only the query flow:
a `updateScope` tap re-emits `combine` **immediately** with the last
*debounced* query — which, mid-typing, is the previous one. Two visible
effects:

- the pager restarts on `SearchRequest(oldQuery, newScope)` and paints a
  window of results for text the user has already moved past, then replaces
  them 300 ms later;
- `debouncing` (SearchViewModel.kt:57-61) compares only the query, so it is
  `false` during that stale reload — the "Searching…" indicator does not show
  (it falls back to the `refresh Loading && itemCount == 0` branch only when
  the list is empty).

Same root cause, second symptom: because the scope side is not debounced, the
query's pending debounce timer is not restarted either — a scope tap during
the 300 ms window schedules both the stale commit *and* the real one.

**Fix sketch.** Debounce the combined request
(`combine(query, scope).debounce(300)`) so a scope tap waits for typing to
settle, and/or make `debouncing` compare the whole `SearchRequest`.

### 4 · LOW · Screen — a failed search has no surface

The status block (SearchScreen.kt:101-112) handles Loading and NotLoading,
never Error. With `refresh is LoadState.Error` and zero items, **no branch
matches**: the user sees a blank area below the chips — no message, no retry —
indistinguishable from a hung search. Append errors are equally silent (the
"+" spinner just never appears again; the list ends early). The paging source
does its part — `SearchPagingSource` wraps every failure in
`LoadResult.Error` (McqRepository.kt:892-894) — the screen drops it.

**Fix sketch.** An `is LoadState.Error ->` branch with a message and a Retry
that calls `hits.retry()`, mirroring the pattern Stats' error box has (its
missing retry is the stats report's #8).

### 5 · LOW · Browse screen — search input can be reverted mid-typing

The field state is a local `remember { mutableStateOf("") }` with two
`LaunchedEffect`s keeping it in sync with the ViewModel
(BrowseScreen.kt:228-237): the first debounces typing into
`viewModel.updateQuery`, the second copies `filterState.query` back into
`searchInput` whenever they differ. The race: `updateQuery("foo")` fires at
t=300; the recomposition carrying `filterState.query = "foo"` lands a frame
later; a keystroke in that frame (`"foob"`) is then **overwritten by the
sync** (`searchInput = "foo"`), and the effect driving the newer value is
cancelled with its key. The user's last keystroke disappears. The window is
one frame, so it is rare — but the direction of the sync is what makes it
possible at all: the ViewModel copy wins ties over the field the user is
typing into.

**Fix sketch.** Only sync back when the field is not focused, or key the
commit on the request generation rather than string inequality.

### 6 · NIT · Domain — `escapeLike`'s KDoc is orphaned

QuestionSearch.kt:15-20 is a complete KDoc ("Escapes a user query for use
inside a SQL `LIKE` pattern…") floating above the *second* KDoc that actually
documents `prefilterKey` (QuestionSearch.kt:21-37). `escapeLike`
(QuestionSearch.kt:52-57) — the function the orphaned doc describes — has no
doc of its own. Move it down; no behaviour change.

### 7 · NIT · Screen — dead surface on `SearchScreen`

`repository: McqRepository` is a parameter but the screen talks only to
`viewModel` (SearchScreen.kt:48-54); `Spacer(Modifier.width(8.dp))` at
SearchScreen.kt:154 trails the row's `weight(1f)` column and does nothing.
Both are leftovers of the pre-paging screen.

### 8 · NIT · Paging — refresh semantics of `SearchPagingSource`

Two design notes, not defects on their own:

- `getRefreshKey()` returns `null` (McqRepository.kt:897-900): any Room
  invalidation re-loads from candidate offset 0 and the list jumps to the
  top. For search results this is defensible (matches change), but a
  mid-list bookmark write elsewhere would yank the user back to page one.
- The candidate windows are `LIMIT/OFFSET` over a live table
  (Daos.kt:1216): a row deleted between two window reads shifts every later
  offset, so the *same generation* can append a duplicate of a hit already
  shown. `hits.itemKey { it.question.id }` then sees the same key twice in
  one LazyColumn. Room's invalidation refreshes the generation and heals it,
  so the exposure is the gap between a write committing and invalidation
  landing — but the failure mode is a hard crash
  (`IllegalArgumentException: Key was already used`), not a visual glitch.
  The same shape exists in Browse's pagers, where selection + delete makes
  the write source the same screen.

---

## Checked and sound

For the record, paths traced that came out clean:

- **Fold consistency.** Every writer stores `searchTextFolded = searchableText.lowercase()` and `tagsFolded = tags.lowercase()` in Kotlin (`QuestionStore.kt:246/253/267`, `Importer.kt:848/855/874`, `QuestionContentMapper.kt:103`), the 17→18 backfill folds in Kotlin with `String.lowercase`, not SQL `lower()` (`AppDatabase.kt:468-513`), and every binder lowercases the query in Kotlin (`McqRepository.kt:255/262`, `McqRepository.kt:539-540`). No A–Z-only fold remains on any live path.
- **`escapeLike` coverage.** `%`, `_` and `\` are escaped on both the whole-query and fragment binds, matching `ESCAPE '\'` in every query that takes a user string; Browse's `BrowseScope.of` escapes the trimmed, folded query the same way (`McqRepository.kt:539-540`).
- **Fragment superset argument.** The longest-run fragment is a substring of any text the whole-query branch would match (words of a contiguous phrase are contiguous), with the `MIN_PREFILTER_RUN = 2` fallback returning the trimmed query for degenerate inputs — the DAO stays a superset of the in-memory filter for question text and tags. (The passage branch is the one place the superset claim breaks — finding #1, but in the direction of *over*-matching, which the authority then corrects; the reverse — authority accepting, DAO missing — needs a `\n` in the query, which a `singleLine` field cannot produce short of a paste edge case.)
- **UNION dedup and ordering.** Both search DAOs dedup the options-join duplicates via `DISTINCT`/`UNION` and order by the unique `qrowid`, so candidate windows are disjoint and stably ordered — no duplicate hits across pages from the query itself.
- **Paging loop termination.** `nextOffset` advances only on a full window and is assigned before the `null` check (`McqRepository.kt:879-885`), so a terminal window clears the key — no re-fetch loop, no skipped tail.
- **Column maintenance.** `saveQuestion`, `insertCopies`, both importers and the passage mapper rewrite `searchText`/`searchTextFolded`/`tagsFolded` on every content write; `updateNonHashedFields` deliberately skips the stem but rewrites tags and `tagsFolded` together (the old stale-twin bug stays fixed).
