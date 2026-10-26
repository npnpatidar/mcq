package com.mcqapp.data.export

import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.PassageDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.data.renderInlineHtml
import com.mcqapp.domain.ContentElement

/**
 * Renders a paper as a single self-contained HTML page, question by question
 * into the caller's [Appendable]. Data-URI images are embedded as-is; remote
 * URLs are referenced in the markup but the page's CSP blocks loading them, so
 * a shared export never phones home.
 *
 * The page used to be assembled as one String first — a gigabyte at a million
 * questions — and then encoded, so it existed twice before it reached the
 * file. Now it goes out as it is written; a shared `java.io.Writer` over the
 * caller's stream means one encode and one buffer.
 */
object HtmlPaperWriter {

    suspend fun writeHtml(stream: PaperStream, out: Appendable) {
        head(stream, out, quiz = false)
        body(stream, out, quiz = false)
        out.append("</body>\n</html>\n")
    }

    /**
     * Quiz mode: correct answers and explanations are hidden until the reader
     * taps "Show answer". Fully self-contained (inline CSS + JS, no network).
     */
    suspend fun writeQuizHtml(stream: PaperStream, out: Appendable) {
        head(stream, out, quiz = true)
        out.append("<p><button class=\"toggle\" onclick=\"toggleAll(true)\">Show all answers</button>\n")
        out.append("<button class=\"toggle\" onclick=\"toggleAll(false)\">Hide all answers</button></p>\n")
        out.append("<script>\n").append(QUIZ_JS).append("</script>\n")
        body(stream, out, quiz = true)
        out.append("</body>\n</html>\n")
    }

    /** The head both pages share; only the CSP and the meta line differ. */
    private fun head(stream: PaperStream, out: Appendable, quiz: Boolean) {
        out.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
        out.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        // A shared export opens in the recipient's browser, so it must never
        // reach the network: data-URI images render, remote image URLs in the
        // bank stay inert (same containment as the in-app math page). The
        // static page runs no script; the quiz page's answer toggles are
        // allowed explicitly and nothing else is.
        out.append("<meta http-equiv=\"Content-Security-Policy\" ")
        out.append(
            if (quiz) {
                "content=\"default-src 'none'; script-src 'unsafe-inline'; "
            } else {
                "content=\"default-src 'none'; script-src 'none'; "
            }
        )
        out.append("style-src 'unsafe-inline'; img-src data:;\">\n")
        out.append("<title>").append(esc(stream.title)).append("</title>\n<style>\n")
        out.append(CSS)
        out.append("</style>\n</head>\n<body>\n")
        out.append("<h1>").append(esc(stream.title)).append("</h1>\n")
        if (quiz) {
            out.append("<p class=\"meta\">Quiz mode — answers are hidden. ")
                .append("Tap &quot;Show answer&quot; on any question to reveal it.</p>\n")
            return
        }
        if (stream.description.isNotBlank()) {
            out.append("<p class=\"meta\">").append(esc(stream.description)).append("</p>\n")
        }
        out.append("<p class=\"meta\">")
        if (stream.durationMinutes > 0) out.append(esc("${stream.durationMinutes} min • "))
        if (stream.negativeMarking > 0) {
            out.append(esc("negative marking ${stream.negativeMarking} • "))
        }
        out.append(esc("${stream.totalQuestions} questions"))
        out.append("</p>\n")
    }

    /** The question flow, shared by the answers-shown and quiz pages. */
    private suspend fun body(stream: PaperStream, out: Appendable, quiz: Boolean) {
        var number = 0
        var openPassageId: String? = null
        suspend fun emit(question: QuestionDto) {
            number++
            if (question.passageId != openPassageId) {
                openPassageId = question.passageId
                appendPassageBlock(out, question, stream.passages)
            }
            if (quiz) appendQuizQuestion(out, number, question)
            else appendQuestion(out, number, question)
        }
        stream.walk(
            onCategory = { category ->
                out.append("<h2>").append(esc(category.title)).append("</h2>\n")
                // The passage prints once above its group, not above every
                // member: contiguity is the invariant, and a document is read
                // linearly. A new run (or a group resumed in another category)
                // prints its header again.
                openPassageId = null
            },
            onQuestion = { emit(it) }
        )

        // Top-level questions, a shape a foreign file can carry: walk does not
        // visit them, while the header counts them (totalQuestions includes
        // them) and DOCX/JSON both render them. With categories present they
        // get their own section instead of joining the last one.
        val loose = stream.looseQuestions
        if (loose.isNotEmpty()) {
            if (stream.categories.isNotEmpty()) out.append("<h2>Uncategorized</h2>\n")
            openPassageId = null
            for (question in loose) emit(question)
        }
    }

