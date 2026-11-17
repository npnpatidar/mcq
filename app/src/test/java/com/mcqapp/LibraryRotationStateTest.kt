package com.mcqapp

import androidx.compose.runtime.saveable.SaverScope
import com.mcqapp.ui.library.BlueprintPartsSaver
import com.mcqapp.ui.library.BlueprintSaveTarget
import com.mcqapp.ui.library.blueprintEditId
import com.mcqapp.ui.library.blueprintSaveTarget
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * H12 regression: a blueprint EDIT must stay an EDIT across a configuration
 * change, and the typed parts must survive. The rotation path is built from
 * three pieces that are pure/state-saver logic and are exercised here exactly
 * as Compose uses them (save -> recreate -> restore):
 *
 * - [blueprintEditId] derives the edit id from saveable state only.
 * - [blueprintSaveTarget] makes the save call Update even when the loaded
 *   blueprint summary is momentarily null (the frame right after rotation).
 * - [BlueprintPartsSaver] is what `rememberSaveable` round-trips on rotation.
 *
 * On the old code these symbols did not exist — the edit id was a plain
 * `remember` and the save branched on the volatile loaded summary, so a
 * rotation silently created a duplicate. This test fails to compile/run there.
 */
class LibraryRotationStateTest {

    // Saver.save is a member extension: `fun SaverScope.save(value: Original)`.
    // Its dispatch receiver is the Saver and its extension receiver is the
    // scope, so call it as `saver.run { scope.save(value) }` — `with(scope) {
    // saver.save(value) }` does not resolve in Kotlin.
    private val saverScope = SaverScope { true }

    private fun saveParts(parts: Map<String, Int>): String =
        BlueprintPartsSaver.run { saverScope.save(parts) }!!

    @Test
    fun blueprintEditStaysEditAfterRotation() {
        // The two saveable inputs (the edit flag and the selected blueprint's
        // id, held in the sheet's `variant`) survive the config change intact.
        val isEditing = true
        val selectedVariant = "42"

        val editId = blueprintEditId(isEditing, selectedVariant)
        assertEquals(42L, editId)

        // Even with the loaded summary absent (blueprint list still reloading
        // after rotation), the save must UPDATE, never CREATE a duplicate.
        assertEquals(BlueprintSaveTarget.Update(42L), blueprintSaveTarget(editId))
    }

    @Test
    fun newBlueprintStaysCreateAcrossRotation() {
        val editId = blueprintEditId(isEditing = false, selectedVariant = "42")
        assertEquals(null, editId)
        assertEquals(BlueprintSaveTarget.Create, blueprintSaveTarget(editId))
    }

    @Test
    fun anUnsetVariantCannotInventAnEditId() {
        // Defensive: a saveable variant that is still null must not turn into
        // an edit (which would target id -1 / a wrong blueprint).
        assertEquals(null, blueprintEditId(isEditing = true, selectedVariant = null))
        assertEquals(BlueprintSaveTarget.Create, blueprintSaveTarget(null))
    }

    @Test
    fun typedBlueprintPartsSurviveSaveRestore() {
        val before = linkedMapOf(
            "science" to 2,
            "physics" to 3,
            "chemistry" to 1
        )

        // What Compose does on configuration change: save the value, recreate
        // the composable, restore the value from the saved representation.
        val saved = saveParts(before)
        val after = BlueprintPartsSaver.restore(saved)

        assertEquals(before, after)
    }

    @Test
    fun partsSaverRoundTripsEmptyAndOddIds() {
        assertEquals(emptyMap<String, Int>(), BlueprintPartsSaver.restore(saveParts(emptyMap())))

        val parts = mapOf("cat-id_with: punctuation =[]|" to 7)
        val saved = saveParts(parts)
        // The delimiters are control characters no category id contains, so an
        // otherwise awkward id still parses back to the exact same weight.
        assertEquals(parts, BlueprintPartsSaver.restore(saved))
    }
}
