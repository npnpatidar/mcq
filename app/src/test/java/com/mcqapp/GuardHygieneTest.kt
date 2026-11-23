package com.mcqapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Rule for new source-scanning guards: a `*GuardTest.kt` must either assert a
 * behaviour (by exercising a pure function) or cite the incident it pins in
 * its class KDoc, using `BEHAVIOUR:` or `INCIDENT:` respectively.
 *
 * A guard that merely checks the spelling of a string is a maintenance cost:
 * it fails when formatting changes, stays green when the behaviour it claimed
 * to protect is gone, and the next reader cannot tell which of the two it is.
 * Existing guards are grandfathered (migrating all of them is a separate
 * cleanup); every NEW guard must carry a marker. The rule is pure text, so the
 * synthetic cases below prove it is not itself a vacuous guard.
 */
class GuardHygieneTest {

    /** Guards written before this rule existed; exempt until migrated. */
    private val grandfathered = setOf(
        "CiAndRound2FixGuardTest.kt",
        "FollowupImportGuardTest.kt",
        "FsrsImportGuardTest.kt",
        "JsonDepthGuardTest.kt",
        "Round2EditorLocalizationGuardTest.kt",
        "Round2EditorNavGuardTest.kt",
        "Round2StudyGradingGuardTest.kt",
        "StatsCardDetailGuardTest.kt",
        "StudyCardActionGuardTest.kt"
    )

    /** The first KDoc block in the file (these guards put it above the class). */
    private fun classKDoc(source: String): String {
        val start = source.indexOf("/**")
        if (start < 0) return ""
        val end = source.indexOf("*/", start)
        return if (end < 0) "" else source.substring(start, end + 2)
    }

    /** Empty means every new guard states why it exists; non-empty names the offenders. */
    fun unmarkedGuards(guards: List<Pair<String, String>>): List<String> = guards
        .filterNot { (name, _) -> name in grandfathered }
        .filter { (_, source) ->
            val doc = classKDoc(source)
            !doc.contains("INCIDENT:") && !doc.contains("BEHAVIOUR:")
        }
        .map { (name, _) -> name }

    @Test
    fun everyNewGuardCitesAnIncidentOrExercisesABehaviour() {
        val root = File("src/test/java/com/mcqapp")
        assertTrue("test source root not found from ${File(".").absolutePath}", root.isDirectory)
        val guards = root.listFiles { file -> file.isFile && file.name.endsWith("GuardTest.kt") }
            .orEmpty().map { it.name to it.readText() }
        val offenders = unmarkedGuards(guards)
        assertTrue(
            "new guards must carry INCIDENT: or BEHAVIOUR: in their class KDoc: " +
                offenders.joinToString(),
            offenders.isEmpty()
        )
    }

    @Test
    fun theRuleRejectsAnUnmarkedGuardAndAcceptsBothMarkers() {
        val guards = listOf(
            "UnmarkedGuardTest.kt" to "/** Checks a string. */\nclass UnmarkedGuardTest",
            "IncidentGuardTest.kt" to
                "/**\n * INCIDENT: the widget lost its label on rotation.\n */\nclass IncidentGuardTest",
            "BehaviourGuardTest.kt" to
                "/**\n * BEHAVIOUR: calls the pure pairing rule.\n */\nclass BehaviourGuardTest"
        )
        assertEquals(listOf("UnmarkedGuardTest.kt"), unmarkedGuards(guards))
    }
}
