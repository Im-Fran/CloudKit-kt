package io.github.imfran.cloudkit

import io.github.imfran.cloudkit.internal.CloudKitHttp
import io.github.imfran.cloudkit.internal.CloudKitJson
import io.github.imfran.cloudkit.model.ShareMetadata
import io.github.imfran.cloudkit.model.ShortGUID
import io.github.imfran.cloudkit.model.UserIdentity
import io.github.imfran.cloudkit.model.UserLookupInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.coroutines.executeAsync
import java.util.concurrent.TimeUnit

/** Mirrors `CloudKit.Container` from CloudKit JS. */
public class Container internal constructor(public val config: ContainerConfig) {
    private val user = MutableStateFlow<UserIdentity?>(null)
    private val http = CloudKitHttp(config, onSignedOut = { user.value = null })

    public val containerIdentifier: String get() = config.containerIdentifier
    public val publicCloudDatabase: Database = Database(DatabaseScope.PUBLIC, http)
    public val privateCloudDatabase: Database = Database(DatabaseScope.PRIVATE, http)
    public val sharedCloudDatabase: Database = Database(DatabaseScope.SHARED, http)

    public fun getDatabaseWithDatabaseScope(scope: DatabaseScope): Database = when (scope) {
        DatabaseScope.PUBLIC -> publicCloudDatabase
        DatabaseScope.PRIVATE -> privateCloudDatabase
        DatabaseScope.SHARED -> sharedCloudDatabase
    }

    // ---- Authentication ------------------------------------------------------------------------

    /** Signed-in user, or null. Becomes null when CloudKit rejects the web auth token. */
    public val userIdentity: StateFlow<UserIdentity?> = user.asStateFlow()

    /** Apple sign-in page to open (Custom Tab on Android) when the user is signed out. */
    public val signInUrl: StateFlow<String?> = http.signInUrl.asStateFlow()

    public val isSignedIn: Boolean get() = http.webAuthToken != null

    /**
     * Like `container.setUpAuth()`: returns the user when a stored token is still valid, otherwise null and
     * [signInUrl] gets the URL to open.
     */
    public suspend fun setUpAuth(): UserIdentity? = try {
        fetchCurrentUserIdentity()
    } catch (e: CloudKitException) {
        if (e.code != ErrorCode.AUTHENTICATION_REQUIRED && e.code != ErrorCode.AUTHENTICATION_FAILED) throw e
        user.value = null
        null
    }

    /**
     * Finishes sign-in with the callback URL Apple redirected to (`…?ckWebAuthToken=…`) or the bare token.
     * On Android `CloudKitSignIn` in `cloudkit-android` calls this for you.
     */
    public suspend fun completeSignIn(callbackUrlOrToken: String): UserIdentity {
        http.webAuthToken = extractWebAuthToken(callbackUrlOrToken)
        return fetchCurrentUserIdentity()
    }

    /** Suspends until a user is signed in, like `container.whenUserSignsIn()`. */
    public suspend fun whenUserSignsIn(): UserIdentity = user.filterNotNull().first()

    /** Suspends until the user signs out or the token is rejected. */
    public suspend fun whenUserSignsOut() { user.first { it == null } }

    /** Forgets the web auth token locally (tokens expire on their own on Apple's side). */
    public fun signOut() {
        http.webAuthToken = null
        user.value = null
    }

    // ---- Users ---------------------------------------------------------------------------------

    public suspend fun fetchCurrentUserIdentity(): UserIdentity =
        CloudKitJson.decodeFromJsonElement<UserIdentity>(http.get(DatabaseScope.PUBLIC, "users/caller"))
            .also { identity ->
                if (config.auth is Auth.ApiToken && http.webAuthToken != null) {
                    user.value = identity
                    http.signInUrl.value = null
                }
            }

    public suspend fun discoverUserIdentities(lookupInfos: List<UserLookupInfo>): List<UserIdentity> {
        val json = http.post(DatabaseScope.PUBLIC, "users/discover", buildJsonObject {
            put("lookupInfos", CloudKitJson.encodeToJsonElement(lookupInfos))
        })
        return json.users()
    }

    public suspend fun discoverUserIdentityWithEmailAddress(emailAddress: String): UserIdentity? =
        discoverUserIdentities(listOf(UserLookupInfo(emailAddress = emailAddress))).firstOrNull()

    public suspend fun discoverUserIdentityWithPhoneNumber(phoneNumber: String): UserIdentity? =
        discoverUserIdentities(listOf(UserLookupInfo(phoneNumber = phoneNumber))).firstOrNull()

    public suspend fun discoverUserIdentityWithUserRecordName(userRecordName: String): UserIdentity? =
        discoverUserIdentities(listOf(UserLookupInfo(userRecordName = userRecordName))).firstOrNull()

