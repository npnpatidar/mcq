# Phase A report — baseline, inventory verification and risk map

**Date:** 2026-11-17
**Branch:** `feature/full-app-audit-plan` @ `1983885` (cut from `main` `ba79e24`, release `0.0.53`).
**Status:** read-only baseline. No product code changed. Coverage ledger: `REVIEW_LEDGER.md` (525 rows).

---

## 1. Local baseline (reproducible)

Command shape: `. apk-build/env.sh` then compile all three Kotlin targets and run every
pure-JVM test class (selection rule in §2). Log: `/tmp/opencode/phase-a-baseline.log`.

| Gate | Result |
|---|---|
| `:app:compileDebugKotlin`, `:app:compileDebugUnitTestKotlin`, `:app:compileDebugAndroidTestKotlin` | BUILD SUCCESSFUL |
| Pure-JVM `:app:testDebugUnitTest` (167 classes) | BUILD SUCCESSFUL, 0 failures |
| CI reference: successful PR run | **44009505597** — wrapper-validation, build, ui-test, release-build all pass |
| CI reference: merge run on `main` | **44010626366** — same four jobs pass |

CI artifacts on those runs: `app-debug`, `app-release`, `test-results`, `lint-report`,
`ui-test-results`. The three report artifacts are not mentioned in `AGENTS.md` /
`PROJECT_NOTES.md` — worth adding to the release/artifact notes later.

---

## 2. Counts verified (discrepancies resolved)

| Item | Verified count | Method / note |
|---|---:|---|
| Production Kotlin files | 217 | `find app/src/main/java -name '*.kt'` |
| Resource XML files | 8 | `app/src/main/res/**` |
| Unit-test Kotlin files | 277 | 2328 `@Test` methods |
| — pure-JVM classes | **167** | line-start `@Test`, no `^import org.robolectric`; run locally |
| — Robolectric classes | **104** | `^import org.robolectric` + `@Test`; CI x86_64 only |
| — helper files | **6** | no line-start `@Test` |
| Instrumentation files / classes | **15 / 15** | earlier "16 classes" inventory claim was wrong; corrected |
| Instrumentation `@Test` methods | **67** | largest: `StudyAndSelectionUiTest` 28, `QuestionEditorUiTest` 14 |
| Room schema JSON files | 29 | versions 1–28 |
| Ledger rows | 525 | all source/resource/config/schema files, status `PENDING` |

**Selection rule (important):** import match, not word match. A word match can hide a class
named in a comment; a class count well below 167 means the run was truncated. Class names
are passed unquoted to Gradle. `run-jvm-tests.sh` does not exist on this host.

---

## 3. Dead / unreachable candidates (to confirm in later phases)

These are *candidates* with evidence; no deletion is proposed without a separate owner
decision and review.

### Unreachable destination
- `ui/navigation/McqNavHost.kt:149-160` — `composable("review/{attemptId}")` has **no**
  `navigate()` caller and no deep link. `reviewMode = true` is only set here; two
  androidTests compose `ResultsScreen(reviewMode = true)` directly. Either wire it from
  Results/History or remove it. **Needs owner decision.**

### Uncalled functions / classes (name occurs once, at declaration)
`Daos.kt:43 getByTitle`, `:866 countRefsByCategories`, `:1239 getIdByContentHash`,
`:1247 getIdsByContentHash`; `McqRepository.kt:1063 panelsWithQuestions`,
`:1623 browseCategoryEdges`; `domain/QuestionSearch.kt:103 filterUncategorizedRefs`;
`LibraryViewModel.kt:506 writeUri`; `ChartKit.kt:112 LegendRow`;
`DocxParser.kt:956-957 imgPattern/tablePattern`, `:1058 docxTextForTest`;
`PdfMath.kt:46 leafTags`; `AnkiScheduling.kt:33 QUEUE_PREVIEW_REPEAT`;
`Reliability.kt:15 AttemptItems`; `Models.kt:355 accuracyOnAnswered`;
`ReportEvidence.kt:255 ScoreInterval.widthPoints`;
`StatsModels.kt:294,296 OverdueAgeBin.fromDays/toDays` (constructed but never read).

**Checked and deliberately kept:** test oracles guarded by `NoDeadCodeTest`,
`LinkedHashMap.removeEldestEntry` (framework callback), DataStore/backup string keys,
the live import-source handoff, and the SEND intent filter.

### Unused resources
24 string resources have zero references (examples: `move_block_up/down`, `remove_block`,
`remove_option`, `about`, `target_retention`, `load_failed`, `save_failed`,
`report_time_none`, `stats_backlog_help`). Two live strings have stale `sm_2_*` names but
FSRS text (`strings.xml:559-560`, used at `StudySchedulerSection.kt:183-184`).

