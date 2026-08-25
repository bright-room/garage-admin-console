package net.brightroom.garage.web.screens.blocks

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
import kotlinx.serialization.builtins.ListSerializer
import net.brightroom.garage.shared.api.NodeActionOutcome
import net.brightroom.garage.shared.api.PurgeBlocksRequest
import net.brightroom.garage.shared.api.RetryResyncRequest
import net.brightroom.garage.shared.model.garage.BlockError
import net.brightroom.garage.shared.model.garage.BlockInfo
import net.brightroom.garage.shared.model.garage.MultiResponse
import net.brightroom.garage.web.api.ApiResult
import net.brightroom.garage.web.api.AppJson
import net.brightroom.garage.web.api.getJson
import net.brightroom.garage.web.api.sendJson
import net.brightroom.garage.web.components.ConfirmDialog
import net.brightroom.garage.web.components.CopyButton
import net.brightroom.garage.web.components.EmptyState
import net.brightroom.garage.web.components.LoadingView
import net.brightroom.garage.web.components.MultiResponseView
import net.brightroom.garage.web.components.NodeOutcomeNotice
import net.brightroom.garage.web.components.PollingHeader
import net.brightroom.garage.web.components.ProblemView
import net.brightroom.garage.web.components.StatusChip
import net.brightroom.garage.web.components.StatusTone
import net.brightroom.garage.web.components.rememberPolling
import net.brightroom.garage.web.session.LocalSession

private const val POLL_INTERVAL_MILLIS = 15_000L

@Composable
fun BlocksScreen() {
    val session = LocalSession.current
    val scope = rememberCoroutineScope()

    var errors by remember { mutableStateOf<MultiResponse<List<BlockError>>?>(null) }
    var detail by remember { mutableStateOf<MultiResponse<BlockInfo>?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<ApiResult.Failure?>(null) }
    var outcome by remember { mutableStateOf<NodeActionOutcome?>(null) }
    var confirmRetryAll by remember { mutableStateOf(false) }
    var confirmPurge by remember { mutableStateOf<String?>(null) }
    var updatedAt by remember { mutableStateOf(0) }

    suspend fun load() {
        when (
            val result = session.api.getJson(
                "/api/blocks/errors",
                MultiResponse.serializer(ListSerializer(BlockError.serializer())),
            )
        ) {
            is ApiResult.Success -> {
                errors = result.value
                failure = null
                updatedAt++
            }

            is ApiResult.Failure -> failure = result

            ApiResult.Unauthorized -> session.invalidate()
        }
    }

    suspend fun openDetail(hash: String) {
        selected = hash
        // 前に開いたブロックの内容が、別のハッシュの見出しの下に残らないようにする
        detail = null

        when (
            val result = session.api.getJson(
                "/api/blocks/$hash",
                MultiResponse.serializer(BlockInfo.serializer()),
            )
        ) {
            is ApiResult.Success -> detail = result.value
            is ApiResult.Failure -> failure = result
            ApiResult.Unauthorized -> session.invalidate()
        }
    }

    /** 副作用だけの操作を投げ、結果をノード別に表示して一覧を取り直す。 */
    suspend fun post(path: String, body: String?) {
        when (
            val result = session.api.sendJson(HttpMethod.Post, path, body, NodeActionOutcome.serializer())
        ) {
            is ApiResult.Success -> {
                outcome = result.value
                load()
            }

            is ApiResult.Failure -> failure = result

            ApiResult.Unauthorized -> session.invalidate()
        }
    }

    val polling = rememberPolling(POLL_INTERVAL_MILLIS) { load() }

    LaunchedEffect(updatedAt) {
        if (updatedAt > 0) polling.markUpdated()
    }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PollingHeader("ブロックエラー", polling, onRefresh = { scope.launch { load() } }) {
            TextButton(onClick = { confirmRetryAll = true }) { Text("全件を再同期") }
        }

        outcome?.let { NodeOutcomeNotice(it) }
        failure?.let { ProblemView(it.problem, it.status, onRetry = { scope.launch { load() } }) }

        when (val current = errors) {
            null -> if (failure == null) LoadingView()

            else -> {
                val total = current.success.values.sumOf { it.size }

                if (total == 0 && current.error.isEmpty()) {
                    EmptyState("再同期に失敗しているブロックはありません")
                } else {
                    MultiResponseView(current, emptyMessage = "再同期に失敗しているブロックはありません") { nodeId, list ->
                        BlockErrorCard(
                            nodeId = nodeId,
                            errors = list,
                            onOpen = { scope.launch { openDetail(it) } },
                            onRetry = { hash ->
                                scope.launch { post("/api/blocks/$hash/retry-resync", null) }
                            },
                            onPurge = { confirmPurge = it },
                        )
                    }
                }
            }
        }

        selected?.let { hash ->
            BlockDetailCard(hash, detail, onClose = {
                selected = null
                detail = null
            })
        }
    }

    if (confirmRetryAll) {
        ConfirmDialog(
            title = "全ブロックの再同期を要求",
            message = "失敗しているすべてのブロックの再同期を、全ノードに要求します。" +
                "対象が多いとノードの負荷が上がります。",
            onConfirm = {
                confirmRetryAll = false
                scope.launch {
                    post(
                        "/api/blocks/retry-resync",
                        AppJson.encodeToString(RetryResyncRequest.serializer(), RetryResyncRequest(all = true)),
                    )
                }
            },
            onDismiss = { confirmRetryAll = false },
        )
    }

    confirmPurge?.let { hash ->
        ConfirmDialog(
            title = "ブロックへの参照を削除",
            message = "ブロック $hash への参照を削除します。参照していたオブジェクトは壊れたままになります。" +
                "この操作は取り消せません。",
            requiredInput = hash,
            onConfirm = {
                confirmPurge = null
                scope.launch {
                    post(
                        "/api/blocks/purge",
                        AppJson.encodeToString(
                            PurgeBlocksRequest.serializer(),
                            PurgeBlocksRequest(listOf(hash)),
                        ),
                    )
                }
            },
            onDismiss = { confirmPurge = null },
        )
    }
}

