package com.mcqapp.data.export

import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.PassageDto
import com.mcqapp.data.io.QuestionDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * Streams the single-paper `McqFileDto` envelope — `{version, papers:[paper],
 * passages:[…]}` — the shape the inline JSON export, a zip's `paper.json` and
 * a docx's hidden payload all write.
 *
 * The envelope's own fields, the category list and the passages are small even
 * at a million questions, so they are written as they come. The questions are
 * encoded one at a time, straight off the [PaperStream] walk, and dropped: the
 * envelope never exists as one String, and the paper's question graph never
 * exists at all.
 *
 * The output is compact and omits fields at their default, like the streaming
 * backup writer; what has to hold is the schema, so this is pinned by a
 * parse-and-compare test rather than by byte equality. `question` and `passage`
 * transform a row on the way out — the zip repoints image srcs at its
 * `images/` files and the docx blanks them — without either building a second
 * copy of the paper.
 */
object PaperJsonWriter {

    suspend fun writePaperJson(
        out: Appendable,
        stream: PaperStream,
        json: Json = Json,
        question: (QuestionDto) -> QuestionDto = { it },
        passage: (PassageDto) -> PassageDto = { it }
    ) {
        val envelope = Envelope(out, json, question, passage)
        out.append("{\"version\":1,\"papers\":[{")
        envelope.fields(stream)
        envelope.categories(stream)
        envelope.questions(stream.looseQuestions)
        envelope.passages(stream.passages)
        out.append("}],\"passages\":[")
        envelope.passageRows(stream.passages)
        out.append("]}")
    }

    private class Envelope(
        private val out: Appendable,
        private val json: Json,
        private val question: (QuestionDto) -> QuestionDto,
        private val passage: (PassageDto) -> PassageDto
    ) {
        /** Whether a category's `"questions":[…]` array is still open. */
        private var openCategory = false

        /** Whether the question array being written already has an element. */
        private var wroteQuestion = false

        /** The paper's own fields, in the DTO's declaration order. */
        fun fields(stream: PaperStream) {
            out.append("\"id\":").append(text(stream.id))
            out.append(",\"title\":").append(text(stream.title))
            if (stream.description.isNotEmpty()) {
                out.append(",\"description\":").append(text(stream.description))
            }
            if (stream.durationMinutes != 0) {
                out.append(",\"durationMinutes\":").append(stream.durationMinutes.toString())
            }
            if (stream.negativeMarking != 0.0) {
                out.append(",\"negativeMarking\":").append(stream.negativeMarking.toString())
            }
        }

        suspend fun categories(stream: PaperStream) {
            if (stream.categories.isEmpty()) return
            out.append(",\"categories\":[")
            stream.walk(onCategory = { open(it) }, onQuestion = { write(it) })
            closeCategory()
            out.append(']')
        }

        /** The paper-level `questions` array: rows outside every category. */
        fun questions(rows: List<QuestionDto>) {
            if (rows.isEmpty()) return
            out.append(",\"questions\":[")
            rows.forEachIndexed { index, row ->
                if (index > 0) out.append(',')
                out.append(json.encodeToString(QuestionDto.serializer(), question(row)))
            }
            out.append(']')
        }

        /** `"passages":[…]`, omitted when the paper references none. */
        fun passages(rows: List<PassageDto>) {
            if (rows.isEmpty()) return
            out.append(",\"passages\":[")
            passageRows(rows)
            out.append(']')
        }

        /** Just the passage objects, for an array the caller already opened. */
        fun passageRows(rows: List<PassageDto>) {
            rows.forEachIndexed { index, row ->
                if (index > 0) out.append(',')
                out.append(json.encodeToString(PassageDto.serializer(), passage(row)))
            }
        }

        /** Whether a category object has already been written (needs a separator). */
        private var wroteCategory = false

        private fun open(category: CategoryDto) {
            closeCategory()
            // The walk emits one object per category, each closed by
            // closeCategory(); without this comma two categories produced
            // `]}{` and every consumer of paper.json rejected the document.
            if (wroteCategory) out.append(',')
            wroteCategory = true
            out.append("{\"id\":").append(text(category.id))
            out.append(",\"title\":").append(text(category.title))
            if (category.parentId != null) {
                out.append(",\"parentId\":").append(text(category.parentId))
            }
            out.append(",\"questions\":[")
            openCategory = true
            wroteQuestion = false
        }

        private fun write(row: QuestionDto) {
            if (wroteQuestion) out.append(',')
            wroteQuestion = true
            out.append(json.encodeToString(QuestionDto.serializer(), question(row)))
        }

        private fun closeCategory() {
            if (!openCategory) return
            out.append("]}")
            openCategory = false
        }

        private fun text(value: String): String = JsonPrimitive(value).toString()
    }
}
