package net.brightroom.garage.web.screens.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
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
import net.brightroom.garage.shared.api.ApplyLayoutRequest
import net.brightroom.garage.shared.api.StageRolesRequest
import net.brightroom.garage.shared.model.garage.ClusterLayout
import net.brightroom.garage.shared.model.garage.LayoutHistory
import net.brightroom.garage.shared.model.garage.LayoutNodeRole
import net.brightroom.garage.shared.model.garage.LayoutPreview
import net.brightroom.garage.shared.model.garage.LayoutVersionStatus
import net.brightroom.garage.shared.model.garage.NodeRoleChange
import net.brightroom.garage.web.api.ApiResult
import net.brightroom.garage.web.api.AppJson
import net.brightroom.garage.web.api.getJson
import net.brightroom.garage.web.api.sendJson
import net.brightroom.garage.web.components.ConfirmDialog
import net.brightroom.garage.web.components.LoadingView
import net.brightroom.garage.web.components.PollingHeader
import net.brightroom.garage.web.components.ProblemView
import net.brightroom.garage.web.components.StatusChip
import net.brightroom.garage.web.components.StatusTone
import net.brightroom.garage.web.components.formatBytes
import net.brightroom.garage.web.components.rememberPolling
import net.brightroom.garage.web.session.LocalSession

private const val POLL_INTERVAL_MILLIS = 15_000L

/**
 * クラスタレイアウト。
 *
 * 適用の前には必ず preview を挟む（spec §8.6）。「適用」を押すと
 * `POST /api/layout/preview` を呼び、その結果を確認ダイアログに出す。
 */
@Composable
fun LayoutScreen() {
    val session = LocalSession.current
    val scope = rememberCoroutineScope()

    var layout by remember { mutableStateOf<ClusterLayout?>(null) }
    var history by remember { mutableStateOf<LayoutHistory?>(null) }
    var failure by remember { mutableStateOf<ApiResult.Failure?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<LayoutPreview?>(null) }
    var confirmRevert by remember { mutableStateOf(false) }
    var updatedAt by remember { mutableStateOf(0) }

    suspend fun load() {
        when (val result = session.api.getJson("/api/layout", ClusterLayout.serializer())) {
            is ApiResult.Success -> {
                layout = result.value
                failure = null
                updatedAt++
            }

            is ApiResult.Failure -> failure = result

            ApiResult.Unauthorized -> {
                session.invalidate()
                return
            }
        }

        (session.api.getJson("/api/layout/history", LayoutHistory.serializer()) as? ApiResult.Success)
            ?.let { history = it.value }
    }

    val polling = rememberPolling(POLL_INTERVAL_MILLIS) { load() }

    LaunchedEffect(updatedAt) {
        if (updatedAt > 0) polling.markUpdated()
    }

    /** 応答を受け取って画面を更新する共通処理。 */
    suspend fun <T> submit(result: ApiResult<T>, message: String, onSuccess: (T) -> Unit = {}) {
        when (result) {
            is ApiResult.Success -> {
                onSuccess(result.value)
                notice = message
                load()
            }

            is ApiResult.Failure -> failure = result

            ApiResult.Unauthorized -> session.invalidate()
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PollingHeader("レイアウト", polling, onRefresh = { scope.launch { load() } })

        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        failure?.let { ProblemView(it.problem, it.status, onRetry = { scope.launch { load() } }) }

        when (val current = layout) {
            null -> if (failure == null) LoadingView()

            else -> {
                CurrentLayout(current)

                StagedChanges(
                    layout = current,
                    onPreview = {
                        scope.launch {
                            notice = null
                            when (
                                val result = session.api.sendJson(
                                    HttpMethod.Post,
                                    "/api/layout/preview",
                                    null,
                                    LayoutPreview.serializer(),
                                )
                            ) {
                                is ApiResult.Success -> preview = result.value
                                is ApiResult.Failure -> failure = result
                                ApiResult.Unauthorized -> session.invalidate()
                            }
                        }
                    },
                    onRevert = { confirmRevert = true },
                )

                LayoutStageForm(
                    nodeIds = current.roles.map { it.id },
                    current = current,
                    onStage = { request ->
                        scope.launch {
                            notice = null
                            submit(
                                session.api.sendJson(
                                    HttpMethod.Post,
                                    "/api/layout/roles",
                                    AppJson.encodeToString(StageRolesRequest.serializer(), request),
                                    ClusterLayout.serializer(),
                                ),
                                "stage しました。適用するには「適用」を押してください",
                            )
                        }
                    },
                )

                history?.let { History(it) }
            }
        }
    }

    preview?.let { current ->
        LayoutPreviewDialog(
            preview = current,
            onApply = {
                val version = (current as? LayoutPreview.Computed)?.newLayout?.version
                preview = null

                if (version != null) {
                    scope.launch {
                        submit(
                            session.api.sendJson(
                                HttpMethod.Post,
                                "/api/layout/apply",
                                AppJson.encodeToString(ApplyLayoutRequest.serializer(), ApplyLayoutRequest(version)),
                                ClusterLayout.serializer(),
                            ),
                            "レイアウト v$version を適用しました",
                        )
                    }
                }
            },
            onDismiss = { preview = null },
        )
    }

    if (confirmRevert) {
        ConfirmDialog(
            title = "stage した変更を破棄",
            message = "適用していない変更をすべて破棄します。この操作は取り消せません。",
            onConfirm = {
                confirmRevert = false
                scope.launch {
                    submit(
                        session.api.sendJson(
                            HttpMethod.Post,
                            "/api/layout/revert",
                            null,
                            ClusterLayout.serializer(),
                        ),
                        "stage した変更を破棄しました",
                    )
                }
            },
            onDismiss = { confirmRevert = false },
        )
    }
}

@Composable
private fun CurrentLayout(layout: ClusterLayout) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "現在のレイアウト v${layout.version}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "パーティションサイズ ${formatBytes(layout.partitionSize)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (layout.roles.isEmpty()) {
                Text("ロールが割り当てられたノードがありません", style = MaterialTheme.typography.bodySmall)
            } else {
                layout.roles.sortedBy { it.zone }.forEach { RoleRow(it) }
            }
        }
    }
}

