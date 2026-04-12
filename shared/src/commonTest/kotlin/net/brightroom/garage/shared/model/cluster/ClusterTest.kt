package net.brightroom.garage.shared.model.cluster

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClusterTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodeClusterHealth() {
        val raw = """
            {
                "status": "healthy",
                "knownNodes": 3,
                "connectedNodes": 3,
                "storageNodes": 3,
                "storageNodesUp": 3,
                "partitions": 256,
                "partitionsQuorum": 256,
                "partitionsAllOk": 256
            }
        """.trimIndent()

        val result = json.decodeFromString<ClusterHealth>(raw)

        assertEquals("healthy", result.status)
        assertEquals(3, result.knownNodes)
        assertEquals(3, result.connectedNodes)
        assertEquals(256, result.partitions)
        assertEquals(256, result.partitionsAllOk)
    }

    @Test
    fun decodeClusterStatus() {
        val raw = """
            {
                "layoutVersion": 5,
                "nodes": [
                    {
                        "id": "node1",
                        "isUp": true,
                        "draining": false,
                        "addr": "10.0.0.1:3901",
                        "hostname": "garage-1",
                        "garageVersion": "v1.0.0",
                        "lastSeenSecsAgo": 0,
                        "role": {
                            "zone": "dc1",
                            "tags": ["fast"],
                            "capacity": 1073741824
                        },
                        "dataPartition": {"available": 500000000, "total": 1000000000},
                        "metadataPartition": {"available": 900000000, "total": 1000000000}
                    },
                    {
                        "id": "node2",
                        "isUp": false,
                        "draining": true
                    }
                ]
            }
        """.trimIndent()

        val result = json.decodeFromString<ClusterStatus>(raw)

        assertEquals(5L, result.layoutVersion)
        assertEquals(2, result.nodes.size)

        val node1 = result.nodes[0]
        assertTrue(node1.isUp)
        assertEquals("10.0.0.1:3901", node1.addr)
        assertEquals("dc1", node1.role?.zone)
        assertEquals(1073741824L, node1.role?.capacity)
        assertEquals(500000000L, node1.dataPartition?.available)

        val node2 = result.nodes[1]
        assertTrue(node2.draining)
        assertNull(node2.addr)
        assertNull(node2.role)
    }
}
