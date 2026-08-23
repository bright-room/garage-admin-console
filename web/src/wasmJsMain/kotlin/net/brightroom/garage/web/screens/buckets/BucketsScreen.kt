package net.brightroom.garage.web.screens.buckets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import net.brightroom.garage.shared.api.CreateBucketRequest
import net.brightroom.garage.shared.model.garage.BucketInfo
import net.brightroom.garage.shared.model.garage.BucketSummary
import net.brightroom.garage.web.api.ApiResult
import net.brightroom.garage.web.api.AppJson
import net.brightroom.garage.web.api.displayMessage
import net.brightroom.garage.web.api.getJson
import net.brightroom.garage.web.api.sendJson
import net.brightroom.garage.web.components.DataTable
import net.brightroom.garage.web.components.LoadingView
import net.brightroom.garage.web.components.ProblemView
import net.brightroom.garage.web.components.TableColumn
import net.brightroom.garage.web.session.LocalSession

@Composable
fun BucketsScreen(onOpen: (String) -> Unit) {
    val session = LocalSession.current
    val scope = rememberCoroutineScope()

    var buckets by remember { mutableStateOf<List<BucketSummary>?>(null) }
    var failure by remember { mutableStateOf<ApiResult.Failure?>(null) }
    var creating by remember { mutableStateOf(false) }

    suspend fun load() {
        when (
            val result = session.api.getJson(
                "/api/buckets",
                ListSerializer(BucketSummary.serializer()),
            )
        ) {
            is ApiResult.Success -> {
                buckets = result.value
                failure = null
            }

            is ApiResult.Failure -> failure = result

            ApiResult.Unauthorized -> session.invalidate()
        }
    }

    LaunchedEffect(Unit) { load() }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "バケット",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { scope.launch { load() } }) { Text("更新") }
            Button(onClick = { creating = true }) { Text("バケットを作成") }
        }

        failure?.let { ProblemView(it.problem, it.status, onRetry = { scope.launch { load() } }) }

        when (val current = buckets) {
            null -> if (failure == null) LoadingView()
            else -> BucketTable(current, onOpen)
        }
    }

    if (creating) {
        CreateBucketDialog(
            onDismiss = { creating = false },
            onCreated = {
                creating = false
                scope.launch { load() }
            },
        )
    }
}

@Composable
private fun BucketTable(buckets: List<BucketSummary>, onOpen: (String) -> Unit) {
    DataTable(
        items = buckets,
        onRowClick = { onOpen(it.id) },
        emptyMessage = "バケットがありません",
        columns = listOf(
            TableColumn(
                title = "名前",
                weight = 2f,
                value = { it.displayName },
            ),
            TableColumn(
                title = "ID",
                weight = 2f,
                value = { it.id },
                content = { bucket ->
                    Text(
                        bucket.id.take(16),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            ),
            TableColumn(
                title = "別名",
                value = { bucket ->
                    (bucket.globalAliases + bucket.localAliases.map { it.alias }).joinToString(", ")
                },
            ),
            TableColumn(
                title = "作成",
                value = { it.created?.toString().orEmpty() },
                comparator = compareBy { it.created },
                content = { bucket ->
                    Text(
                        bucket.created?.toString()?.substringBefore('T') ?: "-",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
            ),
        ),
    )
}

/**
 * バケットの作成。
 *
 * global alias は省略できる（Garage は alias 無しのバケットを許す）。ただし
 * alias が無いと S3 でアドレスできないため、その旨を添える（spec §6.5）。
 */
@Composable
private fun CreateBucketDialog(onDismiss: () -> Unit, onCreated: () -> Unit) {
    val session = LocalSession.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("バケットを作成") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("グローバル別名") },
                    singleLine = true,
                )
                Text(
                    "別名を付けないバケットは S3 API から参照できません",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !sending,
                onClick = {
                    sending = true
                    scope.launch {
                        val body = AppJson.encodeToString(
                            CreateBucketRequest.serializer(),
                            CreateBucketRequest(globalAlias = name.ifBlank { null }),
                        )

                        when (
                            val result = session.api.sendJson(
                                HttpMethod.Post,
                                "/api/buckets",
                                body,
                                BucketInfo.serializer(),
                            )
                        ) {
                            is ApiResult.Success -> onCreated()

                            is ApiResult.Failure -> {
                                error = result.problem.displayMessage
                                sending = false
                            }

                            ApiResult.Unauthorized -> session.invalidate()
                        }
                    }
                },
            ) {
                Text("作成")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}
