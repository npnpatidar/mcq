# Phase B report — Search

**Batch:** B14 · **Reviewer:** full-lens audit of `SearchScreen`, `SearchViewModel`, `QuestionSearch` and paging/DAO paths · **Read-only.**

## Findings

| Sev | Finding | Evidence / fix |
|---|---|---|
| Medium | Scope-chip tap shows the previous scope's hits for ~300 ms with no "Searching…" cue: `debouncing` compares only the query, not the whole request. | `SearchViewModel.kt:56-65`, `SearchScreen.kt:54,92,108-118`. Derive from `SearchRequest(raw, state.scope) != committedRequest`. |
| Low | Result cards have no `onClickLabel`/role for screen readers. | `SearchScreen.kt:139-145`. Add `Role.Button` + label. |
| Low | Result count and "Searching…" are plain `Text`, not announced. | `SearchScreen.kt:108-117`. `liveRegion = Polite`. |
| Low | Hardcoded scope labels, "match/matches", "(blank text)"; count not plural-aware. | `QuestionSearch.kt:13`, `SearchScreen.kt:114-115,158`. Resources/plurals. **Owner.** |
| Low | No paging invalidation after edits/deletes elsewhere; stale hits persist on a retained back-stack entry. | `McqRepository.kt:760-768,2419-2421`, `SearchViewModel.kt:83`. Invalidate on resume/writes or accept. **Owner.** |
| Nit | Blank query on entry is a silent empty list with no prompt. | `SearchScreen.kt:107-118`. "Type to search" hint. |

## Checked with no defect

Whole-request debounce and sync-back; "N+" honesty via refresh+append end states; candidate-offset window walk (no skipped hits); SQL prefilter superset and Kotlin in-memory authority; passage/options-only/Unicode handling; entry/back/rotation and no hidden writes; refresh/append error+retry, empty and searching states.

## Test gaps / owner decisions

No Search instrumentation; no debounce/scope test (needs coroutine test dispatcher — no new dependency without approval); no localisation assertion. Decisions: blank-query hint, localisation scope, stale-result invalidation, flat vs grouped results.
