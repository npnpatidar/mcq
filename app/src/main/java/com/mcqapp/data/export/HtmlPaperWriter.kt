package com.mcqapp.data.export

import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.QuestionDto

/**
 * Renders a paper as a single self-contained HTML page. Data-URI images are
 * embedded as-is; remote URLs are referenced as-is.
 */
object HtmlPaperWriter {

    fun paperToHtml(paper: PaperDto): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        sb.append("<title>").append(esc(paper.title)).append("</title>\n<style>\n")
        sb.append(CSS)
        sb.append("</style>\n</head>\n<body>\n")
        sb.append("<h1>").append(esc(paper.title)).append("</h1>\n")
        if (paper.description.isNotBlank()) {
            sb.append("<p class=\"meta\">").append(esc(paper.description)).append("</p>\n")
        }
        val total = paper.categories.sumOf { it.questions.size }
        sb.append("<p class=\"meta\">")
        if (paper.durationMinutes > 0) sb.append(esc("${paper.durationMinutes} min • "))
        if (paper.negativeMarking > 0) sb.append(esc("negative marking ${paper.negativeMarking} • "))
        sb.append(esc("$total questions"))
        sb.append("</p>\n")

        var number = 0
        for (category in paper.categories) {
            sb.append("<h2>").append(esc(category.title)).append("</h2>\n")
            for (question in category.questions) {
                number++
                appendQuestion(sb, number, question)
            }
        }
        sb.append("</body>\n</html>\n")
        return sb.toString()
    }

    /**
     * Quiz mode: correct answers and explanations are hidden until the reader
     * taps "Show answer". Fully self-contained (inline CSS + JS, no network).
     */
    fun paperToQuizHtml(paper: PaperDto): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        sb.append("<title>").append(esc(paper.title)).append("</title>\n<style>\n")
        sb.append(CSS)
        sb.append("</style>\n</head>\n<body>\n")
        sb.append("<h1>").append(esc(paper.title)).append("</h1>\n")
        sb.append("<p class=\"meta\">Quiz mode — answers are hidden. ")
            .append("Tap &quot;Show answer&quot; on any question to reveal it.</p>\n")
        sb.append("<p><button class=\"toggle\" onclick=\"toggleAll(true)\">Show all answers</button>\n")
        sb.append("<button class=\"toggle\" onclick=\"toggleAll(false)\">Hide all answers</button></p>\n")
        sb.append("<script>\n").append(QUIZ_JS).append("</script>\n")

        var number = 0
        for (category in paper.categories) {
            sb.append("<h2>").append(esc(category.title)).append("</h2>\n")
            for (question in category.questions) {
                number++
                appendQuizQuestion(sb, number, question)
            }
        }
        sb.append("</body>\n</html>\n")
        return sb.toString()
    }

    private fun appendQuizQuestion(sb: StringBuilder, number: Int, question: QuestionDto) {
        sb.append("<div class=\"q\">\n")
        sb.append("<p class=\"qt\">Q").append(number).append(". ")
            .append(esc(question.text)).append(marksSuffix(question)).append("</p>\n")
        appendImage(sb, question.image)
        if (question.options.isNotEmpty()) {
            sb.append("<ul class=\"opts\">\n")
            for (option in question.options) {
                appendOption(sb, option, isCorrect = false)
            }
            sb.append("</ul>\n")
        }
        sb.append("<button class=\"toggle\" onclick=\"toggle('ans")
            .append(number).append("')\">Show answer</button>\n")
        sb.append("<div id=\"ans").append(number).append("\" class=\"ans\" style=\"display:none\">\n")
        val correct = question.options.filter { it.id in question.correctOptionIds }
        if (correct.isNotEmpty()) {
            sb.append("<p class=\"answer\">Answer: ")
                .append(esc(correct.joinToString(", ") { it.text }))
                .append("</p>\n")
        }
        if (question.explanation.isNotBlank()) {
            sb.append("<p class=\"expl\">Explanation: ")
                .append(esc(question.explanation)).append("</p>\n")
        }
        appendImage(sb, question.explanationImage)
        sb.append("</div>\n</div>\n")
    }

    private fun appendQuestion(sb: StringBuilder, number: Int, question: QuestionDto) {
        sb.append("<div class=\"q\">\n")
        sb.append("<p class=\"qt\">Q").append(number).append(". ")
            .append(esc(question.text)).append(marksSuffix(question)).append("</p>\n")
        appendImage(sb, question.image)
        if (question.options.isNotEmpty()) {
            sb.append("<ul class=\"opts\">\n")
            for (option in question.options) {
                appendOption(sb, option, option.id in question.correctOptionIds)
            }
            sb.append("</ul>\n")
        }
        val correct = question.options.filter { it.id in question.correctOptionIds }
        if (correct.isNotEmpty()) {
            sb.append("<p class=\"answer\">Answer: ")
                .append(esc(correct.joinToString(", ") { it.text }))
                .append("</p>\n")
        }
        if (question.explanation.isNotBlank()) {
            sb.append("<p class=\"expl\">Explanation: ")
                .append(esc(question.explanation)).append("</p>\n")
        }
        appendImage(sb, question.explanationImage)
        sb.append("</div>\n")
    }

    private fun appendOption(sb: StringBuilder, option: OptionDto, isCorrect: Boolean) {
        sb.append("<li")
        if (isCorrect) sb.append(" class=\"correct\"")
        sb.append(">")
        sb.append(if (isCorrect) "✓ " else "○ ")
        sb.append(esc(option.id)).append(") ").append(esc(option.text))
        sb.append("</li>\n")
        if (option.image != null) {
            sb.append("<li class=\"optimg\">")
            appendImage(sb, option.image)
            sb.append("</li>\n")
        }
    }

    private fun appendImage(sb: StringBuilder, src: String?) {
        if (src == null) return
        sb.append("<img src=\"").append(esc(src)).append("\" alt=\"question image\">\n")
    }

    fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

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
.qt { font-weight: bold; margin: 0 0 8px 0; }
ul.opts { list-style: none; padding: 0; margin: 8px 0; }
ul.opts li { padding: 2px 0; }
ul.opts li.correct { color: #2E7D32; font-weight: bold; }
ul.opts li.optimg { padding-left: 18px; }
img { max-width: 100%; height: auto; border-radius: 4px; margin: 4px 0; }
.answer { color: #2E7D32; }
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
