package net.brightroom.garage.shared.model.layout

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClusterLayoutTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodeClusterLayout() {
        val raw = """
            {
                "version": 3,
                "roles": [
                    {
                        "id": "node-1",
                        "zone": "dc1",
                        "tags": ["ssd"],
                        "capacity": 1073741824,
                        "storedPartitions": 128,
                        "usableCapacity": 1000000000
                    }
                ],
                "parameters": {"zoneRedundancy": 1},
                "partitionSize": 4194304,
                "stagedRoleChanges": [
                    {"id": "node-2", "zone": "dc2", "tags": ["hdd"], "capacity": 2147483648}
                ]
            }
        """.trimIndent()

        val result = json.decodeFromString<ClusterLayout>(raw)

        assertEquals(3L, result.version)
        assertEquals(1, result.roles.size)
        assertEquals("dc1", result.roles[0].zone)
        assertEquals(1073741824L, result.roles[0].capacity)
        assertEquals(1, result.stagedRoleChanges.size)
        assertNull(result.stagedParameters)
    }

    @Test
    fun decodeLayoutHistory() {
        val raw = """
            {
                "currentVersion": 5,
                "minAck": 3,
                "versions": [
                    {"version": 5, "status": "current", "storageNodes": 3, "gatewayNodes": 0},
                    {"version": 4, "status": "old", "storageNodes": 2, "gatewayNodes": 1}
                ]
            }
        """.trimIndent()

        val result = json.decodeFromString<LayoutHistory>(raw)

        assertEquals(5L, result.currentVersion)
        assertEquals(2, result.versions.size)
        assertEquals("current", result.versions[0].status)
        assertNull(result.updateTrackers)
    }

    @Test
    fun decodeStagedRoleChangeRemove() {
        val raw = """{"id": "node-3", "remove": true}"""

        val result = json.decodeFromString<StagedRoleChange>(raw)

        assertEquals("node-3", result.id)
        assertTrue(result.remove == true)
        assertNull(result.zone)
    }
}
