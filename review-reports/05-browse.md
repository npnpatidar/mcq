# Phase B report — Browse

**Batch:** B5 · **Reviewer:** full-lens audit (behaviour, UI/a11y, data integrity) · **Read-only.**

## Findings

| Sev | Finding | Evidence / fix |
|---|---|---|
| Medium | Single-question Delete has no confirmation, unlike bulk Delete. One tap removes the question, schedule, history, stats and bookmark (`QuestionStore.kt:420-431`). | `BrowseScreen.kt:666,1249` vs `:766-783`. Route through the confirm dialog or add undo. **Owner: behaviour/copy.** |
| Medium | Cross-paper collision (source history dropped when destination already has that card) is not disclosed in the move dialog or toast. | `strings.xml:61`; `QuestionStore.kt:526-548`. Add a line to the warning. **Owner: copy.** |
| Medium | Search query and filter chips lost on process death; category filter survives. | `BrowseViewModel.kt:118-150`, `Routes.kt:57-67`, `BrowseScreen.kt:277-280`. Use `SavedStateHandle`. |
| Medium | Save-to-file export builds the DTO on Main with O(n) sequential queries, while Share uses IO. | `BrowseViewModel.kt:738-760,783-791` vs `:824-825`. Wrap in `withContext(IO)`. |
| Low | Hardcoded "All"/"No answer"/"No explanation", export/move titles. | `BrowseScreen.kt:467-469,867,938`. Strings/plurals. |
| Low | Select-all failure invisible (`Logger.e` only); duplicate not single-shot (two clones possible); three error dialogs can stack. | `BrowseViewModel.kt:259-262,637-...`, `BrowseScreen.kt:785-822`. |
| Low | Checkbox/correct markers lack semantics; filter change silently drops selection; in-flight loads can republish after invalidation. | `BrowseScreen.kt:1154,1179,301-306`; `BrowseViewModel.kt:505-522`. |
| Nit | Uncommitted search text lost on rotation (<300 ms). | `BrowseScreen.kt:277-286`. |

## Checked with no defect

Focus deep-link and one-shot scroll; search debounce cancellation/sync-back; deliberate selection drop on filter change; passage-expanded move counts and generation guard (copy uses tick count by design); reorder guards; bulk marks validation; move/copy/delete transactions and calibration invalidation; delayed spinner and empty/error copy.

## Test gaps / owner decisions

No `BrowseViewModelTest` — debounce/sync, selection drop, count generation and error flows untested. Add Robolectric VM tests. Decisions: row-delete confirmation, collision disclosure, string resources.
