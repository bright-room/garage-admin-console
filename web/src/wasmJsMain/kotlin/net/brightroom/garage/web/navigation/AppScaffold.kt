@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package net.brightroom.garage.web.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.browser.window
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.brightroom.garage.shared.session.IdleState
import net.brightroom.garage.web.router.RouterState
import net.brightroom.garage.web.session.LocalSession
import org.w3c.dom.events.Event

@Composable
fun AppScaffold(
    router: RouterState,
    content: @Composable () -> Unit,
) {
    val session = LocalSession.current
    val scope = rememberCoroutineScope()
    var idleWarning by remember { mutableStateOf(false) }
    var remainingSeconds by remember { mutableStateOf(0L) }

    // アイドル判定の基準は利用者の操作。ポーリングの通信では延長しない（spec §6.6）
    DisposableEffect(Unit) {
        val listener: (Event) -> Unit = { session.recordActivity() }
        window.addEventListener("click", listener)
        window.addEventListener("keydown", listener)

        onDispose {
            window.removeEventListener("click", listener)
            window.removeEventListener("keydown", listener)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            when (session.idleState()) {
                IdleState.ACTIVE -> idleWarning = false
                IdleState.WARNING -> {
                    idleWarning = true
                    remainingSeconds = session.idleRemainingSeconds()
                }

                IdleState.EXPIRED -> {
                    session.signOut()
                    return@LaunchedEffect
                }
            }
        }
    }

    Row(modifier = Modifier.fillMaxSize()) {
        Sidebar(current = router.current, onNavigate = router::navigate)

        Column(modifier = Modifier.fillMaxSize()) {
            Header(
                idleWarning = idleWarning,
                remainingSeconds = remainingSeconds,
                onSignOut = { scope.launch { session.signOut() } },
            )
            HorizontalDivider()

            Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun Header(
    idleWarning: Boolean,
    remainingSeconds: Long,
    onSignOut: () -> Unit,
) {
    val session = LocalSession.current
    val info = session.info

    val scopeText = when {
        info == null -> "scope 不明"
        info.scope.contains("*") -> "すべての operation を許可"
        else -> "${info.scope.size} 個の operation を許可"
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(info?.name ?: "-", style = MaterialTheme.typography.titleSmall)
            Text(
                expirationLabel(scopeText, info?.expiration?.toString()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (idleWarning) {
            Text(
                "操作がないため $remainingSeconds 秒後にログアウトします",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        TextButton(onClick = onSignOut) { Text("ログアウト") }
    }
}

private fun expirationLabel(scopeText: String, expiration: String?): String =
    if (expiration == null) "$scopeText ・ 無期限" else "$scopeText ・ 期限 $expiration"
