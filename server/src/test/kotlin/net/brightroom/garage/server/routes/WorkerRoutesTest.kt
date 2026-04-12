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

class WorkerRoutesTest {

    @Test
    fun listWorkers() = testApplication {
        val adminClient = createMockAdminClient { request ->
            assertEquals("/v2/ListWorkers", request.url.encodedPath)
            respond(
                content = """[]""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        install(ContentNegotiation) { json() }
        routing {
            route("/api") { workerRoutes(adminClient) }
        }

        client.post("/api/workers/list") {
            contentType(ContentType.Application.Json)
            setBody("""{}""")
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun getWorkerInfo() = testApplication {
        val adminClient = createMockAdminClient { request ->
            assertEquals("/v2/GetWorkerInfo", request.url.encodedPath)
            respond(
                content = """{}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        install(ContentNegotiation) { json() }
        routing {
            route("/api") { workerRoutes(adminClient) }
        }

        client.post("/api/workers/info") {
            contentType(ContentType.Application.Json)
            setBody("""{}""")
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun setWorkerVariable() = testApplication {
        val adminClient = createMockAdminClient { request ->
            assertEquals("/v2/SetWorkerVariable", request.url.encodedPath)
            respond(
                content = """{"variable":"block_write_timeout","value":"30"}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        install(ContentNegotiation) { json() }
        routing {
            route("/api") { workerRoutes(adminClient) }
        }

        client.post("/api/workers/variable/set") {
            contentType(ContentType.Application.Json)
            setBody("""{"variable":"block_write_timeout","value":"30"}""")
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }
}
