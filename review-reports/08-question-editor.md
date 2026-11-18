# Phase B report — Question editor

**Batch:** B8 · **Reviewer:** full-lens audit of `QuestionEditorScreen`, `EditorViewModel`, `BlockListEditor`, `EditorSession`, `MathLiveEditor` and called paths · **Read-only.** The earlier editor audit is historical; findings below are from current code.

## Findings

| Sev | Finding | Evidence / fix |
|---|---|---|
| **High** | Formula edits after reordering write into the wrong block. Compose keys are stable (`blockKey(owner, blockId, …)`) but `MathLiveEditor`'s JS callback captures the positional `index`; `AndroidView.factory` installs it once and `update` never rebinds. | `BlockListEditor.kt:76-81,144-148`, `MathLiveEditor.kt:120-175`, `EditorViewModel.kt:690-693,869-873,920-923`. Pass stable `blockId` in the callback and resolve at callback time (mirror image-target). Affects body, option and explanation lists. |
| Medium | `EditorSession` four volatile fields read/written non-atomically; screen can compose a transient mismatched counter/neighbours. | `EditorSession.kt:41-49,105-120`, `QuestionEditorScreen.kt:131-134,152-185`. Publish one immutable snapshot. **Owner: refactor for a cosmetic flicker?** |
| Medium | Any content edit (including Prev/Next auto-save) silently deletes the study schedule; UI never says so, and a KDoc falsely claims the passage label states it. | `QuestionStore.kt:271-287`, `CardContent.kt:13-27`, `QuestionEditorScreen.kt:803-805`, `strings.xml:291`. Inline note near Save. **Owner: copy/scope.** |
| Medium | WebViews unlabelled; errors/counter not announced; success is Toast-only. | `MathLiveEditor.kt:120-127`, `ContentElements.kt:464-531`, `QuestionEditorScreen.kt:152-157,458-526`. Labels + Snackbar/announcements. **Owner: copy.** |
| Low | Hardcoded English errors and "Saved" Toast; half-typed tag follows the user across Prev/Next. | `EditorViewModel.kt:492-494,1115,1162,1183,1244,1259,1316,1357,1399`; `QuestionEditorScreen.kt:154,165,177,416,449`. |

## Checked with no defect

Route/source contract and import fail-closed; dirty leave dialog; operation gating; atomic `MoveTarget` and re-anchor; image-pick target by stable id with off-main encode; block/table keys and boundary controls; marks validation; tag dedupe; WebView hardening and teardown; delete cascade and cache invalidation; rotation survival via VM/`rememberSaveable`.

## Test gaps / owner decisions

No test for formula-edit-after-reorder (needs emulator/JS bridge or identity-callback unit test), runtime a11y, session torn reads or image-pick rotation. Decisions: schedule-reset disclosure, session snapshot refactor, a11y scope.
