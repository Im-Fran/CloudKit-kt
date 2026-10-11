package io.github.imfran.cloudkit.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.Base64

/**
 * A record field value, encoded on the wire as `{"value": …, "type": "…"}`.
 * Booleans are stored by CloudKit as [Int64] (0/1).
 */
@Serializable(with = FieldValueSerializer::class)
public sealed interface FieldValue {
    /** Wire type (`STRING`, `INT64`, `TIMESTAMP`, `STRING_LIST`, …) or null when unknown. */
    public val type: String?

    public data class StringValue(val value: String) : FieldValue { override val type: String get() = "STRING" }
    public data class Int64(val value: Long) : FieldValue { override val type: String get() = "INT64" }
    public data class DoubleValue(val value: Double) : FieldValue { override val type: String get() = "DOUBLE" }
    public data class Timestamp(val value: Instant) : FieldValue { override val type: String get() = "TIMESTAMP" }
    public class Bytes(public val value: ByteArray) : FieldValue {
        override val type: String get() = "BYTES"
        override fun equals(other: Any?): Boolean = other is Bytes && value.contentEquals(other.value)
        override fun hashCode(): Int = value.contentHashCode()
        override fun toString(): String = "Bytes(${value.size} bytes)"
    }
    public data class ReferenceValue(val value: Reference) : FieldValue { override val type: String get() = "REFERENCE" }
    public data class AssetValue(val value: Asset) : FieldValue { override val type: String get() = "ASSETID" }
    public data class LocationValue(val value: Location) : FieldValue { override val type: String get() = "LOCATION" }

    /** A list; [elementType] is needed only to type an empty list (e.g. `"STRING"`). */
    public data class ListValue(val values: List<FieldValue>, val elementType: String? = null) : FieldValue {
        override val type: String? get() = (elementType ?: values.firstOrNull()?.type)?.let { "${it}_LIST" }
    }

    /** Clears the field when saved. */
    public data object Null : FieldValue { override val type: String? get() = null }

    /** Anything this library does not model (e.g. `ENCRYPTED_BYTES`); round-trips untouched. */
    public data class Unknown(val raw: JsonElement, override val type: String?) : FieldValue

    public companion object {
        /** Converts plain Kotlin values (String, Int/Long, Double/Float, Boolean, Instant, ByteArray, Reference, Asset, Location, List, null). */
        public fun of(value: Any?): FieldValue = when (value) {
            null -> Null
            is FieldValue -> value
            is String -> StringValue(value)
            is Int -> Int64(value.toLong())
            is Long -> Int64(value)
            is Short -> Int64(value.toLong())
            is Byte -> Int64(value.toLong())
            is Boolean -> Int64(if (value) 1 else 0)
            is Double -> DoubleValue(value)
            is Float -> DoubleValue(value.toDouble())
            is Instant -> Timestamp(value)
            is java.util.Date -> Timestamp(value.toInstant())
            is ByteArray -> Bytes(value)
            is Reference -> ReferenceValue(value)
            is Asset -> AssetValue(value)
            is Location -> LocationValue(value)
            is Iterable<*> -> ListValue(value.map(::of))
            is Array<*> -> ListValue(value.map(::of))
            else -> throw IllegalArgumentException("Unsupported CloudKit field value: ${value::class}")
        }
    }
}

internal object FieldValueSerializer : KSerializer<FieldValue> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun serialize(encoder: Encoder, value: FieldValue) {
        val json = (encoder as JsonEncoder).json
        encoder.encodeJsonElement(buildJsonObject {
            put("value", rawValue(json, value))
            value.type?.let { put("type", it) }
        })
    }

    override fun deserialize(decoder: Decoder): FieldValue {
        val json = (decoder as JsonDecoder).json
        val obj = decoder.decodeJsonElement().jsonObject
        val type = (obj["type"] as? JsonPrimitive)?.content
        return parse(json, obj["value"] ?: JsonNull, type)
    }

    private fun rawValue(json: Json, v: FieldValue): JsonElement = when (v) {
        is FieldValue.StringValue -> JsonPrimitive(v.value)
        is FieldValue.Int64 -> JsonPrimitive(v.value)
        is FieldValue.DoubleValue -> JsonPrimitive(v.value)
        is FieldValue.Timestamp -> JsonPrimitive(v.value.toEpochMilli())
        is FieldValue.Bytes -> JsonPrimitive(Base64.getEncoder().encodeToString(v.value))
        is FieldValue.ReferenceValue -> json.encodeToJsonElement(v.value)
        is FieldValue.AssetValue -> json.encodeToJsonElement(v.value)
        is FieldValue.LocationValue -> json.encodeToJsonElement(v.value)
        is FieldValue.ListValue -> JsonArray(v.values.map { rawValue(json, it) })
        is FieldValue.Null -> JsonNull
        is FieldValue.Unknown -> v.raw
    }

    private fun parse(json: Json, raw: JsonElement, type: String?): FieldValue {
        if (raw is JsonNull) return FieldValue.Null
        if (type == null) return infer(json, raw)
        if (type.endsWith("_LIST")) {
            val element = type.removeSuffix("_LIST")
            val items = (raw as? JsonArray ?: return FieldValue.Unknown(raw, type)).map { parse(json, it, element) }
            return FieldValue.ListValue(items, element.takeIf { items.isEmpty() && it != "UNKNOWN" })
        }
        return when (type) {
            "STRING" -> FieldValue.StringValue(raw.jsonPrimitive.content)
            // numbersAsStrings=true sends numbers as JSON strings; content handles both.
            "INT64" -> FieldValue.Int64(raw.jsonPrimitive.content.toLong())
            "DOUBLE" -> FieldValue.DoubleValue(raw.jsonPrimitive.content.toDouble())
            "TIMESTAMP" -> FieldValue.Timestamp(Instant.ofEpochMilli(raw.jsonPrimitive.content.toDouble().toLong()))
            "BYTES" -> FieldValue.Bytes(Base64.getDecoder().decode(raw.jsonPrimitive.content))
            "REFERENCE" -> FieldValue.ReferenceValue(json.decodeFromJsonElement(raw))
            "ASSET", "ASSETID" -> FieldValue.AssetValue(json.decodeFromJsonElement(raw))
            "LOCATION" -> FieldValue.LocationValue(json.decodeFromJsonElement(raw))
            else -> FieldValue.Unknown(raw, type)
        }
    }

    /** Untyped values (allowed by the API): guess from the JSON shape. */
    private fun infer(json: Json, raw: JsonElement): FieldValue = when (raw) {
        JsonNull -> FieldValue.Null
        is JsonArray -> FieldValue.ListValue(raw.map { infer(json, it) })
        is JsonObject -> when {
            "recordName" in raw -> FieldValue.ReferenceValue(json.decodeFromJsonElement(raw))
            "latitude" in raw -> FieldValue.LocationValue(json.decodeFromJsonElement(raw))
            "fileChecksum" in raw || "downloadURL" in raw || "receipt" in raw -> FieldValue.AssetValue(json.decodeFromJsonElement(raw))
            else -> FieldValue.Unknown(raw, null)
        }
        is JsonPrimitive -> when {
            raw.isString -> FieldValue.StringValue(raw.content)
            raw.booleanOrNull != null -> FieldValue.Int64(if (raw.booleanOrNull == true) 1 else 0)
            raw.longOrNull != null -> FieldValue.Int64(raw.longOrNull!!)
            else -> FieldValue.DoubleValue(raw.content.toDouble())
        }
    }
}
