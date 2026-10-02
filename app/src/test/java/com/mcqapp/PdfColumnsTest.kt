package com.mcqapp

import com.mcqapp.data.export.PdfColumns
import org.junit.Assert.assertEquals
import org.junit.Test

class PdfColumnsTest {

    @Test
    fun singleColumnIsFullWidth() {
        val cols = PdfColumns(pageWidth = 595f, margin = 40f, twoColumn = false)
        assertEquals(1, cols.count)
        assertEquals(515f, cols.contentWidth(), 0.001f)
        assertEquals(40f, cols.originX(0), 0.001f)
    }

    @Test
    fun twoColumnsShareWidthWithGutter() {
        val cols = PdfColumns(pageWidth = 595f, margin = 40f, twoColumn = true)
        assertEquals(2, cols.count)
        assertEquals(245.5f, cols.contentWidth(), 0.001f)
        assertEquals(40f, cols.originX(0), 0.001f)
        assertEquals(309.5f, cols.originX(1), 0.001f)
        // Right edge lands exactly on the page margin.
        assertEquals(595f, cols.originX(1) + cols.contentWidth() + 40f, 0.001f)
    }
}