@Composable
private fun RoleRow(role: LayoutNodeRole) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusChip(if (role.isGateway) "gateway" else "storage", StatusTone.NEUTRAL)
        Text(role.id.take(16), style = MaterialTheme.typography.bodyMedium)
        Text(role.zone, style = MaterialTheme.typography.bodySmall)
        Text(
            listOfNotNull(
                role.capacity?.let { formatBytes(it) },
                role.storedPartitions?.let { "$it パーティション" },
                role.tags.takeIf { it.isNotEmpty() }?.joinToString(", "),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StagedChanges(layout: ClusterLayout, onPreview: () -> Unit, onRevert: () -> Unit) {
    val hasChanges = layout.stagedRoleChanges.isNotEmpty() || layout.stagedParameters != null

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("未適用の変更", style = MaterialTheme.typography.titleSmall)

            if (!hasChanges) {
                Text(
                    "未適用の変更はありません",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                layout.stagedRoleChanges.forEach { change ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        when (change) {
                            is NodeRoleChange.Remove -> {
                                StatusChip("外す", StatusTone.ERROR)
                                Text(change.id.take(16), style = MaterialTheme.typography.bodySmall)
                            }

                            is NodeRoleChange.Assign -> {
                                StatusChip("割り当て", StatusTone.WARNING)
                                Text(
                                    listOfNotNull(
                                        change.id.take(16),
                                        change.zone,
                                        change.capacity?.let { formatBytes(it) } ?: "gateway",
                                        change.tags.takeIf { it.isNotEmpty() }?.joinToString(", "),
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }

                layout.stagedParameters?.let {
                    Text("ゾーン冗長度の変更が stage されています", style = MaterialTheme.typography.bodySmall)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // 適用の前に必ず preview を通す（spec §8.6）
                TextButton(onClick = onPreview, enabled = hasChanges) { Text("適用") }
                TextButton(onClick = onRevert, enabled = hasChanges) { Text("破棄") }
            }
        }
    }
}

@Composable
private fun History(history: LayoutHistory) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("履歴", style = MaterialTheme.typography.titleSmall)
            Text(
                "全ノードが v${history.minAck} までを認識しています",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            history.versions.sortedByDescending { it.version }.forEach { version ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusChip(
                        version.status.name.lowercase(),
                        when (version.status) {
                            LayoutVersionStatus.CURRENT -> StatusTone.SUCCESS
                            LayoutVersionStatus.DRAINING -> StatusTone.WARNING
                            LayoutVersionStatus.HISTORICAL -> StatusTone.NEUTRAL
                        },
                    )
                    Text("v${version.version}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "storage ${version.storageNodes} · gateway ${version.gatewayNodes}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
