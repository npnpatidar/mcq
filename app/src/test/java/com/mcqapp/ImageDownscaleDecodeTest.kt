package com.mcqapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.mcqapp.data.io.ImageDownscale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/**
 * The downscale's pixel path: what comes out of it, and what is passed
 * through untouched.
 *
 * The fix these pin is a memory one — the decode used to ask
 * `BitmapFactory` for the full image before scaling it — and its output is
 * the same, so the peak is not observable here: the audit mutant is the
 * unbounded decode, under which this suite stays green. What is observable
 * is pinned below: the target dimensions, the format, the byte-identical
 * pass-through below the limit, and the pass-through of an image that
 * cannot be decoded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImageDownscaleDecodeTest {

    /** A real PNG of the given size, as a data URI. */
    private fun png(width: Int, height: Int): String {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return ImageDownscale.encodeDataUri("image/png", out.toByteArray())
    }

    /** The size of the image inside a data URI. */
    private fun sizeOf(dataUri: String): Pair<Int, Int> {
        val (_, bytes) = ImageDownscale.parseDataUri(dataUri)!!
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return bounds.outWidth to bounds.outHeight
    }

    @Test
    fun anOversizedImageIsScaledToTheTarget() {
        val out = ImageDownscale.downscaleDataUri(png(2560, 1280), 1280)!!
        assertEquals(1280 to 640, sizeOf(out))
        assertTrue("the PNG stays a PNG", out.startsWith("data:image/png;base64,"))
    }

    @Test
    fun aSourceThatIsAnExactMultipleOfTheLimitIsStillScaled() {
        // A subsampled decode of a 2560px source lands exactly on 1280, so a
        // decision taken on the decoded size would let this through
        // unchanged — the source size is what decides.
        val src = png(2560, 1280)
        val out = ImageDownscale.downscaleDataUri(src, 1280)!!
        assertNotEquals(src, out)
        assertEquals(1280 to 640, sizeOf(out))
    }

    @Test
    fun anImageAtOrBelowTheLimitPassesThroughByteIdentical() {
        // The duplicate hash is computed on the scaled DTO, so an image the
        // downscale leaves alone has to come out byte for byte the same.
        val src = png(800, 600)
        assertEquals(src, ImageDownscale.downscaleDataUri(src, 1280))
    }

    @Test
    fun anImageThatCannotBeDecodedPassesThrough() {
        val garbage = ImageDownscale.encodeDataUri("image/png", byteArrayOf(1, 2, 3, 4))
        assertEquals(garbage, ImageDownscale.downscaleDataUri(garbage, 1280))
    }
}
