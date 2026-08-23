package net.brightroom.garage.web.screens.keys

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import net.brightroom.garage.shared.api.CreateKeyRequest
import net.brightroom.garage.shared.api.ImportKeyRequest
import net.brightroom.garage.shared.model.garage.KeyInfo
import net.brightroom.garage.shared.model.garage.KeySummary
import net.brightroom.garage.web.api.ApiResult
import net.brightroom.garage.web.api.AppJson
import net.brightroom.garage.web.api.displayMessage
import net.brightroom.garage.web.api.getJson
import net.brightroom.garage.web.api.sendJson
import net.brightroom.garage.web.components.CopyButton
import net.brightroom.garage.web.components.DataTable
import net.brightroom.garage.web.components.LoadingView
import net.brightroom.garage.web.components.ProblemView
import net.brightroom.garage.web.session.LocalSession
import net.brightroom.garage.web.components.Column as TableColumn

@Composable
fun KeysScreen(onOpen: (String) -> Unit) {
    val session = LocalSession.current
    val scope = rememberCoroutineScope()

    var keys by remember { mutableStateOf<List<KeySummary>?>(null) }
    var failure by remember { mutableStateOf<ApiResult.Failure?>(null) }
    var creating by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var created by remember { mutableStateOf<KeyInfo?>(null) }

    suspend fun load() {
        when (val result = session.api.getJson("/api/keys", ListSerializer(KeySummary.serializer()))) {
            is ApiResult.Success -> {
                keys = result.value
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
                "アクセスキー",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { scope.launch { load() } }) { Text("更新") }
            TextButton(onClick = { importing = true }) { Text("インポート") }
            Button(onClick = { creating = true }) { Text("キーを作成") }
        }

        failure?.let { ProblemView(it.problem, it.status, onRetry = { scope.launch { load() } }) }

        when (val current = keys) {
            null -> if (failure == null) LoadingView()

            else -> DataTable(
                items = current,
                onRowClick = { onOpen(it.id) },
                emptyMessage = "アクセスキーがありません",
                searchPlaceholder = "名前や ID で絞り込み",
                columns = listOf(
                    TableColumn(title = "名前", weight = 2f, value = { it.name }),
                    TableColumn(title = "ID", weight = 2f, value = { it.id }),
                    TableColumn(
                        title = "期限",
                        value = { it.expiration?.toString() ?: "無期限" },
                        comparator = compareBy { it.expiration },
                        content = { key ->
                            Text(
                                when {
                                    key.expired -> "失効"
                                    key.expiration == null -> "無期限"
                                    else -> key.expiration.toString().substringBefore('T')
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (key.expired) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        },
                    ),
                ),
            )
        }
    }

    if (creating) {
        CreateKeyDialog(
            onDismiss = { creating = false },
            onCreated = { key ->
                creating = false
                created = key
                scope.launch { load() }
            },
        )
    }

    if (importing) {
        ImportKeyDialog(
            onDismiss = { importing = false },
            onImported = {
                importing = false
                scope.launch { load() }
            },
        )
    }

    created?.let { key ->
        SecretOnceDialog(key) { created = null }
    }
}

@Composable
private fun CreateKeyDialog(onDismiss: () -> Unit, onCreated: (KeyInfo) -> Unit) {
    val session = LocalSession.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var allowCreateBucket by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("アクセスキーを作成") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名前") },
                    singleLine = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = allowCreateBucket, onCheckedChange = { allowCreateBucket = it })
                    Text("バケットの作成を許可する")
                }
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    scope.launch {
                        val body = AppJson.encodeToString(
                            CreateKeyRequest.serializer(),
                            CreateKeyRequest(name = name, allowCreateBucket = allowCreateBucket),
                        )

                        when (
                            val result = session.api.sendJson(
                                HttpMethod.Post,
                                "/api/keys",
                                body,
                                KeyInfo.serializer(),
                            )
                        ) {
                            is ApiResult.Success -> onCreated(result.value)
                            is ApiResult.Failure -> error = result.problem.displayMessage
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

@Composable
private fun ImportKeyDialog(onDismiss: () -> Unit, onImported: () -> Unit) {
    val session = LocalSession.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var accessKeyId by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("アクセスキーをインポート") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "他のクラスタから持ち込んだキーを登録します",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名前") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = accessKeyId,
                    onValueChange = { accessKeyId = it },
                    label = { Text("アクセスキー ID") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = { Text("シークレットアクセスキー") },
                    singleLine = true,
                )
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && accessKeyId.isNotBlank() && secret.isNotBlank(),
                onClick = {
                    scope.launch {
                        val body = AppJson.encodeToString(
                            ImportKeyRequest.serializer(),
                            ImportKeyRequest(name = name, accessKeyId = accessKeyId, secretAccessKey = secret),
                        )

                        when (
                            val result = session.api.sendJson(
                                HttpMethod.Post,
                                "/api/keys/import",
                                body,
                                KeyInfo.serializer(),
                            )
                        ) {
                            is ApiResult.Success -> onImported()
                            is ApiResult.Failure -> error = result.problem.displayMessage
                            ApiResult.Unauthorized -> session.invalidate()
                        }
                    }
                },
            ) {
                Text("インポート")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

/**
 * 作成直後の secret を一度だけ見せる。
 *
 * Garage は作成時の応答でしか平文を返さない。閉じたら取り直しには
 * `showSecretKey=true` が要る。
 */
@Composable
private fun SecretOnceDialog(key: KeyInfo, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${key.name} を作成しました") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("アクセスキー ID", style = MaterialTheme.typography.labelSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(key.accessKeyId, modifier = Modifier.weight(1f))
                    CopyButton(key.accessKeyId)
                }

                Text("シークレットアクセスキー", style = MaterialTheme.typography.labelSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(key.secretAccessKey.orEmpty(), modifier = Modifier.weight(1f))
                    CopyButton(key.secretAccessKey.orEmpty())
                }

                Text(
                    "この画面を閉じると、シークレットは詳細画面から取り直すことになります",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}
