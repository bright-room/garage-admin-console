package net.brightroom.garage.server.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import net.brightroom.garage.server.garageApp
import net.brightroom.garage.server.plugins.GarageJson
import net.brightroom.garage.shared.api.ClusterView
import net.brightroom.garage.shared.api.ConnectNodeResult
import net.brightroom.garage.shared.api.ProblemDetails
import net.brightroom.garage.shared.model.garage.ClusterHealthStatus
import net.brightroom.garage.shared.model.garage.ClusterStatistics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClusterRoutesTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private val statusBody = """
        {"layoutVersion":1,
         "nodes":[{"id":"n1","isUp":true,"draining":false,"hostname":"garage-a",
                   "role":{"zone":"dc1","tags":["dev"],"capacity":1073741824},
                   "dataPartition":{"available":900,"total":1000}}]}
    """.trimIndent()

    private val healthBody = """
        {"status":"healthy","knownNodes":1,"connectedNodes":1,"storageNodes":1,
         "storageNodesUp":1,"partitions":256,"partitionsQuorum":256,"partitionsAllOk":256}
    """.trimIndent()

    @Test
    fun combinesStatusAndHealth() = testApplication {
        val called = mutableListOf<String>()
        garageApp(
            MockEngine { request ->
                val operation = request.url.encodedPath.substringAfterLast('/')
                called += operation
                val body = if (operation == "GetClusterStatus") statusBody else healthBody
                respond(body, HttpStatusCode.OK, jsonHeaders)
            },
        )

        val response = client.get("/api/cluster") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(called.containsAll(listOf("GetClusterStatus", "GetClusterHealth")))
        val view = GarageJson.decodeFromString<ClusterView>(response.bodyAsText())
        assertEquals(ClusterHealthStatus.HEALTHY, view.health.status)
        assertEquals("garage-a", view.status.nodes.single().hostname)
    }

    @Test
    fun propagatesForbiddenFromEitherHalf() = testApplication {
        garageApp(
            MockEngine { request ->
                if (request.url.encodedPath.endsWith("GetClusterHealth")) {
                    respond("insufficient scope", HttpStatusCode.Forbidden)
                } else {
                    respond(statusBody, HttpStatusCode.OK, jsonHeaders)
                }
            },
        )

        val response = client.get("/api/cluster") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        val problem = GarageJson.decodeFromString<ProblemDetails>(response.bodyAsText())
        assertEquals("GetClusterHealth", problem.operation)
    }

    @Test
    fun getsClusterStatistics() = testApplication {
        garageApp(
            MockEngine {
                respond(
                    """{"freeform":"Storage nodes:\n","dataAvail":966453747712,"metadataAvail":966453747712,
                        "incompleteAvailInfo":false,"bucketCount":1,"totalObjectCount":0,"totalObjectBytes":0}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        val response = client.get("/api/cluster/statistics") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val statistics = GarageJson.decodeFromString<ClusterStatistics>(response.bodyAsText())
        assertEquals(966453747712, statistics.dataAvail)
    }

    @Test
    fun connectsNodesAndPairsResultsWithRequestOrder() = testApplication {
        var sent = ""
        garageApp(
            MockEngine { request ->
                sent = (request.body as io.ktor.http.content.TextContent).text
                respond(
                    """[{"success":true,"error":null},{"success":false,"error":"connection refused"}]""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        val response = client.post("/api/cluster/connect") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"nodes":["n1@a:3901","n2@b:3901"]}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        // Garage はトップレベルが文字列配列の本文を要求する
        assertEquals("""["n1@a:3901","n2@b:3901"]""", sent)
        val results = GarageJson.decodeFromString<List<ConnectNodeResult>>(response.bodyAsText())
        assertEquals("n1@a:3901", results[0].node)
        assertTrue(results[0].success)
        assertEquals("n2@b:3901", results[1].node)
        assertFalse(results[1].success)
        assertEquals("connection refused", results[1].error)
    }

    @Test
    fun rejectsEmptyConnectRequest() = testApplication {
        garageApp(MockEngine { respond("[]", HttpStatusCode.OK, jsonHeaders) })

        val response = client.post("/api/cluster/connect") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"nodes":[]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
