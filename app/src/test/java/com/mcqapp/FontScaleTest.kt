package com.mcqapp

import com.mcqapp.util.FontScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FontScaleTest {

    @Test
    fun coerceSnapsToNearestOption() {
        assertEquals(1.0f, FontScale.coerce(1.0f), 0.0001f)
        assertEquals(0.85f, FontScale.coerce(0.8f), 0.0001f)
        assertEquals(1.15f, FontScale.coerce(1.2f), 0.0001f)
        assertEquals(1.3f, FontScale.coerce(2.0f), 0.0001f)
        assertEquals(1.3f, FontScale.coerce(1.3f), 0.0001f)
    }

    @Test
    fun optionsCoverSaneRange() {
        val scales = FontScale.OPTIONS.map { it.first }
        assertEquals(4, scales.size)
        assertTrue(scales.all { it in 0.5f..2.0f })
    }
}
