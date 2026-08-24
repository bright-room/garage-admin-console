package net.brightroom.garage.server.garage

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import net.brightroom.garage.shared.api.SkipDeadNodesRequest
import net.brightroom.garage.shared.api.StageRolesRequest
import net.brightroom.garage.shared.model.garage.LayoutParameters
import net.brightroom.garage.shared.model.garage.LayoutPreview
import net.brightroom.garage.shared.model.garage.LayoutVersionStatus
import net.brightroom.garage.shared.model.garage.NodeRoleChange
import net.brightroom.garage.shared.model.garage.ZoneRedundancy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LayoutOperationsTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private val layoutBody = """
        {"version":1,
         "roles":[{"id":"n1","zone":"dc1","tags":["dev"],"capacity":1073741824,
                   "storedPartitions":256,"usableCapacity":1073741824}],
         "parameters":{"zoneRedundancy":"maximum"},
         "partitionSize":4194304,"stagedRoleChanges":[],"stagedParameters":null}
    """.trimIndent()

    private fun clientOf(engine: MockEngine) = GarageAdminClient("http://garage.test:3903", engine)

    @Test
    fun getsLayout() = runTest {
        val client = clientOf(MockEngine { respond(layoutBody, HttpStatusCode.OK, jsonHeaders) })

        val layout = client.getLayout("tok")

        assertEquals(1, layout.version)
        assertEquals(ZoneRedundancy.Maximum, layout.parameters?.zoneRedundancy)
    }

    @Test
    fun stagesRolesWithTypedBody() = runTest {
        var sent = ""
        var method: HttpMethod? = null
        val client = clientOf(
            MockEngine { request ->
                method = request.method
                sent = (request.body as TextContent).text
                respond(layoutBody, HttpStatusCode.OK, jsonHeaders)
            },
        )

        client.stageRoles(
            "tok",
            StageRolesRequest(
                roles = listOf(NodeRoleChange.Assign(id = "n2", zone = "dc2", tags = emptyList(), capacity = 512)),
                parameters = LayoutParameters(ZoneRedundancy.AtLeast(2)),
            ),
        )

        assertEquals(HttpMethod.Post, method)
        assertEquals(
            """{"roles":[{"id":"n2","zone":"dc2","tags":[],"capacity":512}],""" +
                """"parameters":{"zoneRedundancy":{"atLeast":2}}}""",
            sent,
        )
    }

    @Test
    fun stagesRemovalWithoutParameters() = runTest {
        var sent = ""
        val client = clientOf(
            MockEngine { request ->
                sent = (request.body as TextContent).text
                respond(layoutBody, HttpStatusCode.OK, jsonHeaders)
            },
        )

        client.stageRoles("tok", StageRolesRequest(roles = listOf(NodeRoleChange.Remove("n2"))))

        // parameters を省略すると zoneRedundancy は変更されない
        assertEquals("""{"roles":[{"id":"n2","remove":true}]}""", sent)
    }

    @Test
    fun decodesComputedPreview() = runTest {
        val client = clientOf(
            MockEngine {
                respond(
                    """{"message":["==== COMPUTATION ===="],"newLayout":$layoutBody}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        val preview = client.previewLayout("tok")

        assertEquals(1, assertIs<LayoutPreview.Computed>(preview).newLayout.version)
    }

    @Test
    fun decodesFailedPreviewAsSuccessfulResponse() = runTest {
        val client = clientOf(
            MockEngine { respond("""{"error":"no node has capacity"}""", HttpStatusCode.OK, jsonHeaders) },
        )

        val preview = client.previewLayout("tok")

        // 計算できなかったことは 200 の中で表される。例外にしない
        assertEquals("no node has capacity", assertIs<LayoutPreview.Failed>(preview).error)
    }

    @Test
    fun appliesLayoutWithVersion() = runTest {
        var sent = ""
        val client = clientOf(
            MockEngine { request ->
                sent = (request.body as TextContent).text
                respond(layoutBody, HttpStatusCode.OK, jsonHeaders)
            },
        )

        client.applyLayout("tok", 2)

        assertEquals("""{"version":2}""", sent)
    }

    @Test
    fun revertsLayoutWithoutBody() = runTest {
        var operation = ""
        var method: HttpMethod? = null
        val client = clientOf(
            MockEngine { request ->
                operation = request.url.encodedPath.substringAfterLast('/')
                method = request.method
                respond(layoutBody, HttpStatusCode.OK, jsonHeaders)
            },
        )

        client.revertLayout("tok")

        assertEquals(HttpMethod.Post, method)
        assertEquals("RevertClusterLayout", operation)
    }

    @Test
    fun getsLayoutHistory() = runTest {
        val client = clientOf(
            MockEngine {
                respond(
                    """{"currentVersion":2,"minAck":1,
                        "versions":[{"version":2,"status":"Current","storageNodes":1,"gatewayNodes":0},
                                    {"version":1,"status":"Draining","storageNodes":1,"gatewayNodes":0}],
                        "updateTrackers":null}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        val history = client.getLayoutHistory("tok")

        assertEquals(2, history.currentVersion)
        assertEquals(LayoutVersionStatus.DRAINING, history.versions[1].status)
    }

    @Test
    fun skipsDeadNodes() = runTest {
        var sent = ""
        val client = clientOf(
            MockEngine { request ->
                sent = (request.body as TextContent).text
                respond(
                    """{"currentVersion":3,"minAck":3,"versions":[],"updateTrackers":null}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        client.skipDeadNodes("tok", SkipDeadNodesRequest(version = 3, allowMissingData = true))

        assertEquals("""{"version":3,"allowMissingData":true}""", sent)
    }

    @Test
    fun propagatesForbidden() = runTest {
        val client = clientOf(MockEngine { respond("insufficient scope", HttpStatusCode.Forbidden) })

        val failure = kotlin.runCatching { client.getLayout("tok") }.exceptionOrNull()

        assertIs<GarageException>(failure)
        assertEquals(HttpStatusCode.Forbidden, failure.status)
        assertEquals("GetClusterLayout", failure.operation)
    }
}
