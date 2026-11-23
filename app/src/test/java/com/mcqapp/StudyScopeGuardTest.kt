package com.mcqapp

import com.mcqapp.ui.study.ScopeChoice
import com.mcqapp.ui.study.canStartScope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BEHAVIOUR: exercises the pure Start guard for the study scope sheet.
 *
 * A TAG scope cannot start until a tag is selected. The button's enabled state
 * and its click handler share this pure guard, so it is checked on the JVM host
 * as well as by the Compose UI test that drives the dialog.
 */
class StudyScopeGuardTest {

    @Test
    fun emptyTagCannotStartButEveryOtherChoiceCan() {
        assertFalse(canStartScope(ScopeChoice.TAG, ""))
        assertFalse(canStartScope(ScopeChoice.TAG, "   "))
        assertTrue(canStartScope(ScopeChoice.WHOLE, ""))
        assertTrue(canStartScope(ScopeChoice.MISTAKES, ""))
    }

    @Test
    fun aSelectedTagCanStart() {
        assertTrue(canStartScope(ScopeChoice.TAG, "science"))
    }
}
