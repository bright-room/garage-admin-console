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

class AdminTokenRoutesTest {

    @Test
    fun listAdminTokens() = testApplication {
        val adminClient = createMockAdminClient { request ->
            assertEquals("/v2/ListAdminTokens", request.url.encodedPath)
            respond(
                content = """[]""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        install(ContentNegotiation) { json() }
        routing {
            route("/api") { adminTokenRoutes(adminClient) }
        }

        client.get("/api/admin-tokens").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun createAdminToken() = testApplication {
        val adminClient = createMockAdminClient { request ->
            assertEquals("/v2/CreateAdminToken", request.url.encodedPath)
            assertEquals(HttpMethod.Post, request.method)
            respond(
                content = """{"id":"new-token","name":"ci","secretToken":"secret"}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        install(ContentNegotiation) { json() }
        routing {
            route("/api") { adminTokenRoutes(adminClient) }
        }

        client.post("/api/admin-tokens") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"ci","scope":["Admin"]}""")
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun getCurrentAdminToken() = testApplication {
        val adminClient = createMockAdminClient { request ->
            assertEquals("/v2/GetCurrentAdminTokenInfo", request.url.encodedPath)
            respond(
                content = """{"name":"current","expired":false,"scope":["Admin"]}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        install(ContentNegotiation) { json() }
        routing {
            route("/api") { adminTokenRoutes(adminClient) }
        }

        client.get("/api/admin-tokens/current").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun getAdminTokenById() = testApplication {
        val adminClient = createMockAdminClient { request ->
            assertEquals("/v2/GetAdminTokenInfo", request.url.encodedPath)
            assertEquals("token-123", request.url.parameters["id"])
            respond(
                content = """{}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        install(ContentNegotiation) { json() }
        routing {
            route("/api") { adminTokenRoutes(adminClient) }
        }

        client.get("/api/admin-tokens/token-123").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun deleteAdminToken() = testApplication {
        val adminClient = createMockAdminClient { request ->
            assertEquals("/v2/DeleteAdminToken", request.url.encodedPath)
            assertEquals(HttpMethod.Post, request.method)
            respond(
                content = """{}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        install(ContentNegotiation) { json() }
        routing {
            route("/api") { adminTokenRoutes(adminClient) }
        }

        client.delete("/api/admin-tokens/token-123").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }
}