    private fun appendQuizQuestion(out: Appendable, number: Int, question: QuestionDto) {
        out.append("<div class=\"q\">\n")
        out.append("<p class=\"qt\">Q").append(number.toString()).append(". ")
            .append(questionText(question)).append(marksSuffix(question)).append("</p>\n")
        if (question.options.isNotEmpty()) {
            out.append("<ul class=\"opts\">\n")
            question.options.forEachIndexed { index, option ->
                appendOption(out, index, option, isCorrect = false)
            }
            out.append("</ul>\n")
        }
        out.append("<button class=\"toggle\" onclick=\"toggle('ans")
            .append(number.toString()).append("')\">Show answer</button>\n")
        out.append("<div id=\"ans").append(number.toString())
            .append("\" class=\"ans\" style=\"display:none\">\n")
        val answer = answerLabels(question.options, question.correctOptionIds)
        if (answer.isNotEmpty()) {
            out.append("<p class=\"answer\">Answer: ")
                .append(answer.joinToString(", "))
                .append("</p>\n")
        }
        // The image-only explanation must pass: explanationElements empty and
        // explanation blank while explanationImage carries the block was
        // dropped entirely here, while PDF (answer key) and DOCX rendered it —
        // HTML was the odd one out of the three writers.
        if (question.explanationElements.isNotEmpty() ||
            question.explanation.isNotBlank() ||
            question.explanationImage != null
        ) {
            out.append("<p class=\"expl\">Explanation: ")
                .append(explanationHtml(question)).append("</p>\n")
        }
        out.append("</div>\n</div>\n")
    }

    private fun appendQuestion(out: Appendable, number: Int, question: QuestionDto) {
        out.append("<div class=\"q\">\n")
        out.append("<p class=\"qt\">Q").append(number.toString()).append(". ")
            .append(questionText(question)).append(marksSuffix(question)).append("</p>\n")
        if (question.options.isNotEmpty()) {
            out.append("<ul class=\"opts\">\n")
            question.options.forEachIndexed { index, option ->
                appendOption(out, index, option, option.id in question.correctOptionIds)
            }
            out.append("</ul>\n")
        }
        val answer = answerLabels(question.options, question.correctOptionIds)
        if (answer.isNotEmpty()) {
            out.append("<p class=\"answer\">Answer: ")
                .append(answer.joinToString(", "))
                .append("</p>\n")
        }
        // Same guard as the answer-shown path above: the image-only
        // explanation must not vanish from the quiz page.
        if (question.explanationElements.isNotEmpty() ||
            question.explanation.isNotBlank() ||
            question.explanationImage != null
        ) {
            out.append("<p class=\"expl\">Explanation: ")
                .append(explanationHtml(question)).append("</p>\n")
        }
        out.append("</div>\n")
    }

    private fun explanationHtml(question: QuestionDto): String =
        if (question.explanationElements.isNotEmpty()) {
            elementsToHtml(question.explanationElements, question.explanationImage)
        } else {
            renderInlineHtml(question.explanation) + imageTag(question.explanationImage)
        }

    /**
     * The shared context printed once above a passage group's first member
     * (the caller's run-detection decides when); the members follow it
     * contiguously, so a document reads like the app's Browse.
     */
    private fun appendPassageBlock(out: Appendable, question: QuestionDto, passages: List<PassageDto>) {
        val passage = passages.firstOrNull { it.id == question.passageId } ?: return
        out.append("<div class=\"passage\">\n")
        out.append("<p class=\"passage-title\">")
            .append(esc(passage.title.ifBlank { "Passage" })).append("</p>\n")
        if (passage.elements.isNotEmpty()) {
            out.append(elementsToHtml(passage.elements, passage.image))
        } else {
            // A legacy passage can carry an image and no elements, in which
            // case elementsToHtml would return "" — its empty-list guard is
            // what keeps it from printing the legacy image on its own. PDF
            // appends the legacy image after its element loop and DOCX embeds
            // it unconditionally, so such a passage prints in both; printing
            // only the title here was the odd one out.
            out.append(imageTag(passage.image))
        }
        out.append("</div>\n")
    }

    private fun appendOption(out: Appendable, index: Int, option: OptionDto, isCorrect: Boolean) {
        out.append("<li")
        if (isCorrect) out.append(" class=\"correct\"")
        out.append(">")
        out.append(if (isCorrect) "✓ " else "○ ")
        // By position, so the label always matches the letter the answer names.
        out.append(optionLetter(index)).append(") ")
            .append(
                if (option.elements.isNotEmpty()) elementsToHtml(option.elements, option.image)
                else renderInlineHtml(option.text) + imageTag(option.image)
            )
        out.append("</li>\n")
    }

    private fun appendImage(sb: StringBuilder, src: String?) {
        if (src == null) return
        sb.append("<img src=\"").append(esc(src)).append("\" alt=\"question image\">\n")
    }

    private fun imageTag(src: String?): String {
        if (src == null) return ""
        return "<img src=\"${esc(src)}\" alt=\"question image\">\n"
    }

