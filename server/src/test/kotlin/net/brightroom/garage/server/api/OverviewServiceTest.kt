package net.brightroom.garage.server.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import net.brightroom.garage.server.garage.GarageAdminClient
import net.brightroom.garage.server.garage.GarageException
import net.brightroom.garage.shared.api.Section
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class OverviewServiceTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private val healthBody = """
        {"status":"healthy","knownNodes":2,"connectedNodes":2,"storageNodes":2,
         "storageNodesUp":2,"partitions":256,"partitionsQuorum":256,"partitionsAllOk":256}
    """.trimIndent()

    private val statusBody = """
        {"layoutVersion":7,"nodes":[
          {"id":"abc","isUp":true,"draining":false,"hostname":"node-a",
           "role":{"zone":"dc1","tags":[],"capacity":1000},
           "dataPartition":{"available":400,"total":1000}}
        ]}
    """.trimIndent()

    private val layoutBody = """
        {"version":7,"roles":[],"parameters":{"zoneRedundancy":"maximum"},"partitionSize":1024,
         "stagedRoleChanges":[{"id":"abc"},{"id":"def"}]}
    """.trimIndent()

    private val blockErrorsBody = """
        {"success":{"node-a":[
           {"blockHash":"ff","refcount":1,"errorCount":2,"lastTrySecsAgo":5,"nextTryInSecs":60}
         ],"node-b":[]},"error":{}}
    """.trimIndent()

    /** operation 名ごとに応答を差し替えられるモック。 */
    private fun engineOf(responses: Map<String, Pair<String, HttpStatusCode>>) = MockEngine { request ->
        val operation = request.url.encodedPath.substringAfterLast('/')
        val (body, status) = responses[operation]
            ?: error("unexpected operation: $operation")
        respond(body, status, jsonHeaders)
    }

    private fun serviceOf(responses: Map<String, Pair<String, HttpStatusCode>>) =
        OverviewService(GarageAdminClient("http://garage.test:3903", engineOf(responses)))

    private val allOk = mapOf(
        "GetClusterHealth" to (healthBody to HttpStatusCode.OK),
        "GetClusterStatus" to (statusBody to HttpStatusCode.OK),
        "GetClusterLayout" to (layoutBody to HttpStatusCode.OK),
        "ListBuckets" to ("""[{"id":"b1"},{"id":"b2"}]""" to HttpStatusCode.OK),
        "ListKeys" to ("""[{"id":"k1"}]""" to HttpStatusCode.OK),
        "ListBlockErrors" to (blockErrorsBody to HttpStatusCode.OK),
    )

    @Test
    fun loadsEverySectionWhenAllOperationsSucceed() = runTest {
        val overview = serviceOf(allOk).build("tok")

        assertEquals("healthy", overview.health.dataOrNull()?.status)
        assertEquals(1, overview.nodes.dataOrNull()?.size)
        assertEquals("node-a", overview.nodes.dataOrNull()?.first()?.hostname)
        assertEquals("dc1", overview.nodes.dataOrNull()?.first()?.zone)
        assertEquals(7L, overview.layout.dataOrNull()?.version)
        assertEquals(2, overview.layout.dataOrNull()?.stagedChanges)
        assertEquals(2, overview.storage.dataOrNull()?.buckets)
        assertEquals(1, overview.storage.dataOrNull()?.keys)
        assertEquals(1, overview.blockErrors.dataOrNull())
    }

    @Test
    fun deniesOnlyTheForbiddenSection() = runTest {
        val overview = serviceOf(
            allOk + ("ListBlockErrors" to ("forbidden" to HttpStatusCode.Forbidden)),
        ).build("tok")

        val denied = assertIs<Section.Denied>(overview.blockErrors)
        assertEquals("ListBlockErrors", denied.operation)
        assertEquals("healthy", overview.health.dataOrNull()?.status)
        assertEquals(2, overview.storage.dataOrNull()?.buckets)
    }

    @Test
    fun deniesStorageWhenEitherListIsForbidden() = runTest {
        val overview = serviceOf(
            allOk + ("ListKeys" to ("forbidden" to HttpStatusCode.Forbidden)),
        ).build("tok")

        val denied = assertIs<Section.Denied>(overview.storage)
        assertEquals("ListKeys", denied.operation)
    }

    @Test
    fun failsOnlyTheBrokenSection() = runTest {
        val overview = serviceOf(
            allOk + ("GetClusterLayout" to ("boom" to HttpStatusCode.InternalServerError)),
        ).build("tok")

        val failed = assertIs<Section.Failed>(overview.layout)
        assertEquals("boom", failed.message)
        assertEquals("healthy", overview.health.dataOrNull()?.status)
    }

    @Test
    fun unauthorizedFailsTheWholeRequest() = runTest {
        val failure = assertFailsWith<GarageException> {
            serviceOf(
                allOk + ("GetClusterHealth" to ("invalid token" to HttpStatusCode.Unauthorized)),
            ).build("tok")
        }

        assertEquals(HttpStatusCode.Unauthorized, failure.status)
    }
}
