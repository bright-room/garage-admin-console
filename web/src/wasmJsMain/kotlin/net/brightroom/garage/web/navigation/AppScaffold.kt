package net.brightroom.garage.web.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun AppScaffold(
    currentScreen: Screen,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(modifier = modifier.fillMaxSize()) {
        Sidebar(
            currentScreen = currentScreen,
            onNavigate = onNavigate,
        )
        Box(modifier = Modifier.fillMaxSize()) {
            content()
        }
    }
}
