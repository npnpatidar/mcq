package com.mcqapp

import com.mcqapp.data.anki.AnkiSchema11
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shape checks for the schema-11 JSON blobs in the `col` row.
 *
 * Anki's schema-11 deserializers (`deckconfig/schema11.rs`, `decks/schema11.rs`,
 * `notetype/schema11.rs`) mark only some fields as required; the rest fall back
 * to defaults. These tests pin the required ones, because a missing required
 * field is the one mistake that makes Anki reject an otherwise well-formed
 * `.apkg`. This is a plain JVM test (no SQLite) so it runs everywhere.
 */
class AnkiSchema11JsonTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val modelId = 1600000000000L
    private val now = 1700000000L

    private fun required(obj: kotlinx.serialization.json.JsonObject, keys: List<String>) {
        keys.forEach { key ->
            assertTrue("missing required key '$key' in $obj", key in obj)
        }
    }

    @Test
    fun modelBlobHasEveryFieldAnkiRequires() {
        val model = json.parseToJsonElement(AnkiSchema11.modelsJson(modelId, now))
            .jsonObject.values.first().jsonObject
        required(model, listOf("id", "name", "type", "mod", "usn", "sortf", "tmpls", "flds"))

        val template = model["tmpls"]!!.jsonArray.single().jsonObject
        required(template, listOf("name", "ord", "qfmt", "afmt"))

        val fields = model["flds"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, fields.size)
        fields.forEach { required(it, listOf("name", "ord", "sticky", "rtl", "font", "size")) }
        assertEquals("Front", fields[0]["name"]!!.jsonPrimitive.content)
        assertEquals("Back", fields[1]["name"]!!.jsonPrimitive.content)
        assertEquals("{{Front}}", template["qfmt"]!!.jsonPrimitive.content)
    }

    @Test
    fun deckBlobHasEveryFieldAnkiRequires() {
        val deck = json.parseToJsonElement(AnkiSchema11.decksJson(1L, "Paper", now))
            .jsonObject.values.first().jsonObject
        required(deck, listOf("id", "name", "usn", "collapsed", "dyn", "conf"))
        // dyn must be a number: serde's schema-11 deck reader only tolerates a
        // bool through a normalisation path, a number is always accepted.
        assertEquals(0, deck["dyn"]!!.jsonPrimitive.float.toInt())
        // today counters are [day, amount] pairs.
        assertEquals(2, deck["lrnToday"]!!.jsonArray.size)
    }

    @Test
    fun deckConfigBlobHasEveryFieldAnkiRequires() {
        val conf = json.parseToJsonElement(AnkiSchema11.dconfJson(1L, now))
            .jsonObject.values.first().jsonObject
        required(conf, listOf("id", "mod", "name", "usn", "maxTaken", "autoplay", "dyn"))
        // Sub-configs default when invalid, but the fields without a default
        // must be present and numeric.
        val new = conf["new"]!!.jsonObject
        required(new, listOf("initialFactor", "ints"))
        assertEquals(2, new["delays"]!!.jsonArray.size)
        val rev = conf["rev"]!!.jsonObject
        required(rev, listOf("ease4", "ivlFct", "maxIvl"))
        val lapse = conf["lapse"]!!.jsonObject
        required(lapse, listOf("leechFails", "minInt", "mult"))
    }

    @Test
    fun configBlobIsAnObjectAnkiCanParse() {
        // upgrade_config_to_schema14 deserialises col.conf as a map of string
        // to arbitrary value; a non-object here breaks every import.
        val conf = json.parseToJsonElement(AnkiSchema11.configJson(modelId, 2)).jsonObject
        assertEquals(2, conf["nextPos"]!!.jsonPrimitive.float.toInt())
        assertEquals(1, conf["curDeck"]!!.jsonPrimitive.float.toInt())
    }
}