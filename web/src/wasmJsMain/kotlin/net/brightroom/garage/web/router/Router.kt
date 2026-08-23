@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package net.brightroom.garage.web.router

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.window
import net.brightroom.garage.shared.navigation.Route

/**
 * History API と [Route] を橋渡しする。
 *
 * ライブラリは使わない（spec §8.1）。パスの解析は `:shared` の [Route.parse] が担い、
 * ここはブラウザ API との接続だけを持つ。
 */
class RouterState internal constructor(initial: Route) {

    var current: Route by mutableStateOf(initial)
        internal set

    /** 履歴に積んで遷移する。 */
    fun navigate(route: Route) {
        if (route == current) return
        window.history.pushState(null, "", route.path)
        current = route
    }

    /** 履歴を積まずに差し替える（ログインへの強制送還など）。 */
    fun replace(route: Route) {
        window.history.replaceState(null, "", route.path)
        current = route
    }
}

@Composable
fun rememberRouter(): RouterState {
    val state = remember {
        RouterState(Route.parse(window.location.pathname + window.location.search))
    }

    DisposableEffect(Unit) {
        val listener: (org.w3c.dom.events.Event) -> Unit = {
            state.current = Route.parse(window.location.pathname + window.location.search)
        }
        window.addEventListener("popstate", listener)

        onDispose { window.removeEventListener("popstate", listener) }
    }

    return state
}
