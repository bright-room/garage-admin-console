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

class LayoutRoutesTest {

    @Test
    fun getClusterLayout() = testApplication {
        val adminClient = createMockAdminClient { request ->
            assertEquals("/v2/GetClusterLayout", request.url.encodedPath)
            respond(
                content = """{}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        install(ContentNegotiation) { json() }
        routing {
            route("/api") { layoutRoutes(adminClient) }
        }

        client.get("/api/layout").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun updateClusterLayout() = testApplication {
        val adminClient = createMockAdminClient { request ->
            assertEquals("/v2/UpdateClusterLayout", request.url.encodedPath)
            assertEquals(HttpMethod.Post, request.method)
            respond(
                content = """{}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        install(ContentNegotiation) { json() }
        routing {
            route("/api") { layoutRoutes(adminClient) }
        }

        client.post("/api/layout/update") {
            contentType(ContentType.Application.Json)
            setBody("""{}""")
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun applyClusterLayout() = testApplication {
        val adminClient = createMockAdminClient { request ->
            assertEquals("/v2/ApplyClusterLayout", request.url.encodedPath)
            respond(
                content = """{}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        install(ContentNegotiation) { json() }
        routing {
            route("/api") { layoutRoutes(adminClient) }
        }

        client.post("/api/layout/apply") {
            contentType(ContentType.Application.Json)
            setBody("""{"version":1}""")
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun revertClusterLayout() = testApplication {
        val adminClient = createMockAdminClient { request ->
            assertEquals("/v2/RevertClusterLayout", request.url.encodedPath)
            respond(
                content = """{}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        install(ContentNegotiation) { json() }
        routing {
            route("/api") { layoutRoutes(adminClient) }
        }

        client.post("/api/layout/revert") {
            contentType(ContentType.Application.Json)
            setBody("")
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun getLayoutHistory() = testApplication {
        val adminClient = createMockAdminClient { request ->
            assertEquals("/v2/GetClusterLayoutHistory", request.url.encodedPath)
            respond(
                content = """{}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        install(ContentNegotiation) { json() }
        routing {
            route("/api") { layoutRoutes(adminClient) }
        }

        client.get("/api/layout/history").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }
}
