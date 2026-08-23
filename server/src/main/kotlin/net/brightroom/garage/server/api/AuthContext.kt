package net.brightroom.garage.server.api

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import net.brightroom.garage.server.plugins.GarageJson
import net.brightroom.garage.shared.api.ProblemDetails

/** `Authorization` ヘッダが無い、または Bearer 形式でない。 */
class MissingTokenException : RuntimeException("Authorization ヘッダに Bearer トークンが必要です")

/**
 * RFC 9457 の定める `application/problem+json` で返す。
 *
 * `type` は省略するため、`title` にはその status の推奨理由句を使う。
 */
suspend fun ApplicationCall.respondProblem(status: HttpStatusCode, detail: String? = null, operation: String? = null) {
    val problem = ProblemDetails(
        title = status.description,
        status = status.value,
        detail = detail,
        instance = request.path(),
        operation = operation,
    )

    respondText(
        text = GarageJson.encodeToString(problem),
        contentType = ContentType("application", "problem+json"),
        status = status,
    )
}

private const val BEARER_PREFIX = "Bearer "

/**
 * リクエストから利用者の admin token を取り出す。
 *
 * サーバーはこの値を保持せず、Garage への転送にのみ使う。
 */
fun ApplicationCall.adminToken(): String {
    val header = request.headers[HttpHeaders.Authorization] ?: throw MissingTokenException()

    if (!header.regionMatches(0, BEARER_PREFIX, 0, BEARER_PREFIX.length, ignoreCase = true)) {
        throw MissingTokenException()
    }

    val token = header.substring(BEARER_PREFIX.length).trim()
    if (token.isEmpty()) throw MissingTokenException()

    return token
}

/** パスやクエリの必須パラメータが無い、または本文が解釈できない。 */
class InvalidRequestException(override val message: String) : RuntimeException(message)

/** パスパラメータを取り出す。ルートの定義と食い違っていれば 400 になる。 */
fun ApplicationCall.pathParam(name: String): String = parameters[name]?.takeIf { it.isNotBlank() }
    ?: throw InvalidRequestException("パスに $name が必要です")

/** クエリパラメータを取り出す。 */
fun ApplicationCall.queryParam(name: String): String = request.queryParameters[name]?.takeIf { it.isNotBlank() }
    ?: throw InvalidRequestException("クエリに $name が必要です")
