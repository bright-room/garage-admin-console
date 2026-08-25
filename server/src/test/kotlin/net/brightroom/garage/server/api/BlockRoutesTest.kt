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
import kotlinx.serialization.builtins.ListSerializer
import net.brightroom.garage.server.garageApp
import net.brightroom.garage.server.plugins.GarageJson
import net.brightroom.garage.shared.api.NodeActionOutcome
import net.brightroom.garage.shared.model.garage.BlockError
import net.brightroom.garage.shared.model.garage.MultiResponse
import kotlin.test.Test
import kotlin.test.assertEquals

class BlockRoutesTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    @Test
    fun listsBlockErrors() = testApplication {
        var operation = ""
        var node: String? = null
        garageApp(
            MockEngine { request ->
                operation = request.url.encodedPath.substringAfterLast('/')
                node = request.url.parameters["node"]
                respond(
                    """{"success":{"n1":[{"blockHash":"abcd","refcount":2,"errorCount":5,
                        "lastTrySecsAgo":60,"nextTryInSecs":120}]},"error":{}}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        val response = client.get("/api/blocks/errors") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ListBlockErrors", operation)
        assertEquals("*", node)
        val errors = GarageJson.decodeFromString(
            MultiResponse.serializer(ListSerializer(BlockError.serializer())),
            response.bodyAsText(),
        )
        assertEquals("abcd", errors.success.getValue("n1").single().blockHash)
    }

    @Test
    fun getsBlockInfo() = testApplication {
        var sent = ""
        garageApp(
            MockEngine { request ->
                sent = (request.body as TextContent).text
                respond(
                    """{"success":{"n1":{"blockHash":"abcd","refcount":1,"versions":[]}},"error":{}}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        val response = client.get("/api/blocks/abcd") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"blockHash":"abcd"}""", sent)
    }

    @Test
    fun retriesSingleBlock() = testApplication {
        var sent = ""
        garageApp(
            MockEngine { request ->
                sent = (request.body as TextContent).text
                respond("""{"success":{"n1":null},"error":{}}""", HttpStatusCode.OK, jsonHeaders)
            },
        )

        val response = client.post("/api/blocks/abcd/retry-resync") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"blockHashes":["abcd"]}""", sent)
    }

    @Test
    fun retriesEverything() = testApplication {
        var sent = ""
        garageApp(
            MockEngine { request ->
                sent = (request.body as TextContent).text
                respond("""{"success":{"n1":null},"error":{}}""", HttpStatusCode.OK, jsonHeaders)
            },
        )

        val response = client.post("/api/blocks/retry-resync") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"all":true}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"all":true}""", sent)
    }

    @Test
    fun rejectsRetryWithNothingToDo() = testApplication {
        garageApp(MockEngine { respond("""{"success":{},"error":{}}""", HttpStatusCode.OK, jsonHeaders) })

        val response = client.post("/api/blocks/retry-resync") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"all":false,"blockHashes":[]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun purgesBlocksWithTopLevelArrayBody() = testApplication {
        var sent = ""
        garageApp(
            MockEngine { request ->
                sent = (request.body as TextContent).text
                respond("""{"success":{"n1":null},"error":{"n2":"busy"}}""", HttpStatusCode.OK, jsonHeaders)
            },
        )

        val response = client.post("/api/blocks/purge") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"blockHashes":["abcd","efgh"]}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        // Garage はトップレベルが配列の本文を要求する
        assertEquals("""["abcd","efgh"]""", sent)
        val outcome = GarageJson.decodeFromString<NodeActionOutcome>(response.bodyAsText())
        assertEquals(listOf("n1"), outcome.ok)
        assertEquals("busy", outcome.failed.getValue("n2"))
    }

    @Test
    fun rejectsEmptyPurge() = testApplication {
        garageApp(MockEngine { respond("""{"success":{},"error":{}}""", HttpStatusCode.OK, jsonHeaders) })

        val response = client.post("/api/blocks/purge") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"blockHashes":[]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
