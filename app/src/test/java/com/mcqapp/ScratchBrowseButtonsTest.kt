package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.io.Importer
import com.mcqapp.data.io.LegacyParser
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.PassageReorder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Scratch diagnostics (not a permanent test): replay the Browse screen's
 * per-row move computation against the imported sample paper and report
 * every row whose buttons compute disabled.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = McqApplication::class)
class ScratchBrowseButtonsTest {

    @Test
    fun report() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val repository = McqRepository(db, context)
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "app/src/main/assets/sample_paper.json").isFile) dir = dir.parentFile
        val sample = dir?.let { File(it, "app/src/main/assets/sample_paper.json") }?.readText()
            ?: error("sample not found")
        val file = LegacyParser.parse(sample)
        Importer(repository.db(), repository.updateAnswersOnDuplicate().first()).import(file)

        val questions = repository.getQuestionsForPaper("paper-demo")
        val categoryQuestions = questions.groupBy { it.categoryId }
        println("rows: ${questions.size}, categories: ${categoryQuestions.size}")
        questions.forEach { q ->
            val cl = categoryQuestions[q.categoryId].orEmpty()
            val i = cl.indexOfFirst { it.id == q.id }
            val up = PassageReorder.canMove(cl, i, up = true)
            val down = PassageReorder.canMove(cl, i, up = false)
            val cat = q.categoryId.takeLast(12)
            val tag = if (q.passageId != null) "member(${q.passageId.takeLast(9)})" else "standalone"
            val flag = if (!up || !down) "  <-- up=$up down=$down" else ""
            println("%-12s cat…%-12s idx=%2d/%d %-18s%s".format(q.id, cat, i, cl.size, tag, flag))
        }
        db.close()
    }
}
