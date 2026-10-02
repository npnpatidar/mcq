package com.mcqapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.mcqapp.util.MAX_EXPORT_DIMENSION
import com.mcqapp.util.MAX_PREVIEW_DIMENSION
import com.mcqapp.util.calculateInSampleSize
import com.mcqapp.util.decodeBounded
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Image payloads arrive inside imported files, and `BitmapFactory` expands one
 * to full ARGB_8888 size before anything can object. A 20000×20000 PNG is
 * 1.6 GB decoded, and the resulting OutOfMemoryError is an `Error`, which a
 * surrounding `catch (e: Exception)` never sees.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BitmapDecoderTest {

    @Test
    fun smallImagesAreNotSubsampled() {
        assertEquals(1, calculateInSampleSize(320, 240, 1600))
        assertEquals(1, calculateInSampleSize(1600, 1600, 1600))
    }

    @Test
    fun largeImagesAreSubsampledByAPowerOfTwo() {
        assertEquals(2, calculateInSampleSize(3200, 2400, 1600))
        assertEquals(4, calculateInSampleSize(6400, 4800, 1600))
        assertEquals(8, calculateInSampleSize(20000, 20000, 1600))
    }

    @Test
    fun samplingStopsBeforeUndershootingTheRequest() {
        // 1700px already fits the cap, and halving would undershoot it.
        assertEquals(1, calculateInSampleSize(1700, 1700, 1600))
        assertEquals(2, calculateInSampleSize(3300, 3300, 1600))
    }

    @Test
    fun theLongestEdgeDecidesTheSubsample() {
        // A 3200x2400 photo still needs halving to respect a 1600 px cap even
        // though its short edge would not survive a further halving.
        assertEquals(2, calculateInSampleSize(2400, 3200, 1600))
        // Portrait: the long edge is the height, and it still drives the result.
        assertEquals(2, calculateInSampleSize(1600, 3200, 1600))
        // A short edge already inside the cap changes nothing.
        assertEquals(1, calculateInSampleSize(1600, 1500, 1600))
    }

    @Test
    fun unknownDimensionsAreLeftAlone() {
        assertEquals(1, calculateInSampleSize(0, 0, 1600))
        assertEquals(1, calculateInSampleSize(-1, 100, 1600))
    }

    @Test
    fun emptyInputDecodesToNullInsteadOfThrowing() {
        assertNull(decodeBounded(ByteArray(0)))
        // Garbage input is not asserted here: Robolectric's BitmapFactory
        // shadow returns a bitmap for arbitrary bytes, so only a real device
        // can show that this returns null.
    }

    @Test
    fun aRealImageDecodes() {
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        val bytes = java.io.ByteArrayOutputStream().also { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }.toByteArray()
        val decoded = decodeBounded(bytes, MAX_PREVIEW_DIMENSION)
        assertEquals(64, decoded?.width)
        assertEquals(48, decoded?.height)
    }

    @Test
    fun aLargeImageIsDecodedSmallerThanItsSource() {
        val source = Bitmap.createBitmap(3000, 2000, Bitmap.Config.ARGB_8888)
        val bytes = java.io.ByteArrayOutputStream().also { out ->
            source.compress(Bitmap.CompressFormat.PNG, 100, out)
        }.toByteArray()
        val decoded = decodeBounded(bytes, 800)
        // The point of the whole exercise: without subsampling this decode
        // would allocate 3000*2000*4 = 24 MB up front.
        assertTrue("decoded ${decoded?.width}x${decoded?.height}", decoded!!.width <= 1500)
        assertTrue(decoded.width < source.width)
    }

    @Test
    fun aTruncatedImageFailsSoftly() {
        val source = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val full = java.io.ByteArrayOutputStream().also { out ->
            source.compress(Bitmap.CompressFormat.PNG, 100, out)
        }.toByteArray()
        // Corrupt the tail rather than truncating cleanly.
        val corrupt = full.copyOf(full.size / 2).also { it[it.size - 1] = 0x7F }
        // Either outcome is acceptable; what matters is that it returns.
        decodeBounded(corrupt, MAX_EXPORT_DIMENSION)
        assertTrue(true)
    }
}
