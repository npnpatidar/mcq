# Bug hunt report: adding & editing questions

Date: 2026-10-25 · Branch inspected: `feature/host-build-notes` (working tree, uncommitted) · Host: NixOS arm64

Scope: the question editor end to end — `ui/editor/*` (ViewModel, screen, block
list, MathLive formula editor), its navigation and session plumbing
(`Routes.kt`, `McqNavHost.kt`, `BrowseScreen`, `LibraryScreen`, `ImportScreen`),
and the write path (`QuestionStore.saveQuestion` / `deleteQuestion`).

Verification: static tracing plus a scratch probe test,
`app/src/test/java/com/mcqapp/EditorBugHuntProbeTest.kt` (untracked), which
passes 4/4 locally. It exercises the import-session paths of
`EditorViewModel`, which are synchronous and DB-free, so they run on the
aarch64 host. DB-path findings are confirmed by code reading and are
CI-authoritative (Robolectric/SQLite cannot run here; see AGENTS.md).

## Summary

| # | Severity | Bug | Where |
|---|----------|-----|-------|
| 1 | HIGH — data loss | Delete after Prev/Next deletes the wrong question | `EditorViewModel.kt:707` |
| 2 | HIGH — data loss | Delete is live in the import-preview editor and deletes from the library DB | `QuestionEditorScreen.kt:342`, `EditorViewModel.kt:705-710` |
| 3 | MEDIUM | Formula edits silently lost (debounce race on save/move) | `MathLiveEditor.kt:64-68` |
| 4 | MEDIUM | Stale formula shown after Prev/Next once the user edited a formula | `MathLiveEditor.kt:114`, `BlockListEditor.kt:172` |
| 5 | MEDIUM | Init failure strands the editor on "Loading…" forever | `EditorViewModel.kt:238-240` |
| 6 | MEDIUM | Process death during import editing → phantom edit; saving it crashes (FK) | `EditorViewModel.kt:196-235` |
| 7 | LOW-MED | `save()`/`delete()` unguarded — DB failure crashes; double-tap Save duplicates a new question | `EditorViewModel.kt:497, 565-569` |
| 8 | LOW | Legacy image-only option makes a question permanently unsavable | `EditorViewModel.kt:42-47` |
| 9 | LOW | Question with no correct answer saves silently (import warns; editor doesn't) | `EditorViewModel.kt:85-88` |

---

## 1. HIGH: Delete after Prev/Next deletes the wrong question

**Symptom.** Edit question Q2 (opened from Browse or a passage list), tap
Next to move to Q3, then tap Delete. **Q2 is deleted from the library** while
the editor is showing Q3; the screen then pops back.

**Root cause.** `moveToQuestion` retargets the *same* ViewModel instance by
updating `state.questionId` (EditorViewModel.kt:626 in the import branch, 671
in the DB branch) — the ViewModel is keyed by nav args
(`QuestionEditorScreen.kt:75`), which never change. But `delete()` targets the
**constructor** property, which is immutable:

```kotlin
// EditorViewModel.kt:705-710
fun delete(onDone: () -> Unit) {
    viewModelScope.launch {
        repository.deleteQuestion(questionId)   // <- constructor id, stale after a move
        onDone()
    }
}
```

The Delete button renders whenever `!state.isNew`
(QuestionEditorScreen.kt:342-348), which holds after moving to any existing
question, so the stale path is reachable in normal use.

**Probe.** `moveToQuestionLeavesTheConstructorIdBehind` pins the divergence:
after an import-session move, `state.questionId` is `"q2"` while the VM was
constructed with `"q1"`.

**Fix.** Delete `_state.value.questionId` (and ideally refresh/clear the
`EditorSession` queue entry for the deleted question).

## 2. HIGH: Delete is live in the import-preview editor and deletes from the library

**Symptom.** Previewing an import, tapping a row's Edit, then the editor's
Delete button silently deletes a stored question from the user's library —
from a screen that is only editing an in-memory preview row.

**Root cause.** Two facts combine:

- Import ids are never blank (`LegacyParser.kt:1371-1372` falls back to
  `stableQuestionId`), so `isNew = questionId.isBlank()` is false and the
  Delete button renders (QuestionEditorScreen.kt:342).
- `delete()` has **no `fromImportSession` branch**, unlike `save()` and
  `moveToQuestion()`, which both special-case the import session. It goes
  straight to `repository.deleteQuestion(...)`.

When the preview row shares an id with a stored question (the "changed"
classification: same id, different hash), the stored row is removed. The
return-to-import path then finds no `pendingEditQuestion` and shows "nothing
to apply", so nothing on the import screen hints at what happened.

**Probe.** `importSessionEditorHasNoSignalThatDeleteIsInapplicable`.

**Fix.** Either hide Delete for import sessions (expose `fromImportSession`
in `EditorUiState`) or make `delete()` a session-aware no-op/writer there.

## 3. MEDIUM: Formula edits can be silently lost (debounce race)

**Symptom.** Type in a formula block and tap Save (or Prev/Next, which
auto-saves dirty edits) within ~400 ms: the last keystrokes are not in the
saved question. Worse, on Prev/Next the dirty-check can read a signature that
does not yet include the pending edit and skip the save entirely as "clean".

**Root cause.** MathLive writes the MathML back to state 400 ms after the
last `input` event (MathLiveEditor.kt:64-68). `save()` and `moveToQuestion()`
read `_state.value` immediately; nothing flushes the pending debounce.

**Fix.** Flush the debounce before save/move (e.g. an `evaluateJavascript`
flush round-trip, or a "pending math edit" flag in state that gates
save/move), or shorten the window and treat it as part of the signature.

## 4. MEDIUM: Stale formula displayed after Prev/Next once a formula was edited

**Symptom.** Edit a formula (latches `userEdited`), tap Next/Prev to a
question that also has a formula at the same block index: the editor shows
the **previous question's formula**. Editing what is on screen then overwrites
the new question's stored MathML with content derived from the stale display.

**Root cause.** Blocks are keyed by position + kind only
(`BlockListEditor.kt:172-173`: `"$index:${element::class.simpleName}"`), which
is not an identity across question loads. When `moveToQuestion` swaps the
whole element list, a Math block at the same index keeps the same key, so the
same WebView and the same `remember { mutableStateOf(false) }` latch survive
(MathLiveEditor.kt:114). `shouldPushLatex` (MathLiveEditor.kt:98-102) refuses
every push once `userEdited` is true, so the new formula never reaches the
field. The doc comment ("a different block gets a different WebView ... so
nothing is left stale") holds for reorder/remove but not for question loads.

Note the state itself is correct — only the view is stale — which makes this
easy to miss in testing and nasty when the user "fixes" what they see.

**Fix.** Include the question id (or a load-generation counter) in `blockKey`,
or reset `userEdited` when the incoming `initialLatex` represents a different
question's load rather than an echo of the user's own edit.

## 5. MEDIUM: Init failure strands the editor on "Loading…" forever

**Symptom.** If the initial DB read throws (disk I/O, corrupted DB, any
Room error), the editor shows the (delayed) "Loading" label indefinitely;
there is no way forward or back except system Back.

**Root cause.** The init coroutine's catch only logs (EditorViewModel.kt:238-240);
`loading` is never cleared, and there is no error state.

**Fix.** In catch, set `loading = false` plus an `error: String?` field and
render it (retry button optional).

## 6. MEDIUM: Process death during import editing → phantom edit; saving it crashes

**Symptom.** Edit a question from the import preview, the process is killed
and restored. The editor destination restores with
`questionId=X&paperId=&categoryId=`, but `ImportDataHolder` (plain statics) is
empty, so `fromImportSession = false`. The DB path finds no question X,
falls through to the **new-question defaults while keeping `questionId = X`
and `isNew = false`** (EditorViewModel.kt:196-235 — only the found-question
branch returns early). The screen says "Edit question" with Delete enabled for
a row that does not exist, and Save upserts a question with `categoryId = ""`:

- with the unguarded save (bug 7): `SQLiteConstraintException` (foreign key
  `questions.categoryId -> categories.id`) → **crash**;
- if a category "" ever existed: a category-less question invisible to every
  per-category list.

**Fix.** When `questionId` is non-blank but the question is missing, reset
`questionId = ""` / `isNew = true` (a genuine new-question form), or surface
an error state instead of the fall-through.

## 7. LOW-MEDIUM: Unguarded `save()` / `delete()`; double-tap Save duplicates a new question

- `save()` (EditorViewModel.kt:565-569) and `delete()` (705-710) launch DB
  work with **no try/catch** (contrast `moveToQuestion`, which has one at
  698-700). Any Room/SQLite failure propagates out of `viewModelScope` →
  default uncaught handler → app crash, and `onDone()` never runs.
- Save is gated only on `canSave`, not on "a save is in flight". A double-tap
  on a **new** question runs `buildDto()` twice while `state.questionId` is
  still blank; each call mints `"q-" + System.currentTimeMillis().toString(36)`
  (EditorViewModel.kt:497) → two ids → **two identical rows**.

**Fix.** Wrap both in try/catch (report via a snackbar/error field); disable
Save while a save is in flight and write the minted id back into
`state.questionId` after the first save.

## 8. LOW: Legacy image-only option makes a question permanently unsavable

**Symptom.** Open a question whose option carries a legacy `image` field but
no element content (possible from imports/backups: `OptionDto.image` with
empty `elements`). The option loads as `[TextElement("")]`
(QuestionContentMapper.kt:50), `isSignificant()` rejects it, so `canSave` is
false — the question **can never be saved again after any edit**, and dirty
Prev/Next moves are blocked (`moveToQuestion` refuses dirty-and-invalid,
EditorViewModel.kt:600). The user's only exits are removing the image or
adding filler text to the option.

**Root cause.** `isSignificant()` (EditorViewModel.kt:42-47) counts only
element content; the legacy image fields (`OptionEditorState.image`,
`EditorUiState.image`, `explanationImage`) are not counted, although the UI
explicitly preserves them as content ("A legacy question that already has one
still shows it, with a way to remove it rather than silently dropping it on
save" — QuestionEditorScreen.kt:235-238).

**Probe.** `imageOnlyLegacyOptionMakesTheQuestionUnsavable`.

**Fix.** Count the legacy image in significance (per option, question body
and explanation), or migrate legacy images into Image blocks on load.

## 9. LOW: Question with no correct answer saves silently

**Symptom.** Untick the only ticked option and Save: the question persists
with an empty answer key and is silently ungraded forever after. The import
screen flags exactly this state ("No answer key — will be ungraded, never
scored", ImportWarnings.kt:33-34); the editor neither blocks nor warns.

**Root cause.** `canSave` (EditorViewModel.kt:85-88) checks significant body
content, ≥2 options and every option non-empty — but never
`options.any { it.isCorrect }`. `toggleCorrect` freely allows zero
(EditorViewModel.kt:350-354).

**Probe.** `zeroCorrectOptionsSavesCleanly` (saves with
`correctOptionIds = []` through the import writer).

**Fix.** Match the import screen's stance: at minimum a visible warning; a
hard block would diverge from the deliberately tolerated import state.

---

## Checked and cleared (no bug found)

- `blockKey` content-independence: text/table/image fields are stateless
  (value driven from the model), so reuse across index changes cannot show
  stale content — only the MathLive latch (bug 4) is stateful.
- Option PKs are composite `(questionId, id)`; adding options cannot collide
  across questions. `addOption` skips used ids, so a middle-option removal no
  longer mints duplicates (documented fix, still correct).
- `saveQuestion` writes question + options + key in one transaction, deleting
  old rows first; removed options leave no orphans, and `signatureOf`
  normalization matches `buildDto` normalization (clean/dirty detection is
  sound, apart from the debounce gap in bug 3 and cosmetic marks-format
  dirtiness).
- Import return path (`pendingEditQuestion` re-apply) is idempotent with the
  writer's live write-through.
- `EditorSession` cursor math (`start`/`startCursor`/`go`/`refreshNeighbors`)
  is consistent; Browse's `openEditor` coerces the index safely.
- `mathMlToLatex` never throws and degrades gracefully; its subset
  limitations only affect the *initial* render of exotic constructs.
- Route ids are percent-encoded (`Routes.kt`), closing the old
  `&paperId=`-injection bug.

## Reproducing the probes

```bash
. apk-build/env.sh
sh gradlew :app:testDebugUnitTest --tests 'com.mcqapp.EditorBugHuntProbeTest' \
  $MCQ_AAPT2_OVERRIDE --no-daemon
```

4/4 pass on the arm64 host (no SQLite involved). Bugs 1, 5, 6, 7 and the DB
side of 2 need the x86_64 CI run to demonstrate against Room.

Suggested fix order: 1 + 2 (delete correctness/session guard), 4 (block
identity), 3 (flush before save), 6 + 7 (fall-through + guarded save),
then 8 and 9.
