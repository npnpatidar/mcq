package com.mcqapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure-JVM test for the shard filter generator. The probe writes a duration
 * manifest on nightly/main builds and caches it under a stable restore prefix;
 * PR shards restore that cache and pass it as the script's third argument. When
 * no manifest is cached yet, CI calls the two-argument form explicitly (as do
 * all local runs), which is the balanced-by-count fallback. Both paths must
 * remain a partition — no class dropped, no class run twice — because a shard
 * is otherwise not a shard.
 *
 * INCIDENT: CI used to call the two-argument form unconditionally, so the
 * duration-aware path was dead code. The probe must also never be a mandatory
 * PR gate: a hung unsharded pass used to skip every shard.
 */
class TestShardScriptTest {

    private val script: String by lazy {
        val candidates = listOf(
            File("apk-build/test-shards.sh"),
            File("../apk-build/test-shards.sh")
        )
        candidates.firstOrNull { it.isFile }?.absolutePath
            ?: throw AssertionError("cannot find apk-build/test-shards.sh from ${File(".").absolutePath}")
    }

    private fun classesDir(): File = listOf(
        File("app/src/test/java/com/mcqapp"),
        File("../app/src/test/java/com/mcqapp")
    ).first { it.isDirectory }

    private fun shards(total: Int, durations: File? = null): List<List<String>> =
        (0 until total).map { index ->
            val cmd = mutableListOf("sh", script, index.toString(), total.toString())
            if (durations != null) cmd += durations.absolutePath
            val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals("shard $index failed: $output", 0, process.waitFor())
            output.lineSequence().filter { it.isNotBlank() }.toList()
        }

    @Test
    fun ciRestoresTheCachedManifestWithAnExplicitFallback() {
        val workflow = listOf(File("../.github/workflows/build.yml"), File(".github/workflows/build.yml"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: throw AssertionError("cannot find .github/workflows/build.yml")
        assertTrue("the duration probe must exist", workflow.contains(":app:writeTestDurations"))
        assertTrue(
            "the probe must cache the manifest for later PRs",
            workflow.contains("restore-keys: test-durations-")
        )
        assertTrue("the shard job must restore that cache", workflow.contains("uses: actions/cache/restore@"))
        assertTrue(
            "CI must use the measured-cost path when a manifest is present",
            workflow.contains("test-shards.sh \${{ matrix.shard }} 4 apk-build/test-durations.tsv")
        )
        assertTrue(
            "CI must fall back to the balanced-by-count call when none is cached",
            workflow.contains("test-shards.sh \${{ matrix.shard }} 4)")
        )
    }

    @Test
    fun theFallbackIsACompletePartition() {
        val all = classesDir().listFiles { f -> f.name.endsWith("Test.kt") }!!.map { it.name.removeSuffix(".kt") }
        val shards = shards(4)
        val flat = shards.flatten()
        assertEquals("every test class must be in exactly one shard", all.size, flat.size)
        assertEquals("no class may be duplicated across shards", flat.size, flat.toSet().size)
        assertEquals(all.toSet(), flat.map { it.removePrefix("--tests com.mcqapp.") }.toSet())
    }

    @Test
    fun missingManifestFallsBackToBalancedByCount() {
        // A PR with no cached manifest still gets a complete, unique partition.
        val absent = File.createTempFile("mcq-absent-durations", ".tsv").apply { delete() }
        try {
            assertEquals(
                "a missing manifest must not change the partition",
                shards(4),
                shards(4, absent)
            )
        } finally {
            absent.delete()
        }
    }

    @Test
    fun durationManifestKeepsTheSamePartitionWithMeasuredCosts() {
        val all = classesDir().listFiles { f -> f.name.endsWith("Test.kt") }!!
            .map { it.name.removeSuffix(".kt") }
        val heavy = "StreamingImporterEquivalenceTest"
        val heavyArg = "--tests com.mcqapp.$heavy"
        val durations = File.createTempFile("mcq-durations", ".tsv").apply {
            // Two distinct weights, not one: with a single weight the script's
            // fallback default (the median) equals it, so every class weighs
            // the same and the cost-aware split can look like the count-balanced
            // one. One enormous class against a light default for everything
            // else makes the third argument observable.
            writeText(all.joinToString("\n") { name ->
                "com.mcqapp.$name\t${if (name == heavy) 600000 else 1000}"
            } + "\n")
        }
        try {
            val fallback = shards(4)
            val measured = shards(4, durations)
            val fallbackFlat = fallback.flatten()
            val measuredFlat = measured.flatten()

            // The third argument must change the partition. If the script
            // ignores it, both calls return the same lists and this fails.
            assertTrue(
                "a measured partition must differ from the count-balanced one — " +
                    "the third argument is being ignored",
                fallback != measured
            )

            // Coverage stays complete and duplicate-free on both paths.
            assertEquals(
                "measured shards must cover the same classes",
                fallbackFlat.toSet(), measuredFlat.toSet()
            )
            assertEquals(
                "measured shards must cover every test class",
                all.toSet(),
                measuredFlat.map { it.removePrefix("--tests com.mcqapp.") }.toSet()
            )
            assertEquals("no class may be duplicated", measuredFlat.size, measuredFlat.toSet().size)
            assertTrue("the measured class must still be present", heavyArg in measuredFlat)
            assertEquals("the heavy class must appear exactly once", 1, measuredFlat.count { it == heavyArg })

            // A specific placement only the cost-aware algorithm produces: the
            // heavy class is moved out of the contiguous count-balanced range.
            val fallbackShard = fallback.indexOfFirst { heavyArg in it }
            val measuredShard = measured.indexOfFirst { heavyArg in it }
            assertTrue(
                "the heavy class must be present in both partitions",
                fallbackShard >= 0 && measuredShard >= 0
            )
            assertTrue(
                "the cost-aware split must move the heavy class to a different shard " +
                    "(fallback $fallbackShard, measured $measuredShard)",
                fallbackShard != measuredShard
            )
        } finally {
            durations.delete()
        }
    }
}
