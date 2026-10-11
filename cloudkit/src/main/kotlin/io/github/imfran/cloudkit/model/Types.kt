package io.github.imfran.cloudkit.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Zone identifier. `_defaultZone` is the default zone of a database. */
@Serializable
public data class ZoneID(
    val zoneName: String = DEFAULT_ZONE_NAME,
    val ownerRecordName: String? = null,
) {
    public companion object {
        public const val DEFAULT_ZONE_NAME: String = "_defaultZone"
        public val Default: ZoneID = ZoneID()
    }
}

@Serializable
public data class Zone(
    val zoneID: ZoneID,
    val syncToken: String? = null,
    val atomic: Boolean? = null,
    val deleted: Boolean = false,
)

@Serializable
public data class Reference(
    val recordName: String,
    val zoneID: ZoneID? = null,
    val action: Action? = null,
) {
    @Serializable
    public enum class Action { NONE, DELETE_SELF, VALIDATE }
}

/**
 * Asset value. When saving, set the values returned by [io.github.imfran.cloudkit.Database.uploadAsset];
 * when fetching, [downloadURL] is filled in.
 */
@Serializable
public data class Asset(
    val fileChecksum: String? = null,
    val size: Long? = null,
    val referenceChecksum: String? = null,
    val wrappingKey: String? = null,
    val receipt: String? = null,
    val downloadURL: String? = null,
)

@Serializable
public data class Location(
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracy: Double? = null,
    val verticalAccuracy: Double? = null,
    val altitude: Double? = null,
    val speed: Double? = null,
    val course: Double? = null,
    /** Milliseconds since the Unix epoch. */
    val timestamp: Long? = null,
)

/** `created` / `modified` record metadata. */
@Serializable
public data class ChangeInfo(
    /** Milliseconds since the Unix epoch. */
    val timestamp: Long,
    val userRecordName: String? = null,
    val deviceID: String? = null,
)

@Serializable
public data class RecordRef(val recordName: String, val zoneID: ZoneID? = null, val recordChangeTag: String? = null)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
public data class ShortGUID(
    val value: String,
    @EncodeDefault val shouldFetchRootRecord: Boolean = false,
    val rootRecordDesiredKeys: List<String>? = null,
)

@Serializable
public enum class Permission { NONE, READ_ONLY, READ_WRITE, UNKNOWN }

@Serializable
public data class ShareParticipant(
    val userIdentity: UserIdentity? = null,
    val permission: Permission? = null,
    val type: Type? = null,
    val acceptanceStatus: AcceptanceStatus? = null,
) {
    @Serializable
    public enum class Type { OWNER, ADMINISTRATOR, USER, PUBLIC_USER, UNKNOWN }

    @Serializable
    public enum class AcceptanceStatus { INVITED, ACCEPTED, REMOVED, UNKNOWN }
}

/**
 * A CloudKit record. [recordName] is assigned by CloudKit on create when null.
 * Field access helpers live in `RecordDsl.kt` (`record.string("title")`, `record { this["title"] = "…" }`).
 */
@Serializable
public data class Record(
    val recordName: String? = null,
    val recordType: String? = null,
    val recordChangeTag: String? = null,
    val fields: Map<String, FieldValue> = emptyMap(),
    val zoneID: ZoneID? = null,
    val created: ChangeInfo? = null,
    val modified: ChangeInfo? = null,
    val deleted: Boolean = false,
    val parent: RecordRef? = null,
    val share: RecordRef? = null,
    val shortGUID: ShortGUID? = null,
    val createShortGUID: Boolean? = null,
    // Share (`cloudkit.share`) records only.
    val forRecord: RecordRef? = null,
    val publicPermission: Permission? = null,
    val participants: List<ShareParticipant>? = null,
    val owner: ShareParticipant? = null,
    val currentUserParticipant: ShareParticipant? = null,
) {
    public companion object {
        public const val SHARE_RECORD_TYPE: String = "cloudkit.share"
    }
}

