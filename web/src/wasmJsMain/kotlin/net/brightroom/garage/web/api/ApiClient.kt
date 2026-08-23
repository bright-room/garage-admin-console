package net.brightroom.garage.web.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
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
 * 通常の失敗と区別する。それ以外の分岐（403 の scope 不足、409 の S3 縮退）は
 * [Failure.status] と [ProblemDetails.type] で判断する。
 *
 * 分岐に使うのは HTTP レスポンスのステータスであって、problem details の
 * `status` フィールドではない（spec §7.1）。
 */
sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    data class Failure(val status: HttpStatusCode, val problem: ProblemDetails) : ApiResult<Nothing>
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

    suspend fun getText(path: String): ApiResult<String> = sendText(HttpMethod.Get, path)

    /**
     * @param json 送る本文。null なら本文なしで送る。
     */
    suspend fun sendText(method: HttpMethod, path: String, json: String? = null): ApiResult<String> = runCatching {
        http.request(path) {
            this.method = method
            authorize()
            if (json != null) {
                contentType(ContentType.Application.Json)
                setBody(json)
            }
        }
    }.fold(
        onSuccess = { it.toResult { body -> body } },
        onFailure = { ApiResult.Failure(HttpStatusCode.ServiceUnavailable, networkProblem(it)) },
    )

    private fun HttpRequestBuilder.authorize() {
        tokenProvider()?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    private suspend fun <T> HttpResponse.toResult(transform: (String) -> T): ApiResult<T> {
        val body = bodyAsText()

        return when {
            status == HttpStatusCode.Unauthorized -> ApiResult.Unauthorized
            status.isSuccess() -> ApiResult.Success(transform(body))
            else -> ApiResult.Failure(status, parseProblem(body, status))
        }
    }

    /** サーバーは RFC 9457 の problem details を返す。壊れていた場合だけ自前で組み立てる。 */
    private fun parseProblem(body: String, status: HttpStatusCode): ProblemDetails =
        runCatching { AppJson.decodeFromString<ProblemDetails>(body) }
            .getOrElse { problemOf(status, "サーバーからの応答を解釈できませんでした") }

    private fun networkProblem(cause: Throwable): ProblemDetails = problemOf(
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
suspend fun <T> ApiClient.getJson(path: String, deserializer: DeserializationStrategy<T>): ApiResult<T> =
    decode(getText(path), deserializer)

/** JSON を送り、返ってきた JSON をデコードする。 */
suspend fun <T> ApiClient.sendJson(
    method: HttpMethod,
    path: String,
    body: String?,
    deserializer: DeserializationStrategy<T>,
): ApiResult<T> = decode(sendText(method, path, body), deserializer)

/** 応答の本文を読まない呼び出し（204 を返すもの）。 */
suspend fun ApiClient.sendEmpty(method: HttpMethod, path: String, body: String? = null): ApiResult<Unit> =
    when (val result = sendText(method, path, body)) {
        is ApiResult.Success -> ApiResult.Success(Unit)
        is ApiResult.Failure -> result
        ApiResult.Unauthorized -> ApiResult.Unauthorized
    }

private fun <T> decode(raw: ApiResult<String>, deserializer: DeserializationStrategy<T>): ApiResult<T> = when (raw) {
    is ApiResult.Success ->
        runCatching { ApiResult.Success(AppJson.decodeFromString(deserializer, raw.value)) }
            .getOrElse {
                ApiResult.Failure(
                    HttpStatusCode.InternalServerError,
                    problemOf(
                        status = HttpStatusCode.InternalServerError,
                        detail = "サーバーからの応答を解釈できませんでした",
                    ),
                )
            }

    is ApiResult.Failure -> raw

    ApiResult.Unauthorized -> ApiResult.Unauthorized
}