    /** Discoverable users in the signed-in user's contacts. */
    public suspend fun discoverAllUserIdentities(): List<UserIdentity> = http.get(DatabaseScope.PUBLIC, "users/discover").users()

    private fun JsonObject.users(): List<UserIdentity> =
        (this["users"] as? JsonArray).orEmpty().map { CloudKitJson.decodeFromJsonElement<UserIdentity>(it) }

    // ---- Sharing -------------------------------------------------------------------------------

    /** Resolves share short GUIDs (from share URLs) to their metadata (`records/resolve`). */
    public suspend fun fetchShareMetadata(shortGUIDs: List<String>, shouldFetchRootRecord: Boolean = false): Results<ShareMetadata> =
        shareCall("records/resolve", shortGUIDs, shouldFetchRootRecord)

    /** Accepts shares; afterwards the records show up in [sharedCloudDatabase] (`records/accept`). */
    public suspend fun acceptShares(shortGUIDs: List<String>): Results<ShareMetadata> = shareCall("records/accept", shortGUIDs, false)

    private suspend fun shareCall(subpath: String, shortGUIDs: List<String>, fetchRoot: Boolean): Results<ShareMetadata> {
        val body = buildJsonObject {
            put("shortGUIDs", buildJsonArray {
                shortGUIDs.forEach { add(CloudKitJson.encodeToJsonElement(ShortGUID(it, shouldFetchRootRecord = fetchRoot))) }
            })
        }
        return split(http.post(DatabaseScope.PUBLIC, subpath, body), "results", ShareMetadata.serializer())
    }

    // ---- Notifications -------------------------------------------------------------------------

    /** Creates a web push token (`tokens/create`); [notifications] does this for you. */
    public suspend fun registerForNotifications(): NotificationToken {
        val json = http.post(DatabaseScope.PUBLIC, "tokens/create", buildJsonObject {
            put("apnsEnvironment", config.environment.wire)
        })
        return NotificationToken(
            apnsToken = json.string("apnsToken"),
            webcourierURL = json.string("webcourierURL") ?: error("CloudKit returned no webcourierURL"),
        )
    }

    /**
     * Subscription notifications delivered by long-polling CloudKit's web courier, the same mechanism
     * CloudKit JS uses. Collect it while the app is in the foreground; for background delivery use your
     * own push channel (FCM) since APNs is not available on Android.
     */
    public fun notifications(retryDelayMillis: Long = 5_000): Flow<Notification> = flow {
        val longPoll = http.client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        var url = registerForNotifications().webcourierURL
        while (true) {
            try {
                longPoll.newCall(Request.Builder().url(url).build()).executeAsync().use { response ->
                    val text = response.body.string()
                    when {
                        response.isSuccessful && text.isNotBlank() ->
                            emit(Notification(CloudKitJson.parseToJsonElement(text).jsonObject))
                        response.code in 400..499 -> url = registerForNotifications().webcourierURL
                        !response.isSuccessful -> delay(retryDelayMillis)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                delay(retryDelayMillis)
            }
        }
    }

    internal companion object {
        fun extractWebAuthToken(callbackUrlOrToken: String): String {
            val url = callbackUrlOrToken.toHttpUrlOrNull()
                ?: callbackUrlOrToken.takeIf { "://" in it }?.let { ("https://" + it.substringAfter("://")).toHttpUrlOrNull() }
            // Apple has been seen returning the token as `ckSession` too.
            return url?.let { it.queryParameter("ckWebAuthToken") ?: it.queryParameter("ckSession") }
                ?: callbackUrlOrToken.takeUnless { "://" in it }
                ?: throw IllegalArgumentException("No ckWebAuthToken in callback URL")
        }
    }
}

public data class NotificationToken(val apnsToken: String?, val webcourierURL: String)

/**
 * A CloudKit push payload. ponytail: the web payload is undocumented; it mirrors the APNs `ck` dictionary
 * (`nid`, `cid`, `qry`/`fet`/`met`), so the common bits are parsed and [raw] keeps everything.
 */
public data class Notification(val raw: JsonObject) {
    private val ck: JsonObject? get() = raw["ck"] as? JsonObject
    private val body: JsonObject? get() = ck?.let { (it["qry"] ?: it["fet"] ?: it["met"]) as? JsonObject }

    public val notificationID: String? get() = ck?.string("nid")
    public val containerIdentifier: String? get() = ck?.string("cid")
    public val subscriptionID: String? get() = body?.string("sid")
    /** Changed record name, for query notifications. */
    public val recordName: String? get() = body?.string("rid")
    public val zoneName: String? get() = body?.string("zid")
    public val type: Type get() = when {
        ck?.containsKey("qry") == true -> Type.QUERY
        ck?.containsKey("fet") == true -> Type.ZONE
        ck?.containsKey("met") == true -> Type.DATABASE
        else -> Type.UNKNOWN
    }

    public enum class Type { QUERY, ZONE, DATABASE, UNKNOWN }
}
