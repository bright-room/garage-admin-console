package net.brightroom.garage.web.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.ktor.http.HttpStatusCode
import net.brightroom.garage.shared.api.ProblemDetails
import net.brightroom.garage.web.api.displayMessage

@Composable
fun LoadingView() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
fun ErrorView(message: String, onRetry: (() -> Unit)? = null) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            onRetry?.let {
                TextButton(onClick = it) { Text("再試行") }
            }
        }
    }
}

/**
 * scope 不足でこのセクションを参照できないことを伝える。
 *
 * 403 は正常系であり、画面全体を失敗させないための表示である（spec §6.3）。
 */
@Composable
fun DeniedView(operation: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "このトークンでは参照できません",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "必要な scope: $operation",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 何も無いことを伝える。次にできることが分かっているなら導線を置く。
 */
@Composable
fun EmptyState(message: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/**
 * 失敗をそのまま画面に出す。
 *
 * 403 は scope 不足であり、コンソールの不具合ではないことが伝わる文言にする
 * （spec §6.3）。それ以外は problem details の文言をそのまま見せる。
 */
@Composable
fun ProblemView(problem: ProblemDetails, status: HttpStatusCode, onRetry: (() -> Unit)? = null) {
    if (status == HttpStatusCode.Forbidden) {
        DeniedView(problem.operation ?: "不明な operation")
        return
    }

    ErrorView(problem.displayMessage, onRetry)
}
