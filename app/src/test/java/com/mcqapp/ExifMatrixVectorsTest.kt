package com.mcqapp

import android.graphics.Matrix
import com.mcqapp.util.ImageUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Direction and mirroring, pinned by GEOMETRY instead of pixels: Robolectric's
 * matrix `Bitmap.createBitmap` yields transparent bitmaps (observed on CI run
 * 39406314390 and on this host), so the fixture test cannot testify about
 * colours. `mapVectors` is affine math — no pixels involved — so the basis
 * vectors through the matrix the code ACTUALLY applies say exactly which way
 * each orientation turns and whether it mirrors.
 *
 * This table is the composition-level statement of PIL's exif_transpose: the
 * auditor checked it against PIL on all seven committed fixtures, pixel for
 * pixel, and the fixture dimensions carry the same agreement independently.
 *
 * Mutant: scrambling the orientation table in ImageUtils.exifFix turns every
 * row of this test red.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExifMatrixVectorsTest {

    private fun exifMatrixFrom(orientation: Int): Pair<FloatArray, FloatArray> {
        val m = ImageUtils.exifMatrix(orientation, 8, 4)!!
        val ex = floatArrayOf(1f, 0f)
        val ey = floatArrayOf(0f, 1f)
        m.mapVectors(ex)
        m.mapVectors(ey)
        return ex to ey
    }

    @Test
    fun theVectorTableMatchesTheReferenceDisplayTransform() {
        // Fields: orientation, ex(1,0) destination, ey(0,1) destination.
        val table = mapOf(
            2 to (floatArrayOf(-1f, 0f) to floatArrayOf(0f, 1f)),   // flip horizontal
            3 to (floatArrayOf(-1f, 0f) to floatArrayOf(0f, -1f)),  // 180
            4 to (floatArrayOf(1f, 0f) to floatArrayOf(0f, -1f)),   // flip vertical
            5 to (floatArrayOf(0f, 1f) to floatArrayOf(1f, 0f)),    // transpose
            6 to (floatArrayOf(0f, 1f) to floatArrayOf(-1f, 0f)),   // rotate 90 CW
            7 to (floatArrayOf(0f, -1f) to floatArrayOf(-1f, 0f)),  // transverse
            8 to (floatArrayOf(0f, -1f) to floatArrayOf(1f, 0f))    // rotate 90 CCW
        )
        fun close(want: FloatArray, got: FloatArray) =
            kotlin.math.abs(want[0] - got[0]) <= 1e-4f &&
                kotlin.math.abs(want[1] - got[1]) <= 1e-4f

        val actual = table.keys.associateWith { exifMatrixFrom(it) }
        val report = table.entries.joinToString("\n") { (o, exp) ->
            val got = actual[o]!!
            "o$o: ex want(${exp.first.contentToString()}) got(${got.first.contentToString()})" +
                " | ey want(${exp.second.contentToString()}) got(${got.second.contentToString()})" +
                (if (close(exp.first, got.first) && close(exp.second, got.second)) "" else "   <-- MISMATCH")
        }
        assertTrue(
            "the vector table must match the reference transform:\n$report",
            table.entries.all { (o, exp) ->
                close(exp.first, actual[o]!!.first) && close(exp.second, actual[o]!!.second)
            }
        )
    }

    @Test
    fun anUnorientedImageGetsNoMatrix() {
        assertNull(ImageUtils.exifMatrix(1, 8, 4))
        assertNull(ImageUtils.exifMatrix(0, 8, 4))
        assertNull(ImageUtils.exifMatrix(99, 8, 4))
    }
}
