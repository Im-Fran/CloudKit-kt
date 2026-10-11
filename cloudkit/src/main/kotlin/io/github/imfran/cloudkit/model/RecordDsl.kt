package io.github.imfran.cloudkit.model

import java.time.Instant

/**
 * Builds a record:
 * ```
 * val note = record("Note") {
 *     this["title"] = "Hola"
 *     this["createdAt"] = Instant.now()
 * }
 * ```
 * Values go through [FieldValue.of].
 */
public fun record(recordType: String, recordName: String? = null, zoneID: ZoneID? = null, block: RecordBuilder.() -> Unit = {}): Record =
    Record(recordName = recordName, recordType = recordType, zoneID = zoneID, fields = RecordBuilder().apply(block).fields)

/** Copy of this record (keeping name, change tag and zone) with some fields changed, ready for `update`. */
public fun Record.edit(block: RecordBuilder.() -> Unit): Record = copy(fields = RecordBuilder(fields.toMutableMap()).apply(block).fields)

public class RecordBuilder internal constructor(internal val fields: MutableMap<String, FieldValue> = mutableMapOf()) {
    public operator fun set(name: String, value: Any?) { fields[name] = FieldValue.of(value) }
}

public operator fun Record.get(name: String): FieldValue? = fields[name]?.takeUnless { it is FieldValue.Null }

public fun Record.string(name: String): String? = (this[name] as? FieldValue.StringValue)?.value
public fun Record.long(name: String): Long? = (this[name] as? FieldValue.Int64)?.value
public fun Record.int(name: String): Int? = long(name)?.toInt()
public fun Record.double(name: String): Double? = when (val v = this[name]) {
    is FieldValue.DoubleValue -> v.value
    is FieldValue.Int64 -> v.value.toDouble()
    else -> null
}
public fun Record.boolean(name: String): Boolean? = long(name)?.let { it != 0L }
public fun Record.instant(name: String): Instant? = (this[name] as? FieldValue.Timestamp)?.value
public fun Record.bytes(name: String): ByteArray? = (this[name] as? FieldValue.Bytes)?.value
public fun Record.reference(name: String): Reference? = (this[name] as? FieldValue.ReferenceValue)?.value
public fun Record.asset(name: String): Asset? = (this[name] as? FieldValue.AssetValue)?.value
public fun Record.location(name: String): Location? = (this[name] as? FieldValue.LocationValue)?.value
public fun Record.list(name: String): List<FieldValue> = (this[name] as? FieldValue.ListValue)?.values.orEmpty()
public fun Record.stringList(name: String): List<String> = list(name).mapNotNull { (it as? FieldValue.StringValue)?.value }
public fun Record.referenceList(name: String): List<Reference> = list(name).mapNotNull { (it as? FieldValue.ReferenceValue)?.value }

/** Reference to this record, e.g. to set a parent/child relation. */
public fun Record.toReference(action: Reference.Action = Reference.Action.NONE): Reference =
    Reference(requireNotNull(recordName) { "Record has no recordName yet" }, zoneID, action)

/** Filter on one of your fields: `filter("title", Comparator.EQUALS, "Hola")`. */
public fun filter(fieldName: String, comparator: Comparator, value: Any?, distance: Double? = null): Filter =
    Filter(comparator, FieldValue.of(value), fieldName = fieldName, distance = distance)

/** Filter on a system field (`recordName`, `createdTimestamp`, `modifiedTimestamp`, `createdUserRecordName`, …). */
public fun systemFilter(systemFieldName: String, comparator: Comparator, value: Any?): Filter =
    Filter(comparator, FieldValue.of(value), systemFieldName = systemFieldName)
