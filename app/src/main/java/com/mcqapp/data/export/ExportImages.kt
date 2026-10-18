package com.mcqapp.data.export

/** Decoded `data:<mime>;base64,...` image. Remote URLs are not embedded. */
data class EmbeddedImage(val mimeType: String, val bytes: ByteArray)

/** Parses a `data:<mime>;base64,...` URI, or returns null for anything else. */
fun parseDataUri(src: String?): EmbeddedImage? {
    if (src == null || !src.startsWith("data:")) return null
    val comma = src.indexOf(',')
    if (comma < 0) return null
    val header = src.substring(5, comma)
    if (!header.contains(";base64", ignoreCase = true)) return null
    val mime = header.substringBefore(';').ifBlank { "image/jpeg" }
    return try {
        val bytes = java.util.Base64.getMimeDecoder().decode(src.substring(comma + 1))
        if (bytes.isEmpty()) null else EmbeddedImage(mime, bytes)
    } catch (e: IllegalArgumentException) {
        null
    }
}

fun imageExtension(mimeType: String): String = when (mimeType.lowercase()) {
    "image/jpeg" -> "jpg"
    "image/png" -> "png"
    "image/webp" -> "webp"
    "image/gif" -> "gif"
    else -> "jpg"
}

/**
 * A compact key for an image's data URI, for the writers' dedupe maps.
 *
 * Every rich format must store one copy per distinct picture, so each writer
 * keeps a `src -> file` map while it walks the paper. Keyed on the URI itself
 * that map holds every picture again as a base64 string — at a million images,
 * hundreds of megabytes of keys, the very thing [MediaSpool] exists to avoid.
 * The URI's SHA-256 tells the same pictures apart in 32 bytes each; a collision
 * would merge two pictures, which the odds make unreachable.
 */
internal fun mediaKey(dataUri: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
        .digest(dataUri.toByteArray(Charsets.UTF_8))
    val hex = CharArray(digest.size * 2)
    var i = 0
    for (b in digest) {
        val v = b.toInt() and 0xFF
        hex[i++] = HEX_DIGITS[v ushr 4]
        hex[i++] = HEX_DIGITS[v and 0x0F]
    }
    return String(hex)
}

private val HEX_DIGITS = "0123456789abcdef".toCharArray()
