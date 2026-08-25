package net.brightroom.garage.web.screens.nodes

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.ktor.http.HttpMethod
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import net.brightroom.garage.shared.api.ClusterView
import net.brightroom.garage.shared.api.ConnectNodeResult
import net.brightroom.garage.shared.api.ConnectNodesRequest
import net.brightroom.garage.shared.api.NodeActionOutcome
import net.brightroom.garage.shared.api.RepairRequest
import net.brightroom.garage.shared.model.garage.ClusterHealthStatus
import net.brightroom.garage.shared.model.garage.ClusterStatistics
import net.brightroom.garage.shared.model.garage.MultiResponse
import net.brightroom.garage.shared.model.garage.NodeInfo
import net.brightroom.garage.shared.model.garage.NodeResp
import net.brightroom.garage.shared.model.garage.NodeStatistics
import net.brightroom.garage.shared.navigation.Route
import net.brightroom.garage.web.api.ApiResult
import net.brightroom.garage.web.api.AppJson
import net.brightroom.garage.web.api.getJson
import net.brightroom.garage.web.api.sendJson
import net.brightroom.garage.web.components.ConfirmDialog
import net.brightroom.garage.web.components.LoadingView
import net.brightroom.garage.web.components.NodeOutcomeNotice
import net.brightroom.garage.web.components.PollingHeader
import net.brightroom.garage.web.components.ProblemView
import net.brightroom.garage.web.components.StatusChip
import net.brightroom.garage.web.components.StatusTone
import net.brightroom.garage.web.components.formatBytes
import net.brightroom.garage.web.components.rememberPolling
import net.brightroom.garage.web.session.LocalSession

private const val POLL_INTERVAL_MILLIS = 15_000L

/**
 * クラスタ状態とノード（spec §8.1 で旧 Cluster 画面と旧 Nodes 画面を統合した先）。
 *
 * 同じノードの情報が `GetClusterStatus` / `GetNodeInfo` / `GetNodeStatistics` に
 * 散っているため、ノード ID で束ねて 1 行にする（P3-12）。
 */
