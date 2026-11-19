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
            "build.yml: expected 5 upload-artifact steps, got ${uploads.size}",
            uploads.size == 5
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
            "app-debug", "app-release", "lint-report", "ui-test-results"
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

    @Test
    fun releaseBuildCommentMatchesActualMinifySettings() {
        assertFalse(
            "the stale 'isMinifyEnabled is still false' comment must be gone",
            build.contains("isMinifyEnabled is still")
        )
        assertTrue(
            "the comment must describe the live R8 settings",
            build.contains("isShrinkResources = true")
        )
    }

    /** Lines that actually invoke Gradle; comments only mention it. */
    private fun gradleInvocationLines(yaml: String): List<String> =
        yaml.lineSequence().filter { it.contains("sh gradlew ") }.toList()

    private fun allGradleStepsRefreshDependencies(yaml: String): Boolean {
        val invocations = gradleInvocationLines(yaml)
        return invocations.isNotEmpty() && invocations.all { "--refresh-dependencies" in it }
    }

    @Test
    fun everyGradleInvocationForcesDependencyRefresh() {
        for ((label, yaml) in listOf("build.yml" to build, "release.yml" to release)) {
            val invocations = gradleInvocationLines(yaml)
            assertTrue("$label: no 'sh gradlew' invocations found", invocations.isNotEmpty())
            assertTrue(
                "$label: every Gradle step must pass --refresh-dependencies:\n" +
                    invocations.joinToString("\n"),
                allGradleStepsRefreshDependencies(yaml)
            )
        }
    }

    @Test
    fun refreshDependenciesGuardDetectsAMissingFlag() {
        // Negative case: strip one flag from the real workflow text and prove
        // the guard rejects it, so the assertion above is not a tautology.
        val regressed = build.replaceFirst("--refresh-dependencies", "")
        assertTrue(
            "the mutation must not delete a Gradle step",
            gradleInvocationLines(regressed).size == gradleInvocationLines(build).size
        )
        assertFalse(
            "the guard must reject a Gradle step missing --refresh-dependencies",
            allGradleStepsRefreshDependencies(regressed)
        )
    }
}
