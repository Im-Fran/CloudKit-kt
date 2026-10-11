package io.github.imfran.cloudkit.sync

import io.github.imfran.cloudkit.CloudKitException
import io.github.imfran.cloudkit.CloudKitStore
import io.github.imfran.cloudkit.Database
import io.github.imfran.cloudkit.DatabaseScope
import io.github.imfran.cloudkit.ZoneChangesRequest
import io.github.imfran.cloudkit.model.Record
import io.github.imfran.cloudkit.model.ZoneID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

public sealed interface SyncChange {
    public data class Upserted(val record: Record) : SyncChange
    public data class Deleted(val recordName: String, val zoneID: ZoneID) : SyncChange
    public data class ZoneDeleted(val zoneID: ZoneID) : SyncChange
}

/**
 * Incremental sync of the custom zones of a private or shared database — what a CKSyncEngine / CKFetch*Changes
 * loop does on iOS. Each `changes()` collection yields only what changed since the last completed one.
 *
 * Tokens are saved after the collector has handled each page, so an interrupted sync resumes without losing
 * changes (a page may be delivered twice; make your upserts idempotent).
 *
 * The default zone and the public database do not support change tokens; query them by `modifiedTimestamp`.
 */
public class CloudKitSync(
    private val database: Database,
    private val store: CloudKitStore,
    private val desiredRecordTypes: List<String>? = null,
) {
    init {
        require(database.scope != DatabaseScope.PUBLIC) { "Change tokens are only available in private and shared databases" }
    }

    private val prefix = "cloudkit.sync.${database.scope.name.lowercase()}"

    public fun changes(): Flow<SyncChange> = flow {
        var dbToken = store.get("$prefix.db")
        do {
            val dbChanges = database.fetchDatabaseChanges(dbToken)
            dbChanges.errors.firstOrNull()?.let { throw CloudKitException(it, 200) }
            for (zone in dbChanges.zones) {
                if (zone.deleted) {
                    emit(SyncChange.ZoneDeleted(zone.zoneID))
                    store.set(zoneKey(zone.zoneID), null)
                } else {
                    syncZone(zone.zoneID)
                }
            }
            dbToken = dbChanges.syncToken
            store.set("$prefix.db", dbToken)
        } while (dbChanges.moreComing)
    }

    private suspend fun FlowCollector<SyncChange>.syncZone(zoneID: ZoneID) {
        val key = zoneKey(zoneID)
        var token = store.get(key)
        do {
            val result = database.fetchRecordZoneChanges(
                listOf(ZoneChangesRequest(zoneID, syncToken = token, desiredRecordTypes = desiredRecordTypes)),
            )
            result.errors.firstOrNull()?.let { throw CloudKitException(it, 200) }
            val page = result.results.single()
            for (record in page.records) {
                val name = record.recordName ?: continue
                emit(if (record.deleted) SyncChange.Deleted(name, record.zoneID ?: zoneID) else SyncChange.Upserted(record))
            }
            token = page.syncToken
            store.set(key, token)
        } while (page.moreComing)
    }

    private fun zoneKey(zoneID: ZoneID) = "$prefix.zone.${zoneID.ownerRecordName.orEmpty()}.${zoneID.zoneName}"
}
