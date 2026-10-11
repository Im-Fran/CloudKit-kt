package io.github.imfran.cloudkit

import io.github.imfran.cloudkit.internal.CloudKitHttp
import io.github.imfran.cloudkit.internal.CloudKitJson
import io.github.imfran.cloudkit.model.Asset
import io.github.imfran.cloudkit.model.Permission
import io.github.imfran.cloudkit.model.Query
import io.github.imfran.cloudkit.model.Record
import io.github.imfran.cloudkit.model.RecordRef
import io.github.imfran.cloudkit.model.ShareParticipant
import io.github.imfran.cloudkit.model.Subscription
import io.github.imfran.cloudkit.model.Zone
import io.github.imfran.cloudkit.model.ZoneID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Per-item results: CloudKit reports failures item by item instead of failing the request. */
public data class Results<T>(val results: List<T>, val errors: List<CloudKitError>) {
    public val hasErrors: Boolean get() = errors.isNotEmpty()

    /** Returns [results], or throws the first item error. */
    public fun getOrThrow(): List<T> = if (hasErrors) throw CloudKitException(errors.first(), 200) else results
}

public data class QueryResults(
    val records: List<Record>,
    val errors: List<CloudKitError>,
    /** Pass to [QueryOptions.continuationMarker] for the next page; null when there are no more results. */
    val continuationMarker: String?,
) {
    val moreComing: Boolean get() = continuationMarker != null
}

public data class DatabaseChanges(
    val zones: List<Zone>,
    val errors: List<CloudKitError>,
    val syncToken: String?,
    val moreComing: Boolean,
)

@Serializable
public data class ZoneChanges(
    val zoneID: ZoneID,
    val records: List<Record> = emptyList(),
    val syncToken: String? = null,
    val moreComing: Boolean = false,
)

/** One zone to fetch changes for; options here override the call-level ones. */
@Serializable
public data class ZoneChangesRequest(
    val zoneID: ZoneID,
    val syncToken: String? = null,
    val resultsLimit: Int? = null,
    val desiredKeys: List<String>? = null,
    val desiredRecordTypes: List<String>? = null,
    val reverse: Boolean? = null,
)

public data class ModifyOptions(
    /** Zone for all operations; defaults to the first record's zone, then the default zone. */
    val zoneID: ZoneID? = null,
    /** All-or-nothing (custom zones only). Server default: true. */
    val atomic: Boolean? = null,
    val desiredKeys: List<String>? = null,
    val numbersAsStrings: Boolean? = null,
)

public data class FetchOptions(
    val zoneID: ZoneID? = null,
    val desiredKeys: List<String>? = null,
    val numbersAsStrings: Boolean? = null,
)

public data class QueryOptions(
    val zoneID: ZoneID? = null,
    val resultsLimit: Int? = null,
    val desiredKeys: List<String>? = null,
    /** Search all zones (ignored when [zoneID] is set). */
    val zoneWide: Boolean? = null,
    val continuationMarker: String? = null,
    val numbersAsStrings: Boolean? = null,
)

public data class UploadedAsset(val recordName: String, val fieldName: String, val asset: Asset)

