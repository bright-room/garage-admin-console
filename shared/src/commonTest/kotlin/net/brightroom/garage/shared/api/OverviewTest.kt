package net.brightroom.garage.shared.api

import kotlinx.serialization.json.Json
import net.brightroom.garage.shared.model.garage.ClusterHealth
import net.brightroom.garage.shared.model.garage.ClusterHealthStatus
import net.brightroom.garage.shared.model.garage.FreeSpace
import net.brightroom.garage.shared.model.garage.NodeAssignedRole
import net.brightroom.garage.shared.model.garage.NodeResp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OverviewTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun health(status: ClusterHealthStatus, quorum: Int = 256) = ClusterHealth(
        status = status,
        knownNodes = 3,
        connectedNodes = 3,
        storageNodes = 3,
        storageNodesUp = 3,
        partitions = 256,
        partitionsQuorum = quorum,
        partitionsAllOk = 256,
    )

    private fun overview(
        healthSection: Section<ClusterHealth> = Section.Loaded(health(ClusterHealthStatus.HEALTHY)),
        nodes: List<NodeSummary> = emptyList(),
        layout: LayoutSummary = LayoutSummary(version = 7, stagedChanges = 0),
        blockErrors: Int = 0,
    ) = Overview(
        health = healthSection,
        nodes = Section.Loaded(nodes),
        layout = Section.Loaded(layout),
        storage = Section.Loaded(StorageSummary(buckets = 12, keys = 8)),
        blockErrors = Section.Loaded(blockErrors),
    )

    @Test
    fun summarisesNode() {
        val node = NodeResp(
            id = "abc",
            isUp = true,
            draining = false,
            hostname = "node-a",
            role = NodeAssignedRole(zone = "dc1", tags = listOf("ssd"), capacity = 1000),
            dataPartition = FreeSpace(available = 400, total = 1000),
        )

        val summary = node.toSummary()

        assertEquals("abc", summary.id)
        assertEquals("node-a", summary.hostname)
        assertEquals("dc1", summary.zone)
        assertEquals(1000L, summary.capacity)
        assertEquals(400L, summary.dataAvailable)
        assertEquals(1000L, summary.dataTotal)
    }

    @Test
    fun healthyClusterHasNoAlerts() {
        assertEquals(emptyList(), overview().alerts())
    }

    @Test
    fun stagedLayoutChangesRaiseWarning() {
        val alerts = overview(layout = LayoutSummary(version = 7, stagedChanges = 2)).alerts()

        assertEquals(1, alerts.size)
        assertEquals(AlertSeverity.WARNING, alerts[0].severity)
        assertTrue(alerts[0].message.contains("2"))
    }

    @Test
    fun blockErrorsRaiseWarning() {
        val alerts = overview(blockErrors = 3).alerts()

        assertEquals(1, alerts.size)
        assertEquals(AlertSeverity.WARNING, alerts[0].severity)
    }

    @Test
    fun downNodeRaisesError() {
        val down = NodeSummary(id = "def", isUp = false, draining = false)
        val alerts = overview(nodes = listOf(down)).alerts()

        assertEquals(1, alerts.size)
        assertEquals(AlertSeverity.ERROR, alerts[0].severity)
    }

    @Test
    fun unavailableClusterRaisesError() {
        val unavailable = health(ClusterHealthStatus.UNAVAILABLE, quorum = 200)
        val alerts = overview(healthSection = Section.Loaded(unavailable)).alerts()

        assertTrue(alerts.any { it.severity == AlertSeverity.ERROR })
    }

    @Test
    fun deniedSectionsProduceNoAlerts() {
        val denied = Overview(
            health = Section.Denied("GetClusterHealth"),
            nodes = Section.Denied("GetClusterStatus"),
            layout = Section.Denied("GetClusterLayout"),
            storage = Section.Denied("ListBuckets"),
            blockErrors = Section.Denied("ListBlockErrors"),
        )

        assertEquals(emptyList(), denied.alerts())
    }

    // --- wire format の検証 ---
    // サーバーは Overview.serializer() で書き、web は同じ serializer で読む。
    // generic sealed interface の discriminator がこの経路で往復できることを
    // ここで確定させる（サーバーと web を書く前に失敗させる）。

    @Test
    fun overviewRoundTripsWhenEverySectionIsLoaded() {
        val original = overview(
            nodes = listOf(
                NodeSummary(
                    id = "abc",
                    isUp = true,
                    draining = false,
                    hostname = "node-a",
                    zone = "dc1",
                    capacity = 1000,
                    dataAvailable = 400,
                    dataTotal = 1000,
                ),
            ),
        )

        val encoded = json.encodeToString(Overview.serializer(), original)

        assertEquals(original, json.decodeFromString(Overview.serializer(), encoded))
        assertTrue(encoded.contains(""""type":"loaded""""), "discriminator が出力されるべき: $encoded")
    }

    @Test
    fun overviewRoundTripsWithMixedSections() {
        val original = Overview(
            health = Section.Loaded(health(ClusterHealthStatus.DEGRADED)),
            nodes = Section.Denied("GetClusterStatus"),
            layout = Section.Failed("connection refused"),
            storage = Section.Loaded(StorageSummary(buckets = 1, keys = 2)),
            blockErrors = Section.Denied("ListBlockErrors"),
        )

        val encoded = json.encodeToString(Overview.serializer(), original)

        assertEquals(original, json.decodeFromString(Overview.serializer(), encoded))
        assertTrue(encoded.contains(""""type":"denied""""), "discriminator が出力されるべき: $encoded")
        assertTrue(encoded.contains(""""type":"failed""""), "discriminator が出力されるべき: $encoded")
    }
}
