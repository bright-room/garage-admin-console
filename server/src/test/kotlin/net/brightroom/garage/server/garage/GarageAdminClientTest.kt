package net.brightroom.garage.server.garage

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.ContentType
import kotlinx.coroutines.test.runTest
import net.brightroom.garage.shared.model.garage.ClusterHealth
import net.brightroom.garage.shared.model.garage.ClusterHealthStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GarageAdminClientTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    @Test
    fun sendsBearerTokenAndParsesResponse() = runTest {
        var capturedAuth: String? = null
        var capturedUrl: String? = null

        val engine = MockEngine { request ->
            capturedAuth = request.headers[HttpHeaders.Authorization]
            capturedUrl = request.url.toString()
            respond(
                content = """
                    {"status":"healthy","knownNodes":1,"connectedNodes":1,"storageNodes":1,
                     "storageNodesUp":1,"partitions":256,"partitionsQuorum":256,"partitionsAllOk":256}
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val client = GarageAdminClient("http://garage.test:3903", engine)

        val health: ClusterHealth = client.get("tok-abc", "GetClusterHealth")
            .garageBody("GetClusterHealth")

        assertEquals("Bearer tok-abc", capturedAuth)
        assertEquals("http://garage.test:3903/v2/GetClusterHealth", capturedUrl)
        assertEquals(ClusterHealthStatus.HEALTHY, health.status)
    }

    @Test
    fun appendsQueryParameters() = runTest {
        var capturedUrl: String? = null
        val engine = MockEngine { request ->
            capturedUrl = request.url.toString()
            respond("""{"success":{},"error":{}}""", HttpStatusCode.OK, jsonHeaders)
        }
        val client = GarageAdminClient("http://garage.test:3903", engine)

        client.get("tok", "ListBlockErrors", mapOf("node" to "*"))

        // Ktor は `*` を %2A に percent-encode する。Garage v2.3.0 は
        // ListBlockErrors?node=%2A を node=* と同じに扱うことを実機で確認済み。
        assertEquals("http://garage.test:3903/v2/ListBlockErrors?node=%2A", capturedUrl)
    }

    @Test
    fun carriesForbiddenStatusAndOperation() = runTest {
        val engine = MockEngine {
            respond("insufficient scope", HttpStatusCode.Forbidden)
        }
        val client = GarageAdminClient("http://garage.test:3903", engine)

        val failure = assertFailsWith<GarageException> {
            client.get("tok", "GetKeyInfo").requireSuccess("GetKeyInfo")
        }

        assertEquals(HttpStatusCode.Forbidden, failure.status)
        assertEquals("GetKeyInfo", failure.operation)
    }

    @Test
    fun carriesUnauthorizedStatus() = runTest {
        val engine = MockEngine { respond("bad token", HttpStatusCode.Unauthorized) }
        val client = GarageAdminClient("http://garage.test:3903", engine)

        val failure = assertFailsWith<GarageException> {
            client.get("tok", "GetClusterHealth").requireSuccess("GetClusterHealth")
        }

        assertEquals(HttpStatusCode.Unauthorized, failure.status)
    }

    @Test
    fun carriesResponseBodyAsMessage() = runTest {
        val engine = MockEngine { respond("boom", HttpStatusCode.InternalServerError) }
        val client = GarageAdminClient("http://garage.test:3903", engine)

        val failure = assertFailsWith<GarageException> {
            client.get("tok", "GetClusterStatus").requireSuccess("GetClusterStatus")
        }

        assertEquals(HttpStatusCode.InternalServerError, failure.status)
        assertEquals("boom", failure.message)
    }

    @Test
    fun fallsBackToStatusDescriptionWhenBodyIsEmpty() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.Forbidden) }
        val client = GarageAdminClient("http://garage.test:3903", engine)

        val failure = assertFailsWith<GarageException> {
            client.get("tok", "GetKeyInfo").requireSuccess("GetKeyInfo")
        }

        assertEquals("Forbidden", failure.message)
    }

    @Test
    fun postsJsonBody() = runTest {
        var capturedBody: String? = null
        val engine = MockEngine { request ->
            capturedBody = (request.body as io.ktor.http.content.TextContent).text
            respond("""[]""", HttpStatusCode.OK, jsonHeaders)
        }
        val client = GarageAdminClient("http://garage.test:3903", engine)

        client.post(
            token = "tok",
            operation = "ListWorkers",
            body = kotlinx.serialization.json.buildJsonObject {
                put("node", kotlinx.serialization.json.JsonPrimitive("*"))
            },
        )

        assertEquals("""{"node":"*"}""", capturedBody)
    }
}
