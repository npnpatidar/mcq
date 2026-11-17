package com.mcqapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * CI must check gradle-wrapper.jar against Gradle's published checksums
 * before any job executes it — the jar runs with full privileges on the
 * runner, so a tampered one is game over. Both workflows gain a
 * validation job, and every gradle-running job waits on it.
 *
 * Unit tests run with the app module as working directory.
 */
class WrapperValidationTest {

    private fun source(path: String): String {
        val file = File(path)
        assertTrue("missing: ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    /**
     * The wrapper-validation action may be referenced by its tag or, after
     * supply-chain pinning, by a 40-char commit SHA with the version kept in a
     * trailing `# v6` comment. Either form proves the step still exists; a
     * removed step, a wrong action or a SHA without its version comment fails.
     */
    private fun usesWrapperValidation(yaml: String): Boolean {
        val action = "gradle/actions/wrapper-validation@"
        val uses = Regex("""(?m)^[ \t]*uses:[ \t]*(\S+)[ \t]*(#.*)?$""")
        return uses.findAll(yaml).any { line ->
            val value = line.groupValues[1]
            if (!value.startsWith(action)) return@any false
            val ref = value.removePrefix(action)
            val comment = line.groupValues[2]
            ref == "v6" ||
                (Regex("""^[0-9a-f]{40}$""").matches(ref) &&
                    comment != null && comment.contains("v6"))
        }
    }

    @Test
    fun releasePublishWaitsForWrapperValidation() {
        val release = source("../.github/workflows/release.yml")
        assertTrue(
            "the release workflow must validate the wrapper jar",
            usesWrapperValidation(release)
        )
        assertEquals(
            "the publish job must wait for validation",
            1,
            release.split("needs: wrapper-validation").size - 1
        )
    }

    @Test
    fun everyBuildJobWaitsForWrapperValidation() {
        val build = source("../.github/workflows/build.yml")
        assertTrue(
            "the build workflow must validate the wrapper jar",
            usesWrapperValidation(build)
        )
        assertEquals(
            "every gradle-running job (build, release-build, ui-test) must wait for validation",
            3,
            build.split("needs: wrapper-validation").size - 1
        )
    }
}
