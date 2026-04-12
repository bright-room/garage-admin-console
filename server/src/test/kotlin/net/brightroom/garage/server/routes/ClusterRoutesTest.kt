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

class ClusterRoutesTest {

    @Test
    fun getClusterStatus() = testApplication {
        val adminClient = createMockAdminClient { request ->
            assertEquals("/v2/GetClusterStatus", request.url.encodedPath)
            respond(
                content = """{"layoutVersion":1,"nodes":[]}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        install(ContentNegotiation) { json() }
        routing {
            route("/api") { clusterRoutes(adminClient) }
        }

        client.get("/api/cluster/status").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun getClusterHealth() = testApplication {
        val adminClient = createMockAdminClient { request ->
            assertEquals("/v2/GetClusterHealth", request.url.encodedPath)
            respond(
                content = """{"status":"healthy","knownNodes":3,"connectedNodes":3,"storageNodes":3,"storageNodesUp":3,"partitions":256,"partitionsQuorum":256,"partitionsAllOk":256}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        install(ContentNegotiation) { json() }
        routing {
            route("/api") { clusterRoutes(adminClient) }
        }

        client.get("/api/cluster/health").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun getClusterStatistics() = testApplication {
        val adminClient = createMockAdminClient { request ->
            assertEquals("/v2/GetClusterStatistics", request.url.encodedPath)
            respond(
                content = """{}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        install(ContentNegotiation) { json() }
        routing {
            route("/api") { clusterRoutes(adminClient) }
        }

        client.get("/api/cluster/statistics").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun postConnectClusterNodes() = testApplication {
        val adminClient = createMockAdminClient { request ->
            assertEquals("/v2/ConnectClusterNodes", request.url.encodedPath)
            assertEquals(HttpMethod.Post, request.method)
            respond(
                content = """{"success":{},"error":{}}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        install(ContentNegotiation) { json() }
        routing {
            route("/api") { clusterRoutes(adminClient) }
        }

        client.post("/api/cluster/connect") {
            contentType(ContentType.Application.Json)
            setBody("""{"addresses":["10.0.0.1:3901"]}""")
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun adminClientReceivesBearerToken() = testApplication {
        val adminClient = createMockAdminClient { request ->
            assertEquals("Bearer test-token", request.headers[HttpHeaders.Authorization])
            respond(
                content = """{}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        install(ContentNegotiation) { json() }
        routing {
            route("/api") { clusterRoutes(adminClient) }
        }

        client.get("/api/cluster/status").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }
}
