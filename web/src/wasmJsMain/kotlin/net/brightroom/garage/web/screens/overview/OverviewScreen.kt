@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package net.brightroom.garage.web.screens.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.brightroom.garage.shared.api.AlertSeverity
import net.brightroom.garage.shared.api.NodeSummary
import net.brightroom.garage.shared.api.Overview
import net.brightroom.garage.shared.api.Section
import net.brightroom.garage.shared.api.alerts
import net.brightroom.garage.web.api.ApiResult
import net.brightroom.garage.web.api.displayMessage
import net.brightroom.garage.web.api.getJson
import net.brightroom.garage.web.components.DeniedView
import net.brightroom.garage.web.components.ErrorView
import net.brightroom.garage.web.components.LoadingView
import net.brightroom.garage.web.components.formatBytes
import net.brightroom.garage.web.session.LocalSession

private const val POLL_INTERVAL_MILLIS = 10_000L

/**
 * タブが隠れているか。
 *
 * `document.hidden` は kotlinx-browser の wasmJs 向け Document に無く、
 * `visibilityState` も external な列挙型で扱いが不安定なため直接参照する。
 */
private fun isDocumentHidden(): Boolean = js("document.hidden")

@Composable
fun OverviewScreen() {
    val session = LocalSession.current
    val scope = rememberCoroutineScope()

    var overview by remember { mutableStateOf<Overview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var autoRefresh by remember { mutableStateOf(true) }
    var secondsSinceUpdate by remember { mutableStateOf(0) }

    suspend fun load() {
        when (val result = session.api.getJson("/api/overview", Overview.serializer())) {
            is ApiResult.Success -> {
                overview = result.value
                error = null
                secondsSinceUpdate = 0
            }

            is ApiResult.Failure -> error = result.problem.displayMessage
            // トークンが失効した。ログイン画面に戻す
            ApiResult.Unauthorized -> session.invalidate()
        }
    }

    LaunchedEffect(Unit) { load() }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)

            // 経過時間は自動更新を切っていても進める。止めると古いデータを
            // 「最終更新 0 秒前」と偽ってしまう。
            secondsSinceUpdate++

            // 放置されたタブが Garage を叩き続けないようにする
            if (autoRefresh &&
                secondsSinceUpdate * 1000L >= POLL_INTERVAL_MILLIS &&
                !isDocumentHidden()
            ) {
                load()
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("概況", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            Text(
                "最終更新 $secondsSinceUpdate 秒前",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("自動更新", style = MaterialTheme.typography.bodySmall)
            Switch(checked = autoRefresh, onCheckedChange = { autoRefresh = it })
            TextButton(onClick = { scope.launch { load() } }) { Text("更新") }
        }

        error?.let { ErrorView(it, onRetry = { scope.launch { load() } }) }

        when (val current = overview) {
            null -> if (error == null) LoadingView()
            else -> OverviewContent(current)
        }
    }
}

@Composable
private fun OverviewContent(overview: Overview) {
    AlertBand(overview)
    KeyFigures(overview)
    NodeList(overview.nodes)
}

/** 最上段の異常帯。正常時は 1 行に収める（spec §8.3）。 */
@Composable
private fun AlertBand(overview: Overview) {
    val alerts = overview.alerts()

    if (alerts.isEmpty()) {
        Text(
            "異常はありません",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        alerts.forEach { alert ->
            val container = when (alert.severity) {
                AlertSeverity.ERROR -> MaterialTheme.colorScheme.errorContainer
                AlertSeverity.WARNING -> MaterialTheme.colorScheme.surfaceVariant
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = container),
            ) {
                Text(
                    alert.message,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun KeyFigures(overview: Overview) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FigureCard("ノード") {
            when (val nodes = overview.nodes) {
                is Section.Loaded -> Text(
                    "${nodes.data.count { it.isUp }} / ${nodes.data.size}",
                    style = MaterialTheme.typography.headlineMedium,
                )

                is Section.Denied -> DeniedView(nodes.operation)
                is Section.Failed -> Text(nodes.message, style = MaterialTheme.typography.bodySmall)
            }
        }

        FigureCard("状態") {
            when (val health = overview.health) {
                is Section.Loaded -> Column {
                    Text(health.data.status, style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "quorum ${health.data.partitionsQuorum}/${health.data.partitions}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is Section.Denied -> DeniedView(health.operation)
                is Section.Failed -> Text(health.message, style = MaterialTheme.typography.bodySmall)
            }
        }

        FigureCard("ストレージ") {
            when (val storage = overview.storage) {
                is Section.Loaded -> Column {
                    Text(
                        "${storage.data.buckets} バケット",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        "${storage.data.keys} キー",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is Section.Denied -> DeniedView(storage.operation)
                is Section.Failed -> Text(storage.message, style = MaterialTheme.typography.bodySmall)
            }
        }

        FigureCard("レイアウト") {
            when (val layout = overview.layout) {
                is Section.Loaded -> Column {
                    Text("v${layout.data.version}", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        if (layout.data.stagedChanges == 0) {
                            "未適用の変更なし"
                        } else {
                            "${layout.data.stagedChanges} 件が未適用"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is Section.Denied -> DeniedView(layout.operation)
                is Section.Failed -> Text(layout.message, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun FigureCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.width(220.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

@Composable
private fun NodeList(section: Section<List<NodeSummary>>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("ノード", style = MaterialTheme.typography.titleSmall)

            when (section) {
                is Section.Denied -> DeniedView(section.operation)
                is Section.Failed -> Text(section.message, style = MaterialTheme.typography.bodySmall)
                is Section.Loaded ->
                    if (section.data.isEmpty()) {
                        Text(
                            "ノードがありません",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        section.data.forEach { NodeRow(it) }
                    }
            }
        }
    }
}

@Composable
private fun NodeRow(node: NodeSummary) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (node.isUp) "稼働" else "停止",
            style = MaterialTheme.typography.labelSmall,
            color = if (node.isUp) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
            modifier = Modifier.width(48.dp),
        )

        Text(
            node.hostname ?: node.id.take(12),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(160.dp),
        )

        Text(
            node.zone ?: "-",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp),
        )

        val total = node.dataTotal
        val available = node.dataAvailable

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

        if (node.draining) {
            Text(
                "退避中",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
