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

    @Test
    fun releasePublishWaitsForWrapperValidation() {
        val release = source("../.github/workflows/release.yml")
        assertTrue(
            "the release workflow must validate the wrapper jar",
            release.contains("gradle/actions/wrapper-validation@v6")
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
            build.contains("gradle/actions/wrapper-validation@v6")
        )
        assertEquals(
            "every gradle-running job (build, release-build, ui-test) must wait for validation",
            3,
            build.split("needs: wrapper-validation").size - 1
        )
    }
}
