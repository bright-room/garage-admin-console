package net.brightroom.garage.web

import androidx.compose.runtime.*
import net.brightroom.garage.web.navigation.AppScaffold
import net.brightroom.garage.web.navigation.Screen
import net.brightroom.garage.web.screens.admintoken.AdminTokenListScreen
import net.brightroom.garage.web.screens.block.BlockErrorScreen
import net.brightroom.garage.web.screens.bucket.BucketDetailScreen
import net.brightroom.garage.web.screens.bucket.BucketListScreen
import net.brightroom.garage.web.screens.cluster.ClusterScreen
import net.brightroom.garage.web.screens.dashboard.DashboardScreen
import net.brightroom.garage.web.screens.key.KeyDetailScreen
import net.brightroom.garage.web.screens.key.KeyListScreen
import net.brightroom.garage.web.screens.layout.LayoutScreen
import net.brightroom.garage.web.screens.node.NodeScreen
import net.brightroom.garage.web.screens.s3.ObjectBrowserScreen
import net.brightroom.garage.web.screens.s3.S3BrowserScreen
import net.brightroom.garage.web.screens.worker.WorkerScreen
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
                is Screen.S3Browser -> S3BrowserScreen(onNavigate)
                is Screen.AdminTokens -> AdminTokenListScreen()
                is Screen.Nodes -> NodeScreen()
                is Screen.Workers -> WorkerScreen()
                is Screen.Blocks -> BlockErrorScreen()
                is Screen.BucketDetail -> BucketDetailScreen(screen.bucketId, onNavigate)
                is Screen.KeyDetail -> KeyDetailScreen(screen.keyId, onNavigate)
                is Screen.ObjectBrowser -> ObjectBrowserScreen(screen.bucketId, screen.bucketAlias, onNavigate)
            }
        }
    }
}
