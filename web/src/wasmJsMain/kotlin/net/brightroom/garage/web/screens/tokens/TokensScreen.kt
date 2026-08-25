package net.brightroom.garage.web.screens.tokens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import net.brightroom.garage.shared.api.CreateAdminTokenRequest
import net.brightroom.garage.shared.api.CreatedAdminToken
import net.brightroom.garage.shared.api.UpdateAdminTokenRequest
import net.brightroom.garage.shared.model.garage.AdminToken
import net.brightroom.garage.web.api.ApiResult
import net.brightroom.garage.web.api.AppJson
import net.brightroom.garage.web.api.getJson
import net.brightroom.garage.web.api.sendEmpty
import net.brightroom.garage.web.api.sendJson
import net.brightroom.garage.web.components.ConfirmDialog
import net.brightroom.garage.web.components.CopyButton
import net.brightroom.garage.web.components.DataTable
import net.brightroom.garage.web.components.LoadingView
import net.brightroom.garage.web.components.ProblemView
import net.brightroom.garage.web.components.StatusChip
import net.brightroom.garage.web.components.StatusTone
import net.brightroom.garage.web.components.TableColumn
import net.brightroom.garage.web.session.LocalSession

/**
 * Admin token の管理（spec §8.2 の「設定」）。
 *
 * ポーリングはしない（spec §8.5）。トークンは頻繁に変わるものではない。
 */
