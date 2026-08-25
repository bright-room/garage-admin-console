package net.brightroom.garage.shared.model.garage

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LayoutModelTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /** 実機の GetClusterLayout の応答。 */
    private val liveLayout = """
        {"version":1,
         "roles":[{"id":"e97d97ad","zone":"dc1","tags":["dev"],"capacity":1073741824,
                   "storedPartitions":256,"usableCapacity":1073741824}],
         "parameters":{"zoneRedundancy":"maximum"},
         "partitionSize":4194304,
         "stagedRoleChanges":[],
         "stagedParameters":null}
    """.trimIndent()

    @Test
    fun decodesLiveLayout() {
        val layout = json.decodeFromString<ClusterLayout>(liveLayout)

        assertEquals(1, layout.version)
        assertEquals(4194304, layout.partitionSize)
        assertEquals(ZoneRedundancy.Maximum, layout.parameters?.zoneRedundancy)
        assertEquals("dc1", layout.roles.single().zone)
        assertEquals(256, layout.roles.single().storedPartitions)
        assertTrue(layout.stagedRoleChanges.isEmpty())
    }

    @Test
    fun gatewayRoleHasNoCapacity() {
        val role = json.decodeFromString<LayoutNodeRole>(
            """{"id":"abc","zone":"dc2","tags":[]}""",
        )

        assertTrue(role.isGateway)
    }

    @Test
    fun roundTripsZoneRedundancy() {
        listOf("\"maximum\"", """{"atLeast":2}""").forEach { raw ->
            val decoded = json.decodeFromString<ZoneRedundancy>(raw)

            assertEquals(raw, json.encodeToString(decoded))
        }
    }

    @Test
    fun decodesAtLeastZoneRedundancy() {
        assertEquals(ZoneRedundancy.AtLeast(3), json.decodeFromString<ZoneRedundancy>("""{"atLeast":3}"""))
    }

    @Test
    fun roundTripsStagedRoleChanges() {
        val removal = """{"id":"e97d97ad","remove":true}"""
        val assignment = """{"id":"e97d97ad","zone":"dc1","tags":["dev"],"capacity":1073741824}"""

        assertEquals(NodeRoleChange.Remove("e97d97ad"), json.decodeFromString<NodeRoleChange>(removal))
        assertEquals(removal, json.encodeToString(json.decodeFromString<NodeRoleChange>(removal)))
        assertEquals(assignment, json.encodeToString(json.decodeFromString<NodeRoleChange>(assignment)))
    }

    @Test
    fun omitsCapacityForGatewayAssignment() {
        val change: NodeRoleChange = NodeRoleChange.Assign(id = "abc", zone = "dc2", tags = emptyList())

        assertEquals("""{"id":"abc","zone":"dc2","tags":[]}""", json.encodeToString(change))
    }

    @Test
    fun decodesLayoutHistory() {
        val history = json.decodeFromString<LayoutHistory>(
            """
            {"currentVersion":1,"minAck":1,
             "versions":[{"version":1,"status":"Current","storageNodes":1,"gatewayNodes":0}],
             "updateTrackers":null}
            """.trimIndent(),
        )

        assertEquals(1, history.currentVersion)
        assertEquals(LayoutVersionStatus.CURRENT, history.versions.single().status)
    }

    @Test
    fun decodesLayoutVersionStatuses() {
        listOf(
            "Current" to LayoutVersionStatus.CURRENT,
            "Draining" to LayoutVersionStatus.DRAINING,
            "Historical" to LayoutVersionStatus.HISTORICAL,
        ).forEach { (raw, expected) ->
            assertEquals(expected, json.decodeFromString<LayoutVersionStatus>("\"$raw\""))
        }
    }

    @Test
    fun decodesComputedPreview() {
        val preview = json.decodeFromString<LayoutPreview>(
            """
            {"message":["==== COMPUTATION ====",""],
             "newLayout":$liveLayout,
             "statistics":{"replicationFactor":1,"effectiveZoneRedundancy":1,"partitionSize":4194304,
                           "previousPartitionSize":4194304,"lowPartitionSize":false,
                           "usableCapacity":1073741824,"totalCapacity":1073741824,
                           "effectiveCapacity":1073741824,"lowUsableCapacity":false,
                           "totalMovedPartitions":0,
                           "zones":[{"name":"dc1","totalReplicatedPartitions":256,"uniquePartitions":256,
                                     "totalCapacity":1073741824,"usableCapacity":1073741824}]}}
            """.trimIndent(),
        )

        val computed = assertIs<LayoutPreview.Computed>(preview)
        assertEquals(2, computed.message.size)
        assertEquals(1, computed.newLayout.version)
        assertEquals(1, computed.statistics?.replicationFactor)
        assertEquals("dc1", computed.statistics?.zones?.single()?.name)
    }

    @Test
    fun decodesFailedPreview() {
        val preview = json.decodeFromString<LayoutPreview>(
            """{"error":"Zone dc2 has no node with a positive capacity"}""",
        )

        assertEquals("Zone dc2 has no node with a positive capacity", assertIs<LayoutPreview.Failed>(preview).error)
    }

    @Test
    fun decodesClusterStatistics() {
        val statistics = json.decodeFromString<ClusterStatistics>(
            """
            {"freeform":"Storage nodes:\n","dataAvail":966453747712,"metadataAvail":966453747712,
             "incompleteAvailInfo":false,"bucketCount":1,"totalObjectCount":0,"totalObjectBytes":0}
            """.trimIndent(),
        )

        assertEquals(966453747712, statistics.dataAvail)
        assertEquals(1, statistics.bucketCount)
    }
}
