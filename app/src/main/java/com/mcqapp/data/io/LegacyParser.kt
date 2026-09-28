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

object LegacyParser {

    private val letterIds = ('a'..'z').map { it.toString() }

    fun parse(json: String): McqFileDto {
        val root = Json.parseToJsonElement(json).jsonObject
        val version = root["version"]?.jsonPrimitive?.intOrNull ?: 1
        val papersJson = root["papers"] as? JsonArray ?: JsonArray(emptyList())
        val papers = papersJson.map { parsePaper(it.jsonObject) }
        return McqFileDto(version = version, papers = papers)
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
        val finalCategories = if (categories.isEmpty() && topLevelQuestions != null) {
            listOf(
                CategoryDto(
                    id = id + "-root",
                    title = title,
                    questions = topLevelQuestions.map { parseQuestion(it.jsonObject) }
                )
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
            questions = topLevelQuestions?.map { parseQuestion(it.jsonObject) } ?: emptyList()
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
            questions = questionsJson.map { parseQuestion(it.jsonObject) }
        )
    }

    private fun parseQuestion(obj: JsonObject): QuestionDto {
        val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: "q-" + System.currentTimeMillis().toString(36) + "-" + (0..99999).random()
        val text = obj["text"]?.jsonPrimitive?.contentOrNull
            ?: obj["question"]?.jsonPrimitive?.contentOrNull
            ?: ""
        val image = obj["image"]?.jsonPrimitive?.contentOrNull
            ?: obj["imageUrl"]?.jsonPrimitive?.contentOrNull
        val explanation = obj["explanation"]?.jsonPrimitive?.contentOrNull
            ?: obj["explain"]?.jsonPrimitive?.contentOrNull
            ?: obj["reason"]?.jsonPrimitive?.contentOrNull
            ?: ""
        val difficulty = obj["difficulty"]?.jsonPrimitive?.contentOrNull ?: "medium"
        val tags = when (val t = obj["tags"]) {
            is JsonArray -> t.mapNotNull { it.jsonPrimitive.contentOrNull }
            is JsonPrimitive -> t.contentOrNull?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
            else -> null
        } ?: emptyList()

        val options = parseOptions(obj)
        val correctIds = parseCorrectIds(obj, options)

        return QuestionDto(
            id = id,
            text = text,
            image = image,
            options = options,
            correctOptionIds = correctIds,
            explanation = explanation,
            difficulty = difficulty,
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
