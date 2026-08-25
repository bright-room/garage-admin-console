package net.brightroom.garage.web.screens.nodes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.brightroom.garage.shared.api.RepairRequest

/**
 * 接続先のノードを入力する。
 *
 * Garage は `<nodeId>@<host>:<port>` の形を要求する。1 行に 1 件入力させ、
 * 空行は無視する。形式の検証はしない。可否の実体は Garage 側にあり、
 * 結果は接続の応答で返る。
 */
@Composable
fun ConnectNodeDialog(onConfirm: (List<String>) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val nodes = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ノードを接続") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "1 行に 1 件、<nodeId>@<host>:<port> の形で入力してください。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(nodes) }, enabled = nodes.isNotEmpty()) { Text("実行") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
    )
}

/** Garage が受け付ける修復の種類。`scrub` だけは追加のコマンドを伴う。 */
private val repairTypes = listOf(
    "tables" to "テーブルの整合性を取り直す",
    "blocks" to "ブロックの再同期をやり直す",
    "versions" to "孤立したバージョンを掃除する",
    "multipartUploads" to "孤立したマルチパートアップロードを掃除する",
    "blockRefs" to "ブロック参照を掃除する",
    "blockRc" to "ブロックの参照カウントを数え直す",
    "rebalance" to "ブロックをドライブ間で再配置する",
    "aliases" to "バケットの別名を貼り直す",
    "clearResyncQueue" to "再同期キューを空にする",
    "scrub" to "保存されたデータを走査して破損を探す",
)

private val scrubCommands = listOf("start", "pause", "resume", "cancel")

/**
 * 修復の種類を選ぶ。
 *
 * 影響範囲の説明を必ず添える（spec §8.6）。修復はクラスタ全体に影響し、
 * 途中で止められないものもある。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepairDialog(onConfirm: (RepairRequest) -> Unit, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf(repairTypes.first()) }
    var scrubCommand by remember { mutableStateOf(scrubCommands.first()) }
    var typeExpanded by remember { mutableStateOf(false) }
    var commandExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修復を開始") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "修復はクラスタの全ノードで実行され、完了までノードの負荷が上がります。" +
                        "進行状況はワーカー画面で確認できます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                ExposedDropdownMenuBox(
                    expanded = typeExpanded,
                    onExpandedChange = { typeExpanded = it },
                ) {
                    OutlinedTextField(
                        value = selected.first,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("種類") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeExpanded) },
                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = typeExpanded,
                        onDismissRequest = { typeExpanded = false },
                    ) {
                        repairTypes.forEach { entry ->
                            DropdownMenuItem(
                                text = { Text("${entry.first} — ${entry.second}") },
                                onClick = {
                                    selected = entry
                                    typeExpanded = false
                                },
                            )
                        }
                    }
                }

                Text(selected.second, style = MaterialTheme.typography.bodyMedium)

                if (selected.first == "scrub") {
                    ExposedDropdownMenuBox(
                        expanded = commandExpanded,
                        onExpandedChange = { commandExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = scrubCommand,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("scrub の操作") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(commandExpanded) },
                            modifier = Modifier.menuAnchor(
                                ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                            ).fillMaxWidth(),
                        )
                        ExposedDropdownMenu(
                            expanded = commandExpanded,
                            onDismissRequest = { commandExpanded = false },
                        ) {
                            scrubCommands.forEach { command ->
                                DropdownMenuItem(
                                    text = { Text(command) },
                                    onClick = {
                                        scrubCommand = command
                                        commandExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        RepairRequest(
                            repairType = selected.first,
                            scrubCommand = scrubCommand.takeIf { selected.first == "scrub" },
                        ),
                    )
                },
            ) {
                Text("実行", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
    )
}
