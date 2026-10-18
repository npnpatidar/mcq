package com.mcqapp.data.anki

import android.content.Context
import com.mcqapp.util.Logger
import java.io.File

/**
 * Import/export stages its work in temp files next to the app's cache dir: the
 * Anki collection (`anki-export-*.anki2`, `anki-import-*.anki2`) and the
 * pictures an export spools (`mcq-media*`, a directory). A killed process skips
 * the finally-block delete, so startup sweeps the known names; delete() results
 * are checked so a leftover is reported rather than silently retried every
 * launch.
 */
object AnkiTempFiles {

    private val PREFIXES = listOf("anki-export", "anki-import")
    private const val SUFFIX = ".anki2"

    /** The directory an export spools its pictures into. */
    private const val MEDIA_PREFIX = "mcq-media"

    /** Deletes leftovers from a previous, possibly killed, process. */
    fun cleanup(context: Context): Int =
        cleanup(System.getProperty("java.io.tmpdir")?.let { File(it) } ?: context.cacheDir)

    /** Sweep one directory; returns how many stale entries were removed. */
    fun cleanup(dir: File): Int {
        val leftovers = dir.listFiles { file ->
            file.name.startsWith(MEDIA_PREFIX) ||
                (file.name.endsWith(SUFFIX) && PREFIXES.any { file.name.startsWith(it) })
        } ?: return 0
        var removed = 0
        for (file in leftovers) {
            try {
                val gone = if (file.isDirectory) file.deleteRecursively() else file.delete()
                if (gone) {
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
