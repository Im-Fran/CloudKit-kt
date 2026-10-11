package io.github.imfran.cloudkit

import io.github.imfran.cloudkit.auth.ServerToServerSigner
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

public enum class Environment(internal val wire: String) { DEVELOPMENT("development"), PRODUCTION("production") }

public enum class DatabaseScope(internal val wire: String) { PUBLIC("public"), PRIVATE("private"), SHARED("shared") }

/** Tiny persistence hook (web auth token, change tokens). Android: `PreferencesStore` in `cloudkit-android`. */
public interface CloudKitStore {
    public fun get(key: String): String?
    public fun set(key: String, value: String?)
}

public class InMemoryStore : CloudKitStore {
    private val map = ConcurrentHashMap<String, String>()
    override fun get(key: String): String? = map[key]
    override fun set(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
}

public sealed interface Auth {
    /**
     * API token from CloudKit Console. Public database works signed out; private/shared need the user
     * to sign in (see [Container.setUpAuth]). The web auth token is persisted in [store].
     */
    public class ApiToken(public val apiToken: String, public val store: CloudKitStore = InMemoryStore()) : Auth

    /**
     * Server-to-server key (public database only, acts as the key's developer).
     * [privateKeyPem] is the output of `openssl ecparam -name prime256v1 -genkey -noout`.
     * Never ship a private key inside an app; this is meant for backends and tools.
     */
    public class ServerToServer(public val keyId: String, privateKeyPem: String) : Auth {
        internal val signer = ServerToServerSigner(keyId, privateKeyPem)
    }
}

public data class ContainerConfig(
    val containerIdentifier: String,
    val environment: Environment,
    val auth: Auth,
    val baseUrl: String = "https://api.apple-cloudkit.com",
    val httpClient: OkHttpClient? = null,
    /** Retries for `THROTTLED` / `TRY_AGAIN_LATER` honoring `retryAfter`. */
    val maxRetries: Int = 3,
) {
    init { require(containerIdentifier.startsWith("iCloud.")) { "Container identifiers start with 'iCloud.'" } }
}

/** Entry point, equivalent to `CloudKit.configure({ containers: [...] })` in CloudKit JS. */
public class CloudKit private constructor(configs: List<ContainerConfig>) {
    private val containers: Map<String, Container> = configs.associate { it.containerIdentifier to Container(it) }
    private val default: Container = containers.getValue(configs.first().containerIdentifier)

    public fun getDefaultContainer(): Container = default
    public fun getContainer(containerIdentifier: String): Container =
        containers[containerIdentifier] ?: throw IllegalArgumentException("Container $containerIdentifier not configured")

    public companion object {
        public fun configure(vararg containers: ContainerConfig): CloudKit {
            require(containers.isNotEmpty()) { "At least one container is required" }
            return CloudKit(containers.toList())
        }
    }
}
