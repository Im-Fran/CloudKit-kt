package io.github.imfran.cloudkit

import io.github.imfran.cloudkit.model.Comparator
import io.github.imfran.cloudkit.model.Query
import io.github.imfran.cloudkit.model.ZoneID
import io.github.imfran.cloudkit.model.filter
import io.github.imfran.cloudkit.model.record
import io.github.imfran.cloudkit.model.string
import io.github.imfran.cloudkit.sync.CloudKitSync
import io.github.imfran.cloudkit.sync.SyncChange
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DatabaseTest {
    private val server = MockWebServer()
    private val store = InMemoryStore()
    private lateinit var container: Container

    @BeforeTest
    fun setUp() {
        server.start()
        container = CloudKit.configure(
            ContainerConfig(
                "iCloud.com.example.app",
                Environment.DEVELOPMENT,
                Auth.ApiToken("api-token", store),
                baseUrl = server.url("/").toString(),
            ),
        ).getDefaultContainer()
    }

    @AfterTest
    fun tearDown() = server.close()

    private fun enqueue(body: String, code: Int = 200, headers: Map<String, String> = emptyMap()) =
        server.enqueue(MockResponse.Builder().code(code).body(body).apply { headers.forEach { (k, v) -> addHeader(k, v) } }.build())

    private fun RecordedRequest.json(): JsonObject = Json.parseToJsonElement(body!!.utf8()).jsonObject

    @Test
    fun `saveRecords creates new records and updates tagged ones`() = runTest {
        enqueue("""{"records":[{"recordName":"a","recordType":"Note","recordChangeTag":"1"},{"recordName":"b","serverErrorCode":"CONFLICT","reason":"old tag"}]}""")
        val result = container.privateCloudDatabase.saveRecords(
            listOf(
                record("Note") { this["title"] = "new" },
                record("Note", recordName = "b").copy(recordChangeTag = "0"),
            ),
            ModifyOptions(zoneID = ZoneID("Notes")),
        )

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/database/1/iCloud.com.example.app/development/private/records/modify", req.url.encodedPath)
        assertEquals("api-token", req.url.queryParameter("ckAPIToken"))
        val ops = req.json()["operations"]!!.jsonArray
        assertEquals("create", ops[0].jsonObject["operationType"]!!.jsonPrimitive.content)
        assertEquals("update", ops[1].jsonObject["operationType"]!!.jsonPrimitive.content)
        assertEquals("Notes", req.json()["zoneID"]!!.jsonObject["zoneName"]!!.jsonPrimitive.content)
        assertEquals("""{"value":"new","type":"STRING"}""", ops[0].jsonObject["record"]!!.jsonObject["fields"]!!.jsonObject["title"].toString())

        assertEquals(listOf("a"), result.results.map { it.recordName })
        assertEquals(ErrorCode.CONFLICT, result.errors.single().code)
        assertFailsWith<CloudKitException> { result.getOrThrow() }
    }

    @Test
    fun `queryAll follows continuation markers`() = runTest {
        enqueue("""{"records":[{"recordName":"1","fields":{"title":{"value":"a","type":"STRING"}}}],"continuationMarker":"m1"}""")
        enqueue("""{"records":[{"recordName":"2","fields":{"title":{"value":"b","type":"STRING"}}}]}""")

        val query = Query("Note", filterBy = listOf(filter("title", Comparator.BEGINS_WITH, "a")))
        val titles = container.publicCloudDatabase.queryAll(query).toList().map { it.string("title") }

        assertEquals(listOf("a", "b"), titles)
        val first = server.takeRequest().json()
        assertEquals("BEGINS_WITH", first["query"]!!.jsonObject["filterBy"]!!.jsonArray[0].jsonObject["comparator"]!!.jsonPrimitive.content)
        assertEquals("m1", server.takeRequest().json()["continuationMarker"]!!.jsonPrimitive.content)
    }

    @Test
    fun `sign-in flow stores, sends and rotates the web auth token`() = runTest {
        enqueue("""{"uuid":"u","serverErrorCode":"AUTHENTICATION_REQUIRED","reason":"needs auth","redirectURL":"https://idmsa.apple.com/x"}""", code = 421)
        assertNull(container.setUpAuth())
        assertEquals("https://idmsa.apple.com/x", container.signInUrl.value)

        enqueue("""{"userRecordName":"_abc"}""", headers = mapOf("X-Apple-CloudKit-Web-Auth-Token" to "rotated"))
        val user = container.completeSignIn("myapp-cloudkit://auth?ckWebAuthToken=a%2Bb%2Fc%3D")
        assertEquals("_abc", user.userRecordName)
        assertEquals(user, container.userIdentity.value)
        assertNull(container.signInUrl.value)

        server.takeRequest() // setUpAuth
        val signIn = server.takeRequest()
        assertEquals("/database/1/iCloud.com.example.app/development/public/users/caller", signIn.url.encodedPath)
        assertTrue("ckWebAuthToken=a%2Bb%2Fc%3D" in signIn.url.encodedQuery!!, signIn.url.encodedQuery)

        enqueue("""{"records":[]}""")
        container.privateCloudDatabase.fetchRecords(listOf("x"))
        assertEquals("rotated", server.takeRequest().url.queryParameter("ckWebAuthToken"))
    }

    @Test
    fun `retries throttled requests honoring retryAfter`() = runTest {
        enqueue("""{"serverErrorCode":"THROTTLED","retryAfter":1}""", code = 429)
        enqueue("""{"zones":[{"zoneID":{"zoneName":"Notes"},"syncToken":"t"}]}""")
        val zones = container.privateCloudDatabase.fetchAllRecordZones().getOrThrow()
        assertEquals("Notes", zones.single().zoneID.zoneName)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `sync walks database and zone changes saving tokens`() = runTest {
        enqueue("""{"zones":[{"zoneID":{"zoneName":"Notes"}}],"syncToken":"db1","moreComing":false}""")
        enqueue("""{"zones":[{"zoneID":{"zoneName":"Notes"},"records":[{"recordName":"a","recordType":"Note"}],"syncToken":"z1","moreComing":true}]}""")
        enqueue("""{"zones":[{"zoneID":{"zoneName":"Notes"},"records":[{"recordName":"b","deleted":true}],"syncToken":"z2","moreComing":false}]}""")

        val changes = CloudKitSync(container.privateCloudDatabase, store).changes().toList()

        assertEquals(2, changes.size)
        assertEquals("a", (changes[0] as SyncChange.Upserted).record.recordName)
        assertEquals(SyncChange.Deleted("b", ZoneID("Notes")), changes[1])
        assertEquals("db1", store.get("cloudkit.sync.private.db"))
        assertEquals("z2", store.get("cloudkit.sync.private.zone..Notes"))

        server.takeRequest()
        server.takeRequest()
        val second = server.takeRequest().json()
        assertEquals("z1", second["zones"]!!.jsonArray[0].jsonObject["syncToken"]!!.jsonPrimitive.content)
    }

    @Test
    fun `extracts token from callback urls`() {
        assertEquals("a+b", Container.extractWebAuthToken("myapp://cb?ckWebAuthToken=a%2Bb"))
        assertEquals("s", Container.extractWebAuthToken("https://example.com/cb?ckSession=s"))
        assertEquals("raw", Container.extractWebAuthToken("raw"))
    }
}
