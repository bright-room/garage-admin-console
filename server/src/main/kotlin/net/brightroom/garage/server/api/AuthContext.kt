package net.brightroom.garage.server.api

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import net.brightroom.garage.server.plugins.GarageJson
import net.brightroom.garage.shared.api.ProblemDetails

/** `Authorization` ヘッダが無い、または Bearer 形式でない。 */
class MissingTokenException : RuntimeException("Authorization ヘッダに Bearer トークンが必要です")

/** RFC 9457 の定める `application/problem+json` で返す。 */
suspend fun ApplicationCall.respondProblem(problem: ProblemDetails) {
    respondText(
        text = GarageJson.encodeToString(problem),
        contentType = ContentType("application", "problem+json"),
        status = problem.status,
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
