package net.brightroom.garage.shared.api

import kotlinx.serialization.json.Json
import net.brightroom.garage.shared.model.garage.LayoutParameters
import net.brightroom.garage.shared.model.garage.NodeRoleChange
import net.brightroom.garage.shared.model.garage.ZoneRedundancy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClusterRequestsTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun encodesStageRolesRequest() {
        val request = StageRolesRequest(
            roles = listOf(NodeRoleChange.Assign(id = "abc", zone = "dc1", tags = listOf("ssd"), capacity = 1024)),
            parameters = LayoutParameters(ZoneRedundancy.AtLeast(2)),
        )

        assertEquals(
            """{"roles":[{"id":"abc","zone":"dc1","tags":["ssd"],"capacity":1024}],""" +
                """"parameters":{"zoneRedundancy":{"atLeast":2}}}""",
            json.encodeToString(request),
        )
    }

    @Test
    fun omitsParametersWhenNotStaged() {
        val request = StageRolesRequest(roles = listOf(NodeRoleChange.Remove("abc")))

        assertEquals("""{"roles":[{"id":"abc","remove":true}]}""", json.encodeToString(request))
    }

    @Test
    fun decodesRepairRequestWithScrubCommand() {
        val request = json.decodeFromString<RepairRequest>("""{"repairType":"scrub","scrubCommand":"start"}""")

        assertEquals("scrub", request.repairType)
        assertEquals("start", request.scrubCommand)
    }

    @Test
    fun retryResyncDefaultsToNothing() {
        val request = json.decodeFromString<RetryResyncRequest>("{}")

        assertEquals(false, request.all)
        assertTrue(request.blockHashes.isEmpty())
    }

    @Test
    fun nodeActionOutcomeSeparatesFailures() {
        val outcome = json.decodeFromString<NodeActionOutcome>(
            """{"ok":["node-a"],"failed":{"node-b":"connection refused"}}""",
        )

        assertEquals(listOf("node-a"), outcome.ok)
        assertEquals("connection refused", outcome.failed.getValue("node-b"))
    }
}
