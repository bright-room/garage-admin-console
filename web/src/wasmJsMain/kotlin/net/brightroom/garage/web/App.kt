package net.brightroom.garage.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import net.brightroom.garage.shared.navigation.Route
import net.brightroom.garage.web.components.ErrorView
import net.brightroom.garage.web.navigation.AppScaffold
import net.brightroom.garage.web.router.RouterState
import net.brightroom.garage.web.router.rememberRouter
import net.brightroom.garage.web.screens.buckets.BucketDetailScreen
import net.brightroom.garage.web.screens.buckets.BucketsScreen
import net.brightroom.garage.web.screens.keys.KeyDetailScreen
import net.brightroom.garage.web.screens.keys.KeysScreen
import net.brightroom.garage.web.screens.login.LoginScreen
import net.brightroom.garage.web.screens.overview.OverviewScreen
import net.brightroom.garage.web.session.LocalSession
import net.brightroom.garage.web.session.SessionState
import net.brightroom.garage.web.theme.GarageAdminTheme
import net.brightroom.garage.web.theme.loadJapaneseFontFamily

@Composable
fun App() {
    val router = rememberRouter()
    val session = remember { SessionState() }

    // sessionStorage に残ったトークンでの復帰を試みる間は判断を保留する
    var restoring by remember { mutableStateOf(true) }

    // 日本語グリフを持つフォント。読み込み前に描画すると豆腐が一瞬見えるため、
    // 復帰の判定と同じ待ちに含める。
    var fontFamily by remember { mutableStateOf<FontFamily?>(null) }

    LaunchedEffect(Unit) {
        fontFamily = loadJapaneseFontFamily()
        session.restore()
        restoring = false
    }

    GarageAdminTheme(fontFamily) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            CompositionLocalProvider(LocalSession provides session) {
                when {
                    restoring -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }

                    !session.isSignedIn -> LoginScreen(
                        onSignedIn = {
                            val destination =
                                if (router.current == Route.Login) Route.Overview else router.current
                            router.replace(destination)
                        },
                    )

                    else -> AuthenticatedApp(router)
                }
            }
        }
    }
}

@Composable
private fun AuthenticatedApp(router: RouterState) {
    AppScaffold(router) {
        when (val route = router.current) {
            Route.Overview -> OverviewScreen()

            Route.Login -> OverviewScreen()

            // ログイン済みで /login に来たら概況を出す
            is Route.NotFound -> ErrorView("画面が見つかりません: ${route.path}")

            Route.Buckets -> BucketsScreen(onOpen = { router.navigate(Route.BucketDetail(it)) })

            is Route.BucketDetail -> BucketDetailScreen(
                bucketId = route.id,
                onOpenObjects = { router.navigate(Route.Objects(it)) },
                onOpenKey = { router.navigate(Route.KeyDetail(it)) },
                onDeleted = { router.replace(Route.Buckets) },
            )

            Route.Keys -> KeysScreen(onOpen = { router.navigate(Route.KeyDetail(it)) })

            is Route.KeyDetail -> KeyDetailScreen(
                keyId = route.id,
                onOpenBucket = { router.navigate(Route.BucketDetail(it)) },
                onDeleted = { router.replace(Route.Keys) },
            )

            // Task 17 以降で差し替える
            is Route.Objects -> OverviewScreen()
        }
    }
}
