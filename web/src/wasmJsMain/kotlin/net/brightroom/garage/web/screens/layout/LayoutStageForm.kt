package net.brightroom.garage.web.screens.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.brightroom.garage.shared.api.StageRolesRequest
import net.brightroom.garage.shared.model.garage.ClusterLayout
import net.brightroom.garage.shared.model.garage.LayoutParameters
import net.brightroom.garage.shared.model.garage.NodeRoleChange
import net.brightroom.garage.shared.model.garage.ZoneRedundancy

/**
 * ロールの割り当てを stage する（P3-5）。
 *
 * 容量は GiB で入力させる。バイトで入力させると桁を間違えやすく、Garage の
 * CLI も人が読める単位を受け付ける。gateway にすると容量を送らない。
 */
@Composable
fun LayoutStageForm(nodeIds: List<String>, current: ClusterLayout, onStage: (StageRolesRequest) -> Unit) {
    var nodeId by remember { mutableStateOf(nodeIds.firstOrNull().orEmpty()) }
    var zone by remember { mutableStateOf("") }
    var capacityGib by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    var gateway by remember { mutableStateOf(false) }
    var zoneRedundancy by remember { mutableStateOf("") }

    val capacity = capacityGib.trim().toLongOrNull()
    val canAssign = nodeId.isNotBlank() && zone.isNotBlank() && (gateway || capacity != null)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("ロールを stage", style = MaterialTheme.typography.titleSmall)
            Text(
                "stage しただけでは反映されません。下の「適用」で確認してから反映します。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = nodeId,
                onValueChange = { nodeId = it },
                label = { Text("ノード ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = zone,
                onValueChange = { zone = it },
                label = { Text("ゾーン") },
                singleLine = true,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = gateway, onCheckedChange = { gateway = it })
                Text("gateway として割り当てる（容量を持たない）", style = MaterialTheme.typography.bodySmall)
            }

            if (!gateway) {
                OutlinedTextField(
                    value = capacityGib,
                    onValueChange = { capacityGib = it },
                    label = { Text("容量（GiB）") },
                    singleLine = true,
                )
            }

            OutlinedTextField(
                value = tags,
                onValueChange = { tags = it },
                label = { Text("タグ（カンマ区切り）") },
                singleLine = true,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                    enabled = canAssign,
                    onClick = {
                        onStage(
                            StageRolesRequest(
                                roles = listOf(
                                    NodeRoleChange.Assign(
                                        id = nodeId.trim(),
                                        zone = zone.trim(),
                                        tags = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                                        capacity = if (gateway) null else capacity?.let { it * GIB },
                                    ),
                                ),
                            ),
                        )
                    },
                ) {
                    Text("割り当てを stage")
                }

                TextButton(
                    enabled = nodeId.isNotBlank(),
                    onClick = { onStage(StageRolesRequest(roles = listOf(NodeRoleChange.Remove(nodeId.trim())))) },
                ) {
                    Text("このノードを外す")
                }
            }

            Text("ゾーン冗長度", style = MaterialTheme.typography.titleSmall)
            Text(
                "現在: ${current.parameters?.zoneRedundancy.describe()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = zoneRedundancy,
                    onValueChange = { zoneRedundancy = it },
                    label = { Text("最小ゾーン数（空なら maximum）") },
                    singleLine = true,
                )

                TextButton(
                    onClick = {
                        val zones = zoneRedundancy.trim().toIntOrNull()

                        onStage(
                            StageRolesRequest(
                                parameters = LayoutParameters(
                                    if (zones == null) ZoneRedundancy.Maximum else ZoneRedundancy.AtLeast(zones),
                                ),
                            ),
                        )
                    },
                ) {
                    Text("冗長度を stage")
                }
            }
        }
    }
}

private const val GIB = 1024L * 1024L * 1024L

private fun ZoneRedundancy?.describe(): String = when (this) {
    null -> "未設定"
    ZoneRedundancy.Maximum -> "maximum（可能な限り多くのゾーン）"
    is ZoneRedundancy.AtLeast -> "atLeast $zones"
}