@Serializable
public enum class Comparator {
    EQUALS, NOT_EQUALS, LESS_THAN, LESS_THAN_OR_EQUALS, GREATER_THAN, GREATER_THAN_OR_EQUALS,
    NEAR, CONTAINS_ALL_TOKENS, CONTAINS_ANY_TOKENS, IN, NOT_IN,
    LIST_CONTAINS, NOT_LIST_CONTAINS, NOT_LIST_CONTAINS_ANY, LIST_CONTAINS_ALL, NOT_LIST_CONTAINS_ALL,
    BEGINS_WITH, NOT_BEGINS_WITH, LIST_MEMBER_BEGINS_WITH, NOT_LIST_MEMBER_BEGINS_WITH,
}

/** Use [fieldName] for your fields or [systemFieldName] (`recordName`, `createdTimestamp`, `modifiedTimestamp`, …). */
@Serializable
public data class Filter(
    val comparator: Comparator,
    val fieldValue: FieldValue,
    val fieldName: String? = null,
    val systemFieldName: String? = null,
    /** Meters; only for [Comparator.NEAR] on Location fields. */
    val distance: Double? = null,
)

@Serializable
public data class Sort(
    val fieldName: String? = null,
    val systemFieldName: String? = null,
    val ascending: Boolean = true,
    val relativeLocation: FieldValue? = null,
)

@Serializable
public data class Query(
    val recordType: String,
    val filterBy: List<Filter> = emptyList(),
    val sortBy: List<Sort> = emptyList(),
)

@Serializable
public data class NotificationInfo(
    val alertBody: String? = null,
    val alertLocalizationKey: String? = null,
    val alertLocalizationArgs: List<String>? = null,
    val alertActionLocalizationKey: String? = null,
    val alertLaunchImage: String? = null,
    val soundName: String? = null,
    val shouldBadge: Boolean? = null,
    val shouldSendContentAvailable: Boolean? = null,
)

@Serializable
public data class Subscription(
    val subscriptionID: String? = null,
    val subscriptionType: Type,
    val zoneID: ZoneID? = null,
    val query: Query? = null,
    val firesOn: List<FiresOn>? = null,
    val firesOnce: Boolean? = null,
    val zoneWide: Boolean? = null,
    val notificationInfo: NotificationInfo? = null,
) {
    @Serializable
    public enum class Type { @SerialName("zone") ZONE, @SerialName("query") QUERY }

    @Serializable
    public enum class FiresOn { @SerialName("create") CREATE, @SerialName("update") UPDATE, @SerialName("delete") DELETE }
}

@Serializable
public data class UserLookupInfo(
    val emailAddress: String? = null,
    val phoneNumber: String? = null,
    val userRecordName: String? = null,
)

@Serializable
public data class NameComponents(
    val namePrefix: String? = null,
    val givenName: String? = null,
    val middleName: String? = null,
    val familyName: String? = null,
    val nameSuffix: String? = null,
    val nickname: String? = null,
    val phoneticRepresentation: String? = null,
)

@Serializable
public data class UserIdentity(
    val userRecordName: String? = null,
    val lookupInfo: UserLookupInfo? = null,
    val nameComponents: NameComponents? = null,
)

/** Result of resolving or accepting a share (`records/resolve`, `records/accept`). */
@Serializable
public data class ShareMetadata(
    val containerIdentifier: String? = null,
    val environment: String? = null,
    val databaseScope: String? = null,
    val zoneID: ZoneID? = null,
    val rootRecordName: String? = null,
    val rootRecord: Record? = null,
    val share: Record? = null,
    val shortGUID: ShortGUID? = null,
    val ownerIdentity: UserIdentity? = null,
    val participantPermission: Permission? = null,
    val participantStatus: ShareParticipant.AcceptanceStatus? = null,
    val participantType: ShareParticipant.Type? = null,
    val webpageURL: String? = null,
)
