package com.mcqapp.data.export

import androidx.annotation.StringRes
import com.mcqapp.R

/**
 * Export formats offered for a single paper.
 *
 * [title] and [description] stay as stored literals for compatibility; the
 * paired [titleRes]/[descriptionRes] are the localized strings the UI resolves
 * with `stringResource`. Keeping both on the same enum entry means a call site
 * cannot silently pick the wrong label for a format.
 *
 * [contentOnlyNoteRes] is the honest caveat for the re-importable per-paper
 * formats that carry questions but no study progress (JSON and ZIP). Without
 * it a learner could mistake them for a full backup and lose schedules and
 * review history. Zero means the format does not need the caveat.
 */
enum class ExportFormat(
    val mimeType: String,
    val extension: String,
    val title: String,
    val description: String,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    val fileSuffix: String = "",
    @StringRes val contentOnlyNoteRes: Int = 0
) {
    JSON_INLINE(
        "application/json",
        "json",
        "JSON (images inline)",
        "Single .json file, images embedded as data URIs. Re-importable.",
        R.string.r2_res_export_json_inline_title,
        R.string.r2_res_export_json_inline_description,
        contentOnlyNoteRes = R.string.r2_backup_paper_export_content_only
    ),
    ZIP(
        "application/zip",
        "zip",
        "ZIP (JSON + images)",
        ".zip with paper.json plus an images/ folder the JSON refers to.",
        R.string.r2_res_export_zip_title,
        R.string.r2_res_export_zip_description,
        contentOnlyNoteRes = R.string.r2_backup_paper_export_content_only
    ),
    HTML(
        "text/html",
        "html",
        "Web page (answers shown)",
        "Single .html file, correct answers visible. Opens in any browser.",
        R.string.r2_res_export_html_title,
        R.string.r2_res_export_html_description
    ),
    HTML_QUIZ(
        "text/html",
        "html",
        "Web page (quiz mode)",
        "Answers hidden; tap Show answer to reveal. Self-contained.",
        R.string.r2_res_export_html_quiz_title,
        R.string.r2_res_export_html_quiz_description,
        "-quiz"
    ),
    PDF(
        "application/pdf",
        "pdf",
        "PDF (answers inline)",
        ".pdf with correct answers under each question.",
        R.string.r2_res_export_pdf_title,
        R.string.r2_res_export_pdf_description
    ),
    PDF_ANSWER_KEY(
        "application/pdf",
        "pdf",
        "PDF (answer key at end)",
        ".pdf for self-testing: questions first, all answers at the end.",
        R.string.r2_res_export_pdf_answer_key_title,
        R.string.r2_res_export_pdf_answer_key_description,
        "-answer-key"
    ),
    APKG(
        "application/octet-stream",
        "apkg",
        "Anki deck (.apkg)",
        "Importable with Anki. Each question becomes a flashcard in one deck.",
        R.string.r2_res_export_apkg_title,
        R.string.r2_res_export_apkg_description
    ),
    DOCX(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "docx",
        "Word document (.docx)",
        "Opens in Word. Headings carry the category tree; answers and explanations inline. Re-importable.",
        R.string.r2_res_export_docx_title,
        R.string.r2_res_export_docx_description
    );

    /**
     * The picker description for this format, with the content-only caveat
     * appended when the export drops study progress. Both pieces are resolved
     * through the caller's locale, so the note follows the selected language.
     * Formats without a caveat render just their own description.
     */
    fun descriptionWithCaveat(getString: (Int) -> String): String {
        val base = getString(descriptionRes)
        return if (contentOnlyNoteRes == 0) base else base + "\n\n" + getString(contentOnlyNoteRes)
    }
}
