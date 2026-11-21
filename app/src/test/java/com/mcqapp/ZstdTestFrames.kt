package com.mcqapp

import java.io.ByteArrayOutputStream

/**
 * A valid zstd frame holding [bytes] in raw (uncompressed) blocks.
 *
 * The production decoder (`com.mcqapp.data.anki.zstd`) is decompression-only,
 * and no encoder belongs in the build: real compressed frames are exercised by
 * the real-package fixture, while these generated packages only need a frame
 * the reader must decode. Raw blocks keep the helper dependency-free and
 * exactly reversible.
 */
internal fun zstdFrame(bytes: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte()))
    // Frame_Header_Descriptor: single segment, 4-byte Frame_Content_Size.
    out.write(0x20 or (2 shl 6))
    writeLittleEndian(out, bytes.size.toLong(), 4)
    val maxBlock = 128 * 1024
    var offset = 0
    while (true) {
        val size = minOf(maxBlock, bytes.size - offset)
        val last = offset + size >= bytes.size
        // Block_Header: last-block bit, Block_Type 0 (raw), 21-bit size.
        val header = (if (last) 1 else 0) or (size shl 3)
        writeLittleEndian(out, header.toLong(), 3)
        out.write(bytes, offset, size)
        offset += size
        if (last) break
    }
    return out.toByteArray()
}

private fun writeLittleEndian(out: ByteArrayOutputStream, value: Long, count: Int) {
    for (i in 0 until count) out.write(((value ushr (8 * i)) and 0xFF).toInt())
}
