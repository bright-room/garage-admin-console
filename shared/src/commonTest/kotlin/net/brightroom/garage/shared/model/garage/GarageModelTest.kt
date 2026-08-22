package net.brightroom.garage.shared.model.garage

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GarageModelTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesClusterHealth() {
        val health = json.decodeFromString<ClusterHealth>(
            """
            {"status":"healthy","knownNodes":3,"connectedNodes":3,"storageNodes":3,
             "storageNodesUp":3,"partitions":256,"partitionsQuorum":256,"partitionsAllOk":256}
            """.trimIndent(),
        )

        assertEquals("healthy", health.status)
        assertEquals(256, health.partitionsAllOk)
        assertTrue(health.isHealthy)
    }

    @Test
    fun degradedHealthIsNotHealthy() {
        val health = json.decodeFromString<ClusterHealth>(
            """
            {"status":"degraded","knownNodes":3,"connectedNodes":2,"storageNodes":3,
             "storageNodesUp":2,"partitions":256,"partitionsQuorum":256,"partitionsAllOk":200}
            """.trimIndent(),
        )

        assertEquals(false, health.isHealthy)
    }

    @Test
    fun decodesClusterStatusWithOptionalNodeFields() {
        val status = json.decodeFromString<ClusterStatus>(
            """
            {"layoutVersion":7,"nodes":[
              {"id":"abc","isUp":true,"draining":false,"hostname":"node-a","addr":"10.0.0.1:3901",
               "garageVersion":"v2.3.0","role":{"zone":"dc1","tags":["ssd"],"capacity":1073741824},
               "dataPartition":{"available":500,"total":1000},
               "metadataPartition":{"available":900,"total":1000},"unknownField":1},
              {"id":"def","isUp":false,"draining":true,"lastSeenSecsAgo":120}
            ]}
            """.trimIndent(),
        )

        assertEquals(7L, status.layoutVersion)
        assertEquals(2, status.nodes.size)
        assertEquals("dc1", status.nodes[0].role?.zone)
        assertEquals(1073741824L, status.nodes[0].role?.capacity)
        assertEquals(500L, status.nodes[0].dataPartition?.available)
        assertNull(status.nodes[1].role)
        assertEquals(120L, status.nodes[1].lastSeenSecsAgo)
    }

    @Test
    fun decodesClusterLayoutAndCountsStagedChanges() {
        val layout = json.decodeFromString<ClusterLayout>(
            """
            {"version":7,"roles":[],"parameters":{"zoneRedundancy":"maximum"},"partitionSize":1024,
             "stagedRoleChanges":[{"id":"abc","zone":"dc2","capacity":1,"tags":[]}]}
            """.trimIndent(),
        )

        assertEquals(7L, layout.version)
        assertEquals(1, layout.stagedRoleChanges.size)
    }

    @Test
    fun decodesMultiResponseOfBlockErrors() {
        val serializer = MultiResponse.serializer(ListSerializer(BlockError.serializer()))
        val response = json.decodeFromString(
            serializer,
            """
            {"success":{"node-a":[
               {"blockHash":"ff00","refcount":2,"errorCount":3,"lastTrySecsAgo":10,"nextTryInSecs":60}
             ],"node-b":[]},
             "error":{"node-c":"node unavailable"}}
            """.trimIndent(),
        )

        assertEquals(1, response.success["node-a"]?.size)
        assertEquals(0, response.success["node-b"]?.size)
        assertEquals("ff00", response.success["node-a"]?.first()?.blockHash)
        assertEquals("node unavailable", response.error["node-c"])
    }

    @Test
    fun decodesAdminTokenInfo() {
        val info = json.decodeFromString<AdminTokenInfo>(
            """
            {"id":"tok1","name":"alice","scope":["ListBuckets","GetBucketInfo"],
             "expired":false,"created":"2026-01-01T00:00:00Z","expiration":"2026-12-31T23:59:59Z"}
            """.trimIndent(),
        )

        assertEquals("alice", info.name)
        assertEquals(listOf("ListBuckets", "GetBucketInfo"), info.scope)
        assertEquals(false, info.expired)
    }

    @Test
    fun decodesAdminTokenInfoWithoutOptionalFields() {
        val info = json.decodeFromString<AdminTokenInfo>(
            """{"name":"bob","scope":["*"],"expired":false}""",
        )

        assertNull(info.expiration)
        assertNull(info.created)
        assertNull(info.id)
    }
}
