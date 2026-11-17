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
        for (name in listOf("app-debug", "app-release", "test-results", "lint-report", "ui-test-results")) {
            assertTrue(
                "build.yml: artifact '${name}' is missing",
                Regex("""(?m)^[ \t]*name: ${name}[ \t]*$""").containsMatchIn(build)
            )
        }
    }

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
}
