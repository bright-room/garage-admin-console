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
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import net.brightroom.garage.server.garageApp
import net.brightroom.garage.server.plugins.GarageJson
import net.brightroom.garage.shared.model.garage.ClusterLayout
import net.brightroom.garage.shared.model.garage.LayoutPreview
import net.brightroom.garage.shared.model.garage.NodeRoleChange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LayoutRoutesTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private val layoutBody = """
        {"version":1,
         "roles":[{"id":"n1","zone":"dc1","tags":["dev"],"capacity":1073741824,
                   "storedPartitions":256,"usableCapacity":1073741824}],
         "parameters":{"zoneRedundancy":"maximum"},
         "partitionSize":4194304,
         "stagedRoleChanges":[{"id":"n2","remove":true}],
         "stagedParameters":null}
    """.trimIndent()

    @Test
    fun getsLayoutWithStagedChanges() = testApplication {
        var operation = ""
        garageApp(
            MockEngine { request ->
                operation = request.url.encodedPath.substringAfterLast('/')
                respond(layoutBody, HttpStatusCode.OK, jsonHeaders)
            },
        )

        val response = client.get("/api/layout") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("GetClusterLayout", operation)
        val layout = GarageJson.decodeFromString<ClusterLayout>(response.bodyAsText())
        assertEquals(NodeRoleChange.Remove("n2"), layout.stagedRoleChanges.single())
    }

    @Test
    fun stagesRoles() = testApplication {
        var operation = ""
        garageApp(
            MockEngine { request ->
                operation = request.url.encodedPath.substringAfterLast('/')
                respond(layoutBody, HttpStatusCode.OK, jsonHeaders)
            },
        )

        val response = client.post("/api/layout/roles") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"roles":[{"id":"n2","zone":"dc2","tags":[],"capacity":512}]}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("UpdateClusterLayout", operation)
    }

    @Test
    fun rejectsStageWithNothingToChange() = testApplication {
        garageApp(MockEngine { respond(layoutBody, HttpStatusCode.OK, jsonHeaders) })

        val response = client.post("/api/layout/roles") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"roles":[]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun returnsFailedPreviewAsTwoHundred() = testApplication {
        garageApp(
            MockEngine { respond("""{"error":"no node has capacity"}""", HttpStatusCode.OK, jsonHeaders) },
        )

        val response = client.post("/api/layout/preview") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val preview = GarageJson.decodeFromString<LayoutPreview>(response.bodyAsText())
        assertEquals("no node has capacity", assertIs<LayoutPreview.Failed>(preview).error)
    }

    @Test
    fun appliesLayout() = testApplication {
        var operation = ""
        garageApp(
            MockEngine { request ->
                operation = request.url.encodedPath.substringAfterLast('/')
                respond(layoutBody, HttpStatusCode.OK, jsonHeaders)
            },
        )

        val response = client.post("/api/layout/apply") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"version":2}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ApplyClusterLayout", operation)
    }

    @Test
    fun revertsLayoutWithoutRequestBody() = testApplication {
        var operation = ""
        garageApp(
            MockEngine { request ->
                operation = request.url.encodedPath.substringAfterLast('/')
                respond(layoutBody, HttpStatusCode.OK, jsonHeaders)
            },
        )

        val response = client.post("/api/layout/revert") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("RevertClusterLayout", operation)
    }

    @Test
    fun getsHistory() = testApplication {
        var operation = ""
        garageApp(
            MockEngine { request ->
                operation = request.url.encodedPath.substringAfterLast('/')
                respond(
                    """{"currentVersion":1,"minAck":1,
                        "versions":[{"version":1,"status":"Current","storageNodes":1,"gatewayNodes":0}],
                        "updateTrackers":null}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        val response = client.get("/api/layout/history") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("GetClusterLayoutHistory", operation)
    }

    @Test
    fun skipsDeadNodes() = testApplication {
        var operation = ""
        garageApp(
            MockEngine { request ->
                operation = request.url.encodedPath.substringAfterLast('/')
                respond(
                    """{"currentVersion":2,"minAck":2,"versions":[],"updateTrackers":null}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        val response = client.post("/api/layout/skip-dead-nodes") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"version":2,"allowMissingData":false}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ClusterLayoutSkipDeadNodes", operation)
    }

    @Test
    fun requiresBearerToken() = testApplication {
        garageApp(MockEngine { respond(layoutBody, HttpStatusCode.OK, jsonHeaders) })

        val response = client.get("/api/layout")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}
