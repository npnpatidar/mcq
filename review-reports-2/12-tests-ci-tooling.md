# Round-2 review 12 — tests, CI and release tooling

**Reviewer:** independent read-only reviewer. **Branch:** `feature/review-round-2` == main `fbc8e15`, tag `0.0.61`.
**Scope:** `app/src/test/`, `app/src/androidTest/`, `app/build.gradle.kts` (`testPureJvm`, lint baseline, `isIncludeAndroidResources`, bundle language split), `.github/workflows/`, Gradle wrapper/plugin/dependency pins, `apk-build/env.sh`, release/tag process, `REVIEW_LEDGER.md`.
**Method:** `git log/diff 0.0.53..HEAD`, direct file inspection, `git ls-remote` against each action's upstream repo, `gh release view`, official docs. No build/tests run locally (ARM64 host; CI remains authoritative per AGENTS.md). Old report `review-reports/19-g-tests-ci.md` treated as claims, not evidence.

## Verdict

No P0/P1 defects. The audit's 0.0.53 gaps are substantially closed: tautologies removed, ignored/skipped tests moved to executed device tests, actions SHA-pinned and verified, least-privilege permissions + concurrency added, lint baseline committed and enforced, release signing/checksums in place. Four residuals remain (2 low, 2 informational); none should block the release, but C1 is an owner decision.

## Findings

### C1 — Low — user-facing release notes are a warning, not a gate
**File:** `.github/workflows/release.yml:137-145`; guard at `app/src/test/java/com/mcqapp/WorkflowGuardTest.kt:131-141`.
**Evidence:** the workflow creates the release with `--generate-notes` (lines 120-129), uploads the APK, then only emits `::warning::` if the body lacks `What's new for you`. `WorkflowGuardTest.releaseWarnsWhenGeneratedNotesLackUserFacingSection` explicitly asserts the annotation is *non-blocking*. AGENTS.md:25-30 makes user-facing notes mandatory for every release. The process has been followed (0.0.59, 0.0.60, 0.0.61 bodies all contain the section), so no release has actually shipped without notes.
**Why missed:** the old audit asked for a "release-notes gate"; the fix implemented a reminder and a guard test that freezes the reminder, which reads as done.
**Fix shape (owner call):** either keep the manual `gh release edit --notes-file` process and accept the warning, or make it a gate: read a committed notes file, pass `--notes-file` to `gh release create`, and fail before publishing when the `What's new for you` marker is absent.

### C2 — Low — stale-cache workaround applied to only two of four Gradle jobs
**File:** `.github/workflows/build.yml:71,77` vs `:172` and `:234`; `cache: gradle` at `:52,122,207`.
**Evidence:** `build` and `lint` pass `--refresh-dependencies` because a restored cache repeatedly served stale plugin metadata ("compose 2.2.10 not found"); `release-build` (`assembleRelease`) and `ui-test` (`connectedDebugAndroidTest`) restore the same cache but do not. CI history suggests release/UI jobs have been green, so this is latent, not observed breakage.
**Why missed:** the cache fix was scoped to the two jobs that had visibly failed (commit `40ad517`), and no guard asserts uniform Gradle invocation flags.
**Fix shape:** either apply the flag uniformly to all `sh gradlew` steps, or remove the workaround after fixing the cache-key/plugin-metadata root cause.

### C3 — Informational — `checkLintBaseline` guard is not reachable from CI
**File:** `app/build.gradle.kts:385-398`.
**Evidence:** `checkLintBaseline` is wired only to `check` (`tasks.named("check") { dependsOn(...) }`), but no workflow invokes `check`; the Gradle steps are `assembleDebug`, `testDebugUnitTest`, `lintDebug`, `assembleRelease`, `connectedDebugAndroidTest`. Impact is nil because `lint.abortOnError = true` + `warningsAsErrors = true` + a missing baseline makes all 187 warnings new errors, so lint fails anyway.
**Why missed:** the guard was added as belt-and-braces at the same time as the baseline; the task graph was not checked against the workflow commands.
**Fix shape:** either add `:app:check` to the CI gate or drop the redundant task and rely on lint's own failure.

