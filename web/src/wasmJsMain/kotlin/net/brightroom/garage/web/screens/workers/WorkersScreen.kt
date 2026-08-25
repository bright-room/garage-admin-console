package net.brightroom.garage.web.screens.workers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import net.brightroom.garage.shared.api.NodeActionOutcome
import net.brightroom.garage.shared.api.SetWorkerVariableRequest
import net.brightroom.garage.shared.model.garage.MultiResponse
import net.brightroom.garage.shared.model.garage.WorkerInfo
import net.brightroom.garage.shared.model.garage.WorkerState
import net.brightroom.garage.web.api.ApiResult
import net.brightroom.garage.web.api.AppJson
import net.brightroom.garage.web.api.getJson
import net.brightroom.garage.web.api.sendJson
import net.brightroom.garage.web.components.ConfirmDialog
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

/** 状態の重さ。エラーを抱えたワーカーは状態にかかわらず目立たせる。 */
private fun WorkerInfo.tone(): StatusTone = when {
    consecutiveErrors > 0 -> StatusTone.ERROR
    errors > 0 -> StatusTone.WARNING
    state is WorkerState.Throttled -> StatusTone.WARNING
    state == WorkerState.Busy -> StatusTone.SUCCESS
    else -> StatusTone.NEUTRAL
}

@Composable
fun WorkersScreen() {
    val session = LocalSession.current
    val scope = rememberCoroutineScope()

    var workers by remember { mutableStateOf<MultiResponse<List<WorkerInfo>>?>(null) }
    var variables by remember { mutableStateOf<MultiResponse<Map<String, String>>?>(null) }
    var failure by remember { mutableStateOf<ApiResult.Failure?>(null) }
    var outcome by remember { mutableStateOf<NodeActionOutcome?>(null) }
    var pending by remember { mutableStateOf<SetWorkerVariableRequest?>(null) }
    var updatedAt by remember { mutableStateOf(0) }

    // 編集中の値。サーバーの値で上書きしないよう、変数名ごとに保持する
    val drafts = remember { mutableStateMapOf<String, String>() }

    suspend fun load() {
        when (
            val result = session.api.getJson(
                "/api/workers",
                MultiResponse.serializer(ListSerializer(WorkerInfo.serializer())),
            )
        ) {
            is ApiResult.Success -> {
                workers = result.value
                failure = null
                updatedAt++
            }

            is ApiResult.Failure -> failure = result

            ApiResult.Unauthorized -> {
                session.invalidate()
                return
            }
        }

        (
            session.api.getJson(
                "/api/workers/variables",
                MultiResponse.serializer(MapSerializer(String.serializer(), String.serializer())),
            ) as? ApiResult.Success
            )?.let { variables = it.value }
    }

    val polling = rememberPolling(POLL_INTERVAL_MILLIS) { load() }

    LaunchedEffect(updatedAt) {
        if (updatedAt > 0) polling.markUpdated()
    }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PollingHeader("ワーカー", polling, onRefresh = { scope.launch { load() } })

        outcome?.let { NodeOutcomeNotice(it) }
        failure?.let { ProblemView(it.problem, it.status, onRetry = { scope.launch { load() } }) }

        variables?.let { current ->
            VariablesCard(
                variables = current,
                drafts = drafts,
                onSubmit = { pending = it },
            )
        }

        when (val current = workers) {
            null -> if (failure == null) LoadingView()

            else -> MultiResponseView(current, emptyMessage = "ワーカーがありません") { nodeId, list ->
                WorkerCard(nodeId, list)
            }
        }
    }

    pending?.let { request ->
        ConfirmDialog(
            title = "ワーカーの設定を変更",
            message = "${request.variable} を ${request.value} に変更します。" +
                "全ノードに適用され、ワーカーの動作がすぐに変わります。",
            onConfirm = {
                pending = null
                scope.launch {
                    when (
                        val result = session.api.sendJson(
                            HttpMethod.Put,
                            "/api/workers/variables",
                            AppJson.encodeToString(SetWorkerVariableRequest.serializer(), request),
                            NodeActionOutcome.serializer(),
                        )
                    ) {
                        is ApiResult.Success -> {
                            outcome = result.value
                            drafts.remove(request.variable)
                            load()
                        }

                        is ApiResult.Failure -> failure = result

                        ApiResult.Unauthorized -> session.invalidate()
                    }
                }
            },
            onDismiss = { pending = null },
        )
    }
}

/**
 * ワーカーの設定変数。
 *
 * Garage が返したキーだけを並べる（P3-8）。読み取り専用の変数も混ざるが、
 * 書き込めないものは Garage が失敗を返し、それが結果として表示される。
 */
@Composable
private fun VariablesCard(
    variables: MultiResponse<Map<String, String>>,
    drafts: MutableMap<String, String>,
    onSubmit: (SetWorkerVariableRequest) -> Unit,
) {
    // ノードごとに同じ変数を持つ。名前で束ねて 1 行にする
    val names = variables.success.values.flatMap { it.keys }.distinct().sorted()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("設定変数", style = MaterialTheme.typography.titleSmall)
            Text(
                "変更は全ノードに適用されます。読み取り専用の変数もここに並びます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            variables.error.takeIf { it.isNotEmpty() }?.let { failures ->
                Text(
                    "${failures.size} 台のノードから設定変数を取得できませんでした",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            names.forEach { name ->
                val values = variables.success.values.mapNotNull { it[name] }.distinct()
                val shown = values.singleOrNull() ?: values.joinToString(" / ")

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(220.dp))

                    OutlinedTextField(
                        value = drafts[name] ?: shown,
                        onValueChange = { drafts[name] = it },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )

                    TextButton(
                        enabled = drafts[name]?.takeIf { it != shown } != null,
                        onClick = { onSubmit(SetWorkerVariableRequest(name, drafts.getValue(name))) },
                    ) {
                        Text("設定")
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkerCard(nodeId: String, workers: List<WorkerInfo>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(nodeId.take(16), style = MaterialTheme.typography.titleSmall)

            // エラーを抱えたものと動いているものを上に出す
            workers.sortedWith(
                compareByDescending<WorkerInfo> { it.consecutiveErrors }
                    .thenByDescending { it.errors }
                    .thenBy { it.name },
            ).forEach { WorkerRow(it) }
        }
    }
}

@Composable
private fun WorkerRow(worker: WorkerInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusChip(worker.state.label, worker.tone())
            Text(worker.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))

            worker.progress?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            worker.queueLength?.let {
                Text(
                    "待ち $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val details = listOfNotNull(
            "ID ${worker.id}",
            worker.tranquility?.let { "tranquility $it" },
            worker.errors.takeIf { it > 0 }?.let { "エラー $it 件" },
            worker.consecutiveErrors.takeIf { it > 0 }?.let { "連続エラー $it 件" },
            worker.persistentErrors?.takeIf { it > 0 }?.let { "恒久エラー $it 件" },
            worker.lastError?.let { "直近のエラー ${it.secsAgo} 秒前: ${it.message}" },
        ) + worker.freeform

        Text(
            details.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
