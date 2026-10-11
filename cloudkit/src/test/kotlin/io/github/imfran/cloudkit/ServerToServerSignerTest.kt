package io.github.imfran.cloudkit

import io.github.imfran.cloudkit.auth.ServerToServerSigner
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerToServerSignerTest {
    // Throwaway key generated with `openssl ecparam -name prime256v1 -genkey -noout` (SEC1 format).
    private val sec1Pem = """
        -----BEGIN EC PRIVATE KEY-----
        MHcCAQEEIFtZ/Vb4RSE+2A7UsMWDbCarHDcJvuA92/3diZxh0yYfoAoGCCqGSM49
        AwEHoUQDQgAEpZHtoOamvQ7lMA0MPTnMp5l1T3y7jVagoR0MkddoKR2icKW25F2F
        h1fKpiOnTrHc4BBKnpvdOFMOAu2fPmEVjA==
        -----END EC PRIVATE KEY-----
    """.trimIndent()
    private val publicPem = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEpZHtoOamvQ7lMA0MPTnMp5l1T3y7" +
        "jVagoR0MkddoKR2icKW25F2Fh1fKpiOnTrHc4BBKnpvdOFMOAu2fPmEVjA=="

    private val clock = Clock.fixed(Instant.parse("2016-01-25T22:15:43.512Z"), ZoneOffset.UTC)
    private val subpath = "/database/1/iCloud.com.example.MyApp/development/public/records/query"

    @Test
    fun `signs date, body hash and subpath with the SEC1 key`() {
        val body = """{"query":{"recordType":"Note"}}""".toByteArray()
        val headers = ServerToServerSigner("KEY", sec1Pem, clock).headers(subpath, body)

        assertEquals("KEY", headers["X-Apple-CloudKit-Request-KeyID"])
        assertEquals("2016-01-25T22:15:43Z", headers["X-Apple-CloudKit-Request-ISO8601Date"])

        val bodyHash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(body))
        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicPem)))
        val ok = Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update("2016-01-25T22:15:43Z:$bodyHash:$subpath".toByteArray())
            verify(Base64.getDecoder().decode(headers["X-Apple-CloudKit-Request-SignatureV1"]))
        }
        assertTrue(ok)
    }

    @Test
    fun `accepts PKCS8 keys`() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(pair.private.encoded) + "\n-----END PRIVATE KEY-----"
        val headers = ServerToServerSigner("K", pem, clock).headers(subpath, ByteArray(0))
        val emptyHash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(ByteArray(0)))
        val ok = Signature.getInstance("SHA256withECDSA").run {
            initVerify(pair.public)
            update("2016-01-25T22:15:43Z:$emptyHash:$subpath".toByteArray())
            verify(Base64.getDecoder().decode(headers["X-Apple-CloudKit-Request-SignatureV1"]))
        }
        assertTrue(ok)
    }
}