### C4 — Informational — two small test-hygiene residuals
**File:** `app/src/test/java/com/mcqapp/EditorMoveToQuestionTest.kt:183`; `app/src/test/java/com/mcqapp/BlueprintBugsTest.kt:24-28`.
**Evidence:** `dirtyInvalidEditsStillBlockTheMove` uses a blind `Thread.sleep(200)` before asserting the negative ("still on q1"); a slower async move would be silently accepted. `BlueprintBugsTest`'s header still claims each test is `@Ignore`d while its bug is open, but no active `@Ignore` remains anywhere in either source set.
**Why missed:** fixed sleeps were reduced, not eliminated, and the stale comment was already noted in the old report but never actioned.
**Fix shape:** replace the blind sleep with a bounded condition/drain (as `pumpUntil` does in the same file) and update the comment to say the bugs are fixed and the tests run.

## Re-verified fixed since 0.0.53 (old claims)

| Old claim | Current evidence |
|---|---|
| Tautologies (`BitmapDecoderTest:106`, `BrowseCategoryMoveUiTest:119`) | Both replaced with real assertions (non-throw + bounded decode; exact restored order + enabled buttons). No `assertTrue(true)`/`assertFalse(false)` remains in either source set. |
| Ignored PDF tests / `assumeTrue` skip | No active `@Ignore`/`assumeTrue` anywhere. PDF byte tests now run on the CI emulator as `PdfPaperWriterDeviceTest`; `DeviceExportedFileTest` uses a committed `fixtures/device-exported-fixture.docx`. |
| Actions pinned by tag, no permissions/concurrency, no UI timeout | All 22 `uses:` refs are 40-char SHAs with version comments. `git ls-remote` confirms every pin is a real commit of its claimed major: checkout `11d5960…` = v4.4.0; wrapper-validation `8da46b5…` = v6.4.1; setup-java `cf277c6…` = v4; setup-android `9fc6c4e…` = v3; upload-artifact `ea165f8…` = v4; emulator-runner `a421e43…` = v2.38.0. Top-level `contents: read` in both workflows, `contents: write` only in `release.yml` publish, per-ref `concurrency` with `cancel-in-progress`, 25/30-min job timeouts. Matches GitHub hardening/concurrency guidance: https://docs.github.com/en/actions/security-for-github-actions/security-guides/security-hardening-for-github-actions and https://docs.github.com/en/actions/writing-workflows/choosing-what-your-workflow-does/control-the-concurrency-of-workflows-and-jobs |
| No wrapper verification | Both workflows have a `wrapper-validation` job gating every Gradle job; it checks the jar against Gradle's official SHA-256 list: https://github.com/gradle/actions/blob/main/docs/wrapper-validation.md |
| No lint baseline / abortOnError | `app/lint-baseline.xml` committed with 187 issues (188 at introduction, one host-specific `AndroidGradlePluginVersion` entry removed); `baseline`, `abortOnError = true`, `warningsAsErrors = true`, that check disabled. No baseline change since `495fe06`, so no drift after freeze. |
| Stale minify comment / no release-only build | `release-build` assembles the release variant (R8 + resource shrinking are on: `app/build.gradle.kts:132-133`) on every push; `WorkflowGuardTest.releaseBuildCommentMatchesActualMinifySettings` freezes the comment. |
| Mask before file exists / unbounded uploads / artifact docs | Masks are emitted after `keystore.properties` is written (`build.yml:151-169`, `release.yml:77-93`); all five uploads use `if-no-files-found: error` and the documented names; `WorkflowGuardTest` asserts all of the above. |
| No pure-JVM task | `testPureJvm` (`app/build.gradle.kts:321-381`) codifies the `^import org.robolectric` rule, fails on empty selection and on any leaked Robolectric class; 194 pure-JVM classes selected, matching PROJECT_NOTES. |

## Checked, no defect

