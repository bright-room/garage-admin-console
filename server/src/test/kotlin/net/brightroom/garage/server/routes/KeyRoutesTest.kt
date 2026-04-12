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

class KeyRoutesTest {

    @Test
    fun listKeys() = testApplication {
        val adminClient = createMockAdminClient { request ->
            assertEquals("/v2/ListKeys", request.url.encodedPath)
            respond(
                content = """[]""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        install(ContentNegotiation) { json() }
        routing {
            route("/api") { keyRoutes(adminClient) }
        }

        client.get("/api/keys").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun createKey() = testApplication {
        val adminClient = createMockAdminClient { request ->
            assertEquals("/v2/CreateKey", request.url.encodedPath)
            assertEquals(HttpMethod.Post, request.method)
            respond(
                content = """{"accessKeyId":"GK123","name":"new-key"}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        install(ContentNegotiation) { json() }
        routing {
            route("/api") { keyRoutes(adminClient) }
        }

        client.post("/api/keys") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"new-key"}""")
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun getKeyById() = testApplication {
        val adminClient = createMockAdminClient { request ->
            assertEquals("/v2/GetKeyInfo", request.url.encodedPath)
            assertEquals("GK123", request.url.parameters["id"])
            assertEquals("true", request.url.parameters["showSecretKey"])
            respond(
                content = """{"accessKeyId":"GK123"}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        install(ContentNegotiation) { json() }
        routing {
            route("/api") { keyRoutes(adminClient) }
        }

        client.get("/api/keys/GK123?showSecretKey=true").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun deleteKey() = testApplication {
        val adminClient = createMockAdminClient { request ->
            assertEquals("/v2/DeleteKey", request.url.encodedPath)
            assertEquals(HttpMethod.Post, request.method)
            respond(
                content = """{}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        install(ContentNegotiation) { json() }
        routing {
            route("/api") { keyRoutes(adminClient) }
        }

        client.delete("/api/keys/GK123").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun importKey() = testApplication {
        val adminClient = createMockAdminClient { request ->
            assertEquals("/v2/ImportKey", request.url.encodedPath)
            respond(
                content = """{}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        install(ContentNegotiation) { json() }
        routing {
            route("/api") { keyRoutes(adminClient) }
        }

        client.post("/api/keys/import") {
            contentType(ContentType.Application.Json)
            setBody("""{"accessKeyId":"GK123","secretAccessKey":"secret"}""")
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }
}
