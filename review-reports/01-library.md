# Phase B report — Library area

**Batch:** B1 · **Reviewers:** behaviour/actions, UI/accessibility, data integrity (independent, parallel).
**Scope:** `ui/library/**` (LibraryScreen, LibraryDrawer, LibraryPaperCard, dialogs, TestSetupSheet, BlueprintEditorSheet, LibraryViewModel), repository/store paths and tests. Read-only; no product code changed.

## Severity summary

| Severity | Count | Themes |
|---|---:|---|
| High | 2 | rotation loses editor/sheet state and blueprint identity |
| Medium | 13 | destructive disclosure, dialog stacking, double-taps, non-atomic edits, import rollback, CSV/stale scope |
| Low/Nit | 14 | localization, a11y semantics, layout, dead code |

## Findings

### High

**H1 — Rotation discards paper/category/test-setup state.** `LibraryScreen.kt:119,122,126` keeps `editingPaper`, `pendingTestSetup`, `editingCategory` in plain `remember` while neighbouring flags are `rememberSaveable` (`:117,123-125,134`); nested state is also plain `remember` (`TestSetupSheet.kt:213,241,243`; `BlueprintEditorSheet.kt:81-83`). Rotating while setting up a test or editing a paper closes the flow and loses typed input. Saved instance state is the documented mechanism ([runtime changes](https://developer.android.com/guide/topics/resources/runtime-changes), [rememberSaveable](https://developer.android.com/reference/kotlin/androidx/compose/runtime/saveable/rememberSaveable)). Fix: hoist ids/flags to the ViewModel or provide savers; make `BlueprintEditorSheet.parts` saveable.

**H2 — Blueprint edit silently becomes create after rotation.** `TestSetupSheet.kt:241` `editingBlueprintId by remember`, resolved at `:1073` and passed as `editing = null` when lost (`:1076-1079`); the save branches on `editing` (`:1082-1088`). The name field survives (`BlueprintEditorSheet.kt:77`) while `parts` does not (`:78`), so Save attempts to create a duplicate or fails uniqueness. Fix: derive identity from the saveable `variant` (`:173`).

### Medium

- **M1 — Delete-impact copy understates data loss.** `LibraryScreen.kt:584-608` lists questions/attempts/bookmarks/schedules; `PaperStore.kt:200-225` also sweeps `review_log`, `question_stats`, resume snapshot and cascades categories/options/answers. Category delete has no impact breakdown at all (`LibraryPaperCard.kt:679-691,716-737`) despite the same sweep (`:252-282`). Counts are accurate for what they name; the history/statistics are invisible. **Owner decision: copy.**
- **M2 — Category edit is two non-atomic writes.** `LibraryViewModel.kt:639-648` calls `renameCategory` then `reparentCategory` as separate commits; the latter's `false` (cycle/cross-paper) is discarded (`:944`/`PaperStore.kt:413-425`). Rename can land alone. Fix: one transaction or surface refusal.
- **M3 — Stale selection after category delete.** `LibraryViewModel.kt:662-672` deletes a category but leaves its id in `_selection` (contrast `deletePaper` `:537-538`). Ticked dead ids make Test/Study scope expand to zero and block Start (`TestSetupSheet.kt:253-255`). Fix: prune selection on delete and on `papers` emission.
- **M4 — Dialog/sheet stacking and shared error slot.** One `_exportError` serves import/export/delete/category errors (`LibraryViewModel.kt`) and renders independently of open sheets (`LibraryScreen.kt:634-643`); errors can appear over the setup sheet. Nested sheets/dialogs stack (`TestSetupSheet.kt:1072-1140`). Save coroutines use `rememberCoroutineScope` in `BlueprintEditorSheet.kt:378-402`, cancelled if the sheet is dismissed mid-save.
- **M5 — Drawer navigation double-tap.** Seven drawer rows call `close` + plain `navigate()` (`LibraryDrawer.kt:99-206`); two fast taps queue duplicate destinations. Use `launchSingleTop` ([NavOptions](https://developer.android.com/reference/kotlin/androidx/navigation/NavOptions.Builder)).
- **M6 — "Load sample" double-tap.** `LibraryScreen.kt:313` has no loading guard; a second tap starts a second import. Disable while loading.
- **M7 — Partial import on failure.** `AnkiApkgImporter.kt:53-94` has no enclosing transaction; `StreamingImportSession.kt:198,911` commits per batch. A corrupt file leaves committed batches, and dedupe then protects the partial state. **Owner decision: rollback contract.**
- **M8 — Export failure leaves truncated file.** `LibraryViewModel.kt:497-504` streams directly to the SAF URI; a mid-write error leaves an invalid file with no cleanup. Render to a temp file, copy on success.
- **M9 — Misleading composition label.** `TestSetupSheet.kt:1285-1286` maps authored-hard/authored-medium fills to `R.string.test_setup_fill_recent` ("recent") while a distinct authored-easy string exists (`:460`).
- **M10 — Failed delete is reported after the dialog closes.** `LibraryScreen.kt:619-624` cancels the confirm dialog then calls `deletePaper`; failure leaves the screen with a stale card and no context.
- **M11 — Test setup loader failures are swallowed.** `runCatching{}.getOrDefault(emptyList())` at `TestSetupSheet.kt:199,206,218,231,279,328,382` makes load failure indistinguishable from empty pools; Start still runs the real selector (`:110-116`).
- **M12 — Panel/blueprint delete reports success on failure.** `TestSetupSheet.kt:1055,1126` and `BlueprintEditorSheet.kt:441-444` ignore `runCatching` results and remove the row anyway.
- **M13 — Dwell/verdict colour-only and hardcoded copy in study-adjacent strings** are tracked in the Study report; Library's own hardcoded dialogs/VM errors are listed under L1.

### Low / Nit

- **L1 — Hardcoded English and plural logic:** `LibraryScreen.kt:581-615,652-668,747-749`; `LibraryViewModel.kt:109,112,139,143,164,169,344,372,392,413,449,486,526,543,564,585,598,609,621,633,646,657,669,729,746`; `PickedArchive.kt:123-124`; `domain/TestPlan.kt:229-256`; `Models.kt:204-207`. Raw exception text leaks into dialogs. Resource names `sm_2_*` at `strings.xml:559-560` now describe FSRS. Non-plural `"%1$d categories"` (`strings.xml:631-632,465-470`).
- **L2 — Checkbox/radio rows unlabelled:** `LibraryDrawer.kt:329-338`, `LibraryPaperCard.kt:455-464`, `LibraryDialogs.kt:224-229,258-263`, `LibraryScreen.kt:763-784`. TalkBack hears "checkbox, checked" with no category/format name; tapping the label text does nothing.
- **L3 — Raw `clickable` rows** (`LibraryDrawer.kt:95-206`, `LibraryPaperCard.kt:595-607`, `LibraryScreen.kt:764-769`) have no `Role.Button` or `onClickLabel`.
- **L4 — Non-scrollable dialog at large font scale:** `LibraryDialogs.kt:55-88` packs four text fields with no `verticalScroll`; Save/Cancel can be pushed off screen.
- **L5 — Disabled controls give no reason:** `LibraryDialogs.kt:99,147,192`; `TestSetupSheet.kt:845,673`; `LibraryPaperCard.kt:383-402`.
- **L6 — Errors not announced:** `LibraryScreen.kt:638`, `TestSetupSheet.kt:942-948`, `BlueprintEditorSheet.kt:352-372` use plain `Text`; Toast-only share failure at `LibraryScreen.kt:735-737`.
- **L7 — Stepper controls unlabelled:** `BlueprintEditorSheet.kt:277-295` `Text("−")/value/"+"`; pristine-form validation errors shown immediately (`:352-364`).
- **L8 — Long titles/count line unclipped:** `LibraryPaperCard.kt:165-185`; font scale/landscape risk.
- **L9 — Legacy test fallback** (`LibraryScreen.kt:253-259`) silently runs a whole-paper legacy test when the paper record is missing.
- **L10 — Scope wording:** whole-paper vs ticked-categories Study buttons (`LibraryPaperCard.kt:227-281`) and "N categories" vs question counts (`LibraryDrawer.kt:271-276`) confuse new users. **Owner: copy.**
- **L11 — Import stream opened up to 3×** (`LibraryViewModel.kt:98-193`); single-use providers could fail.
- **L12 — Unused resources/dependencies and dead helpers** from Phase A: `exportAll`, `writeUri`, 24 strings, `ui-tooling-preview`.
- **N1 — Export-format row double event** (`LibraryScreen.kt:763-774`).

## Checked with no defect

Delete transaction completeness and cascade sweeps; tri-state/nested category logic; paper/category reorder normalization and edge buttons; blueprint validation, uniqueness, atomic save and orphan-section warning; preview and Start share one selector/plan; Start disabled during loading/blocked states; SAF target survives Activity recreation; paper-card icon labels; M3 delayed-visibility empty-state handling; Room serializes independent row mutations, so two rapid taps on different rows are safe under the honest-user premise.

## Test gaps

1. Tick a category → delete it → assert `selectedFor` no longer contains it (Robolectric).
2. Failing import stream after batch 1 → assert committed rows match the agreed contract.
3. `OutputStream` throwing after N bytes → assert no usable export file remains.
4. Multi-review delete-impact count test; rename+reparent partial failure test.
5. One Library emulator test covering rotation of dialogs/sheets and two rapid row taps.

## Owner decisions

- Exact delete-impact copy (including review history and in-progress test discard).
- Import rollback scope and whether partial imports are disclosed.
- Removal of dead helpers/dependencies and localization scope.
- Wording for scoped-vs-whole-paper Study/Test controls.
