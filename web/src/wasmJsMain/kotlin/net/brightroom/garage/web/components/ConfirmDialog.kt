package net.brightroom.garage.web.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp

/**
 * 破壊的な操作の確認（spec §8.6）。
 *
 * **確認ボタンの文言は既定の「実行」のままにする。** 代わりに、それを開く画面側の
 * ボタンへ「バケットを削除」「権限を外す」のような固有の名前を付ける。同じ画面に
 * 「削除」が複数あると、押した対象を取り違えるし、e2e からも区別できない。
 *
 * @param requiredInput 指定すると、その文字列を打ち込むまで実行できない。
 *   バケットの削除のように、取り返しがつかず対象を取り違えやすい操作に使う。
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "実行",
    requiredInput: String? = null,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var typed by remember { mutableStateOf("") }
    val enabled = requiredInput == null || typed == requiredInput

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(message, style = MaterialTheme.typography.bodyMedium)

                if (requiredInput != null) {
                    Text(
                        "確認のため「$requiredInput」と入力してください",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = enabled) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
    )
}