/** Mirrors `CloudKit.Database` from CloudKit JS; every call is a suspend function hitting CloudKit Web Services. */
public class Database internal constructor(
    public val scope: DatabaseScope,
    private val http: CloudKitHttp,
) {
    // ---- Records -------------------------------------------------------------------------------

    /** Creates records without a change tag, updates the rest (`forceUpdate` when [force]). */
    public suspend fun saveRecords(records: List<Record>, options: ModifyOptions = ModifyOptions(), force: Boolean = false): Results<Record> =
        newRecordsBatch().apply {
            records.forEach { r ->
                when {
                    force -> forceUpdate(r)
                    r.recordChangeTag == null -> create(r)
                    else -> update(r)
                }
            }
        }.commit(options)

    public suspend fun saveRecord(record: Record, options: ModifyOptions = ModifyOptions(), force: Boolean = false): Record =
        saveRecords(listOf(record), options, force).getOrThrow().single()

    /** Deletes records; without [force] the change tag must match. */
    public suspend fun deleteRecords(records: List<Record>, options: ModifyOptions = ModifyOptions(), force: Boolean = false): Results<Record> =
        newRecordsBatch().apply { records.forEach { if (force) forceDelete(it) else delete(it) } }.commit(options)

    public suspend fun deleteRecordsByName(recordNames: List<String>, options: ModifyOptions = ModifyOptions()): Results<Record> =
        deleteRecords(recordNames.map { Record(recordName = it) }, options, force = true)

    /** Batch with explicit operation types, like `database.newRecordsBatch()` in CloudKit JS. */
    public fun newRecordsBatch(): RecordsBatch = RecordsBatch(this)

    public suspend fun fetchRecords(recordNames: List<String>, options: FetchOptions = FetchOptions()): Results<Record> {
        val body = buildJsonObject {
            put("records", buildJsonArray { recordNames.forEach { add(buildJsonObject { put("recordName", it) }) } })
            putOpt("zoneID", options.zoneID)
            putOpt("desiredKeys", options.desiredKeys)
            options.numbersAsStrings?.let { put("numbersAsStrings", it) }
        }
        return split(http.post(scope, "records/lookup", body), "records", Record.serializer())
    }

    public suspend fun fetchRecord(recordName: String, options: FetchOptions = FetchOptions()): Record =
        fetchRecords(listOf(recordName), options).getOrThrow().single()

    public suspend fun performQuery(query: Query, options: QueryOptions = QueryOptions()): QueryResults {
        val body = buildJsonObject {
            putOpt("query", query)
            putOpt("zoneID", options.zoneID)
            options.resultsLimit?.let { put("resultsLimit", it) }
            putOpt("desiredKeys", options.desiredKeys)
            options.zoneWide?.let { put("zoneWide", it) }
            options.continuationMarker?.let { put("continuationMarker", it) }
            options.numbersAsStrings?.let { put("numbersAsStrings", it) }
        }
        val json = http.post(scope, "records/query", body)
        val (records, errors) = split(json, "records", Record.serializer())
        return QueryResults(records, errors, json.string("continuationMarker"))
    }

    /** Every record matching [query], following continuation markers. Item errors throw. */
    public fun queryAll(query: Query, options: QueryOptions = QueryOptions()): Flow<Record> = flow {
        var marker = options.continuationMarker
        do {
            val page = performQuery(query, options.copy(continuationMarker = marker))
            page.errors.firstOrNull()?.let { throw CloudKitException(it, 200) }
            page.records.forEach { emit(it) }
            marker = page.continuationMarker
        } while (marker != null)
    }

    // ---- Changes (private / shared databases, custom zones) -------------------------------------

    /** Zones changed since [syncToken] (`changes/database`). */
    public suspend fun fetchDatabaseChanges(syncToken: String? = null, resultsLimit: Int? = null): DatabaseChanges {
        val json = http.post(scope, "changes/database", buildJsonObject {
            syncToken?.let { put("syncToken", it) }
            resultsLimit?.let { put("resultsLimit", it) }
        })
        val (zones, errors) = split(json, "zones", Zone.serializer())
        return DatabaseChanges(zones, errors, json.string("syncToken"), json.bool("moreComing"))
    }

    /** Record changes per zone (`changes/zone`). Deleted records come back with `deleted = true`. */
    public suspend fun fetchRecordZoneChanges(
        zones: List<ZoneChangesRequest>,
        desiredKeys: List<String>? = null,
        desiredRecordTypes: List<String>? = null,
        resultsLimit: Int? = null,
        numbersAsStrings: Boolean? = null,
    ): Results<ZoneChanges> {
        val json = http.post(scope, "changes/zone", buildJsonObject {
            putOpt("zones", zones)
            putOpt("desiredKeys", desiredKeys)
            putOpt("desiredRecordTypes", desiredRecordTypes)
            resultsLimit?.let { put("resultsLimit", it) }
            // Spelled `numberAsStrings` on this endpoint in Apple's reference.
            numbersAsStrings?.let { put("numberAsStrings", it) }
        })
        return split(json, "zones", ZoneChanges.serializer())
    }

    // ---- Zones ---------------------------------------------------------------------------------

    public suspend fun saveRecordZones(zoneIDs: List<ZoneID>): Results<Zone> = modifyZones("create", zoneIDs)
    public suspend fun deleteRecordZones(zoneIDs: List<ZoneID>): Results<Zone> = modifyZones("delete", zoneIDs)

    public suspend fun fetchRecordZones(zoneIDs: List<ZoneID>): Results<Zone> =
        split(http.post(scope, "zones/lookup", buildJsonObject { putOpt("zones", zoneIDs) }), "zones", Zone.serializer())

    public suspend fun fetchAllRecordZones(): Results<Zone> = split(http.get(scope, "zones/list"), "zones", Zone.serializer())

    private suspend fun modifyZones(operationType: String, zoneIDs: List<ZoneID>): Results<Zone> {
        val ops = buildJsonArray {
            zoneIDs.forEach { id ->
                add(buildJsonObject {
                    put("operationType", operationType)
                    put("zone", buildJsonObject { putOpt("zoneID", id) })
                })
            }
        }
        return split(http.post(scope, "zones/modify", buildJsonObject { put("operations", ops) }), "zones", Zone.serializer())
    }

    // ---- Subscriptions -------------------------------------------------------------------------

    public suspend fun saveSubscriptions(subscriptions: List<Subscription>, update: Boolean = false): Results<Subscription> =
        modifySubscriptions(subscriptions.map { (if (update) "update" else "create") to CloudKitJson.encodeToJsonElement(it) })

    public suspend fun deleteSubscriptions(subscriptionIDs: List<String>): Results<Subscription> =
        modifySubscriptions(subscriptionIDs.map { "delete" to buildJsonObject { put("subscriptionID", it) } })

    public suspend fun fetchSubscriptions(subscriptionIDs: List<String>): Results<Subscription> {
        val body = buildJsonObject {
            put("subscriptions", buildJsonArray { subscriptionIDs.forEach { add(buildJsonObject { put("subscriptionID", it) }) } })
        }
        return split(http.post(scope, "subscriptions/lookup", body), "subscriptions", Subscription.serializer())
    }

    public suspend fun fetchAllSubscriptions(): Results<Subscription> =
        split(http.get(scope, "subscriptions/list"), "subscriptions", Subscription.serializer())

    private suspend fun modifySubscriptions(ops: List<Pair<String, JsonElement>>): Results<Subscription> {
        val body = buildJsonObject {
            put("operations", buildJsonArray {
                ops.forEach { (type, sub) -> add(buildJsonObject { put("operationType", type); put("subscription", sub) }) }
            })
        }
        return split(http.post(scope, "subscriptions/modify", body), "subscriptions", Subscription.serializer())
    }

    // ---- Assets --------------------------------------------------------------------------------

    /**
     * Uploads [data] (max 15 MB) for an Asset field and returns the value to put in that field:
     * `record { this[fieldName] = uploaded.asset }`, saved with `recordName = uploaded.recordName`.
     */
    public suspend fun uploadAsset(
        recordType: String,
        fieldName: String,
        data: ByteArray,
        recordName: String? = null,
        zoneID: ZoneID? = null,
    ): UploadedAsset {
        val tokenJson = http.post(scope, "assets/upload", buildJsonObject {
            put("tokens", buildJsonArray {
                add(buildJsonObject {
                    put("recordType", recordType)
                    put("fieldName", fieldName)
                    recordName?.let { put("recordName", it) }
                })
            })
            putOpt("zoneID", zoneID)
        })
        val token = tokenJson["tokens"]!!.jsonArray.first().jsonObject
        val uploadUrl = token.string("url") ?: error("CloudKit returned no upload URL")
        val request = Request.Builder().url(uploadUrl).post(data.toRequestBody(OCTET_STREAM)).build()
        val uploaded = http.raw(request).use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                throw CloudKitException(CloudKitError("ASSET_UPLOAD_FAILED", reason = text.take(500)), response.code)
            }
            CloudKitJson.parseToJsonElement(text).jsonObject
        }
        val asset = CloudKitJson.decodeFromJsonElement<Asset>(uploaded["singleFile"] ?: uploaded)
        return UploadedAsset(token.string("recordName") ?: recordName.orEmpty(), token.string("fieldName") ?: fieldName, asset)
    }

    /** Downloads an asset fetched with its record (needs [Asset.downloadURL]). */
    public suspend fun downloadAsset(asset: Asset, fileName: String = "file"): ByteArray {
        val url = asset.downloadURL ?: throw IllegalArgumentException("Asset has no downloadURL; fetch the record first")
        // CloudKit download URLs carry a `${f}` file name placeholder.
        val request = Request.Builder().url(url.replace("\${f}", fileName)).build()
        return http.raw(request).use { response ->
            if (!response.isSuccessful) {
                throw CloudKitException(CloudKitError("ASSET_DOWNLOAD_FAILED", reason = "HTTP ${response.code}"), response.code)
            }
            response.body.bytes()
        }
    }

    /** Copies asset values from existing records so other records can reuse them (`assets/rereference`). */
    public suspend fun rereferenceAssets(assets: List<Pair<String, String>>, zoneID: ZoneID? = null): List<Asset> {
        val json = http.post(scope, "assets/rereference", buildJsonObject {
            put("assets", buildJsonArray {
                assets.forEach { (recordName, fieldName) ->
                    add(buildJsonObject { put("recordName", recordName); put("fieldName", fieldName) })
                }
            })
            putOpt("zoneID", zoneID)
        })
        return (json["assets"] as? JsonArray).orEmpty().map { CloudKitJson.decodeFromJsonElement<Asset>(it) }
    }

    // ---- Sharing -------------------------------------------------------------------------------

    /**
     * Creates a `cloudkit.share` for [root] (which must live in a custom zone of the private database).
     * Equivalent of `shareWithUI` without the UI. The returned share carries [Record.shortGUID] to hand out.
     */
    public suspend fun shareRecord(
        root: Record,
        participants: List<ShareParticipant> = emptyList(),
        publicPermission: Permission = Permission.NONE,
        options: ModifyOptions = ModifyOptions(),
    ): Record {
        val rootName = requireNotNull(root.recordName) { "Save the root record before sharing it" }
        val share = Record(
            recordType = Record.SHARE_RECORD_TYPE,
            forRecord = RecordRef(rootName, recordChangeTag = root.recordChangeTag),
            publicPermission = publicPermission,
            participants = participants,
        )
        return newRecordsBatch().create(share).commit(options.copy(zoneID = options.zoneID ?: root.zoneID)).getOrThrow().single()
    }

    internal suspend fun modify(ops: List<Pair<String, Record>>, options: ModifyOptions): Results<Record> {
        val zone = options.zoneID ?: ops.firstNotNullOfOrNull { it.second.zoneID }
        val body = buildJsonObject {
            put("operations", buildJsonArray {
                ops.forEach { (type, record) ->
                    add(buildJsonObject {
                        put("operationType", type)
                        // zoneID is a request-level key for records/modify.
                        put("record", CloudKitJson.encodeToJsonElement(record.copy(zoneID = null)))
                    })
                }
            })
            putOpt("zoneID", zone)
            options.atomic?.let { put("atomic", it) }
            putOpt("desiredKeys", options.desiredKeys)
            options.numbersAsStrings?.let { put("numbersAsStrings", it) }
        }
        return split(http.post(scope, "records/modify", body), "records", Record.serializer())
    }

    private companion object {
        val OCTET_STREAM = "application/octet-stream".toMediaType()
    }
}

