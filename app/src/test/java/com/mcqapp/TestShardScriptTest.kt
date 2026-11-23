package com.mcqapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure-JVM test for the shard filter generator. The two-argument call is what
 * CI uses today (balanced-by-count fallback); the three-argument call adds a
 * prior JUnit-duration manifest. Both must remain a partition — no class
 * dropped, no class run twice — because a shard is otherwise not a shard.
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
    fun theFallbackIsACompletePartition() {
        val all = classesDir().listFiles { f -> f.name.endsWith("Test.kt") }!!.map { it.name.removeSuffix(".kt") }
        val shards = shards(4)
        val flat = shards.flatten()
        assertEquals("every test class must be in exactly one shard", all.size, flat.size)
        assertEquals("no class may be duplicated across shards", flat.size, flat.toSet().size)
        assertEquals(all.toSet(), flat.map { it.removePrefix("--tests com.mcqapp.") }.toSet())
    }

    @Test
    fun durationManifestKeepsTheSamePartitionWithMeasuredCosts() {
        val durations = File.createTempFile("mcq-durations", ".tsv").apply {
            // One deliberately enormous class: a duration-aware partition must
            // still place it exactly once, not duplicate it to fill a shard.
            writeText("com.mcqapp.StreamingImporterEquivalenceTest\t600000\n")
        }
        try {
            val fallback = shards(4).flatten().toSet()
            val measured = shards(4, durations).flatten()
            assertEquals("measured shards must cover the same classes", fallback, measured.toSet())
            assertEquals("no class may be duplicated", measured.size, measured.toSet().size)
            assertTrue(
                "the measured class must still be present",
                "--tests com.mcqapp.StreamingImporterEquivalenceTest" in measured
            )
        } finally {
            durations.delete()
        }
    }
}
