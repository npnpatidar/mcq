package com.mcqapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * CI guard for the two workflow files. Pure JVM: reads the YAML as text from
 * the repository root (unit tests run with the app module as working dir).
 *
 * Every assertion here fails on the pre-hardening workflows: actions pinned by
 * tag, no permissions/concurrency, no ui-test timeout, a mask applied to a
 * keystore.properties that did not exist yet, unbounded artifact uploads and
 * no release-notes reminder.
 *
 * INCIDENT: unpinned actions, a mask applied before keystore.properties existed
 * and unbounded uploads each shipped once; the blanket --refresh-dependencies
 * workaround then turned four transient fetches into failed PRs.
 */
class WorkflowGuardTest {

    private val cwd: String = File(".").absolutePath

    private fun workflow(name: String): String {
        val candidates = listOf(
            File("../.github/workflows/$name"),
            File(".github/workflows/$name")
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("cannot find .github/workflows/$name from $cwd")
        return file.readText()
    }

    private val build: String by lazy { workflow("build.yml") }
    private val release: String by lazy { workflow("release.yml") }

    private val usesLine = Regex("""^[ \t]*uses:[ \t]*(\S+).*$""", RegexOption.MULTILINE)
    private val sha = Regex("""^[0-9a-f]{40}$""")
    private val versionComment = Regex("""#[ \t]*v\d+""")

    @Test
    fun everyActionIsPinnedToACommitShaWithAVersionComment() {
        for ((label, yaml) in listOf("build.yml" to build, "release.yml" to release)) {
            val uses = usesLine.findAll(yaml).toList()
            assertTrue("$label: no uses: steps found", uses.isNotEmpty())
            uses.forEach { match ->
                val value = match.groupValues[1]
                val ref = value.substringAfterLast('@')
                assertTrue("$label: $value is not pinned to a 40-char SHA", sha.matches(ref))
                assertTrue(
                    "$label: $value must keep its version in a trailing comment",
                    versionComment.containsMatchIn(match.value)
                )
            }
        }
    }

    @Test
    fun workflowsDeclareLeastPrivilegePermissionsAndPerRefConcurrency() {
        for ((label, yaml) in listOf("build.yml" to build, "release.yml" to release)) {
            assertTrue(
                "$label: default permissions must be contents: read",
                Regex("""(?m)^permissions:\n  contents: read[ \t]*$""").containsMatchIn(yaml)
            )
            assertTrue(
                "$label: per-ref concurrency with cancel-in-progress is required",
                Regex("""(?m)^concurrency:\n  group:.*\n  cancel-in-progress: true[ \t]*$""")
                    .containsMatchIn(yaml)
            )
        }
        assertFalse(
            "build.yml must not grant write access",
            Regex("""(?m)^[ \t]*contents: write[ \t]*$""").containsMatchIn(build)
        )
        assertTrue(
            "release.yml: only the publish job gets contents: write",
            Regex("""(?ms)^  publish:\n.*?^    permissions:\n      contents: write[ \t]*$""")
                .containsMatchIn(release)
        )
    }

    @Test
    fun secretsAreMaskedOnlyAfterKeystorePropertiesIsWritten() {
        for ((label, yaml) in listOf("build.yml" to build, "release.yml" to release)) {
            val writeAt = yaml.indexOf("> keystore.properties")
            assertTrue("$label: keystore.properties is never written", writeAt >= 0)
            for (secret in listOf("STORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")) {
                val at = yaml.indexOf("::add-mask::\$$secret")
                assertTrue("$label: $secret is not masked", at >= 0)
                assertTrue(
                    "$label: $secret must be masked after keystore.properties exists",
                    at > writeAt
                )
            }
            assertFalse(
                "$label: masking a not-yet-written keystore.properties only hid an empty string",
                yaml.contains("::add-mask::$(cat keystore.properties")
            )
        }
    }

    @Test
    fun uiTestJobHasItsOwnTimeout() {
        val uiTest = build.substringAfter("  ui-test:\n")
        assertTrue("build.yml: ui-test job is missing", uiTest.length != build.length)
        assertTrue(
            "ui-test must have a job-level timeout-minutes",
            Regex("""(?m)^    timeout-minutes: [1-9]\d*[ \t]*$""").containsMatchIn(uiTest)
        )
    }

    @Test
    fun expectedArtifactUploadsFailWhenNothingIsFound() {
        val steps = build.split(Regex("""\n[ \t]*- name:"""))
        val uploads = steps.filter { it.contains("uses: actions/upload-artifact@") }
        assertTrue(
            "build.yml: expected 6 upload-artifact steps, got ${uploads.size}",
            uploads.size == 6
        )
        uploads.forEach { step ->
            assertTrue(
                "artifact upload must set if-no-files-found: error:\n$step",
                Regex("""(?m)^[ \t]*if-no-files-found: error[ \t]*$""").containsMatchIn(step)
            )
        }
        // The `test-results` upload is a single step in the 4-way unit-test
        // matrix: one YAML step, four artifacts when expanded.
        for (name in listOf(
            "app-debug", "app-release", "lint-report", "ui-test-results", "test-durations"
        )) {
            assertTrue(
                "build.yml: artifact '${name}' is missing",
                Regex("""(?m)^[ \t]*name: ${name}[ \t]*$""").containsMatchIn(build)
            )
        }
        assertTrue(
            "build.yml: the sharded unit-test results upload is missing",
            Regex(
                """(?m)^[ \t]*name: test-results-shard-\$\{\{ matrix\.shard \}\}[ \t]*$"""
            ).containsMatchIn(build)
        )
    }

    /**
     * Accepted owner policy (round-2 finding C1): the release-notes check is a
     * *deliberate* advisory reminder, not a publish gate. `--generate-notes`
     * never contains the plain-language "What's new for you" section, so CI
     * emits a warning and the supervisor adds the notes afterwards with
     * `gh release edit --notes-file`. That manual flow has been followed for
     * every release. The assertions below freeze the reminder on purpose —
     * they are not a weakened substitute for a gate.
     */
    @Test
    fun releaseWarnsWhenGeneratedNotesLackUserFacingSection() {
        assertTrue(
            "release.yml must check for the user-facing notes marker",
            release.contains("What's new for you")
        )
        assertTrue(
            "release.yml must emit a non-blocking warning annotation",
            release.contains("::warning::")
        )
    }

    /**
     * BEHAVIOUR: the release variant must keep resource shrinking and R8 on —
     * the release build job is the only CI check that a keep rule or a
     * release-only resource regression still works. Replaced the old
     * comment-wording assertion with one that reads the actual build settings.
     */
    @Test
    fun releaseBuildKeepsShrinkingAndR8Enabled() {
        val gradle = listOf(File("../app/build.gradle.kts"), File("app/build.gradle.kts"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: throw AssertionError("cannot find app/build.gradle.kts from $cwd")
        val release = gradle.substringAfter("        release {")
        assertTrue("release resource shrinking is off", "isShrinkResources = true" in release)
        assertTrue("release R8 minification is off", "isMinifyEnabled = true" in release)
        assertTrue("release must keep its ProGuard rules", "proguard-rules.pro" in release)
        assertTrue(
            "CI must assemble the release variant so shrinking is exercised",
            build.contains(":app:assembleRelease")
        )
    }

    /** Lines that actually invoke Gradle; comments only mention it. */
    private fun gradleInvocationLines(yaml: String): List<String> =
        yaml.lineSequence().filter { it.contains("sh gradlew ") }.toList()

    /**
     * The one intentional refresh: the nightly job that re-resolves against
     * the repositories independently. Per-PR steps must not force
     * re-resolution — doing so turned three transient plugin fetches and one
     * Maven 429 into failed PRs in a single release. This is the ONLY line
     * where the flag is allowed.
     */
    private fun nightlyRefreshLine(yaml: String): String? =
        gradleInvocationLines(yaml).firstOrNull { "--refresh-dependencies" in it }

    /** Every Gradle step except the deliberate nightly refresh. */
    private fun regularGradleInvocations(yaml: String): List<String> =
        gradleInvocationLines(yaml).filter { it != nightlyRefreshLine(yaml) }

    /** Non-empty means a regular step reintroduced the blanket flag. */
    private fun blanketRefreshOffences(yaml: String): List<String> =
        regularGradleInvocations(yaml).filter { "--refresh-dependencies" in it }

    /** Every prefix a cache step's `restore-keys` declares (inline or block form). */
    private fun restorePrefixes(step: String): List<String> {
        val regex = Regex(
            """(?m)^[ \t]*restore-keys:[ \t]*(?:\|[ \t]*\n((?:[ \t]+\S[^\n]*\n?)*)|(\S+)[ \t]*)$"""
        )
        val match = regex.find(step) ?: return emptyList()
        val body = match.groupValues[1].ifBlank { match.groupValues[2] }
        return body.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    }

    @Test
    fun onlyTheNightlyJobForcesDependencyRefresh() {
        // build.yml: one scheduled refresh, and every ordinary Gradle step
        // (build, lint, 4-way unit-test matrix, release build, UI test) relies
        // on the refreshed cache. The wrapper retries only the distribution
        // download, so a transient plugin fetch on a PR is not retried here.
        val nightly = nightlyRefreshLine(build)
        assertTrue("build.yml: the nightly refresh Gradle step is missing", nightly != null)
        assertTrue(
            "the refresh step must re-resolve plugin metadata too:\n$nightly",
            nightly!!.contains(":app:dependencies") && nightly.contains("--refresh-dependencies")
        )
        assertTrue(
            "build.yml must declare a nightly schedule",
            Regex("""(?m)^  schedule:\n[ \t]*- cron: [^\n]+$""").containsMatchIn(build)
        )
        assertTrue(
            "the refresh job must never run on a PR",
            Regex("""(?m)^[ \t]*if: github\.event_name == 'schedule'[ \t]*$""").containsMatchIn(build)
        )
        for ((label, yaml) in listOf("build.yml" to build, "release.yml" to release)) {
            val invocations = regularGradleInvocations(yaml)
            assertTrue("$label: no regular 'sh gradlew' invocations found", invocations.isNotEmpty())
            assertTrue(
                "$label: a regular Gradle step forced dependency refresh:\n" +
                    blanketRefreshOffences(yaml).joinToString("\n"),
                blanketRefreshOffences(yaml).isEmpty()
            )
        }
        // A forced refresh is inert if the same cache is restored every time:
        // GitHub's actions/cache does not save on an exact-key hit, and
        // setup-java's hash key is an exact hit on an unchanged repo. Every job
        // therefore uses a run-scoped key (always a miss, so the refresh is
        // saved) and a shared restore prefix (so PRs pick up that refresh).
        assertFalse(
            "setup-java's own cache would be an unsaved exact hit",
            Regex("""(?m)^[ \t]*cache: gradle[ \t]*$""").containsMatchIn(build)
        )
        val allCacheSteps = build.split(Regex("""\n[ \t]*- name:"""))
            .filter { it.contains("uses: actions/cache@") }
        // The duration manifest has its own cross-run cache; exclude it here so
        // this guard stays about the rotating Gradle dependency caches.
        val cacheSteps = allCacheSteps.filter {
            it.contains("gradle-nightly-") || it.contains("gradle-run-")
        }
        assertTrue(
            "one rotating Gradle cache step per Gradle job, got ${cacheSteps.size}",
            cacheSteps.size == 6
        )
        cacheSteps.forEach { step ->
            assertTrue(
                "the cache key must be scoped to the run so it always saves:\n$step",
                step.contains("github.run_id") &&
                    (step.contains("gradle-nightly-") || step.contains("gradle-run-"))
            )
            assertTrue(
                "the cache must restore the latest nightly refresh:\n$step",
                "gradle-nightly-" in restorePrefixes(step)
            )
        }
        assertTrue(
            "exactly one job owns the nightly key — a parallel job saving under it would discard the refresh",
            cacheSteps.count { it.contains("key: gradle-nightly-\${{ github.run_id }}") } == 1
        )
    }

    /**
     * A run-scoped save is only useful if a later run can restore it. The PR
     * jobs saved under `gradle-run-<run_id>` but declared only the
     * `gradle-nightly-` restore prefix, so those caches could never be hit.
     */
    @Test
    fun runScopedGradleCachesAreRestorableByALaterRun() {
        val cacheSteps = build.split(Regex("""\n[ \t]*- name:"""))
            .filter { it.contains("uses: actions/cache@") }
        val runKeyed = cacheSteps.filter { it.contains("key: gradle-run-") }
        assertTrue("expected the PR jobs' run-scoped caches, got ${runKeyed.size}", runKeyed.isNotEmpty())
        runKeyed.forEach { step ->
            assertTrue(
                "a cache saved under gradle-run- must be restored by a later run:\n$step",
                "gradle-run-" in restorePrefixes(step)
            )
        }
    }

    /**
     * The duration probe is a measurement, not a gate. It used to run on every
     * PR and `unit-test` had a hard `needs` edge on it, so the whole unsharded
     * suite ran twice and a hung probe skipped every shard. Now only nightly
     * and main refresh the manifest into a cross-run cache; PR shards restore
     * it and fall back to the 2-arg balanced-by-count call when it is absent.
     */
    @Test
    fun durationAwareShardingIsWiredWithoutBlockingPRs() {
        assertTrue("a prior job must write the manifest", build.contains(":app:writeTestDurations"))
        assertTrue("the manifest must be uploaded for inspection", build.contains("name: test-durations"))

        val probe = build.substringAfter("  test-durations:\n").substringBefore("\n  build:\n")
        assertTrue(
            "the probe must run only on schedule or a push to main",
            probe.contains(
                "if: github.event_name == 'schedule' || github.event_name == 'push'"
            )
        )

        val shardJob = build.substringAfter("  unit-test:\n").substringBefore("\n  release-build:\n")
        val needs = Regex("""(?m)^[ \t]*needs:.*$""").find(shardJob)?.value.orEmpty()
        assertTrue(
            "the shard job must depend only on wrapper-validation: $needs",
            needs.trim() == "needs: wrapper-validation"
        )
        assertFalse(
            "a hard needs edge on the probe would gate every PR on the unsharded suite",
            needs.contains("test-durations")
        )
        assertTrue(
            "the shards must restore a manifest cached by an earlier main/nightly run",
            shardJob.contains("uses: actions/cache/restore@") &&
                shardJob.contains("restore-keys: test-durations-")
        )
        assertTrue(
            "the shard call must pass the measured durations as its third argument",
            build.contains("4 apk-build/test-durations.tsv")
        )
        assertTrue(
            "CI must fall back to the two-argument balanced-by-count call when no manifest is cached",
            build.contains("test-shards.sh \${{ matrix.shard }} 4)")
        )
    }

    @Test
    fun refreshDependenciesGuardDetectsARegressedBlanketFlag() {
        // Negative case: add the blanket flag back to a regular step and prove
        // the guard rejects it, so the assertion above is not a tautology.
        val regular = regularGradleInvocations(build).first()
        val regressed = build.replace(regular, regular.replace(" --no-daemon", " --refresh-dependencies --no-daemon"))
        assertTrue(
            "the mutation must add a flag, not delete a Gradle step",
            gradleInvocationLines(regressed).size == gradleInvocationLines(build).size
        )
        assertTrue(
            "the guard must reject a regular step with --refresh-dependencies",
            blanketRefreshOffences(regressed).isNotEmpty()
        )
    }
}
