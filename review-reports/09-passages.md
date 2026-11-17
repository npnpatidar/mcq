# Phase B report — Passages

**Batch:** B9 · **Reviewer:** full-lens audit of `PassagesScreen`, `PassageStore`, repository wrappers and tests · **Read-only.**

## Findings

| Sev | Finding | Evidence / fix |
|---|---|---|
| **High** | Stale member count can silently suppress the mandatory cross-paper warning. `crossesPaper` uses `state.memberCounts`, refreshed only on `passages` Flow emissions (`SELECT * FROM passages`), which do not emit when membership changes in `questions`. Membership can change elsewhere while this screen stays on the back stack; a later cross-paper save then moves the whole group without confirmation. | `PassagesScreen.kt:399,122-128`, `Daos.kt:233`, `PassageStore.kt:96-105`. Re-read `passageMemberCount` at save decision time. |
| Medium | Cross-paper "Cancel" silently discards all form edits despite the comment promising otherwise: the edit dialog leaves composition when the confirmation appears, so its `remember` fields are destroyed. | `PassagesScreen.kt:184-185,390,456-458,475-477`. Hoist draft to VM/`rememberSaveable`. **Owner: intended contract?** |
| Medium | Rotation/process death loses every open dialog and typed text (plain `remember`). | `PassagesScreen.kt:179-186,475-477,573`. |
| Medium | No-op save can destroy rich content when the flattened body has edge whitespace: `edited = text.trim()` compared to untrimmed `initial.text`. | `PassagesScreen.kt:536-551`. Compare consistently. |
| Low | Collision-case warning copy is inaccurate (source history is dropped). | `strings.xml:61`, `PassageStore.kt:137-140`. **Owner: copy.** |
| Low | Pager `LoadState.Error` rendered as "No passages yet" with no retry; flat global list vs per-category reorder looks arbitrary; boundary moves are silent no-ops; delete guard race gives silent refusal; `LaunchedEffect(Unit)` can leave a new passage permanently unsavable; member row lacks click semantics. | `PassagesScreen.kt:363-376,155-157,144-153,488-492,328-345`. |

## Checked with no defect

Dialog mutual exclusion; rich-content preservation on the normal path; category-only passage moves do not trip content hash; cache invalidation after store call; delete count double-check; reorder transaction; single-expanded pager and member navigation; grouped COUNT efficiency; a11y basics.

## Test gaps / owner decisions

No test that Cancel restores edits, rotation, stale-count warning, pager error or whitespace preservation. Decisions: Cancel contract, collision copy, list grouping and whether flat-text editing should be replaced by a block editor.
