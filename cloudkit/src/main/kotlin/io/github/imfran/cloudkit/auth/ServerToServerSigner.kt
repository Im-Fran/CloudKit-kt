package io.github.imfran.cloudkit.auth

import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64

/**
 * Signs requests for server-to-server keys:
 * `SignatureV1 = base64(ECDSA-SHA256("<ISO8601 date>:<base64(sha256(body))>:<subpath>"))`.
 */
internal class ServerToServerSigner(
    private val keyId: String,
    privateKeyPem: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val key: PrivateKey = parsePrivateKey(privateKeyPem)

    /** [subpath] is the URL path, e.g. `/database/1/iCloud.x/development/public/records/query`. */
    fun headers(subpath: String, body: ByteArray): Map<String, String> {
        val date = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS).toString()
        val bodyHash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(body))
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update("$date:$bodyHash:$subpath".toByteArray())
            Base64.getEncoder().encodeToString(sign())
        }
        return mapOf(
            "X-Apple-CloudKit-Request-KeyID" to keyId,
            "X-Apple-CloudKit-Request-ISO8601Date" to date,
            "X-Apple-CloudKit-Request-SignatureV1" to signature,
        )
    }

    internal companion object {
        /** Accepts PKCS#8 (`BEGIN PRIVATE KEY`) or SEC1 (`BEGIN EC PRIVATE KEY`, what openssl emits). */
        fun parsePrivateKey(pem: String): PrivateKey {
            val sec1 = pem.contains("BEGIN EC PRIVATE KEY")
            val der = Base64.getMimeDecoder().decode(
                pem.lineSequence().filterNot { it.startsWith("-----") }.joinToString("")
            )
            val pkcs8 = if (sec1) sec1ToPkcs8(der) else der
            return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
        }

        // PrivateKeyInfo ::= SEQUENCE { INTEGER 0, SEQUENCE { id-ecPublicKey, prime256v1 }, OCTET STRING <sec1> }
        private val P256_ALGORITHM_ID = byteArrayOf(
            0x30, 0x13,
            0x06, 0x07, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x02, 0x01,
            0x06, 0x08, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x03, 0x01, 0x07,
        )

        private fun sec1ToPkcs8(sec1: ByteArray): ByteArray {
            val content = byteArrayOf(0x02, 0x01, 0x00) + P256_ALGORITHM_ID + der(0x04, sec1)
            return der(0x30, content)
        }

        private fun der(tag: Int, content: ByteArray): ByteArray = ByteArrayOutputStream().apply {
            write(tag)
            val len = content.size
            when {
                len < 0x80 -> write(len)
                len < 0x100 -> { write(0x81); write(len) }
                else -> { write(0x82); write(len shr 8); write(len and 0xFF) }
            }
            write(content)
        }.toByteArray()
    }
}