@Composable
fun TokensScreen() {
    val session = LocalSession.current
    val scope = rememberCoroutineScope()

    var tokens by remember { mutableStateOf<List<AdminToken>?>(null) }
    var failure by remember { mutableStateOf<ApiResult.Failure?>(null) }
    var created by remember { mutableStateOf<CreatedAdminToken?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<AdminToken?>(null) }
    var editing by remember { mutableStateOf<AdminToken?>(null) }

    val currentId = session.info?.id

    suspend fun load() {
        when (
            val result = session.api.getJson("/api/admin-tokens", ListSerializer(AdminToken.serializer()))
        ) {
            is ApiResult.Success -> {
                tokens = result.value
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
            Text("Admin token", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { scope.launch { load() } }) { Text("更新") }
            TextButton(onClick = { showCreate = true }) { Text("トークンを作成") }
        }

        created?.let { SecretCard(it, onDismiss = { created = null }) }

        failure?.let { ProblemView(it.problem, it.status, onRetry = { scope.launch { load() } }) }

        when (val current = tokens) {
            null -> if (failure == null) LoadingView()

            else -> DataTable(
                items = current,
                emptyMessage = "トークンがありません",
                columns = listOf(
                    TableColumn(
                        title = "名前",
                        value = { it.name },
                        weight = 2f,
                    ),
                    TableColumn(
                        title = "状態",
                        value = { token -> token.stateLabel(currentId) },
                        content = { token ->
                            StatusChip(token.stateLabel(currentId), token.stateTone(currentId))
                        },
                    ),
                    TableColumn(
                        title = "有効期限",
                        value = { it.expiration?.toString() ?: "無期限" },
                        comparator = compareBy { it.expiration },
                    ),
                    TableColumn(
                        title = "scope",
                        value = { it.scope.joinToString(", ") },
                        weight = 2f,
                    ),
                    TableColumn(
                        title = "操作",
                        value = { "" },
                        content = { token ->
                            if (token.id == null) {
                                Text(
                                    "設定ファイル由来",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Row {
                                    TextButton(onClick = { editing = token }) { Text("編集") }
                                    TextButton(onClick = { confirmDelete = token }) { Text("削除") }
                                }
                            }
                        },
                    ),
                ),
            )
        }
    }

    if (showCreate) {
        CreateTokenDialog(
            onConfirm = { request ->
                showCreate = false
                scope.launch {
                    when (
                        val result = session.api.sendJson(
                            HttpMethod.Post,
                            "/api/admin-tokens",
                            AppJson.encodeToString(CreateAdminTokenRequest.serializer(), request),
                            CreatedAdminToken.serializer(),
                        )
                    ) {
                        is ApiResult.Success -> {
                            created = result.value
                            load()
                        }

                        is ApiResult.Failure -> failure = result

                        ApiResult.Unauthorized -> session.invalidate()
                    }
                }
            },
            onDismiss = { showCreate = false },
        )
    }

    editing?.let { token ->
        EditTokenDialog(
            token = token,
            isCurrent = token.id != null && token.id == currentId,
            onConfirm = { request ->
                editing = null
                scope.launch {
                    when (
                        val result = session.api.sendJson(
                            HttpMethod.Patch,
                            "/api/admin-tokens/${token.id}",
                            AppJson.encodeToString(UpdateAdminTokenRequest.serializer(), request),
                            AdminToken.serializer(),
                        )
                    ) {
                        is ApiResult.Success -> load()
                        is ApiResult.Failure -> failure = result
                        ApiResult.Unauthorized -> session.invalidate()
                    }
                }
            },
            onDismiss = { editing = null },
        )
    }

    confirmDelete?.let { token ->
        val isCurrent = token.id != null && token.id == currentId

        ConfirmDialog(
            title = "トークンを削除",
            message = buildString {
                append("トークン「${token.name}」を削除します。この操作は取り消せません。")
                if (isCurrent) {
                    append("\n\nこれはいまログインに使っているトークンです。削除するとログイン画面に戻ります。")
                }
            },
            requiredInput = token.name,
            onConfirm = {
                confirmDelete = null
                scope.launch {
                    when (session.api.sendEmpty(HttpMethod.Delete, "/api/admin-tokens/${token.id}")) {
                        is ApiResult.Success -> load()

                        is ApiResult.Failure -> load()

                        // 自分のトークンを消した場合はここに来る。ログイン画面へ戻る
                        ApiResult.Unauthorized -> session.invalidate()
                    }
                }
            },
            onDismiss = { confirmDelete = null },
        )
    }
}

private fun AdminToken.stateLabel(currentId: String?): String = when {
    expired -> "期限切れ"
    id != null && id == currentId -> "使用中"
    else -> "有効"
}

private fun AdminToken.stateTone(currentId: String?): StatusTone = when {
    expired -> StatusTone.ERROR
    id != null && id == currentId -> StatusTone.SUCCESS
    else -> StatusTone.NEUTRAL
}

/** 作成直後にだけ出る。Garage は secret を二度と返さない。 */
@Composable
private fun SecretCard(created: CreatedAdminToken, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("「${created.token.name}」を作成しました", style = MaterialTheme.typography.titleSmall)
            Text(
                "この値が表示されるのは一度だけです。閉じると二度と取得できません。",
                style = MaterialTheme.typography.bodySmall,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(created.secretToken, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                CopyButton(created.secretToken)
                TextButton(onClick = onDismiss) { Text("閉じる") }
            }
        }
    }
}

@Composable
private fun CreateTokenDialog(onConfirm: (CreateAdminTokenRequest) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var scopeText by remember { mutableStateOf("*") }

    val scopes = scopeText.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val grantsEscalation = scopes.any { it == "*" || it == "CreateAdminToken" || it == "UpdateAdminToken" }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("トークンを作成") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "名前は「誰に渡したか」が分かるものにしてください。Garage には利用者の概念が無く、" +
                        "権限の管理はトークン名に頼ります。",
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
                    value = scopeText,
                    onValueChange = { scopeText = it },
                    label = { Text("scope（カンマ区切り、* ですべて）") },
                    modifier = Modifier.fillMaxWidth(),
                )

                if (grantsEscalation) {
                    Text(
                        "この scope はトークンの発行・更新を許すため、実質的にすべての権限と同じです。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && scopes.isNotEmpty(),
                onClick = { onConfirm(CreateAdminTokenRequest(name = name.trim(), scope = scopes)) },
            ) {
                Text("作成")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
    )
}

@Composable
private fun EditTokenDialog(
    token: AdminToken,
    isCurrent: Boolean,
    onConfirm: (UpdateAdminTokenRequest) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(token.id) { mutableStateOf(token.name) }
    var scopeText by remember(token.id) { mutableStateOf(token.scope.joinToString(", ")) }

    val scopes = scopeText.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("トークンを編集") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (isCurrent) {
                    Text(
                        "これはいまログインに使っているトークンです。scope を狭めると、" +
                            "この画面を含む一部の操作が行えなくなります。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名前") },
                    singleLine = true,
                )

                OutlinedTextField(
                    value = scopeText,
                    onValueChange = { scopeText = it },
                    label = { Text("scope（カンマ区切り）") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && scopes.isNotEmpty(),
                onClick = { onConfirm(UpdateAdminTokenRequest(name = name.trim(), scope = scopes)) },
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
    )
}