    /**
     * Renders content elements to HTML: text is escaped, MathML is
     * re-emitted attribute-free through the shared sanitizer, tables become
     * `<table>` blocks, and images are embedded. Falls back to plain text
     * for legacy questions with no elements.
     */
    private fun elementsToHtml(elements: List<ContentElement>, legacyImage: String? = null): String {
        if (elements.isEmpty()) return ""
        val sb = StringBuilder()
        for (element in elements) {
            when (element) {
                is ContentElement.TextElement -> sb.append(renderInlineHtml(element.text))
                is ContentElement.ImageElement -> appendImage(sb, element.src)
                is ContentElement.TableElement -> {
                    sb.append("<table border=\"1\" cellspacing=\"0\" cellpadding=\"4\">")
                    for (row in element.rows) {
                        sb.append("<tr>")
                        // Cells carry inline markup like a TextElement; the
                        // escaped plain text rendered every tag literally
                        // (consolidated_bugs.md G1).
                        for (cell in row) {
                            sb.append("<td>")
                                .append(com.mcqapp.util.tableCellHtml(cell) {
                                    com.mcqapp.data.sanitizeMathml(it)
                                })
                                .append("</td>")
                        }
                        sb.append("</tr>")
                    }
                    sb.append("</table>")
                }
                is ContentElement.MathElement -> sb.append(com.mcqapp.data.sanitizeMathml(element.mathml))
            }
        }
        appendImage(sb, legacyImage)
        return sb.toString()
    }

    /**
     * Renders inline HTML tags (`<sub>`, `<sup>`, `<strong>`, `<em>`, etc.)
     * as-is, escaping anything else — including unknown tag shapes like
     * `<one>`, which are literal text in legacy questions and must stay
     * visible rather than vanish. Matches the app's InlineHtml display
     * for known tags, so the export looks the same as the screen.
     */
    /** Legacy fallback: render plain text when no elements are stored. */
    private fun questionText(question: QuestionDto): String =
        if (question.elements.isNotEmpty()) elementsToHtml(question.elements, question.image)
        else renderInlineHtml(question.text) + imageTag(question.image)

    /** Shared with the Anki and in-app renderers so all three agree. */
    fun esc(s: String): String = com.mcqapp.data.escapeHtmlText(s)

    /** Shown only for non-default weights so existing output is byte-identical. */
    private fun marksSuffix(question: QuestionDto): String {
        if (question.marks == 1.0) return ""
        val display = if (question.marks == kotlin.math.floor(question.marks)) {
            question.marks.toLong().toString()
        } else {
            question.marks.toString()
        }
        return " [$display marks]"
    }

    private const val CSS = """
body { font-family: sans-serif; max-width: 800px; margin: 0 auto; padding: 16px; color: #222; }
h1 { font-size: 1.5em; border-bottom: 2px solid #333; padding-bottom: 8px; }
h2 { font-size: 1.2em; color: #444; margin-top: 1.5em; }
.meta { color: #666; font-size: 0.9em; }
.q { border: 1px solid #ddd; border-radius: 8px; padding: 12px; margin: 12px 0; }
.qt { margin: 0 0 8px 0; }
ul.opts { list-style: none; padding: 0; margin: 8px 0; }
ul.opts li { padding: 2px 0; }
ul.opts li.correct { color: #2E7D32; font-weight: bold; }
ul.opts li.optimg { padding-left: 18px; }
img { max-width: 100%; height: auto; border-radius: 4px; margin: 4px 0; }
.answer { color: #2E7D32; }
.passage { background: #f5f5f5; border-left: 3px solid #1976D2; padding: 8px 12px;
  margin: 0 0 8px 0; border-radius: 4px; }
.passage-title { font-weight: bold; color: #1976D2; margin: 0 0 4px 0; }
.expl { color: #666; font-size: 0.9em; }
button.toggle { background: #1976D2; color: #fff; border: none; border-radius: 6px;
  padding: 6px 12px; margin: 4px 4px 4px 0; font-size: 0.9em; cursor: pointer; }
.ans { border-top: 1px dashed #bbb; margin-top: 8px; padding-top: 4px; }
"""

    private const val QUIZ_JS = """
function toggle(id) {
  var e = document.getElementById(id);
  if (!e) return;
  var show = e.style.display === 'none';
  e.style.display = show ? 'block' : 'none';
  var btns = e.parentElement.getElementsByClassName('toggle');
  for (var i = 0; i < btns.length; i++) {
    if (btns[i].getAttribute('onclick') && btns[i].getAttribute('onclick').indexOf(id) >= 0) {
      btns[i].textContent = show ? 'Hide answer' : 'Show answer';
    }
  }
}
function toggleAll(show) {
  var list = document.getElementsByClassName('ans');
  for (var i = 0; i < list.length; i++) {
    list[i].style.display = show ? 'block' : 'none';
  }
  var btns = document.getElementsByClassName('toggle');
  for (var j = 0; j < btns.length; j++) {
    if (btns[j].textContent === 'Show answer' || btns[j].textContent === 'Hide answer') {
      btns[j].textContent = show ? 'Hide answer' : 'Show answer';
    }
  }
}
"""
}
