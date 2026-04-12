package net.brightroom.garage.web

import androidx.compose.runtime.*
import net.brightroom.garage.web.navigation.AppScaffold
import net.brightroom.garage.web.navigation.Screen
import net.brightroom.garage.web.screens.PlaceholderScreen
import net.brightroom.garage.web.screens.bucket.BucketDetailScreen
import net.brightroom.garage.web.screens.bucket.BucketListScreen
import net.brightroom.garage.web.screens.cluster.ClusterScreen
import net.brightroom.garage.web.screens.dashboard.DashboardScreen
import net.brightroom.garage.web.screens.key.KeyDetailScreen
import net.brightroom.garage.web.screens.key.KeyListScreen
import net.brightroom.garage.web.screens.layout.LayoutScreen
import net.brightroom.garage.web.theme.GarageAdminTheme

@Composable
fun App() {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Dashboard) }
    val onNavigate: (Screen) -> Unit = { currentScreen = it }

    GarageAdminTheme {
        AppScaffold(
            currentScreen = currentScreen,
            onNavigate = onNavigate,
        ) {
            when (val screen = currentScreen) {
                is Screen.Dashboard -> DashboardScreen()
                is Screen.Cluster -> ClusterScreen()
                is Screen.Layout -> LayoutScreen()
                is Screen.Buckets -> BucketListScreen(onNavigate)
                is Screen.Keys -> KeyListScreen(onNavigate)
                is Screen.BucketDetail -> BucketDetailScreen(screen.bucketId, onNavigate)
                is Screen.KeyDetail -> KeyDetailScreen(screen.keyId, onNavigate)
                is Screen.S3Browser -> PlaceholderScreen("S3 Browser")
                is Screen.AdminTokens -> PlaceholderScreen("Admin Tokens")
                is Screen.Nodes -> PlaceholderScreen("Nodes")
                is Screen.Workers -> PlaceholderScreen("Workers")
                is Screen.Blocks -> PlaceholderScreen("Blocks")
                is Screen.ObjectBrowser -> PlaceholderScreen("Object Browser: ${screen.bucketAlias}")
            }
        }
    }
}
