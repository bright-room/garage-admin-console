package net.brightroom.garage.web.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun Sidebar(
    currentScreen: Screen,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.width(240.dp).fillMaxHeight(),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "Garage Admin",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )

            Spacer(Modifier.height(8.dp))

            Screen.sidebarItems.forEach { screen ->
                NavigationDrawerItem(
                    label = { Text(screen.title) },
                    selected = currentScreen::class == screen::class,
                    onClick = { onNavigate(screen) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }
    }
}
