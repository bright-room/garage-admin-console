package net.brightroom.garage.server.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import net.brightroom.garage.server.garageApp
import net.brightroom.garage.server.plugins.GarageJson
import net.brightroom.garage.shared.api.NodeActionOutcome
import net.brightroom.garage.shared.model.garage.MultiResponse
import net.brightroom.garage.shared.model.garage.WorkerInfo
import net.brightroom.garage.shared.model.garage.WorkerState
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkerRoutesTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private val workersBody = """
        {"success":{"n1":[
          {"id":9,"name":"Block scrub worker","state":"idle","errors":0,"consecutiveErrors":0,
           "lastError":null,"tranquility":4,"progress":null,"queueLength":null,
           "persistentErrors":0,"freeform":["Last scrub completed"]},
          {"id":1,"name":"Block resync worker #1","state":{"throttled":{"durationSecs":0.5}},
           "errors":2,"consecutiveErrors":1,"lastError":{"message":"timeout","secsAgo":30},
           "tranquility":2,"progress":"12%","queueLength":4,"persistentErrors":0,"freeform":[]}
        ]},"error":{}}
    """.trimIndent()

    @Test
    fun listsWorkersOverGetWhileCallingGaragePost() = testApplication {
        var method: HttpMethod? = null
        var node: String? = null
        var body = ""
        garageApp(
            MockEngine { request ->
                method = request.method
                node = request.url.parameters["node"]
                body = (request.body as TextContent).text
                respond(workersBody, HttpStatusCode.OK, jsonHeaders)
            },
        )

        val response = client.get("/api/workers") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        // コンソールは GET、Garage 側は POST
        assertEquals(HttpMethod.Post, method)
        assertEquals("*", node)
        assertEquals("{}", body)

        val workers = GarageJson.decodeFromString(
            MultiResponse.serializer(ListSerializer(WorkerInfo.serializer())),
            response.bodyAsText(),
        )
        assertEquals(2, workers.success.getValue("n1").size)
        assertEquals(WorkerState.Idle, workers.success.getValue("n1")[0].state)
        assertEquals(WorkerState.Throttled(0.5), workers.success.getValue("n1")[1].state)
    }

    @Test
    fun getsSingleWorker() = testApplication {
        var sent = ""
        garageApp(
            MockEngine { request ->
                sent = (request.body as TextContent).text
                respond(
                    """{"success":{"n1":{"id":9,"name":"Block scrub worker","state":"done",
                        "errors":0,"consecutiveErrors":0,"freeform":[]}},"error":{}}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        val response = client.get("/api/workers/9") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"id":9}""", sent)
    }

    @Test
    fun rejectsNonNumericWorkerId() = testApplication {
        garageApp(MockEngine { respond("""{"success":{},"error":{}}""", HttpStatusCode.OK, jsonHeaders) })

        val response = client.get("/api/workers/scrub") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun getsWorkerVariables() = testApplication {
        var operation = ""
        garageApp(
            MockEngine { request ->
                operation = request.url.encodedPath.substringAfterLast('/')
                respond(
                    """{"success":{"n1":{"resync-worker-count":"1","scrub-tranquility":"4"}},"error":{}}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        val response = client.get("/api/workers/variables") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("GetWorkerVariable", operation)
        val variables = GarageJson.decodeFromString(
            MultiResponse.serializer(MapSerializer(String.serializer(), String.serializer())),
            response.bodyAsText(),
        )
        assertEquals("4", variables.success.getValue("n1").getValue("scrub-tranquility"))
    }

    @Test
    fun setsWorkerVariable() = testApplication {
        var sent = ""
        garageApp(
            MockEngine { request ->
                sent = (request.body as TextContent).text
                respond("""{"success":{"n1":null},"error":{}}""", HttpStatusCode.OK, jsonHeaders)
            },
        )

        val response = client.put("/api/workers/variables") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"variable":"scrub-tranquility","value":"6"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"variable":"scrub-tranquility","value":"6"}""", sent)
        val outcome = GarageJson.decodeFromString<NodeActionOutcome>(response.bodyAsText())
        assertEquals(listOf("n1"), outcome.ok)
    }

    @Test
    fun rejectsBlankVariableName() = testApplication {
        garageApp(MockEngine { respond("""{"success":{},"error":{}}""", HttpStatusCode.OK, jsonHeaders) })

        val response = client.put("/api/workers/variables") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"variable":"","value":"6"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
