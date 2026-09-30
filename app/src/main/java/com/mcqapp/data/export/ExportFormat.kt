package com.mcqapp.data.export

/** Export formats offered for a single paper. */
enum class ExportFormat(
    val mimeType: String,
    val extension: String,
    val title: String,
    val description: String,
    val fileSuffix: String = ""
) {
    JSON_INLINE(
        "application/json",
        "json",
        "JSON (images inline)",
        "Single .json file, images embedded as data URIs. Re-importable."
    ),
    ZIP(
        "application/zip",
        "zip",
        "ZIP (JSON + images)",
        ".zip with paper.json plus an images/ folder the JSON refers to."
    ),
    HTML(
        "text/html",
        "html",
        "Web page (answers shown)",
        "Single .html file, correct answers visible. Opens in any browser."
    ),
    HTML_QUIZ(
        "text/html",
        "html",
        "Web page (quiz mode)",
        "Answers hidden; tap Show answer to reveal. Self-contained.",
        "-quiz"
    ),
    PDF(
        "application/pdf",
        "pdf",
        "PDF (answers inline)",
        ".pdf with correct answers under each question."
    ),
    PDF_ANSWER_KEY(
        "application/pdf",
        "pdf",
        "PDF (answer key at end)",
        ".pdf for self-testing: questions first, all answers at the end.",
        "-answer-key"
    ),
    APKG(
        "application/octet-stream",
        "apkg",
        "Anki deck (.apkg)",
        "Importable with Anki. Each question becomes a flashcard in one deck."
    )
}
