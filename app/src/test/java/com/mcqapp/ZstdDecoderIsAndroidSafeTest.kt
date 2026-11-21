package com.mcqapp

import com.mcqapp.data.anki.zstd.ZstdInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.jar.JarFile
import java.util.zip.ZipInputStream

/**
 * The vendored zstd decoder must load on Android, which plain JVM tests cannot
 * show.
 *
 * The upstream decoders we evaluated link against APIs Android does not have:
 * aircompressor's zstd classes use `sun.misc.Unsafe` array offsets, and the
 * later VarHandle rewrite needs API 33 while this app's `minSdk` is 26. Both
 * failures are `LinkageError`s at class initialisation, which every
 * `catch (Exception)` in the import path misses and JVM/Robolectric CI never
 * sees. So the class files themselves are scanned for those references, and the
 * decoder is exercised on a real frame from the committed v3 fixture.
 */
class ZstdDecoderIsAndroidSafeTest {

    /** Constant-pool entries Android's runtime does not provide below API 33. */
    private val forbiddenReferences = listOf(
        "sun/misc/Unsafe",
        "java/lang/foreign",
        "java/lang/invoke/VarHandle"
    )

    /**
     * Every class the vendored package compiles to (top-level plus nested
     * classes). Kept explicit so a class sneaking in without being scanned —
     * or a scan finding nothing because the directory moved — fails here.
     */
    private val expectedClasses = setOf(
        "BitInputStream",
        "BitInputStream\$Initializer",
        "BitInputStream\$Loader",
        "Constants",
        "Decompressor",
        "FiniteStateEntropy",
        "FiniteStateEntropy\$Table",
        "FrameHeader",
        "FseTableReader",
        "Huffman",
        "MalformedInputException",
        "SafeAccess",
        "Util",
        "XxHash64",
        "ZstdDecompressor",
        "ZstdFrameDecompressor",
        "ZstdIncrementalFrameDecompressor",
        "ZstdIncrementalFrameDecompressor\$State",
        "ZstdInputStream"
    )

    @Test
    fun theDecoderIsVendoredWithNoExternalZstdLibrary() {
        assertEquals("com.mcqapp.data.anki.zstd", ZstdInputStream::class.java.packageName)
        assertNull(
            "aircompressor must not be on the classpath",
            runCatching { Class.forName("io.airlift.compress.zstd.ZstdInputStream") }.getOrNull()
        )
        assertNull(
            "unzstd must not be an external dependency",
            runCatching { Class.forName("com.qyntrax.unzstd.ZstdInputStream") }.getOrNull()
        )
    }

    @Test
    fun everyVendoredDecoderClassAvoidsJvmOnlyApis() {
        val classes = decoderClasses()
        assertEquals(
            "every vendored class must be scanned; update expectedClasses when adding one",
            expectedClasses,
            classes.keys
        )

        val offenders = classes.flatMap { (name, bytes) ->
            forbiddenReferences
                .filter { bytes.containsAscii(it) }
                .map { "$name -> $it" }
        }
        assertFalse(
            "the decoder references APIs missing from Android: $offenders",
            offenders.isNotEmpty()
        )
    }

    @Test
    fun theVendoredDecoderReadsARealAnkiFrame() {
        val collection = ZstdInputStream(fixtureEntry("collection.anki21b").inputStream()).use {
            it.readBytes()
        }
        assertEquals(356_352, collection.size)
        assertEquals(
            "SQLite format 3\u0000",
            String(collection, 0, 16, Charsets.US_ASCII)
        )

        val manifest = ZstdInputStream(fixtureEntry("media").inputStream()).use { it.readBytes() }
        assertEquals(986, manifest.size)
        assertEquals("the protobuf MediaEntries manifest begins field 1", 0x0A, manifest[0].toInt())
    }

    /** Every `com.mcqapp.data.anki.zstd` class file, with its simple class name. */
    private fun decoderClasses(): Map<String, ByteArray> {
        val location = ZstdInputStream::class.java.protectionDomain?.codeSource?.location
            ?: error("the vendored decoder has no code source")
        val source = File(location.toURI())
        val out = LinkedHashMap<String, ByteArray>()
        if (source.isFile) {
            JarFile(source).use { jar ->
                for (entry in jar.entries()) {
                    if (entry.name.startsWith(PACKAGE_PATH) && entry.name.endsWith(".class")) {
                        out[entry.name.substringAfterLast('/').removeSuffix(".class")] =
                            jar.getInputStream(entry).use { it.readBytes() }
                    }
                }
            }
        } else {
            File(source, PACKAGE_PATH).listFiles()
                ?.filter { it.isFile && it.extension == "class" }
                ?.forEach { out[it.name.removeSuffix(".class")] = it.readBytes() }
        }
        return out
    }

    private fun fixtureEntry(name: String): ByteArray {
        val apkg = javaClass.classLoader?.getResourceAsStream(FIXTURE)
            ?.use { it.readBytes() }
            ?: throw AssertionError("missing test fixture $FIXTURE")
        ZipInputStream(apkg.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == name) return zip.readBytes()
            }
        }
        throw AssertionError("$FIXTURE has no $name entry")
    }

    /** A byte-level search, so binary class data needs no text decoding. */
    private fun ByteArray.containsAscii(needle: String): Boolean {
        val pattern = needle.toByteArray(Charsets.US_ASCII)
        if (pattern.isEmpty() || pattern.size > size) return false
        outer@ for (start in 0..size - pattern.size) {
            for (offset in pattern.indices) {
                if (this[start + offset] != pattern[offset]) continue@outer
            }
            return true
        }
        return false
    }

    private companion object {
        const val PACKAGE_PATH = "com/mcqapp/data/anki/zstd"
        const val FIXTURE = "fixtures/anki-v3-sample.apkg"
    }
}
