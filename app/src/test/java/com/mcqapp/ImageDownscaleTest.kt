package com.mcqapp

import com.mcqapp.data.io.ImageDownscale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageDownscaleTest {

    private val tinyPng = "data:image/png;base64,iVBORw0KGgo="

    @Test
    fun parseDataUriExtractsMimeAndBytes() {
        val (mime, bytes) = ImageDownscale.parseDataUri(tinyPng)!!
        assertEquals("image/png", mime)
        assertTrue(bytes.isNotEmpty())
    }

    @Test
    fun parseDataUriRejectsGarbage() {
        assertNull(ImageDownscale.parseDataUri(null))
        assertNull(ImageDownscale.parseDataUri("https://example.com/a.png"))
        assertNull(ImageDownscale.parseDataUri("data:image/png;base64"))
        assertNull(ImageDownscale.parseDataUri("data:image/png;base64,!!!not-base64!!!"))
        assertNull(ImageDownscale.parseDataUri("data:text/plain,hello"))
    }

    @Test
    fun targetSizeCapsLongEdge() {
        assertEquals(1280 to 640, ImageDownscale.targetSize(2560, 1280, 1280))
        assertEquals(640 to 1280, ImageDownscale.targetSize(1280, 2560, 1280))
        assertEquals(800 to 600, ImageDownscale.targetSize(800, 600, 1280))
        assertEquals(1280 to 1280, ImageDownscale.targetSize(2000, 2000, 1280))
        assertEquals(0 to 0, ImageDownscale.targetSize(0, 0, 1280))
    }

    @Test
    fun encodeRoundTripsBytes() {
        val (_, bytes) = ImageDownscale.parseDataUri(tinyPng)!!
        val encoded = ImageDownscale.encodeDataUri("image/png", bytes)
        val (mime2, bytes2) = ImageDownscale.parseDataUri(encoded)!!
        assertEquals("image/png", mime2)
        assertTrue(bytes.contentEquals(bytes2))
    }

    @Test
    fun downscalePassesThroughUntouchableSources() {
        assertNull(ImageDownscale.downscaleDataUri(null))
        val remote = "https://example.com/a.png"
        assertEquals(remote, ImageDownscale.downscaleDataUri(remote))
        val corrupt = "data:image/png;base64,!!!not-base64!!!"
        assertEquals(corrupt, ImageDownscale.downscaleDataUri(corrupt))
        val text = "data:text/plain;base64,aGVsbG8="
        assertEquals(text, ImageDownscale.downscaleDataUri(text))
    }
}
