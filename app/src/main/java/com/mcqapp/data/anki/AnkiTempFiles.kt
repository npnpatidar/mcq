package com.mcqapp.data.anki

import android.content.Context
import com.mcqapp.util.Logger
import java.io.File

/**
 * Anki import/export stages the collection in a temp SQLite file next to the
 * app's cache dir. A killed process skips the finally-block delete, so startup
 * sweeps the known prefixes; delete() results are checked so a leftover is
 * reported rather than silently retried every launch.
 */
object AnkiTempFiles {

    private val PREFIXES = listOf("anki-export", "anki-import")
    private const val SUFFIX = ".anki2"

    /** Deletes leftover temp files from a previous, possibly killed, process. */
    fun cleanup(context: Context): Int =
        cleanup(System.getProperty("java.io.tmpdir")?.let { File(it) } ?: context.cacheDir)

    /** Sweep one directory; returns how many stale files were removed. */
    fun cleanup(dir: File): Int {
        val leftovers = dir.listFiles { file ->
            file.name.endsWith(SUFFIX) && PREFIXES.any { file.name.startsWith(it) }
        } ?: return 0
        var removed = 0
        for (file in leftovers) {
            try {
                if (file.delete()) {
                    removed++
                } else {
                    Logger.w("ANKI", "Could not delete stale temp file ${file.absolutePath}")
                }
            } catch (e: Exception) {
                Logger.w("ANKI", "Temp file cleanup failed for ${file.absolutePath}: ${e.message}")
            }
        }
        return removed
    }
}
