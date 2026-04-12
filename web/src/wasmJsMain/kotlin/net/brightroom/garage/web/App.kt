package net.brightroom.garage.web

import androidx.compose.runtime.*
import net.brightroom.garage.web.navigation.AppScaffold
import net.brightroom.garage.web.navigation.Screen
import net.brightroom.garage.web.screens.PlaceholderScreen
import net.brightroom.garage.web.screens.dashboard.DashboardScreen
import net.brightroom.garage.web.theme.GarageAdminTheme

@Composable
fun App() {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Dashboard) }

    GarageAdminTheme {
        AppScaffold(
            currentScreen = currentScreen,
            onNavigate = { currentScreen = it },
        ) {
            when (currentScreen) {
                is Screen.Dashboard -> DashboardScreen()
                is Screen.Cluster -> PlaceholderScreen("Cluster")
                is Screen.Layout -> PlaceholderScreen("Layout")
                is Screen.Buckets -> PlaceholderScreen("Buckets")
                is Screen.Keys -> PlaceholderScreen("Keys")
                is Screen.S3Browser -> PlaceholderScreen("S3 Browser")
                is Screen.AdminTokens -> PlaceholderScreen("Admin Tokens")
                is Screen.Nodes -> PlaceholderScreen("Nodes")
                is Screen.Workers -> PlaceholderScreen("Workers")
                is Screen.Blocks -> PlaceholderScreen("Blocks")
                is Screen.BucketDetail -> PlaceholderScreen("Bucket Detail")
                is Screen.KeyDetail -> PlaceholderScreen("Key Detail")
                is Screen.ObjectBrowser -> PlaceholderScreen("Object Browser")
            }
        }
    }
}
