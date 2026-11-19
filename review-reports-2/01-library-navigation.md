# Round-2 review — Library screen & app backbone

**Branch:** `feature/review-round-2` @ `fbc8e15` (= main, release 0.0.61). Read-only; no product/test/docs code changed.
**Scope:** `ui/library/**` (LibraryScreen, Drawer, PaperCard, Dialogs, TestSetupSheet, BlueprintEditorSheet, PickedArchive, ViewModel), `ui/navigation/**`, `MainActivity.kt`, `McqApplication.kt`, manifest, first-run seeding, import entry, destructive dialogs, rotation/process-death state; cited data-layer helpers where a UI claim depends on them.
**Method:** old `review-reports/01-library.md` + `00-baseline.md` treated as **claims**; `git log --oneline 0.0.53..HEAD -- app/src/main/java/com/mcqapp/ui/library .../navigation` and full diffs read. Platform claims checked against official docs, cited inline.

## Verdict

H12 and most medium claims (atomic edit, selection pruning, import rollback, export temp-file, localized delete copy, a11y announcements) **are genuinely fixed**. But the same batch introduced/retained two cancellation- and rotation-driven state bugs in the entry points, and the Hindi/i18n and preview-copy passes missed several user-visible strings. One High finding (SEND replay on every rotation) is new — the earlier audit flagged the *related* missing `launchMode` but never traced `onCreate`.

## Findings

