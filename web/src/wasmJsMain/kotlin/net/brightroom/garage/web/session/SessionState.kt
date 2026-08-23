package net.brightroom.garage.web.session

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.browser.window
import net.brightroom.garage.shared.api.ProblemDetails
import net.brightroom.garage.shared.api.Session
import net.brightroom.garage.shared.session.IdleState
import net.brightroom.garage.shared.session.IdleTracker
import net.brightroom.garage.web.api.ApiClient
import net.brightroom.garage.web.api.ApiResult
import net.brightroom.garage.web.api.getJson
import net.brightroom.garage.web.api.sendEmpty
import kotlin.time.Clock

private const val TOKEN_STORAGE_KEY = "garage-admin-console.token"

/**
 * ログイン中の admin token とその情報を保持する。
 *
 * トークンは sessionStorage に置く（タブを閉じれば消え、リロードでは残る）。
 * localStorage を使わないのは共有端末での残留を避けるため。
 */
class SessionState {

    var token: String? by mutableStateOf(null)
        private set

    var info: Session? by mutableStateOf(null)
        private set

    val api: ApiClient = ApiClient { token }

    private val idle = IdleTracker(startedAtMillis = nowMillis())

    val isSignedIn: Boolean get() = info != null

    /** sessionStorage に残っているトークンで復帰を試みる。成功したら true。 */
    suspend fun restore(): Boolean {
        val stored = readStoredToken() ?: return false
        return signIn(stored) == null
    }

    /** 成功したら null、失敗したらその理由を返す。 */
    suspend fun signIn(candidate: String): ProblemDetails? {
        token = candidate

        return when (val result = api.getJson("/api/session", Session.serializer())) {
            is ApiResult.Success -> {
                info = result.value
                storeToken(candidate)
                idle.recordActivity(nowMillis())
                null
            }

            is ApiResult.Failure -> {
                clear()
                result.problem
            }

            ApiResult.Unauthorized -> {
                clear()
                // Garage v2.3.0 は master token でも GetCurrentAdminTokenInfo を
                // 受け付けるため、トークンの種類ではなく値そのものを疑う案内にする。
                ProblemDetails(
                    title = HttpStatusCode.Unauthorized.description,
                    status = HttpStatusCode.Unauthorized.value,
                    detail = "トークンが受け付けられませんでした。値を確認してください。",
                )
            }
        }
    }

    suspend fun signOut() {
        // サーバー側の後始末（Phase 2 以降は S3 secret キャッシュの破棄）を依頼する。
        // 失敗してもローカルの破棄は必ず行う。
        api.sendEmpty(HttpMethod.Post, "/api/session/logout")
        clear()
    }

    /** 401 を受け取った画面から呼ばれる。サーバーへの通知は行わない。 */
    fun invalidate() {
        clear()
    }

    fun recordActivity() {
        idle.recordActivity(nowMillis())
    }

    fun idleState(): IdleState = idle.state(nowMillis())

    fun idleRemainingSeconds(): Long = idle.remainingMillis(nowMillis()) / 1000

    private fun clear() {
        token = null
        info = null
        window.sessionStorage.removeItem(TOKEN_STORAGE_KEY)
    }

    private fun storeToken(value: String) {
        window.sessionStorage.setItem(TOKEN_STORAGE_KEY, value)
    }

    private fun readStoredToken(): String? =
        window.sessionStorage.getItem(TOKEN_STORAGE_KEY)?.takeIf { it.isNotBlank() }

    private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
}

val LocalSession = compositionLocalOf<SessionState> {
    error("SessionState が提供されていません")
}
