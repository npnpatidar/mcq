package com.mcqapp

import android.graphics.Bitmap
import com.mcqapp.util.ImageUtils.uprightedBitmap
import com.mcqapp.util.decodeBounded
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A gallery/camera pick carries JPEG EXIF orientation != 1, which
 * `BitmapFactory` ignores: the stored bytes — and every export and
 * re-import after them — came out rotated. The fixtures under
 * test/resources/exif are a red-left/blue-right image stored with each
 * orientation tag, generated so that the REFERENCE display implementation
 * (PIL's `exif_transpose`) restores the red-left/blue-right scene from each
 * of them; the app's transform must agree with that reference.
 *
 * The mutants to run against these: drop the `applyExif` call in
 * `uprightedBitmap`, or revert the orientation table in `exifFix` — either
 * leaves the stored (tagged but unrotated) pixels in place and these asserts
 * go red.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImageUprightTest {

    private fun exifBytes(orientation: Int): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("exif/o$orientation.jpg")!!
            .use { it.readBytes() }

    private fun redLeftBlueRight(bitmap: android.graphics.Bitmap, orientation: Int, what: String) {
        // Sampled away from the JPEG's mid-image chroma ringing.
        val left = bitmap.getPixel(1, bitmap.height / 4)
        val right = bitmap.getPixel(bitmap.width - 2, bitmap.height / 4)
        assertTrue(
            "$what: left half must be red (orientation $orientation: $left)",
            android.graphics.Color.red(left) > 200 && android.graphics.Color.blue(left) < 60
        )
        assertTrue(
            "$what: right half must be blue (orientation $orientation: $right)",
            android.graphics.Color.blue(right) > 200 && android.graphics.Color.red(right) < 60
        )
    }

    @Test
    fun theDecodeAloneKeepsTheTaggedPixelsAsStored() {
        // Groundwork, not the proof: BitmapFactory ignores EXIF, so for the
        // sideways orientations the stored decode is 4x8. If this ever fails,
        // the decode environment changed and the proof below needs a new home.
        val stored = decodeBounded(exifBytes(6))!!
        assertEquals("BitmapFactory must ignore the orientation tag", 4, stored.width)
        assertEquals(8, stored.height)
    }

    @Test
    fun everyTaggedOrientationIsUprightedBeforeItIsStored() {
        for (orientation in 2..8) {
            val bytes = exifBytes(orientation)
            val upright = uprightedBitmap(bytes, 1024)
            assertNotNull("orientation $orientation produced no bitmap", upright)
            // All uprights are back to the scene's shape: 8x4, red left, blue right.
            assertEquals("orientation $orientation width", 8, upright!!.width)
            assertEquals("orientation $orientation height", 4, upright.height)
            redLeftBlueRight(upright, orientation, "uprighted")
        }
    }

    @Test
    fun anImageWithoutAnOrientationTagIsNotRotated() {
        // o1.jpg is the same red/blue scene with no (or neutral) EXIF tag:
        // the upright path must pass it through untouched, so a "fix" that
        // rotates every image cannot slip in.
        val bytes = exifBytes(1)
        val through = uprightedBitmap(bytes, 1024)!!
        assertEquals(8, through.width)
        assertEquals(4, through.height)
        redLeftBlueRight(through, 1, "untagged pass-through")
    }
}