### Config drift
- `app/build.gradle.kts:199,219` — `ui-tooling-preview` / `ui-tooling` with no `@Preview`
  in the tree (Layout Inspector may justify `ui-tooling`). **Needs owner.**
- `.github/workflows/build.yml:87-94` stale minify comment; actual build sets
  `isMinifyEnabled = true` and `isShrinkResources = true`.
- `build.yml:130` masks an empty string: `::add-mask::$(cat keystore.properties ...)`
  runs before the file is written at `:133-148`. CI annotates "Can't add secret mask for
  empty string".
- `PROJECT_NOTES.md:277-278` still says 0.0.52 is current; `:253-256` still says ≈124
  local test classes (actual 167); `BlueprintBugsTest.kt:22-28` says tests are `@Ignore`d
  but no annotation remains.

---

## 4. Structural guards: what they do **not** prove

| Guard | Enforces | Blind spot |
|---|---|---|
| `NoDeadCodeTest` (11) | named helpers absent; full-table accessors have no production caller | source-text matching, exclusion by filename; unreachable routes and uncalled DAO methods not checked |
| `StringResourceUsageTest` (5) | `stringResource` calls in rendered sinks; references defined; no `$` in strings | scans only `ui/`; defined-but-unused strings not checked; `isRendered` shortcuts can accept discarded expressions |
| `UiAccessibilityTest` (5) | source contains `Role.Checkbox`/`stateDescription`; no empty `IconButton`; 48dp regex | presence-anywhere, not runtime semantics; no Compose harness |
| `WebViewLifecycleTest` (4) | teardown/security substrings in **3 named files** | new WebView sites invisible; `>= 2` thresholds allow sites to vanish |
| `ReliabilityTest` (16) | stats math, mostly exact | several weak assertions (e.g. "high reliability" only checks finiteness) |
| `VersionCodeGuardTest` (1) | literal Gradle strings present | reformatting breaks it; never executes versionCode logic |

Skipped tests: `PdfPaperWriterTest` (whole class, 5 tests), `ExportWritersTest:676` (1),
`DeviceExportedFileTest:130` (`assumeTrue`, conditionally skipped on CI). Tautologies:
`BitmapDecoderTest:106` and `BrowseCategoryMoveUiTest:119` contain `assertTrue(true)`.

---

## 5. Non-UI backbone: highest-risk paths for later phases

1. **High — WebView content.** `ContentElements.kt:471` enables JS on HTML built from
   imported text/MathML behind CSP `default-src 'none'` but `script-src 'unsafe-inline'
   file:` (`:231`); `@JavascriptInterface` height bridge at `:498-510`. Audit sanitisation
   allow-list and exposed methods against official WebView docs.
2. **High — `CardScheduleMapping`** (`:17-84`) is load-bearing for progress restore; no
   direct test of bare-key collisions or a crafted NUL id. Round-trip tests cover the
   happy alias path only.
3. **High — `EditorSession`** process-global volatile `index/size/prevId/nextId`; `reanchor`
   writes on IO while the screen reads four separate volatiles on Main. Tear risk; `clear()`
   called only from Library, not on editor disposal.
4. **High — main activity SEND handling.** `MainActivity` handles `onCreate` and
   `onNewIntent`, but the manifest declares no `launchMode`; verify whether a SEND to a
   running app creates a second instance and drops in-flight state.
5. **Medium — process-global holders:** `ImportDataHolder` can pin ~48 MB until process
   death; `SharedImport.consume()` is read-then-clear with two LaunchedEffect collectors.
6. **Medium — failure opacity:** startup jobs, bookmark toggles, CSV/PDF export and many
   ViewModel paths only `Logger.e`; Toasts are the only user signal in many others. No
   `announceForAccessibility`/Snackbar anywhere.

---

## 6. Phase A conclusions and next steps

- Baseline is green and reproducible; the ledger is the authoritative "nothing skipped"
  record for the rest of the review.
- The inventory and CI evidence are now verified; the "16 androidTest classes" claim is
  corrected to 15.
- Three documentation/config items can be fixed cheaply but need owner approval: stale
  minify comment, empty secret mask, stale release/class-count notes.
- **Phase B starts next:** screen-by-screen walkthrough. Recommended order: Library →
  Study → Test session → Results → Browse → Stats → Settings → Editor → Passages → Import
  → remaining drawer screens. Each screen's report is saved as
  `review-reports/0N-<screen>.md` and the ledger is updated as rows complete.

No owner decision is blocking Phase B.