/** Ordered record operations sent in one `records/modify` request. */
public class RecordsBatch internal constructor(private val database: Database) {
    private val ops = mutableListOf<Pair<String, Record>>()

    public fun create(record: Record): RecordsBatch = add("create", record)
    public fun update(record: Record): RecordsBatch = add("update", record)
    public fun forceUpdate(record: Record): RecordsBatch = add("forceUpdate", record)
    public fun replace(record: Record): RecordsBatch = add("replace", record)
    public fun forceReplace(record: Record): RecordsBatch = add("forceReplace", record)
    public fun delete(record: Record): RecordsBatch = add("delete", record.forDelete())
    public fun forceDelete(record: Record): RecordsBatch = add("forceDelete", record.forDelete())

    public suspend fun commit(options: ModifyOptions = ModifyOptions()): Results<Record> = database.modify(ops.toList(), options)

    private fun add(type: String, record: Record) = apply { ops += type to record }
    private fun Record.forDelete() = Record(recordName = recordName, recordChangeTag = recordChangeTag, zoneID = zoneID)
}

// ---- JSON helpers --------------------------------------------------------------------------------

internal fun <T> split(json: JsonObject, key: String, serializer: KSerializer<T>): Results<T> {
    val ok = mutableListOf<T>()
    val errors = mutableListOf<CloudKitError>()
    (json[key] as? JsonArray).orEmpty().forEach { item ->
        if (item is JsonObject && "serverErrorCode" in item) errors += CloudKitJson.decodeFromJsonElement(CloudKitError.serializer(), item)
        else ok += CloudKitJson.decodeFromJsonElement(serializer, item)
    }
    return Results(ok, errors)
}

internal inline fun <reified T> JsonObjectBuilder.putOpt(key: String, value: T?) {
    if (value != null) put(key, CloudKitJson.encodeToJsonElement(value))
}

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false
