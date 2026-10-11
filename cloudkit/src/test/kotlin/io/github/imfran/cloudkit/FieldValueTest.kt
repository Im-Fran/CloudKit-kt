package io.github.imfran.cloudkit

import io.github.imfran.cloudkit.internal.CloudKitJson
import io.github.imfran.cloudkit.model.Asset
import io.github.imfran.cloudkit.model.FieldValue
import io.github.imfran.cloudkit.model.Location
import io.github.imfran.cloudkit.model.Record
import io.github.imfran.cloudkit.model.Reference
import io.github.imfran.cloudkit.model.boolean
import io.github.imfran.cloudkit.model.edit
import io.github.imfran.cloudkit.model.instant
import io.github.imfran.cloudkit.model.long
import io.github.imfran.cloudkit.model.record
import io.github.imfran.cloudkit.model.string
import io.github.imfran.cloudkit.model.stringList
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FieldValueTest {
    private val values = listOf(
        FieldValue.StringValue("hola"),
        FieldValue.Int64(42),
        FieldValue.DoubleValue(1.5),
        FieldValue.Timestamp(Instant.ofEpochMilli(1_700_000_000_123)),
        FieldValue.Bytes(byteArrayOf(1, 2, 3)),
        FieldValue.ReferenceValue(Reference("parent", action = Reference.Action.DELETE_SELF)),
        FieldValue.AssetValue(Asset(fileChecksum = "abc", size = 10, receipt = "r")),
        FieldValue.LocationValue(Location(-33.45, -70.66)),
        FieldValue.ListValue(listOf(FieldValue.StringValue("a"), FieldValue.StringValue("b"))),
        FieldValue.ListValue(emptyList(), elementType = "INT64"),
        FieldValue.Null,
    )

    @Test
    fun `every type round-trips`() {
        for (v in values) {
            val encoded = CloudKitJson.encodeToString(FieldValue.serializer(), v)
            assertEquals(v, CloudKitJson.decodeFromString(FieldValue.serializer(), encoded), encoded)
        }
    }

    @Test
    fun `wire format matches CloudKit`() {
        assertEquals(
            """{"value":1700000000123,"type":"TIMESTAMP"}""",
            CloudKitJson.encodeToString(FieldValue.serializer(), FieldValue.Timestamp(Instant.ofEpochMilli(1_700_000_000_123))),
        )
        assertEquals(
            """{"value":["a"],"type":"STRING_LIST"}""",
            CloudKitJson.encodeToString(FieldValue.serializer(), FieldValue.of(listOf("a"))),
        )
        assertEquals("""{"value":null}""", CloudKitJson.encodeToString(FieldValue.serializer(), FieldValue.Null))
    }

    @Test
    fun `parses server responses including untyped and numbersAsStrings`() {
        val record = CloudKitJson.decodeFromString(
            Record.serializer(),
            """
            {"recordName":"n1","recordType":"Note","recordChangeTag":"x","fields":{
              "title":{"value":"Hola","type":"STRING"},
              "count":{"value":"7","type":"INT64"},
              "done":{"value":1},
              "ratio":{"value":0.5},
              "tags":{"value":["a","b"],"type":"STRING_LIST"},
              "ref":{"value":{"recordName":"p","action":"NONE"}},
              "img":{"value":{"fileChecksum":"c","size":3,"downloadURL":"https://x/${'$'}{f}"},"type":"ASSETID"},
              "secret":{"value":"zzz","type":"ENCRYPTED_BYTES"},
              "when":{"value":1700000000000,"type":"TIMESTAMP"}
            },"created":{"timestamp":1,"userRecordName":"_u"},"unknownKey":true}
            """.trimIndent(),
        )
        assertEquals("Hola", record.string("title"))
        assertEquals(7, record.long("count"))
        assertEquals(true, record.boolean("done"))
        assertEquals(listOf("a", "b"), record.stringList("tags"))
        assertIs<FieldValue.ReferenceValue>(record.fields["ref"])
        assertIs<FieldValue.AssetValue>(record.fields["img"])
        assertEquals(FieldValue.Unknown(kotlinx.serialization.json.JsonPrimitive("zzz"), "ENCRYPTED_BYTES"), record.fields["secret"])
        assertEquals(Instant.ofEpochMilli(1_700_000_000_000), record.instant("when"))
    }

    @Test
    fun `dsl builds and edits records`() {
        val note = record("Note", recordName = "n1") {
            this["title"] = "Hola"
            this["done"] = false
        }
        val edited = note.copy(recordChangeTag = "t").edit { this["done"] = true }
        assertEquals("Hola", edited.string("title"))
        assertEquals(true, edited.boolean("done"))
        assertEquals("t", edited.recordChangeTag)
    }
}
