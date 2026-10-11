package io.github.imfran.cloudkit

import kotlinx.serialization.Serializable

/** Error returned by CloudKit, either for a whole request or for a single item (record, zone, subscription). */
@Serializable
public data class CloudKitError(
    val serverErrorCode: String,
    val reason: String? = null,
    val uuid: String? = null,
    /** Seconds to wait before retrying; absent means not retryable. */
    val retryAfter: Double? = null,
    /** Sign-in URL, only for `AUTHENTICATION_REQUIRED`. */
    val redirectURL: String? = null,
    val recordName: String? = null,
    val subscriptionID: String? = null,
    val zoneID: io.github.imfran.cloudkit.model.ZoneID? = null,
) {
    public val code: ErrorCode get() = ErrorCode.from(serverErrorCode)
}

public enum class ErrorCode {
    ACCESS_DENIED, ATOMIC_ERROR, AUTHENTICATION_FAILED, AUTHENTICATION_REQUIRED, BAD_REQUEST,
    CONFLICT, EXISTS, INTERNAL_ERROR, NOT_FOUND, QUOTA_EXCEEDED, THROTTLED, TRY_AGAIN_LATER,
    VALIDATING_REFERENCE_ERROR, ZONE_NOT_FOUND, UNKNOWN;

    internal companion object {
        fun from(code: String): ErrorCode = entries.firstOrNull { it.name == code } ?: UNKNOWN
    }
}

public class CloudKitException(
    public val error: CloudKitError,
    public val httpStatus: Int,
) : Exception("${error.serverErrorCode} (HTTP $httpStatus): ${error.reason ?: "no reason"}") {
    public val code: ErrorCode get() = error.code
}
