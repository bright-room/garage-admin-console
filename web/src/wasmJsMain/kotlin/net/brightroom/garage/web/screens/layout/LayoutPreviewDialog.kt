package net.brightroom.garage.web.screens.layout

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import net.brightroom.garage.shared.model.garage.LayoutPreview
import net.brightroom.garage.web.components.formatBytes

/**
 * 適用前の確認（spec §8.6）。
 *
 * Garage が計算した結果をそのまま見せる。`message` は「パースするな」と
 * 仕様に明記されているため、整形せず行のまま出す。
 */
@Composable
fun LayoutPreviewDialog(preview: LayoutPreview, onApply: () -> Unit, onDismiss: () -> Unit) {
    val computed = preview as? LayoutPreview.Computed

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("レイアウトの変更を適用") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (preview) {
                    is LayoutPreview.Failed -> Text(
                        "このままでは適用できません: ${preview.error}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )

                    is LayoutPreview.Computed -> {
                        Text(
                            "適用すると v${preview.newLayout.version} になります。" +
                                "パーティションの移動が始まり、完了するまでノードの負荷が上がります。",
                            style = MaterialTheme.typography.bodyMedium,
                        )

                        preview.statistics?.let { stat ->
                            Text(
                                listOfNotNull(
                                    "複製数 ${stat.replicationFactor}",
                                    "実効ゾーン冗長度 ${stat.effectiveZoneRedundancy}",
                                    "パーティションサイズ ${formatBytes(stat.partitionSize)}",
                                    "実効容量 ${formatBytes(stat.effectiveCapacity)}",
                                    stat.totalMovedPartitions?.let { "移動するパーティション $it" },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            if (stat.lowPartitionSize) {
                                Text(
                                    "パーティションが小さすぎます。容量の割り当てを見直してください。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }

                            if (stat.lowUsableCapacity) {
                                Text(
                                    "割り当てた容量を活かしきれていません。ゾーンの偏りを見直してください。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }

                        Text(
                            preview.message.joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onApply, enabled = computed != null) {
                Text("適用する", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
    )
}