**L1 — High — A shared file is re-imported and re-navigated on every Activity recreation.** `MainActivity.kt:75` calls `handleSendIntent(intent)` unconditionally in `onCreate`; `:89-100` re-copies and calls `SharedImport.request`. `McqNavHost.kt:98-117` consumes it and `navController.navigate("import/direct")`. Android recreates the Activity (and calls `onCreate`) on rotation, with the **same** SEND intent — "the system calls `onDestroy` … then creates a new instance using `onCreate`" and "The system may call `onCreate()` several times … such as when a device screen is rotated" ([runtime changes](https://developer.android.com/guide/topics/resources/runtime-changes), [ViewModel lifecycle](https://developer.android.com/topic/libraries/architecture/viewmodel)). Manifest has no `configChanges` (`AndroidManifest.xml:16-19`). Effect: rotate mid-import → typed ImportScreen input lost and a duplicate `import/direct` pushed; rotate later in Study/Test → learner is yanked back to ImportScreen. Even a failed re-copy produces an error snackbar on every rotation. *Why missed:* 00-baseline §5.4 only noted the missing `launchMode` for a *second* SEND; no one read `onCreate` as a replay path. *Fix:* gate on `savedInstanceState == null`, or mark the intent consumed in `SharedImport`; keep `onNewIntent` as the only in-task entry.

**L2 — Medium — Test-Start tap is silently lost if rotation lands between clearing the sheet and navigation.** `LibraryScreen.kt:548-578`: `onStart` clears `pendingTestSetup`, then does async blueprint lookup and `navController.navigate` inside `rememberCoroutineScope()` (`:122`). That scope is cancelled when the composable leaves composition ([Compose side-effects](https://developer.android.com/develop/ui/compose/side-effects): "The scope will be cancelled when the call leaves the Composition"). After rotation the sheet is gone and no test starts. *Why missed:* the rotation fix hoisted the *sheet state* to the VM but left the navigation side effect in composition scope; `LibraryRotationStateTest` only tests pure helpers. *Fix:* build the route in `LibraryViewModel`/`viewModelScope` or use a scope that outlives recreation.

**L3 — Medium — Blueprint save can be cancelled into a half-state.** `BlueprintEditorSheet.kt:417-445` saves via `rememberCoroutineScope`; Cancel (`:462`) and swipe-to-dismiss (`ModalBottomSheet`, `:243`) stay enabled while `saving = true`. Dismissing mid-save cancels the scope ([side-effects](https://developer.android.com/develop/ui/compose/side-effects)); either the Room transaction rolls back with no error shown, or it commits but `onSaved(id)` (`:437`) is skipped, leaving the picker's `variant` unset and Start disabled with "cannot use a blueprint". *Why missed:* old M4 named this risk but the fix only changed identity/savers, not the coroutine owner. *Fix:* disable dismissal while saving or move the save to `viewModelScope`.

**L4 — Medium — Composition preview mislabels authored and blueprint fills.** `TestSetupSheet.kt:1320-1338` maps `AUTHORED_HARD` and `AUTHORED_MEDIUM` to `test_setup_fill_recent` ("recent" / Hindi "हाल के"), and has no branch for `BLUEPRINT` / `BLUEPRINT_FILL` (emitted at `TestSelector.kt:333,810,819`), so they fall through to "random". An authored-hard run reads "12 recent"; a blueprint section reads "20 random". *Why missed:* earlier M9 asked for an authored-easy string; the fix added only `AUTHORED_EASY` (`:1333`) and left the other mappings. *Fix:* add distinct authored-hard/medium, blueprint and blueprint-fill labels in both locales.

**L5 — Medium — The whole export-format dialog stays English under the Hindi locale.** `LibraryScreen.kt:778,780` hardcode "Export format" / "Export selection (N categories)"; `:808,810` render `format.title` / `format.description`, which are English literals in `ExportFormat.kt:11-60` (same pattern in Browse and Bookmarks). The Hindi release (`c68ace5`) translated dialogs around it but not the data-held copy. *Why missed:* earlier L1 listed generic "hardcoded English" without opening the export dialog; resource-usage tests cannot flag plain `Text` literals. *Fix:* resource ids on `ExportFormat` resolved with `stringResource` at each call site.

**L6 — Medium — "Picked a backup" error is hardcoded English.** `PickedArchive.kt:122-124` returns a literal shown verbatim in the error dialog via `LibraryViewModel.kt:225,262` → `LibraryScreen.kt:662-671`. Earlier L1 named this exact file:line; it is still literal after the Hindi release. The comment (`:115-120`) claims a string resource "has to sit in a Text-like sink", but the VM already exposes a `(resId, args)` channel elsewhere (`SharedImportError`, `UiMessage.kt:37`) — use it. *Fix:* send resId+args through `exportError` or announce a resource-backed message.

**L7 — Medium — Failed panel/blueprint deletes still disappear from the UI.** `TestSetupSheet.kt:1093-1097` and `:1176-1180` ignore `runCatching { onDelete… }` and unconditionally drop the row/clear `variant`; `BlueprintEditorSheet.kt:485-488` does the same and closes. A locked DB or failed delete reads as success until a later reload. Earlier M12 was not fixed. *Fix:* propagate Result; on failure keep the row and surface a localized error.

**L8 — Low — Paper-card summary line is untranslated and a stale-cache source.** `LibraryPaperCard.kt:179-183` builds `"N questions • M min • -X neg"` as a literal (Hindi UI sees English). Also `loadSampleData()` (`LibraryViewModel.kt:849-864`) reads/parses the 118 KB asset on `viewModelScope` (Main) with no in-flight guard, so the empty-state button (`LibraryScreen.kt:330`) can be double-tapped into two concurrent imports (old M6). *Fix:* plural/string resources; `withContext(IO)` + an `_isLoadingSample` flag.

**L9 — Low — Dialog/a11y leftovers from the earlier audit remain.** `ManageCategoryRow.confirmDelete` and `menuOpen` use plain `remember` (`LibraryPaperCard.kt:569-570`) so rotation closes the confirmation; checkbox/radio rows are unlabelled (`LibraryDrawer.kt:329-338`, `LibraryDialogs.kt:223-263`, `LibraryPaperCard.kt:455-464`), and category titles are raw `clickable` `Text` with no `Role`/`onClickLabel` (`:602-604`). Stepper buttons are bare "−"/"+" (`BlueprintEditorSheet.kt:325,337`) and pristine-form errors show on open (`:393-407`). *Why missed:* earlier L2/L3/L7 were triaged as Low and not carried into the fix batches. Fix: hoist flags, add `semantics`/`selectable` and localized labels.

## Re-verified as fixed since 0.0.53

- **H12 rotation:** `editingPaper`, `pendingTestSetup`, `editingCategory` moved to VM (`LibraryViewModel.kt:62-88`); nested sheet state saveable (`TestSetupSheet.kt:182-276`, `BlueprintEditorSheet.kt:117-126`).
- **H2 blueprint edit→create:** identity derived from saveable `variant` + edit flag (`TestSetupSheet.kt:79-95,266-270,1124-1130`) — no longer from the async list entry.
- **M2:** `editCategoryAtomic` one transaction with refusal shown (`LibraryViewModel.kt:726-738`, `PaperStore.kt:470-499`).
- **M3:** selection pruned on category delete (`:751-765`) and reconciled on every `papers` emission (`:350-373`).
- **M7:** whole `.apkg` wrapped in `db.withTransaction` (`AnkiApkgImporter.kt:64-96`).
- **M8:** `writeExport` renders to scratch then copies (`LibraryViewModel.kt:567-584`, `ShareExport.kt:154`).
- **M10 / M1 copy:** failed delete sets a localized error dialog; impact dialog uses plural resources (`LibraryScreen.kt:608-671`).
- **F2 export target:** `PendingExportTarget` in VM survives SAF recreation and is consumed once (`LibraryViewModel.kt:90-115`, `LibraryScreen.kt:152-170`).
- **A11y feedback:** activity-owned `Announcer` + queue-backed Snackbar (`MainActivity.kt:54-61`, `McqNavHost.kt:61-89`, `UiMessage.kt:79-90`).
- **L4 dialog scroll:** `ParentPicker` bounded + scrollable (`LibraryDialogs.kt:218-222`).

## Checked, no defect

- Category parent picker genuinely hides the whole subtree (`LibraryDialogs.kt:251` returns before the children loop).
- Card expansion/collapsed-branch savers round-trip correctly (`LibraryPaperCard.kt:78-81,157-159,515`).
- `BlueprintPartsSaver` delimiter round-trip and empty-map case (`BlueprintEditorSheet.kt:53-73`).
- SAF URI survives recreation; no `exportAll` fallback when pending state is lost (intentional no-op, `LibraryScreen.kt:164-167`).
- Hindi plurals/strings used by Library are all present (191 string names, 13 plurals; only non-translatable `mcq_app` absent).
- Import classification by content (zip magic, docx entry, manifest) and the backup refusal path are correct and actionable.
- No main-thread Room access in the reviewed paths; startup jobs and uncaught handler are exception-guarded (`McqApplication.kt:49-51,96-151`).

## Owner decisions

1. L1: approve gating SEND replay on `savedInstanceState == null`; separately decide whether to add `launchMode="singleTop"` (baseline §5.4) — changes share-stack behaviour.
2. L4/L5/L6/L8: copy/i18n scope — whether to resource-ify `ExportFormat` (touches Browse/Bookmarks too) and the backup error.
3. Whether to expand paper/category delete disclosure to review history and `question_stats` (`deleteImpact` counts `card_state` only, `PaperStore.kt:158-169`).
4. Whether to keep the unreachable `review/{attemptId}` destination (`McqNavHost.kt:211-222`, baseline §3).