- **Test quality:** sampled behavioural tests assert outcomes, not tautologies — `VerdictTest` (four-state derivation, glyph/CSV/colour), `HistoryUiTest` (drives ON_RESUME via lifecycle, not a direct `refresh()`), `StatsCardDetailGuardTest` (stale-load guard), `FinalErrorMessagesTest` (friendly copy, no raw cause leak), `BrowseCategoryMoveUiTest`, `BitmapDecoderTest`. Negative cases are present across import collisions, failed restores/saves and skipped/ungraded verdicts. Guards (`WorkflowGuardTest`, `WrapperValidationTest`, `StringResourceUsageTest`) are meta-tests by design; they assert config/repo properties, not app behaviour.
- **No test weakened to get green:** `git diff 0.0.53..HEAD -- app/src/test app/src/androidTest` shows 8750 insertions / 218 deletions, **zero deleted files**. The large deletions are the moved PDF tests (replaced by a device suite with the same five byte-level checks) and the now-unused `pdfOf` helper. Commit `1d88039` replaced a flaky close-the-DB failure with an injectable loader seam; `StatsViewModel.openCardWithLoader` still runs the real catch → `CardDetail.failedLoad` path and the test still asserts paper/question id, friendly copy and no leak. `38c67d9` added `isIncludeAndroidResources = true`, which AGP defines as enabling unit tests to use Android resources/assets/manifests — required for Robolectric `getString` (https://developer.android.com/reference/tools/gradle-api/8.7/com/android/build/api/dsl/UnitTestOptions).
- **Pure-JVM selection sound:** all 118 `@Config`/Robolectric files have a leading `import org.robolectric`; none use fully-qualified `org.robolectric…` or a Robolectric base class without the import; no file has `@Test` only in a comment. Gradle `includeTestsMatching` with FQCNs is the documented filter form: https://docs.gradle.org/current/userguide/java_testing.html#test_filtering. Offline Robolectric SDK staging pins both `14-…` and `15-…` android-all runtimes in separate configurations, so one cannot eclipse the other (http://robolectric.org/configuring/).
- **Flakiness by design:** most `Thread.sleep` calls are inside bounded poll loops with real conditions and timeouts; `HangWatchdogRule`, offline SDK staging and the injected failure seam are deliberate stabilisers. Remaining blind sleeps are C4.
- **Robolectric/SQLite tests cannot run on this ARM64 host** ("native runtime not supported on Linux (aarch64)"); CI x86_64 is authoritative, as AGENTS.md/PROJECT_NOTES state.
- **Release signing/checksums:** `release.yml` fails fast without `RELEASE_KEYSTORE_BASE64`, masks all four secrets after writing `keystore.properties`, removes the key with `if: always()`, builds with v1+v2+v3 signing (`app/build.gradle.kts:108-120`), publishes `mcq-<tag>-signed.apk` plus `.sha256`, and reuses/clobbers an existing release. Tag→commit mapping is correct (`0.0.60` = `381c7ba`, `0.0.61` = `fbc8e15`), both merge commits on main.
- **Gradle pins:** wrapper `gradle-9.6.1-bin.zip` with `validateDistributionUrl=true`; plugins pinned in `build.gradle.kts` (AGP 9.4.1, Kotlin 2.2.10, KSP 2.2.10-2.0.2); all library versions pinned exactly. Wrapper jar SHA-256 is `497c8c2a…a194a9c7`, verified in CI by wrapper-validation. `apk-build/env.sh` supplies the ARM64 aapt2 override and exports JAVA_HOME/ANDROID_HOME as documented; it is host tooling, not part of CI.
- **Bundle language split** (`app/build.gradle.kts:177-184`, `enableSplit = false`) matches the in-app language picker owner decision. Note the setting affects App Bundles; APKs ship all locales regardless — harmless, not a defect.
- **`REVIEW_LEDGER.md`** remains at 0.0.53 with most rows `PENDING` and G rows pointing at the old report; it is a round-1 artefact, not evidence for this review. Round-2 reports live in `review-reports-2/`.

## Owner decisions

1. C1: keep the warning + manual `gh release edit` flow, or make user-facing notes a hard publish gate?
2. C2: apply `--refresh-dependencies` to all Gradle jobs, or fix the stale-cache root cause and remove it?
3. C3: add `:app:check` to CI, or accept lint's own enforcement and delete the redundant guard?
4. Whether to refresh `REVIEW_LEDGER.md` for round 2 (currently stale at 0.0.53).