@Composable
fun NodesScreen(onNavigate: (Route) -> Unit) {
    val session = LocalSession.current
    val scope = rememberCoroutineScope()

    var cluster by remember { mutableStateOf<ClusterView?>(null) }
    var statistics by remember { mutableStateOf<ClusterStatistics?>(null) }
    var nodeInfo by remember { mutableStateOf<MultiResponse<NodeInfo>?>(null) }
    var nodeStatistics by remember { mutableStateOf<MultiResponse<NodeStatistics>?>(null) }
    var failure by remember { mutableStateOf<ApiResult.Failure?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var outcome by remember { mutableStateOf<NodeActionOutcome?>(null) }
    var connectResults by remember { mutableStateOf<List<ConnectNodeResult>>(emptyList()) }
    var showConnect by remember { mutableStateOf(false) }
    var showSnapshot by remember { mutableStateOf(false) }
    var showRepair by remember { mutableStateOf(false) }

    // 取得に成功した回数。polling は load より後に作られるため、load の中から
    // markUpdated() を直接は呼べない。成功を状態に立てて LaunchedEffect で伝える
    var updatedAt by remember { mutableStateOf(0) }

    suspend fun load() {
        when (val result = session.api.getJson("/api/cluster", ClusterView.serializer())) {
            is ApiResult.Success -> {
                cluster = result.value
                failure = null
                updatedAt++
            }

            is ApiResult.Failure -> failure = result

            ApiResult.Unauthorized -> {
                session.invalidate()
                return
            }
        }

        // 統計とノード情報は取れなくても画面の主要部は成立する。
        // 失敗しても画面全体は落とさない
        (session.api.getJson("/api/cluster/statistics", ClusterStatistics.serializer()) as? ApiResult.Success)
            ?.let { statistics = it.value }

        (
            session.api.getJson(
                "/api/nodes/info",
                MultiResponse.serializer(NodeInfo.serializer()),
            ) as? ApiResult.Success
            )?.let { nodeInfo = it.value }

        (
            session.api.getJson(
                "/api/nodes/statistics",
                MultiResponse.serializer(NodeStatistics.serializer()),
            ) as? ApiResult.Success
            )?.let { nodeStatistics = it.value }
    }

    val polling = rememberPolling(POLL_INTERVAL_MILLIS) { load() }

    LaunchedEffect(updatedAt) {
        if (updatedAt > 0) polling.markUpdated()
    }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PollingHeader("クラスタ", polling, onRefresh = { scope.launch { load() } }) {
            TextButton(onClick = { showConnect = true }) { Text("ノードを接続") }
            TextButton(onClick = { showSnapshot = true }) { Text("スナップショット") }
            TextButton(onClick = { showRepair = true }) { Text("修復を開始") }
        }

        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        outcome?.let { NodeOutcomeNotice(it) }
        connectResults.takeIf { it.isNotEmpty() }?.let { ConnectResults(it) }

        failure?.let { ProblemView(it.problem, it.status, onRetry = { scope.launch { load() } }) }

        when (val current = cluster) {
            null -> if (failure == null) LoadingView()

            else -> {
                ClusterSummary(current, statistics)
                NodeTable(current.status.nodes, nodeInfo, nodeStatistics)
                TextButton(onClick = { onNavigate(Route.Layout) }) { Text("レイアウトを見る") }
            }
        }
    }

    if (showConnect) {
        ConnectNodeDialog(
            onConfirm = { nodes ->
                showConnect = false
                scope.launch {
                    val result = session.api.sendJson(
                        HttpMethod.Post,
                        "/api/cluster/connect",
                        AppJson.encodeToString(
                            ConnectNodesRequest.serializer(),
                            ConnectNodesRequest(nodes),
                        ),
                        ListSerializer(ConnectNodeResult.serializer()),
                    )

                    when (result) {
                        is ApiResult.Success -> {
                            connectResults = result.value
                            load()
                        }

                        is ApiResult.Failure -> failure = result

                        ApiResult.Unauthorized -> session.invalidate()
                    }
                }
            },
            onDismiss = { showConnect = false },
        )
    }

    if (showSnapshot) {
        ConfirmDialog(
            title = "メタデータのスナップショットを作成",
            message = "全ノードでメタデータのスナップショットを作成します。ディスクの空き容量を消費します。",
            onConfirm = {
                showSnapshot = false
                scope.launch {
                    notice = null
                    when (
                        val result = session.api.sendJson(
                            HttpMethod.Post,
                            "/api/nodes/snapshot",
                            null,
                            NodeActionOutcome.serializer(),
                        )
                    ) {
                        is ApiResult.Success -> {
                            outcome = result.value
                            notice = "スナップショットを開始しました"
                        }

                        is ApiResult.Failure -> failure = result

                        ApiResult.Unauthorized -> session.invalidate()
                    }
                }
            },
            onDismiss = { showSnapshot = false },
        )
    }

    if (showRepair) {
        RepairDialog(
            onConfirm = { request ->
                showRepair = false
                scope.launch {
                    notice = null
                    when (
                        val result = session.api.sendJson(
                            HttpMethod.Post,
                            "/api/nodes/repair",
                            AppJson.encodeToString(RepairRequest.serializer(), request),
                            NodeActionOutcome.serializer(),
                        )
                    ) {
                        is ApiResult.Success -> {
                            outcome = result.value
                            notice = "${request.repairType} の修復を開始しました"
                        }

                        is ApiResult.Failure -> failure = result

                        ApiResult.Unauthorized -> session.invalidate()
                    }
                }
            },
            onDismiss = { showRepair = false },
        )
    }
}

