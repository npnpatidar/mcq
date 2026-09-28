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
