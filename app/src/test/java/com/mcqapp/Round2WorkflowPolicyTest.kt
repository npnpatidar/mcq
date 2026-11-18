package com.mcqapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round-2 workflow/tooling policy decisions (findings C1 and C3), frozen as
 * pure-JVM guards. Complements [WorkflowGuardTest]: these assert that the
 * chosen policies stay policies — advisory release notes and lint's own
 * enforcement — rather than drifting into a publish gate or a dead task.
 */
class Round2WorkflowPolicyTest {

    private fun read(name: String, vararg candidates: String): String {
        val file = candidates.asSequence().map(::File).firstOrNull { it.isFile }
            ?: throw AssertionError("cannot find $name (cwd=${File(".").absolutePath})")
        return file.readText()
    }

    private val release: String by lazy {
        read(
            "release.yml",
            "../.github/workflows/release.yml",
            ".github/workflows/release.yml"
        )
    }
    private val buildGradle: String by lazy {
        read("build.gradle.kts", "build.gradle.kts", "../app/build.gradle.kts")
    }

    @Test
    fun releaseNotesStayAdvisoryRatherThanAPublishGate() {
        // C1 accepted policy: CI generates notes and only warns. The owner
        // adds the user-facing section afterwards with `gh release edit
        // --notes-file`, so publishing must never read a committed notes file.
        // Cut before the advisory block: its comment and warning text both
        // mention --notes-file, but only as the manual step's instructions.
        val publishBlock = release.substringAfter("gh release create")
            .substringBefore("# Reminder only")
        assertTrue(
            "publish must use --generate-notes",
            "--generate-notes" in publishBlock
        )
        assertFalse(
            "publish must not pass --notes-file (that would gate the release)",
            "--notes-file" in publishBlock
        )
        assertTrue(
            "the non-blocking advisory reminder must remain",
            "::warning::" in release
        )
    }

    @Test
    fun lintEnforcementStaysInLintNotAnUnreachableCheckTask() {
        // C3 accepted decision: the checkLintBaseline task was deleted because
        // CI never invoked `check`. Lint's own settings enforce the baseline.
        assertFalse(
            "the unreachable checkLintBaseline task must stay deleted",
            "checkLintBaseline" in buildGradle
        )
        assertTrue("lint must abort on error", "abortOnError = true" in buildGradle)
        assertTrue(
            "new warnings must fail lint",
            "warningsAsErrors = true" in buildGradle
        )
        assertTrue(
            "the frozen baseline must still be configured",
            "baseline = file(\"lint-baseline.xml\")" in buildGradle
        )
    }
}
