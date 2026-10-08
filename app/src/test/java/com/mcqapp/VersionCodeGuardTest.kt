package com.mcqapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * versionCode is the mixed-radix encoding major*1e6 + minor*1e3 + patch of
 * the release tag, which is unique only while minor and patch stay under
 * 1000 — the old code silently clamped with coerceAtMost(Int.MAX_VALUE), so
 * 0.0.1000 encoded to the same versionCode as 0.1.0. The build must refuse
 * such a tag loudly instead of publishing a collision. Proven end to end
 * once: a local 0.0.1000 tag made `gradlew help` fail with the message.
 *
 * Unit tests run with the app module as working directory.
 */
class VersionCodeGuardTest {

    @Test
    fun versionCodeRefusesTagsThatWouldCollideOrOverflow() {
        val file = File("build.gradle.kts")
        assertTrue("missing: ${file.absolutePath}", file.isFile)
        val gradle = file.readText()
        assertTrue(
            "the build must require minor and patch under 1000, or 0.0.1000 and 0.1.0 share a versionCode",
            gradle.contains("minor.toLong() < 1_000L && patch.toLong() < 1_000L")
        )
        assertTrue(
            "the build must require the encoded versionCode to fit Android's limit",
            gradle.contains("code <= 2_100_000_000L")
        )
        assertFalse(
            "the silent clamp that hid both problems must stay gone",
            gradle.contains("coerceAtMost(Int.MAX_VALUE")
        )
    }
}
