package net.brightroom.garage.server.routes

import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import net.brightroom.garage.server.createMockAdminClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NodeRoutesTest {

    @Test
    fun getNodeInfoWithoutNodeParam() = testApplication {
        val adminClient = createMockAdminClient { request ->
            assertEquals("/v2/GetNodeInfo", request.url.encodedPath)
            assertTrue(request.url.parameters["node"] == null)
            respond(
                content = """{}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        install(ContentNegotiation) { json() }
        routing {
            route("/api") { nodeRoutes(adminClient) }
        }

        client.get("/api/nodes/info").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun getNodeInfoWithNodeParam() = testApplication {
        val adminClient = createMockAdminClient { request ->
            assertEquals("/v2/GetNodeInfo", request.url.encodedPath)
            assertEquals("node-1", request.url.parameters["node"])
            respond(
                content = """{}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        install(ContentNegotiation) { json() }
        routing {
            route("/api") { nodeRoutes(adminClient) }
        }

        client.get("/api/nodes/info?node=node-1").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun getNodeStatistics() = testApplication {
        val adminClient = createMockAdminClient { request ->
            assertEquals("/v2/GetNodeStatistics", request.url.encodedPath)
            respond(
                content = """{"freeform":"stats"}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        install(ContentNegotiation) { json() }
        routing {
            route("/api") { nodeRoutes(adminClient) }
        }

        client.get("/api/nodes/statistics").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun createMetadataSnapshot() = testApplication {
        val adminClient = createMockAdminClient { request ->
            assertEquals("/v2/CreateMetadataSnapshot", request.url.encodedPath)
            assertEquals(HttpMethod.Post, request.method)
            respond(
                content = """{}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        install(ContentNegotiation) { json() }
        routing {
            route("/api") { nodeRoutes(adminClient) }
        }

        client.post("/api/nodes/snapshot") {
            contentType(ContentType.Application.Json)
            setBody("""{}""")
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }
}
