package com.mcqapp.data.export

/** Export formats offered for a single paper. */
enum class ExportFormat(
    val mimeType: String,
    val extension: String,
    val title: String,
    val description: String
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
        "Web page (self-contained)",
        "Single .html file with images embedded. Opens in any browser."
    ),
    PDF(
        "application/pdf",
        "pdf",
        "PDF document",
        ".pdf with questions, options, correct answers and images."
    )
}
