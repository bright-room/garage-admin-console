package net.brightroom.garage.web.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import net.brightroom.garage.shared.api.ProblemDetails

val AppJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/**
 * `/api` の呼び出し結果。
 *
 * 401 は「トークンが無効になった」を意味し、画面をログインへ戻す必要があるため
 * 通常の失敗と区別する。判定にはレスポンスのステータスを使う。
 */
sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    data class Failure(val problem: ProblemDetails) : ApiResult<Nothing>
    data object Unauthorized : ApiResult<Nothing>
}

/** 利用者に見せる文言。RFC 9457 の `detail` は省略されうるので `title` に落とす。 */
val ProblemDetails.displayMessage: String get() = detail ?: title

/**
 * @param tokenProvider 現在のセッションのトークンを返す。未ログインなら null。
 */
class ApiClient(private val tokenProvider: () -> String?) {

    private val http = HttpClient(Js) {
        expectSuccess = false
    }

    suspend fun getText(path: String): ApiResult<String> =
        runCatching {
            http.get(path) { authorize() }
        }.fold(
            onSuccess = { it.toResult { body -> body } },
            onFailure = { ApiResult.Failure(networkProblem(it)) },
        )

    suspend fun postEmpty(path: String): ApiResult<Unit> =
        runCatching {
            http.post(path) { authorize() }
        }.fold(
            onSuccess = { it.toResult { } },
            onFailure = { ApiResult.Failure(networkProblem(it)) },
        )

    private fun HttpRequestBuilder.authorize() {
        tokenProvider()?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    private suspend fun <T> HttpResponse.toResult(transform: (String) -> T): ApiResult<T> {
        val body = bodyAsText()

        return when {
            status == HttpStatusCode.Unauthorized -> ApiResult.Unauthorized
            status.isSuccess() -> ApiResult.Success(transform(body))
            else -> ApiResult.Failure(parseProblem(body, status))
        }
    }

    /** サーバーは RFC 9457 の problem details を返す。壊れていた場合だけ自前で組み立てる。 */
    private fun parseProblem(body: String, status: HttpStatusCode): ProblemDetails =
        runCatching { AppJson.decodeFromString<ProblemDetails>(body) }
            .getOrElse { problemOf(status, "サーバーからの応答を解釈できませんでした") }

    private fun networkProblem(cause: Throwable): ProblemDetails =
        problemOf(
            status = HttpStatusCode.ServiceUnavailable,
            detail = "サーバーに接続できませんでした: ${cause.message ?: "原因不明"}",
        )
}

/**
 * サーバーからの応答が使えないときに、クライアント側で組み立てる problem details。
 *
 * `type` は省略するため、`title` にはその status の推奨理由句を使う。
 */
private fun problemOf(status: HttpStatusCode, detail: String): ProblemDetails =
    ProblemDetails(title = status.description, status = status.value, detail = detail)

/** 本文を [deserializer] でデコードして返す。 */
suspend fun <T> ApiClient.getJson(
    path: String,
    deserializer: DeserializationStrategy<T>,
): ApiResult<T> = when (val raw = getText(path)) {
    is ApiResult.Success ->
        runCatching { ApiResult.Success(AppJson.decodeFromString(deserializer, raw.value)) }
            .getOrElse {
                ApiResult.Failure(
                    problemOf(
                        status = HttpStatusCode.InternalServerError,
                        detail = "サーバーからの応答を解釈できませんでした",
                    ),
                )
            }

    is ApiResult.Failure -> raw
    ApiResult.Unauthorized -> ApiResult.Unauthorized
}
