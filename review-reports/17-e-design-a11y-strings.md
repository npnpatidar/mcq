# Phase E report — cross-cutting design, accessibility and strings

**Reviewer:** cross-app audit; builds on screen reports but re-verified in source. **Read-only.**

## Design system

| Sev | Finding |
|---|---|
| High | `verdictColors()` uses fixed light hexes for every light scheme; custom light themes get stock green/red cards, dark correctly uses scheme roles. `VerdictColors.kt:34-47,69-71`. |
| Medium | Two "is dark?" formulas: scheme uses WCAG luminance (`AppThemes.kt:487-502`), verdict/clock uses ad-hoc weights (`VerdictColors.kt:75-76`). |
| Medium | White launch window causes flash/wrong icons before forced-dark composition (`themes.xml:3-5` vs `Theme.kt:31-41`). |
| Medium | No `typography` override; stock M3 roles used inconsistently for importance; all spacing is literal dp (`Theme.kt:43-46`). |
| Medium | Shared rows have nested/incorrect roles: `Role.Button` + toggleable, `Role.Switch` on a row containing a real Switch; paging errors are unannounced plain Text (`SettingsRows.kt:48-52,88-108`, `PagingStates.kt:38-44`). |

## Strings / i18n

- Only `res/values/`; no `values-*`, no `translatable="false"` / `formatted="false"`.
- 1,143 strings + 13 plurals (baseline's "~680" was low).
- Runtime concatenation of space-bearing fragments (`StudySchedulerSection.kt:183-184`, `ResultsScreen.kt:1467-1475`); 9 values with leading/trailing whitespace.
- Non-plural counts: `strings.xml:161-162,62,626,631-632` produce "Edit 1 questions", "1 categories".
- Stale names: `sm_2_*` now hold FSRS copy (`strings.xml:559-560`), `simplified_anki` (`:592`), `l_0f` (`:526`).
- 25 unused resources (including `load_failed`/`save_failed`, `report_time_none`, `move_block_up/down`, `remove_option`, four orphan fragments).
- Hardcoded literals and raw `e.message` in Compose, VMs and Toasts across Library/Study/Test/Results/Settings/Import/Bookmarks/Sessions/Search — all invisible to the current guard.

## Accessibility

- **Zero** `announceForAccessibility` or `liveRegion` app-wide; errors/counts/async results are plain Text/Toast (WCAG 4.1.3).
- `Role.Checkbox` in 1 file, `Role.Switch` 2, `Role.Button` 3, **Role.RadioButton 0** despite radio rows; raw `.clickable` rows lack button role/label.
- Charts: `contentDescription = testTag` (`ChartKit.kt:52`), values only in Canvas tooltips; no per-point semantics.
- Both WebView sites unlabelled; maths/rich content invisible to TalkBack.
- 22 `contentDescription = null` sites — state glyphs need per-site review.
- Colour-only state in Results, Study dwell band, History trends (WCAG 1.4.1); M3 pairing wrong in Results.

## State coverage, layout, RTL

Shared paging retry components are good, but several screens ignore their own `loading` flag (Sessions, Bookmarks, History); dead ends (Results error no Retry, Stats sheet vanishes, Settings failed restore wedges controls). Fixed heights clip at font scale (Study, Test, Library dialog, Results header); Canvas charts hard-code 9.sp/10.sp labels and fixed dp heights at <3:1 series contrast. `supportsRtl=true` but no locale folders and inconsistent AutoMirrored usage.

## Guard counterexamples

- `StringResourceUsageTest` regex misses 117 calls with format args and all `pluralStringResource`; scans only `ui/`; resource declarations with attributes are invisible.
- `isRendered` accepts discarded branches via `->`/`?:`/`if (` shortcuts.
- `UiAccessibilityTest` "presence anywhere" passes with zero RadioButton roles; no Compose harness.
- `WebViewLifecycleTest` hard-coded 3-file list + `>= 2` thresholds; a new WebView site is invisible.

## Remediation strategy (priority order)

1. App-level `UiMessage` + Snackbar host + announcer; live regions on counts. **Owner: scope/copy.**
2. Shared `Verdict` enum (Correct/Wrong/Skipped/Ungraded/Tricky) owning colour, glyph, semantics and CSV classification. **Owner: skip colour.**
3. String audit script (no new dependency), then guard extensions. **Owner: localisation scope.**
4. Unified selectable/semantic row primitives, theme-aware light verdicts, single luminance formula, theme enum, mirrored icons.
5. Adaptive layout pass (`heightIn(min)`, scrollable dialogs, font-scale-derived chart height) and consume every loading flag.
6. Cleanup: 25 unused strings, fragments, stale names, plurals, decorative-icon annotations.

**Owner decisions:** localisation/RTL commitment, exception-copy policy, verdict colours, announcement/guard scope.