@Composable
private fun ClusterSummary(cluster: ClusterView, statistics: ClusterStatistics?) {
    val health = cluster.health
    val tone = when (health.status) {
        ClusterHealthStatus.HEALTHY -> StatusTone.SUCCESS
        ClusterHealthStatus.DEGRADED -> StatusTone.WARNING
        ClusterHealthStatus.UNAVAILABLE -> StatusTone.ERROR
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("クラスタ全体", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                StatusChip(
                    when (health.status) {
                        ClusterHealthStatus.HEALTHY -> "healthy"
                        ClusterHealthStatus.DEGRADED -> "degraded"
                        ClusterHealthStatus.UNAVAILABLE -> "unavailable"
                    },
                    tone,
                )
            }

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Figure("ノード", "${health.storageNodesUp} / ${health.storageNodes}")
                Figure("接続済み", "${health.connectedNodes} / ${health.knownNodes}")
                Figure("quorum", "${health.partitionsQuorum} / ${health.partitions}")
                Figure("全複製 OK", "${health.partitionsAllOk} / ${health.partitions}")
                Figure("レイアウト", "v${cluster.status.layoutVersion}")

                statistics?.let {
                    Figure("バケット", "${it.bucketCount}")
                    Figure("オブジェクト", "${it.totalObjectCount}")
                    Figure("使用量", formatBytes(it.totalObjectBytes))
                    it.dataAvail?.let { avail -> Figure("データ空き", formatBytes(avail)) }
                }
            }

            if (statistics?.incompleteAvailInfo == true) {
                Text(
                    "一部のノードから空き容量を取得できていません。数値は実際より小さい可能性があります。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Figure(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * ノードごとに、状態・役割・容量・バージョンを 1 行に束ねる。
 *
 * `GetClusterStatus` が軸で、`GetNodeInfo` と `GetNodeStatistics` はノード ID で引き当てる。
 * 情報を取れなかったノードは状態だけを出す。
 */
@Composable
private fun NodeTable(
    nodes: List<NodeResp>,
    info: MultiResponse<NodeInfo>?,
    statistics: MultiResponse<NodeStatistics>?,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("ノード", style = MaterialTheme.typography.titleSmall)

            info?.error?.takeIf { it.isNotEmpty() }?.let { failures ->
                Text(
                    "${failures.size} 台のノードから詳細を取得できませんでした",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            nodes.sortedBy { it.hostname ?: it.id }.forEach { node ->
                NodeRow(node, info?.success?.get(node.id), statistics?.success?.get(node.id))
            }
        }
    }
}

@Composable
private fun NodeRow(node: NodeResp, info: NodeInfo?, statistics: NodeStatistics?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusChip(if (node.isUp) "稼働" else "停止", if (node.isUp) StatusTone.SUCCESS else StatusTone.ERROR)

            Text(
                node.hostname ?: node.id.take(12),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.width(160.dp),
            )

            Text(
                node.role?.zone ?: "役割なし",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(80.dp),
            )

            val total = node.dataPartition?.total
            val available = node.dataPartition?.available

            if (total != null && available != null && total > 0) {
                val used = total - available
                LinearProgressIndicator(
                    progress = { used.toFloat() / total.toFloat() },
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${formatBytes(used)} / ${formatBytes(total)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "容量情報なし",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }

            if (node.draining) StatusChip("退避中", StatusTone.WARNING)
        }

        Text(
            listOfNotNull(
                info?.garageVersion,
                info?.dbEngine,
                node.addr,
                node.lastSeenSecsAgo?.let { "最終応答 $it 秒前" },
                node.role?.tags?.takeIf { it.isNotEmpty() }?.joinToString(", "),
                statistics?.blockManagerStats?.let { "再同期キュー ${it.resyncQueueLen}" },
                // 異常なときだけ主張する（spec §8.3）。0 件なら出さない
                statistics?.blockManagerStats?.takeIf { it.resyncErrors > 0 }?.let { "再同期エラー ${it.resyncErrors}" },
            ).joinToString(" · ").ifEmpty { node.id },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConnectResults(results: List<ConnectNodeResult>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("接続の結果", style = MaterialTheme.typography.titleSmall)

            results.forEach { result ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusChip(
                        if (result.success) "成功" else "失敗",
                        if (result.success) StatusTone.SUCCESS else StatusTone.ERROR,
                    )
                    Text(result.node, style = MaterialTheme.typography.bodySmall)
                    result.error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
