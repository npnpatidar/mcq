package com.mcqapp.data.io

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

object LegacyParser {

    private val letterIds = ('a'..'z').map { it.toString() }

    fun parse(json: String): McqFileDto {
        val element = Json.parseToJsonElement(json)
        if (element is JsonArray) {
            val questions = uniqueIds(element.map { parseQuestion(it.jsonObject) })
            val paperId = "paper-" + System.currentTimeMillis().toString(36)
            val paper = PaperDto(
                id = paperId,
                title = "Imported Questions",
                categories = listOf(
                    CategoryDto(
                        id = "$paperId-uncat",
                        title = "Uncategorized",
                        questions = questions
                    )
                ),
                questions = questions
            )
            return McqFileDto(version = 1, papers = listOf(paper))
        }
        val root = element.jsonObject
        val version = root["version"]?.jsonPrimitive?.intOrNull ?: 1
        val papersJson = root["papers"] as? JsonArray ?: JsonArray(emptyList())
        val papers = papersJson.map { parsePaper(it.jsonObject) }
        val bookmarks = (root["bookmarks"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }?.filter { it.isNotBlank() }
            ?: emptyList()
        val attemptsJson = root["attempts"] as? JsonArray ?: JsonArray(emptyList())
        val attempts = attemptsJson.mapNotNull {
            try {
                parseAttempt(it.jsonObject)
            } catch (e: Exception) {
                null
            }
        }
        return McqFileDto(version = version, papers = papers, bookmarks = bookmarks, attempts = attempts)
    }

    private fun parseAttempt(obj: JsonObject): AttemptDto {
        fun str(key: String) = obj[key]?.jsonPrimitive?.contentOrNull ?: ""
        fun int(key: String) = obj[key]?.jsonPrimitive?.intOrNull ?: 0
        fun long(key: String) = obj[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        fun double(key: String) = obj[key]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
        val resultsJson = obj["results"] as? JsonArray ?: JsonArray(emptyList())
        return AttemptDto(
            paperId = str("paperId"),
            title = str("title").ifBlank { "Test" },
            totalQuestions = int("totalQuestions"),
            correctCount = int("correctCount"),
            wrongCount = int("wrongCount"),
            skippedCount = int("skippedCount"),
            score = double("score"),
            maxScore = double("maxScore"),
            durationSeconds = long("durationSeconds"),
            finishedAt = long("finishedAt"),
            results = resultsJson.map { it.jsonObject }.map { r ->
                AttemptResultDto(
                    questionId = r["questionId"]?.jsonPrimitive?.contentOrNull ?: "",
                    categoryTitle = r["categoryTitle"]?.jsonPrimitive?.contentOrNull ?: "",
                    text = r["text"]?.jsonPrimitive?.contentOrNull ?: "",
                    optionsJson = r["optionsJson"]?.jsonPrimitive?.contentOrNull ?: "[]",
                    correctOptionIds = r["correctOptionIds"]?.jsonPrimitive?.contentOrNull ?: "",
                    selectedOptionIds = r["selectedOptionIds"]?.jsonPrimitive?.contentOrNull ?: "",
                    isCorrect = r["isCorrect"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                    explanation = r["explanation"]?.jsonPrimitive?.contentOrNull ?: "",
                    explanationImage = r["explanationImage"]?.jsonPrimitive?.contentOrNull
                )
            }
        )
    }

    private fun parsePaper(obj: JsonObject): PaperDto {
        val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: "paper-" + System.currentTimeMillis().toString(36)
        val title = obj["title"]?.jsonPrimitive?.contentOrNull
            ?: obj["name"]?.jsonPrimitive?.contentOrNull
            ?: "Untitled Paper"
        val description = obj["description"]?.jsonPrimitive?.contentOrNull
            ?: obj["desc"]?.jsonPrimitive?.contentOrNull
            ?: ""
        val duration = obj["durationMinutes"]?.jsonPrimitive?.intOrNull
            ?: obj["duration"]?.jsonPrimitive?.intOrNull
            ?: 0
        val negative = obj["negativeMarking"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
            ?: obj["negative"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
            ?: 0.0
        val categoriesJson = obj["categories"] as? JsonArray
            ?: obj["sections"] as? JsonArray
            ?: obj["subjects"] as? JsonArray
            ?: JsonArray(emptyList())
        val categories = categoriesJson.map { parseCategory(it.jsonObject) }
        val topLevelQuestions = obj["questions"] as? JsonArray
        val parsedTopLevel = topLevelQuestions?.let { uniqueIds(it.map { q -> parseQuestion(q.jsonObject) }) }
            ?: emptyList()
        // Top-level questions land in their own category instead of being
        // silently dropped when the paper also defines categories.
        val finalCategories = if (categories.isEmpty() && topLevelQuestions != null) {
            listOf(
                CategoryDto(
                    id = id + "-root",
                    title = title,
                    questions = parsedTopLevel
                )
            )
        } else if (topLevelQuestions != null && parsedTopLevel.isNotEmpty()) {
            categories + CategoryDto(
                id = id + "-root",
                title = "Uncategorized",
                questions = parsedTopLevel
            )
        } else {
            categories
        }
        return PaperDto(
            id = id,
            title = title,
            description = description,
            durationMinutes = duration,
            negativeMarking = negative,
            categories = finalCategories,
            questions = parsedTopLevel
        )
    }

    private fun parseCategory(obj: JsonObject): CategoryDto {
        val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: "cat-" + System.currentTimeMillis().toString(36) + "-" + (0..9999).random()
        val title = obj["title"]?.jsonPrimitive?.contentOrNull
            ?: obj["name"]?.jsonPrimitive?.contentOrNull
            ?: "Category"
        val parentId = obj["parentId"]?.jsonPrimitive?.contentOrNull
        val questionsJson = obj["questions"] as? JsonArray ?: JsonArray(emptyList())
        return CategoryDto(
            id = id,
            title = title,
            parentId = parentId,
            questions = uniqueIds(questionsJson.map { parseQuestion(it.jsonObject) })
        )
    }

    /**
     * IDs generated for questions without one are deterministic (content hash),
     * so re-parsing the same JSON yields the same IDs. Random IDs broke the
     * import-screen join between the parsed file and the edited preview state:
     * any divergence silently dropped questions. Suffixes keep duplicates unique.
     */
    private fun uniqueIds(questions: List<QuestionDto>): List<QuestionDto> {
        val seen = HashSet<String>()
        return questions.map { q ->
            var id = q.id
            var n = 2
            while (!seen.add(id)) {
                id = "${q.id}-$n"
                n++
            }
            if (id != q.id) q.copy(id = id) else q
        }
    }

    private fun stableQuestionId(text: String, options: List<OptionDto>): String {
        val raw = text + "|" + options.joinToString(",") { it.id + "=" + it.text }
        val bytes = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return "q-" + bytes.joinToString("") { "%02x".format(it) }.take(12)
    }

    private fun parseQuestion(obj: JsonObject): QuestionDto {
        val text = obj["text"]?.jsonPrimitive?.contentOrNull
            ?: obj["question"]?.jsonPrimitive?.contentOrNull
            ?: ""
        val image = obj["image"]?.jsonPrimitive?.contentOrNull
            ?: obj["imageUrl"]?.jsonPrimitive?.contentOrNull
        val explanation = obj["explanation"]?.jsonPrimitive?.contentOrNull
            ?: obj["explain"]?.jsonPrimitive?.contentOrNull
            ?: obj["reason"]?.jsonPrimitive?.contentOrNull
            ?: ""
        val explanationImage = obj["explanationImage"]?.jsonPrimitive?.contentOrNull
            ?: obj["explanation_image"]?.jsonPrimitive?.contentOrNull
            ?: obj["explainImage"]?.jsonPrimitive?.contentOrNull
            ?: obj["explanationImageUrl"]?.jsonPrimitive?.contentOrNull
        val difficulty = obj["difficulty"]?.jsonPrimitive?.contentOrNull ?: "medium"
        // Weight of a correct answer; absent/invalid/negative values fall back to 1.
        val marks = (obj["marks"] ?: obj["points"] ?: obj["weight"])
            ?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it >= 0.0 } ?: 1.0
        val tags = when (val t = obj["tags"]) {
            is JsonArray -> t.mapNotNull { it.jsonPrimitive.contentOrNull }
            is JsonPrimitive -> t.contentOrNull?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
            else -> null
        } ?: emptyList()

        val options = parseOptions(obj)
        val correctIds = parseCorrectIds(obj, options)
        val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: stableQuestionId(text, options)

        return QuestionDto(
            id = id,
            text = text,
            image = image,
            options = options,
            correctOptionIds = correctIds,
            explanation = explanation,
            explanationImage = explanationImage,
            difficulty = difficulty,
            marks = marks,
            tags = tags
        )
    }

    private fun parseOptions(obj: JsonObject): List<OptionDto> {
        val raw = obj["options"] ?: return emptyList()
        return when (raw) {
            is JsonArray -> raw.mapIndexedNotNull { index, el ->
                when (el) {
                    is JsonPrimitive -> OptionDto(
                        id = letterIds.getOrElse(index) { index.toString() },
                        text = el.content
                    )
                    is JsonObject -> {
                        val text = el["text"]?.jsonPrimitive?.contentOrNull
                            ?: el["value"]?.jsonPrimitive?.contentOrNull
                            ?: return@mapIndexedNotNull null
                        val oid = el["id"]?.jsonPrimitive?.contentOrNull
                            ?: letterIds.getOrElse(index) { index.toString() }
                        OptionDto(
                            id = oid,
                            text = text,
                            image = el["image"]?.jsonPrimitive?.contentOrNull
                        )
                    }
                    else -> null
                }
            }
            else -> emptyList()
        }
    }

    private fun parseCorrectIds(obj: JsonObject, options: List<OptionDto>): List<String> {
        obj["correctOptionIds"]?.let { v ->
            val ids = when (v) {
                is JsonArray -> v.mapNotNull { it.jsonPrimitive.contentOrNull }
                is JsonPrimitive -> v.contentOrNull?.split(",")?.map { it.trim() }
                else -> null
            }
            if (!ids.isNullOrEmpty()) return ids
        }
        obj["correctIndex"]?.jsonPrimitive?.intOrNull?.let { idx ->
            return listOfNotNull(options.getOrNull(idx)?.id)
        }
        obj["answer"]?.jsonPrimitive?.contentOrNull?.let { ans ->
            return resolveAnswer(ans, options)
        }
        obj["correct"]?.jsonPrimitive?.contentOrNull?.let { ans ->
            return resolveAnswer(ans, options)
        }
        obj["correctAnswer"]?.jsonPrimitive?.contentOrNull?.let { ans ->
            return resolveAnswer(ans, options)
        }
        return emptyList()
    }

    private fun resolveAnswer(answer: String, options: List<OptionDto>): List<String> {
        val byId = options.firstOrNull { it.id.equals(answer, ignoreCase = true) }
        if (byId != null) return listOf(byId.id)
        val byText = options.filter { it.text.equals(answer.trim(), ignoreCase = true) }
        if (byText.isNotEmpty()) return byText.map { it.id }
        val idx = answer.trim().toIntOrNull()
        if (idx != null) return listOfNotNull(options.getOrNull(idx)?.id)
        return emptyList()
    }
}
