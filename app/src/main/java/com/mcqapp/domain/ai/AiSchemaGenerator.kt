package com.mcqapp.domain.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Builds a small JSON Schema (draft 2020-12), a short prompt and a tiny example
 * for one combination of [SchemaOptions]. No template engine, no dependency
 * beyond the serialization library the app already ships.
 *
 * Every key declared here is one `LegacyParser` reads. The parser's question
 * body key is `question_elements` (not `elements` — that is the passage key),
 * its back key is `explanation_elements`, and a text-only passage is the
 * `{"id","text"}` fallback. The schema is deliberately closed
 * (`additionalProperties: false` at every object level) so a model cannot
 * invent a key the importer would ignore.
 */
object AiSchemaGenerator {

    /** The three artifacts shown on the generator screen. */
    data class Generated(
        val schema: String,
        val prompt: String,
        val example: String,
        /** Schema + prompt only: an approximate size, not a token count. */
        val characterCount: Int
    )

    private val letters = ('a'..'j').map { it.toString() }.toTypedArray()

    private fun obj(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(pairs.toMap())
    private fun s(value: String): JsonPrimitive = JsonPrimitive(value)
    private fun n(value: Int): JsonPrimitive = JsonPrimitive(value)
    private fun no(): JsonPrimitive = JsonPrimitive(false)
    private fun arr(vararg values: JsonElement): JsonArray = JsonArray(values.toList())
    private fun strings(vararg values: String): JsonArray = JsonArray(values.map(::JsonPrimitive))
    private fun stringList(values: List<String>): JsonArray = JsonArray(values.map(::JsonPrimitive))
    private fun blockName(block: ContentBlock): String =
        block.name.lowercase(java.util.Locale.ROOT)

    // --- schema ---

    private fun blockSchema(block: ContentBlock): JsonObject {
        val content = when (block) {
            ContentBlock.TEXT, ContentBlock.MATH, ContentBlock.IMAGE -> obj("type" to s("string"))
            // A table is rows of cells, each a string; this is exactly the shape
            // parseContentElement turns into ContentElement.TableElement.
            ContentBlock.TABLE -> obj(
                "type" to s("array"),
                "items" to obj(
                    "type" to s("array"),
                    "items" to obj("type" to s("string"))
                )
            )
        }
        return obj(
            "type" to s("object"),
            "properties" to obj(
                "type" to obj("const" to s(blockName(block))),
                "content" to content
            ),
            "required" to strings("type", "content"),
            "additionalProperties" to no()
        )
    }

    private fun elementsSchema(blocks: List<ContentBlock>): JsonObject = obj(
        "type" to s("array"),
        "minItems" to n(1),
        "items" to obj("oneOf" to arr(*blocks.map(::blockSchema).toTypedArray()))
    )

    private fun optionSchema(images: Boolean): JsonObject {
        val props = LinkedHashMap<String, JsonElement>()
        props["id"] = obj("type" to s("string"), "enum" to strings(*letters))
        props["text"] = obj("type" to s("string"))
        if (images) props["image"] = obj("type" to s("string"))
        return obj(
            "type" to s("object"),
            "properties" to JsonObject(props),
            "required" to strings("id", "text"),
            "additionalProperties" to no()
        )
    }

    private fun questionSchema(options: SchemaOptions): JsonObject {
        val props = LinkedHashMap<String, JsonElement>()
        props["id"] = obj("type" to s("string"))
        props["question_elements"] = elementsSchema(options.blocks)
        if (options.kind != QuestionKind.MCQ) {
            props["explanation_elements"] = elementsSchema(options.blocks)
        }
        if (options.image) {
            props["image"] = obj("type" to s("string"))
            if (options.kind != QuestionKind.MCQ) {
                props["explanationImage"] = obj("type" to s("string"))
            }
        }
        if (options.kind != QuestionKind.FLASHCARDS) {
            props["options"] = obj(
                "type" to s("array"),
                "minItems" to n(2),
                "maxItems" to n(10),
                "items" to optionSchema(options.image)
            )
            props["correctOptionIds"] = obj(
                "type" to s("array"),
                "minItems" to n(1),
                "items" to obj("type" to s("string"), "enum" to strings(*letters))
            )
        }
        if (options.effectivePassage != PassageMode.NONE) {
            props["passageId"] = obj("type" to s("string"))
        }
        val required = buildList {
            add("id")
            add("question_elements")
            if (options.kind == QuestionKind.FLASHCARDS) add("explanation_elements")
            if (options.kind == QuestionKind.MCQ) {
                add("options")
                add("correctOptionIds")
            }
        }
        return obj(
            "type" to s("object"),
            "properties" to JsonObject(props),
            "required" to stringList(required),
            "additionalProperties" to no()
        )
    }

    private fun passageSchema(options: SchemaOptions): JsonObject = when (options.effectivePassage) {
        PassageMode.NONE -> obj("type" to s("object"), "additionalProperties" to no())
        PassageMode.TEXT_ONLY -> obj(
            "type" to s("object"),
            "properties" to obj(
                "id" to obj("type" to s("string")),
                "text" to obj("type" to s("string"))
            ),
            "required" to strings("id", "text"),
            "additionalProperties" to no()
        )
        PassageMode.RICH -> {
            val props = LinkedHashMap<String, JsonElement>()
            props["id"] = obj("type" to s("string"))
            props["title"] = obj("type" to s("string"))
            props["elements"] = elementsSchema(options.blocks)
            if (options.image) props["image"] = obj("type" to s("string"))
            obj(
                "type" to s("object"),
                "properties" to JsonObject(props),
                "required" to strings("id", "elements"),
                "additionalProperties" to no()
            )
        }
    }

    /** A `categories[]` entry at the root: id, optional title, its questions. */
    private fun rootCategorySchema(options: SchemaOptions): JsonObject = obj(
        "type" to s("object"),
        "properties" to obj(
            "id" to obj("type" to s("string")),
            "title" to obj("type" to s("string")),
            "questions" to obj(
                "type" to s("array"),
                "minItems" to n(1),
                "items" to questionSchema(options)
            )
        ),
        "required" to strings("id", "questions"),
        "additionalProperties" to no()
    )

    /**
     * A paper may hold `categories`, `questions`, or both. Neither is required:
     * the parser reads either shape and synthesises a category for direct
     * questions, so forcing one here would reject files the importer accepts.
     */
    private fun paperSchema(options: SchemaOptions): JsonObject {
        val props = LinkedHashMap<String, JsonElement>()
        props["id"] = obj("type" to s("string"))
        props["title"] = obj("type" to s("string"))
        // The toggle is load-bearing: with categories off the schema must not
        // declare them at all, so a model cannot emit a key the example avoids.
        if (options.hasCategories) {
            props["categories"] = obj(
                "type" to s("array"),
                "minItems" to n(1),
                "items" to obj(
                    "type" to s("object"),
                    "properties" to obj(
                        "id" to obj("type" to s("string")),
                        "title" to obj("type" to s("string")),
                        "questions" to obj(
                            "type" to s("array"),
                            "minItems" to n(1),
                            "items" to questionSchema(options)
                        )
                    ),
                    "required" to strings("id", "questions"),
                    "additionalProperties" to no()
                )
            )
        }
        props["questions"] = obj(
            "type" to s("array"),
            "minItems" to n(1),
            "items" to questionSchema(options)
        )
        return obj(
            "type" to s("object"),
            "properties" to JsonObject(props),
            "required" to strings("id"),
            "additionalProperties" to no()
        )
    }

    /**
     * Only the selected root keys are declared and required: papers, else root
     * categories, else root questions, plus passages when enabled. No `anyOf`
     * or `minProperties` — the importer accepts any of these shapes on its own,
     * and a wrapper that rejects a subset would be a false constraint.
     */
    private fun schemaJson(options: SchemaOptions): JsonObject {
        val props = LinkedHashMap<String, JsonElement>()
        when {
            options.hasPapers -> props["papers"] = obj(
                "type" to s("array"),
                "minItems" to n(1),
                "items" to paperSchema(options)
            )
            options.hasCategories -> props["categories"] = obj(
                "type" to s("array"),
                "minItems" to n(1),
                "items" to rootCategorySchema(options)
            )
            else -> props["questions"] = obj(
                "type" to s("array"),
                "minItems" to n(1),
                "items" to questionSchema(options)
            )
        }
        if (options.effectivePassage != PassageMode.NONE) {
            props["passages"] = obj(
                "type" to s("array"),
                "minItems" to n(1),
                "items" to passageSchema(options)
            )
        }
        return obj(
            "\$schema" to s("https://json-schema.org/draft/2020-12/schema"),
            "title" to s("AI question bank"),
            "type" to s("object"),
            "properties" to JsonObject(props),
            "required" to stringList(props.keys.toList()),
            "additionalProperties" to no()
        )
    }

    // --- example ---

    private fun elementsExample(blocks: List<ContentBlock>, text: String): JsonArray = JsonArray(
        blocks.map { block ->
            obj(
                "type" to s(blockName(block)),
                "content" to when (block) {
                    ContentBlock.TEXT -> s(text)
                    ContentBlock.TABLE -> arr(
                        arr(s("Header A"), s("Header B")),
                        arr(s("row 1"), s("row 2"))
                    )
                    ContentBlock.MATH ->
                        s("<math><mi>E</mi><mo>=</mo><mi>m</mi><msup><mi>c</mi><mn>2</mn></msup></math>")
                    // Element blocks carry an <img> tag with a base64 data URI:
                    // parseContentElement extracts its src. Scalar image fields
                    // are bare data URIs too — never web URLs.
                    ContentBlock.IMAGE -> s(imageBlockContent())
                }
            )
        }
    )

    private fun questionExample(
        options: SchemaOptions,
        id: String,
        text: String,
        keyed: Boolean = false,
        card: Boolean = false,
        inlineImage: Boolean = false
    ): JsonObject {
        val q = LinkedHashMap<String, JsonElement>()
        q["id"] = s(id)
        q["question_elements"] = elementsExample(
            options.blocks,
            if (inlineImage && options.image) firstQuestionText(text) else text
        )
        if (options.effectivePassage != PassageMode.NONE) q["passageId"] = s("p1")
        if (options.kind == QuestionKind.FLASHCARDS || card) {
            q["explanation_elements"] = elementsExample(options.blocks, "This is the back of the card.")
            if (options.image) q["explanationImage"] = s(TINY_PNG_DATA_URI)
        } else {
            q["options"] = JsonArray((0 until 4).map { i ->
                if (options.image) {
                    obj(
                        "id" to s(letters[i]),
                        "text" to s("Option ${letters[i]}"),
                        "image" to s(TINY_PNG_DATA_URI)
                    )
                } else {
                    obj("id" to s(letters[i]), "text" to s("Option ${letters[i]}"))
                }
            })
            // A keyless MCQ deliberately has options but no correctOptionIds:
            // the app self-grades it. Only MCQ and the keyed Mixed example
            // carry the answer key.
            if (options.kind == QuestionKind.MCQ || keyed) {
                q["correctOptionIds"] = strings("b")
            }
        }
        // A scalar image is read verbatim by LegacyParser. Inline means a
        // base64 data URI here too — no web URLs anywhere in an image example.
        if (options.image) q["image"] = s(TINY_PNG_DATA_URI)
        return JsonObject(q)
    }

    private fun passageExample(options: SchemaOptions): JsonObject? = when (options.effectivePassage) {
        PassageMode.NONE -> null
        PassageMode.TEXT_ONLY -> obj(
            "id" to s("p1"),
            "text" to s("A short passage that the questions refer to.")
        )
        PassageMode.RICH -> {
            val p = LinkedHashMap<String, JsonElement>()
            p["id"] = s("p1")
            p["title"] = s("Reading passage")
            p["elements"] = elementsExample(options.blocks, "A short passage that the questions refer to.")
            if (options.image) p["image"] = s(TINY_PNG_DATA_URI)
            JsonObject(p)
        }
    }

    private fun categoryExample(id: String, title: String, questions: List<JsonObject>): JsonObject = obj(
        "id" to s(id),
        "title" to s(title),
        "questions" to JsonArray(questions)
    )

    private fun exampleJson(options: SchemaOptions): JsonObject {
        val questions = when (options.kind) {
            QuestionKind.FLASHCARDS -> listOf(
                questionExample(options, "q1", "Define the term.", card = true, inlineImage = true)
            )
            QuestionKind.MCQ -> listOf(
                questionExample(options, "q1", "Which option is correct?", inlineImage = true)
            )
            QuestionKind.MIXED -> listOf(
                questionExample(options, "q1", "Which option is correct?", keyed = true, inlineImage = true),
                questionExample(options, "q2", "Which option do you think is correct?"),
                questionExample(options, "q3", "Define the term.", card = true)
            )
        }
        val root = LinkedHashMap<String, JsonElement>()
        when {
            options.hasPapers -> {
                val paper = LinkedHashMap<String, JsonElement>()
                paper["id"] = s("paper-1")
                paper["title"] = s("Sample paper")
                if (options.hasCategories) {
                    paper["categories"] = arr(categoryExample("cat-1", "General", questions))
                } else {
                    paper["questions"] = JsonArray(questions)
                }
                root["papers"] = arr(JsonObject(paper))
            }
            options.hasCategories -> {
                root["categories"] = arr(categoryExample("cat-1", "General", questions))
            }
            else -> root["questions"] = JsonArray(questions)
        }
        if (options.effectivePassage != PassageMode.NONE) {
            passageExample(options)?.let { root["passages"] = arr(it) }
        }
        return JsonObject(root)
    }

    // --- prompt ---

    private fun promptFor(options: SchemaOptions): String {
        val kindText = when (options.kind) {
            QuestionKind.FLASHCARDS -> "flashcards (no options; the back text goes in explanation_elements)"
            QuestionKind.MCQ -> "multiple-choice questions (options with ids a-j and correctOptionIds)"
            QuestionKind.MIXED -> "keyed MCQs, keyless MCQs and flashcards"
        }
        val blockNames = buildList {
            add("text")
            if (options.table) add("table")
            if (options.math) add("math (MathML)")
            if (options.image) add("image")
        }.joinToString(", ")
        val passageText = when (options.effectivePassage) {
            PassageMode.NONE -> "no passages"
            PassageMode.TEXT_ONLY -> "one text-only passage with id and text"
            PassageMode.RICH -> "one rich passage with typed elements"
        }
        val imageText = if (options.image) {
            "image blocks and image fields are allowed; an inline image in a text block must be " +
                "a base64 data URI such as <img src=\"data:image/png;base64,...\">, not a web URL"
        } else {
            "use no image fields anywhere"
        }
        val wrapperText = when {
            options.hasPapers && options.hasCategories -> "group questions into papers and categories"
            options.hasPapers -> "group questions directly into papers (no categories)"
            options.hasCategories -> "group questions into root categories"
            else -> "use no papers/categories; the root holds passages (when any) and questions"
        }
        val sentence = "Make $kindText with $blockNames blocks, $passageText; $imageText; $wrapperText."
        return "Output only JSON matching the schema. No markdown or commentary. $sentence"
    }

    /**
     * Renders all three artifacts for one screen selection. Compact JSON keeps
     * the copy/paste payload small; the character count is a size hint only.
     */
    fun generate(options: SchemaOptions): Generated {
        val schema = schemaJson(options).toString()
        val prompt = promptFor(options)
        return Generated(
            schema = schema,
            prompt = prompt,
            example = exampleJson(options).toString(),
            characterCount = schema.length + prompt.length
        )
    }

    /**
     * A valid 1×1 PNG, base64-encoded. Every image value in an image-on
     * example uses this one payload: inline means a data URI, never a URL,
     * and a format sample must stay tiny.
     */
    private const val TINY_PNG_DATA_URI =
        "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR4nGNgAAIAAAUAAXpeqz8AAAAASUVORK5CYII="

    /**
     * The first question's text block embeds one inline image so the example
     * demonstrates that prose may carry a base64 `<img>` alongside the other
     * blocks. Only used when images are on, so an image-off example stays free
     * of any image markup.
     */
    private fun firstQuestionText(text: String): String =
        "$text <img src=\"$TINY_PNG_DATA_URI\" alt=\"inline figure\"> (see figure)"

    /** Element blocks carry an HTML tag; the parser extracts its src. */
    private fun imageBlockContent(): String = "<img src=\"$TINY_PNG_DATA_URI\" alt=\"diagram\">"
}
