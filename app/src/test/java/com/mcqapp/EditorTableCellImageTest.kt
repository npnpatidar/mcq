package com.mcqapp

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.io.ImageDownscale
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.domain.ContentElement
import com.mcqapp.ui.editor.EditorSession
import com.mcqapp.ui.editor.EditorSource
import com.mcqapp.ui.editor.EditorViewModel
import com.mcqapp.ui.editor.ImageTarget
import com.mcqapp.ui.importscreen.ImportDataHolder
import com.mcqapp.util.CellEditPart
import com.mcqapp.util.cellEditParts
import com.mcqapp.util.cellEditorText
import com.mcqapp.util.cellTokenKey
import com.mcqapp.util.CELL_EDITOR_TOKEN_LEAD
import com.mcqapp.util.imgTagSrc
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import kotlin.random.Random

/**
 * BEHAVIOUR: the real ViewModel's cell-picture path. A gallery pick is
 * re-encoded at the top-level size, then downscaled to the table-cell cap
 * ([com.mcqapp.util.CELL_IMAGE_MAX_DIMENSION]) before it is written into one
 * cell; a picture that is still over the byte cap writes nothing and reports
 * the user-facing message. A text-only cell write through the token view is a
 * no-op for the dirty flag; a real edit makes the draft dirty.
 *
 * Robolectric because the picker decodes real bitmaps; it runs on CI's x86_64
 * runner (the ARM64 host has no Robolectric native runtime). The pure helpers
 * it complements are covered by CellEditorTextTest and
 * EditorTableCellAuthoringTest locally.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorTableCellImageTest {

    private val context: Application
        get() = ApplicationProvider.getApplicationContext()

    private fun dto() = QuestionDto(
        id = "q-cell-image",
        text = "Question body",
        elements = listOf(
            ContentElement.TextElement("Question body"),
            ContentElement.TableElement(listOf(listOf("a", "b"), listOf("c", "d")))
        ),
        options = listOf(
            OptionDto("a", "Alpha", listOf(ContentElement.TextElement("Alpha"))),
            OptionDto("b", "Beta", listOf(ContentElement.TextElement("Beta")))
        ),
        correctOptionIds = listOf("a"),
        explanation = "Because",
        explanationElements = listOf(ContentElement.TextElement("Because"))
    )

    private fun vmFor(question: QuestionDto = dto()): EditorViewModel {
        ImportDataHolder.pendingEditQuestion = question
        EditorSession.start(
            ids = listOf(question.id),
            index = 0,
            reader = { question },
            writer = { }
        )
        return EditorViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            questionId = question.id,
            paperId = "",
            categoryId = "",
            source = EditorSource.IMPORT
        )
    }

    @After
    fun tearDown() {
        ImportDataHolder.pendingEditQuestion = null
        EditorSession.clear()
    }

    /** Pumps Robolectric's main looper until [cond] holds (or times out). */
    private fun pumpUntil(cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!cond()) {
            assertTrue("timed out waiting for the editor", System.currentTimeMillis() < deadline)
            shadowOf(Looper.getMainLooper()).runToEndOfTasks()
            Thread.sleep(20)
        }
    }

    private fun loaded(vm: EditorViewModel): String {
        pumpUntil { vm.state.value.elements.size >= 2 }
        return vm.state.value.blockIds[1]
    }

    private fun tableCell(vm: EditorViewModel, row: Int, col: Int): String =
        (vm.state.value.elements[1] as ContentElement.TableElement).rows[row][col]

    /** A real PNG file; [noise] makes it incompressible so it stays large. */
    private fun pngUri(width: Int, height: Int, noise: Boolean): Uri {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        if (noise) {
            val pixels = IntArray(width * height)
            val random = Random(7)
            for (i in pixels.indices) {
                pixels[i] = 0xFF000000.toInt() or (random.nextInt() and 0x00FFFFFF)
            }
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        } else {
            bitmap.eraseColor(0xFF3366CC.toInt())
        }
        val file = File(context.cacheDir, "pick-$width-$height-$noise.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return Uri.fromFile(file)
    }

    private fun bounds(src: String): Pair<Int, Int> {
        val (_, bytes) = ImageDownscale.parseDataUri(src)
            ?: error("not a data URI: $src")
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return options.outWidth to options.outHeight
    }

    @Test
    fun aPickedCellImageIsDownscaledToTheCellCap() {
        val vm = vmFor()
        val tableId = loaded(vm)
        assertEquals("b", tableCell(vm, 0, 1))

        vm.beginImagePick(ImageTarget.TableCell(tableId, 0, 1))
        vm.onImagePicked(pngUri(1600, 200, noise = false))
        pumpUntil {
            tableCell(vm, 0, 1).contains("<img") || vm.state.value.imageError != null
        }
        assertNull("the pick must not fail: ${vm.state.value.imageError}", vm.state.value.imageError)

        val cell = tableCell(vm, 0, 1)
        assertTrue("the cell text is kept", cell.startsWith("b "))
        val tag = cellEditParts(cell).filterIsInstance<CellEditPart.Image>().single().tag
        val src = imgTagSrc(tag)
        assertNotNull("the inserted tag is drawable", src)
        val (width, height) = bounds(src!!)
        assertEquals("long edge capped at CELL_IMAGE_MAX_DIMENSION", 800, maxOf(width, height))
        assertEquals(100, height)
        assertNull(vm.state.value.imageError)
    }

    @Test
    fun anOversizedPickWritesNothingAndSetsTheMessage() {
        val vm = vmFor()
        val tableId = loaded(vm)
        val before = tableCell(vm, 0, 1)

        vm.beginImagePick(ImageTarget.TableCell(tableId, 0, 1))
        vm.onImagePicked(pngUri(1600, 1600, noise = true))
        pumpUntil { vm.state.value.imageError != null }

        assertEquals(
            context.getString(R.string.editor_cell_image_too_large),
            vm.state.value.imageError
        )
        assertEquals("an oversized picture must not be stored", before, tableCell(vm, 0, 1))
    }

    @Test
    fun theTokenViewIsCleanWhenUnchangedAndDirtyAfterAnEdit() {
        val vm = vmFor()
        val tableId = loaded(vm)
        val cell = tableCell(vm, 0, 0)
        val key = cellTokenKey(tableId, 0, 0)
        assertFalse("a freshly loaded form is clean", vm.state.value.isDirty)

        vm.updateTableCellEditor(tableId, 0, 0, cellEditorText(cell, key))
        assertFalse("writing the unchanged token view is a no-op", vm.state.value.isDirty)

        vm.updateTableCellEditor(tableId, 0, 0, cellEditorText(cell, key) + "x")
        assertTrue("a real text edit is dirty", vm.state.value.isDirty)

        vm.updateTableCellEditor(tableId, 0, 0, cellEditorText(cell, key))
        assertFalse("reverting to the persisted view is clean again", vm.state.value.isDirty)
    }

    /**
     * End-to-end key contract: the field builds its token with
     * `cellTokenKey(blockId, row, col)` and the ViewModel must use the same
     * key when it writes the field back. A mismatch would silently delete
     * every rich part on the first keystroke while every helper test stayed
     * green, so this goes through the real ViewModel with a real token.
     */
    @Test
    fun aTokenBearingEditThroughTheViewModelKeepsThePart() {
        val vm = vmFor()
        val tableId = loaded(vm)
        val formula = "<math><mi>x</mi></math>"
        vm.setTableCellPart(tableId, 0, 1, null, CellEditPart.Formula(formula))
        val cell = tableCell(vm, 0, 1)
        assertTrue("precondition: the formula was inserted", cell.contains(formula))

        val key = cellTokenKey(tableId, 0, 1)
        val view = cellEditorText(cell, key)
        assertTrue(
            "precondition: the field view carries a real token",
            view.any { it == CELL_EDITOR_TOKEN_LEAD }
        )
        vm.updateTableCellEditor(tableId, 0, 1, view + "x")

        val after = tableCell(vm, 0, 1)
        assertTrue("the formula must survive the keystroke", after.contains(formula))
        assertTrue("the typed text must be kept", after.endsWith("x"))
        assertFalse(
            "no raw Private-Use character may be stored",
            after.any { it in '\uE000'..'\uF8FF' }
        )
        assertTrue("the edit is dirty", vm.state.value.isDirty)
    }
}
