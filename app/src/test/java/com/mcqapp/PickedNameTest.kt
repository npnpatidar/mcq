package com.mcqapp

import com.mcqapp.util.pickDisplayName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The import picker's name choice. The provider's display name is the file's
 * real name; a content Uri's last path segment is the provider's row id — the
 * Downloads provider reports "msf:1001050295" — which must never outrank it.
 */
class PickedNameTest {

    @Test
    fun theDisplayNameWinsOverTheRowId() {
        assertEquals(
            "Physics Bank.docx",
            pickDisplayName("Physics Bank.docx", "msf:1001050295")
        )
    }

    @Test
    fun aBlankDisplayNameFallsBackToTheSegment() {
        assertEquals("deck.apkg", pickDisplayName("   ", "deck.apkg"))
        assertEquals("deck.apkg", pickDisplayName(null, "deck.apkg"))
    }

    @Test
    fun theSegmentIsUsedByItsLastComponent() {
        assertEquals("deck.apkg", pickDisplayName(null, "primary:Download/deck.apkg"))
    }

    @Test
    fun nothingUsableIsNullSoTheCallerCanFallBack() {
        assertNull(pickDisplayName(null, null))
        assertNull(pickDisplayName(null, "   "))
        assertNull(pickDisplayName("", ""))
    }
}
