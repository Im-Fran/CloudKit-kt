package io.github.imfran.cloudkit.internal

import io.github.imfran.cloudkit.Auth
import io.github.imfran.cloudkit.CloudKitError
import io.github.imfran.cloudkit.CloudKitException
import io.github.imfran.cloudkit.ContainerConfig
import io.github.imfran.cloudkit.DatabaseScope
import io.github.imfran.cloudkit.ErrorCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.coroutines.executeAsync
import java.util.concurrent.TimeUnit

internal val CloudKitJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
    coerceInputValues = true
}

internal class CloudKitHttp(
    private val config: ContainerConfig,
    private val onSignedOut: () -> Unit = {},
) {
    val client: OkHttpClient = config.httpClient ?: OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val tokenKey = "ckWebAuthToken:${config.containerIdentifier}:${config.environment.wire}"

    /** Latest sign-in URL from an `AUTHENTICATION_REQUIRED` response; null once signed in. */
    val signInUrl = MutableStateFlow<String?>(null)

    var webAuthToken: String?
        get() = (config.auth as? Auth.ApiToken)?.store?.get(tokenKey)
        set(value) { (config.auth as? Auth.ApiToken)?.store?.set(tokenKey, value) }

    suspend fun post(scope: DatabaseScope, subpath: String, body: JsonElement): JsonObject =
        call(scope, "POST", subpath, CloudKitJson.encodeToString(JsonElement.serializer(), body))

    suspend fun get(scope: DatabaseScope, subpath: String): JsonObject = call(scope, "GET", subpath, null)

    private suspend fun call(scope: DatabaseScope, method: String, subpath: String, body: String?): JsonObject {
        var attempt = 0
        while (true) {
            try {
                return callOnce(scope, method, subpath, body)
            } catch (e: CloudKitException) {
                val retryAfter = e.error.retryAfter
                val retryable = e.code == ErrorCode.THROTTLED || e.code == ErrorCode.TRY_AGAIN_LATER
                if (!retryable || retryAfter == null || attempt++ >= config.maxRetries) throw e
                delay((retryAfter * 1000).toLong())
            }
        }
    }

    private suspend fun callOnce(scope: DatabaseScope, method: String, subpath: String, body: String?): JsonObject {
        val path = "/database/1/${config.containerIdentifier}/${config.environment.wire}/${scope.wire}/$subpath"
        val url = (config.baseUrl.trimEnd('/') + path).toHttpUrl().newBuilder()
        val bytes = body?.toByteArray() ?: ByteArray(0)
        val request = Request.Builder()

        when (val auth = config.auth) {
            is Auth.ApiToken -> {
                url.addQueryParameter("ckAPIToken", auth.apiToken)
                webAuthToken?.let { url.addQueryParameter("ckWebAuthToken", it) }
            }
            is Auth.ServerToServer -> {
                require(scope == DatabaseScope.PUBLIC) { "Server-to-server keys only work with the public database" }
                auth.signer.headers(path, bytes).forEach { (k, v) -> request.header(k, v) }
            }
        }

        // text/plain like CloudKit JS; the API reads the body as JSON regardless.
        request.url(url.build()).method(method, body?.let { bytes.toRequestBody(TEXT_PLAIN) })
        return client.newCall(request.build()).executeAsync().use { handle(it) }
    }

    private fun handle(response: Response): JsonObject {
        val text = response.body.string()
        val json = text.takeIf { it.isNotBlank() }?.let { runCatching { CloudKitJson.parseToJsonElement(it).jsonObject }.getOrNull() }

        // ponytail: the docs say every response carries a rotated web auth token but not where;
        // accept it from either place and keep the old one otherwise.
        val rotated = response.header("X-Apple-CloudKit-Web-Auth-Token")
            ?: (json?.get("ckWebAuthToken") as? JsonPrimitive)?.content
        if (rotated != null && config.auth is Auth.ApiToken) webAuthToken = rotated

        if (response.isSuccessful && json != null) return json

        val error = json?.takeIf { "serverErrorCode" in it }?.let { CloudKitJson.decodeFromJsonElement<CloudKitError>(it) }
            ?: CloudKitError(serverErrorCode = if (response.code == 421) "AUTHENTICATION_REQUIRED" else "UNKNOWN", reason = text.take(500))
        when (error.code) {
            ErrorCode.AUTHENTICATION_REQUIRED -> {
                webAuthToken = null
                signInUrl.value = error.redirectURL
                onSignedOut()
            }
            ErrorCode.AUTHENTICATION_FAILED -> {
                webAuthToken = null
                onSignedOut()
            }
            else -> Unit
        }
        throw CloudKitException(error, response.code)
    }

    /** Requests to absolute URLs handed out by CloudKit (asset upload/download, notification long-poll). */
    suspend fun raw(request: Request): Response = client.newCall(request).executeAsync()

    private companion object {
        val TEXT_PLAIN = "text/plain; charset=utf-8".toMediaType()
    }
}
