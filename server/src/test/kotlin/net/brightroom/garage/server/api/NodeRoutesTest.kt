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
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import kotlinx.serialization.builtins.serializer
import net.brightroom.garage.server.garageApp
import net.brightroom.garage.server.plugins.GarageJson
import net.brightroom.garage.shared.api.NodeActionOutcome
import net.brightroom.garage.shared.model.garage.MultiResponse
import net.brightroom.garage.shared.model.garage.NodeInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class NodeRoutesTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    @Test
    fun getsNodeInfoForAllNodes() = testApplication {
        var node: String? = null
        garageApp(
            MockEngine { request ->
                node = request.url.parameters["node"]
                respond(
                    """{"success":{"n1":{"nodeId":"n1","hostname":"garage-a","garageVersion":"v2.3.0",
                        "garageFeatures":["sqlite"],"rustVersion":"1.91.0","dbEngine":"sqlite3"}},
                        "error":{}}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        val response = client.get("/api/nodes/info") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("*", node)
        val info = GarageJson.decodeFromString(
            MultiResponse.serializer(NodeInfo.serializer()),
            response.bodyAsText(),
        )
        assertEquals("garage-a", info.success.getValue("n1").hostname)
    }

    @Test
    fun keepsPerNodeFailures() = testApplication {
        garageApp(
            MockEngine {
                respond(
                    """{"success":{"n1":{"nodeId":"n1"}},"error":{"n2":"node is unreachable"}}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        val response = client.get("/api/nodes/info") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        val info = GarageJson.decodeFromString(
            MultiResponse.serializer(NodeInfo.serializer()),
            response.bodyAsText(),
        )
        // ノード別の失敗を潰さない（spec §7.3）
        assertEquals("node is unreachable", info.error.getValue("n2"))
    }

    @Test
    fun createsMetadataSnapshotAndReportsPerNodeOutcome() = testApplication {
        var operation = ""
        garageApp(
            MockEngine { request ->
                operation = request.url.encodedPath.substringAfterLast('/')
                respond(
                    """{"success":{"n1":null},"error":{"n2":"disk full"}}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        val response = client.post("/api/nodes/snapshot") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("CreateMetadataSnapshot", operation)
        val outcome = GarageJson.decodeFromString<NodeActionOutcome>(response.bodyAsText())
        assertEquals(listOf("n1"), outcome.ok)
        assertEquals("disk full", outcome.failed.getValue("n2"))
    }

    @Test
    fun sendsPlainStringRepairType() = testApplication {
        var sent = ""
        garageApp(
            MockEngine { request ->
                sent = (request.body as TextContent).text
                respond("""{"success":{"n1":null},"error":{}}""", HttpStatusCode.OK, jsonHeaders)
            },
        )

        val response = client.post("/api/nodes/repair") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"repairType":"blockRefs"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"repairType":"blockRefs"}""", sent)
    }

    @Test
    fun sendsScrubRepairTypeAsObject() = testApplication {
        var sent = ""
        garageApp(
            MockEngine { request ->
                sent = (request.body as TextContent).text
                respond("""{"success":{"n1":null},"error":{}}""", HttpStatusCode.OK, jsonHeaders)
            },
        )

        val response = client.post("/api/nodes/repair") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"repairType":"scrub","scrubCommand":"start"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"repairType":{"scrub":"start"}}""", sent)
    }

    @Test
    fun rejectsScrubWithoutCommand() = testApplication {
        garageApp(MockEngine { respond("""{"success":{},"error":{}}""", HttpStatusCode.OK, jsonHeaders) })

        val response = client.post("/api/nodes/repair") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"repairType":"scrub"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun rejectsUnknownRepairType() = testApplication {
        garageApp(MockEngine { respond("""{"success":{},"error":{}}""", HttpStatusCode.OK, jsonHeaders) })

        val response = client.post("/api/nodes/repair") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"repairType":"deleteEverything"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