@Composable
private fun BlockErrorCard(
    nodeId: String,
    errors: List<BlockError>,
    onOpen: (String) -> Unit,
    onRetry: (String) -> Unit,
    onPurge: (String) -> Unit,
) {
    if (errors.isEmpty()) return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("${nodeId.take(16)}（${errors.size} 件）", style = MaterialTheme.typography.titleSmall)

            errors.sortedByDescending { it.errorCount }.forEach { error ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusChip("${error.errorCount} 回失敗", StatusTone.ERROR)
                    Text(
                        error.blockHash.take(16),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    CopyButton(error.blockHash)
                    Text(
                        "参照 ${error.refcount} · 前回 ${error.lastTrySecsAgo} 秒前 · 次回 ${error.nextTryInSecs} 秒後",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { onOpen(error.blockHash) }) { Text("詳細") }
                    TextButton(onClick = { onRetry(error.blockHash) }) { Text("再同期") }
                    TextButton(onClick = { onPurge(error.blockHash) }) { Text("参照を削除") }
                }
            }
        }
    }
}

@Composable
private fun BlockDetailCard(hash: String, detail: MultiResponse<BlockInfo>?, onClose: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "ブロック ${hash.take(16)}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClose) { Text("閉じる") }
            }

            when (detail) {
                null -> LoadingView()

                else -> MultiResponseView(detail, emptyMessage = "このブロックの情報がありません") { nodeId, info ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "${nodeId.take(16)} · 参照 ${info.refcount} · バージョン ${info.versions.size} 件",
                            style = MaterialTheme.typography.bodySmall,
                        )

                        info.versions.forEach { version ->
                            // storedObject / upload は別モジュールの val なので、chained access のまま
                            // when の分岐で使うとスマートキャストが効かない。先にローカル変数へ受ける
                            val storedObject = version.backlink?.storedObject
                            val upload = version.backlink?.upload
                            val target = when {
                                storedObject != null ->
                                    "オブジェクト ${storedObject.key}（バケット ${storedObject.bucketId}）"

                                upload != null ->
                                    "アップロード ${upload.uploadId}" +
                                        upload.key?.let { key -> "（$key）" }.orEmpty()

                                else -> "参照元が不明"
                            }

                            Text(
                                listOfNotNull(
                                    version.versionId.take(16),
                                    target,
                                    "削除済み".takeIf { version.versionDeleted },
                                    "GC 済み".takeIf { version.garbageCollected },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
