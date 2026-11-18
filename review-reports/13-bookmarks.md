# Phase B report — Bookmarks

**Batch:** B13 · **Reviewer:** full-lens audit of `BookmarksScreen`, `BookmarksViewModel`, `BookmarkExport` and share paths · **Read-only.**

## Findings

| Sev | Finding | Evidence / fix |
|---|---|---|
| Medium | Share render runs in `rememberCoroutineScope`; Back or rotation cancels the file write, so no chooser appears and a partial file can remain. Save path uses `viewModelScope` and survives. | `BookmarksScreen.kt:269`, `BookmarksViewModel.kt:121-157,81`. Move render to VM and emit a one-shot Intent. |
| Medium | Export-format rows use bare `Row.clickable` + `RadioButton` with no `Role.RadioButton`, `stateDescription` or label association; competing clicks. | `BookmarksScreen.kt:308-330,173-187`. Use `Modifier.selectable` + merged semantics. |
| Low | Hardcoded `"Export failed: ${e.message}"` in ViewModel, rendered verbatim; bypasses existing `export_failed` resource. | `BookmarksViewModel.kt:107,152`, `BookmarksScreen.kt:244`. Typed error + resources. |
| Low | Header count/export visibility use raw bookmark count while list/export use `INNER JOIN questions`; a dangling bookmark (no FK) shows a share button with an empty list. | `HistoryStore.kt:92,170-189`, `StreamingImportSession.kt:815-816`. Count resolvable rows. |
| Low | No initial-load spinner (`refresh is Loading` ignored). | `BookmarksScreen.kt:148-158,213-225`. |
| Nit | Format dialog dismissable while sharing; chooser still launches later. | `BookmarksScreen.kt:288,268-285`. |

## Checked with no defect

Drawer entry/back; Activity recreation of pending format and URIs; all 8 export formats and MIME routing; bookmarkStream null handling and passage loading; paging keyset and anchor; delete idempotency; paging error/empty/delete-last states; focus deep link into Browse; hidden writes and temp-file sweep; icon labels.

## Test gaps / owner decisions

No Bookmarks instrumentation; add VM-level share lifecycle and Robolectric count-mismatch tests. Decisions: focus destination (Browse highlighted vs full question), delete undo, resolvable-only header count, guard scope expansion.
