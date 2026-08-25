package net.brightroom.garage.web.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.brightroom.garage.shared.api.NodeActionOutcome
import net.brightroom.garage.shared.model.garage.MultiResponse

/**
 * ノード別に成否が割れる応答を、潰さずに描く（spec §7.3）。
 *
 * 失敗したノードを先に出す。「node-c だけ失敗している」ことが見えるのが
 * この表示の目的であり、成功分に紛れさせては意味がない。
 */
@Composable
fun <T> MultiResponseView(
    response: MultiResponse<T>,
    emptyMessage: String,
    content: @Composable (nodeId: String, value: T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (response.error.isNotEmpty()) {
            FailedNodesCard(response.error)
        }

        if (response.success.isEmpty()) {
            if (response.error.isEmpty()) EmptyState(emptyMessage)
            return@Column
        }

        response.success.entries.sortedBy { it.key }.forEach { (nodeId, value) ->
            content(nodeId, value)
        }
    }
}

/** 副作用だけの操作の結果。成功したノード数と、失敗したノードの理由を出す。 */
@Composable
fun NodeOutcomeNotice(outcome: NodeActionOutcome) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (outcome.ok.isNotEmpty()) {
            Text(
                "${outcome.ok.size} 台のノードで実行しました",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (outcome.failed.isNotEmpty()) {
            FailedNodesCard(outcome.failed)
        }
    }
}

@Composable
private fun FailedNodesCard(failures: Map<String, String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("応答しなかったノード", style = MaterialTheme.typography.titleSmall)

            failures.entries.sortedBy { it.key }.forEach { (nodeId, message) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusChip(nodeId.take(12), StatusTone.ERROR)
                    Text(message, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
