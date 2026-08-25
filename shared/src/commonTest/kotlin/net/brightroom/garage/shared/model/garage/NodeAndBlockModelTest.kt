package net.brightroom.garage.shared.model.garage

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NodeAndBlockModelTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun decodesNodeInfoFromLiveShape() {
        val info = json.decodeFromString<NodeInfo>(
            """
            {"nodeId":"e97d97ad","hostname":"e4dcc2c566e3","garageVersion":"v2.3.0",
             "garageFeatures":["bundled-libs","sqlite"],"rustVersion":"1.91.0",
             "dbEngine":"sqlite3 v3.51.1 (using rusqlite crate)"}
            """.trimIndent(),
        )

        assertEquals("e4dcc2c566e3", info.hostname)
        assertEquals(2, info.garageFeatures.size)
    }

    @Test
    fun decodesNodeStatisticsFromLiveShape() {
        val statistics = json.decodeFromString<NodeStatistics>(
            """
            {"freeform":"Node ID: e97d97ad\n",
             "tableStats":[{"tableName":"bucket_v2","items":23,"merkleItems":24,
                            "merkleQueueLen":0,"insertQueueLen":0,"gcQueueLen":0}],
             "blockManagerStats":{"rcEntries":0,"resyncQueueLen":0,"resyncErrors":0}}
            """.trimIndent(),
        )

        assertEquals("bucket_v2", statistics.tableStats.single().tableName)
        assertEquals(23, statistics.tableStats.single().items)
        assertEquals(0, statistics.blockManagerStats?.resyncErrors)
    }

    @Test
    fun decodesBlockInfoWithObjectBacklink() {
        val info = json.decodeFromString<BlockInfo>(
            """
            {"blockHash":"abcd","refcount":2,
             "versions":[{"versionId":"v1","refDeleted":false,"versionDeleted":false,
                          "garbageCollected":false,
                          "backlink":{"object":{"bucketId":"b1","key":"photos/a.jpg"}}}]}
            """.trimIndent(),
        )

        val version = info.versions.single()
        assertEquals("photos/a.jpg", version.backlink?.storedObject?.key)
        assertNull(version.backlink?.upload)
    }

    @Test
    fun decodesBlockInfoWithUploadBacklink() {
        val info = json.decodeFromString<BlockInfo>(
            """
            {"blockHash":"abcd","refcount":1,
             "versions":[{"versionId":"v2","refDeleted":false,"versionDeleted":true,
                          "garbageCollected":false,
                          "backlink":{"upload":{"uploadId":"u1","uploadDeleted":false,
                                                "uploadGarbageCollected":false,
                                                "bucketId":"b1","key":"tmp/big.bin"}}}]}
            """.trimIndent(),
        )

        assertEquals("u1", info.versions.single().backlink?.upload?.uploadId)
    }

    @Test
    fun roundTripsBacklink() {
        val raw = """{"object":{"bucketId":"b1","key":"photos/a.jpg"}}"""

        assertEquals(raw, json.encodeToString(json.decodeFromString<BlockVersionBacklink>(raw)))
    }

    @Test
    fun decodesBlockErrorMultiResponse() {
        val response = json.decodeFromString(
            MultiResponse.serializer(
                kotlinx.serialization.builtins.ListSerializer(BlockError.serializer()),
            ),
            """{"success":{"e97d97ad":[]},"error":{}}""",
        )

        assertEquals(0, response.success.getValue("e97d97ad").size)
        assertEquals(0, response.error.size)
    }
}
