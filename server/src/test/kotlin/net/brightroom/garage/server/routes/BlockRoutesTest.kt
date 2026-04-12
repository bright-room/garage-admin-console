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

class BlockRoutesTest {

    @Test
    fun listBlockErrors() = testApplication {
        val adminClient = createMockAdminClient { request ->
            assertEquals("/v2/ListBlockErrors", request.url.encodedPath)
            respond(
                content = """[]""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        install(ContentNegotiation) { json() }
        routing {
            route("/api") { blockRoutes(adminClient) }
        }

        client.get("/api/blocks/errors").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun listBlockErrorsWithNodeParam() = testApplication {
        val adminClient = createMockAdminClient { request ->
            assertEquals("/v2/ListBlockErrors", request.url.encodedPath)
            assertEquals("node-1", request.url.parameters["node"])
            respond(
                content = """[]""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        install(ContentNegotiation) { json() }
        routing {
            route("/api") { blockRoutes(adminClient) }
        }

        client.get("/api/blocks/errors?node=node-1").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun getBlockInfo() = testApplication {
        val adminClient = createMockAdminClient { request ->
            assertEquals("/v2/GetBlockInfo", request.url.encodedPath)
            respond(
                content = """{}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        install(ContentNegotiation) { json() }
        routing {
            route("/api") { blockRoutes(adminClient) }
        }

        client.post("/api/blocks/info") {
            contentType(ContentType.Application.Json)
            setBody("""{"blockHash":"abc123"}""")
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun purgeBlocks() = testApplication {
        val adminClient = createMockAdminClient { request ->
            assertEquals("/v2/PurgeBlocks", request.url.encodedPath)
            respond(
                content = """{"blocksPurged":0,"objectsDeleted":0,"uploadsDeleted":0,"versionsDeleted":0,"blockRefsPurged":0}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        install(ContentNegotiation) { json() }
        routing {
            route("/api") { blockRoutes(adminClient) }
        }

        client.post("/api/blocks/purge") {
            contentType(ContentType.Application.Json)
            setBody("""{}""")
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }
}
