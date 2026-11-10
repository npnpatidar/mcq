package com.mcqapp.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Subdirectory of `cacheDir` for files rendered to share (see `file_paths.xml`). */
const val SHARE_DIR_NAME = "shared"

/** Files in `shared/` older than this are swept on the next share. */
const val SHARED_MAX_AGE_MS: Long = 7L * 24 * 60 * 60 * 1000L

/** File-name cap; `PaperExporter.baseName` caps at 80 before the suffix. */
const val MAX_SHARED_FILE_NAME: Int = 100

/**
 * A file name safe to use under `cacheDir/shared` and through `FileProvider`.
 *
 * Paths are stripped to the last segment, everything outside `[A-Za-z0-9._-]`
 * becomes `_`, and leading/trailing dots and underscores are removed. A name
 * that survives none of that (`..`, blank, all-illegal) becomes `export`.
 */
fun sanitizeFileName(raw: String): String {
    val base = raw.substringAfterLast('/').substringAfterLast('\\')
    val cleaned = base.replace(Regex("[^A-Za-z0-9._-]+"), "_")
        .trim('.', '_')
    if (cleaned.isEmpty()) return "export"
    return cleaned.take(MAX_SHARED_FILE_NAME).ifBlank { "export" }
}

/**
 * Which files in [dir] the sweep should delete: those older than [maxAgeMillis]
 * at [nowMillis], except the newest file overall, which is always kept.
 *
 * Pure so the rule can be pinned without Android; [sweepSharedDir] deletes the
 * survivors' complement. A directory with one file keeps it: sweeping the only
 * file would mean every share deletes the last thing anyone shared.
 */
fun filesToSweep(
    dir: File,
    nowMillis: Long,
    maxAgeMillis: Long = SHARED_MAX_AGE_MS
): List<File> {
    val files = dir.listFiles()?.filter { it.isFile } ?: return emptyList()
    if (files.size <= 1) return emptyList()
    val cutoff = nowMillis - maxAgeMillis
    val expired = files.filter { it.lastModified() < cutoff }
    if (expired.isEmpty()) return emptyList()
    val newest = files.maxByOrNull { it.lastModified() } ?: return emptyList()
    return expired - newest
}

/**
 * Deletes the files [filesToSweep] picks and returns the count deleted.
 *
 * Called at the start of every share, before the new file is created, so a
 * file is never deleted in the same share that wrote it.
 */
fun sweepSharedDir(
    dir: File,
    nowMillis: Long = System.currentTimeMillis(),
    maxAgeMillis: Long = SHARED_MAX_AGE_MS
): Int {
    val victims = filesToSweep(dir, nowMillis, maxAgeMillis)
    var deleted = 0
    for (file in victims) {
        if (file.delete()) deleted++
    }
    return deleted
}

/**
 * The `shared/` directory under [cacheDir], created if missing. Never null.
 */
fun sharedDir(cacheDir: File): File {
    val dir = File(cacheDir, SHARE_DIR_NAME)
    dir.mkdirs()
    return dir
}

/**
 * Sweeps old shares, then returns the file the caller should render into.
 *
 * The sweep runs before the new file exists, so the just-written file is
 * always the newest and always survives the next sweep.
 */
fun nextSharedFile(
    cacheDir: File,
    fileName: String,
    nowMillis: Long = System.currentTimeMillis()
): File {
    val dir = sharedDir(cacheDir)
    sweepSharedDir(dir, nowMillis)
    return File(dir, sanitizeFileName(fileName))
}

/**
 * Copies [input] into [output] with [MAX_IMPORT_BYTES]' cap enforced as it
 * goes, returning the byte count. A stream over the limit fails with
 * [ImportTooLargeException]; the caller deletes any partial file.
 */
fun copyBounded(
    input: InputStream,
    output: OutputStream,
    limit: Long = MAX_IMPORT_BYTES
): Long {
    var total = 0L
    boundedStream(input, limit).use { stream ->
        val buffer = ByteArray(COPY_CHUNK_BYTES)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            output.write(buffer, 0, read)
        }
    }
    return total
}

/** Chunk for [copyBounded]; matches the constant in `BoundedRead.kt`. */
private const val COPY_CHUNK_BYTES = 64 * 1024

/**
 * The raw `ACTION_SEND` intent for a rendered shared file. The caller wraps
 * it with `Intent.createChooser(...)` and shows the chooser. The `ClipData`
 * plus the read grant are both required: without the data the receiver gets
 * `EXTRA_STREAM` but the permission cannot follow it.
 */
fun shareIntent(context: Context, file: File, mimeType: String): Intent {
    val uri = FileProvider.getUriForFile(
        context,
        context.packageName + ".fileprovider",
        file
    )
    return Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(null, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
