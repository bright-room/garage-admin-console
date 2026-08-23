package net.brightroom.garage.server.garage

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonElement
import net.brightroom.garage.server.plugins.GarageJson

/**
 * Garage Admin API v2 へのアクセス。
 *
 * トークンはインスタンスではなく呼び出しごとに受け取る。サーバーは利用者の
 * admin token を保持しないため、インスタンスに持たせてはならない。
 */
class GarageAdminClient(
    private val endpoint: String,
    engine: HttpClientEngine = CIO.create(),
) {
    private val client = HttpClient(engine) {
        install(ContentNegotiation) {
            json(GarageJson)
        }
        expectSuccess = false
    }

    suspend fun get(
        token: String,
        operation: String,
        params: Map<String, String> = emptyMap(),
    ): HttpResponse = client.get("$endpoint/v2/$operation") {
        header(HttpHeaders.Authorization, "Bearer $token")
        params.forEach { (key, value) -> parameter(key, value) }
    }

    suspend fun post(
        token: String,
        operation: String,
        body: JsonElement? = null,
        params: Map<String, String> = emptyMap(),
    ): HttpResponse = client.post("$endpoint/v2/$operation") {
        header(HttpHeaders.Authorization, "Bearer $token")
        params.forEach { (key, value) -> parameter(key, value) }
        contentType(ContentType.Application.Json)
        if (body != null) {
            setBody(body)
        }
    }

    fun close() {
        client.close()
    }
}

/** 非 2xx なら [GarageException] を投げ、成功ならそのまま返す。 */
suspend fun HttpResponse.requireSuccess(operation: String): HttpResponse {
    if (status.isSuccess()) return this

    val detail = runCatching { bodyAsText() }.getOrDefault("")
    throw GarageException(
        status = status,
        operation = operation,
        message = detail.ifBlank { status.description },
    )
}

/** 成功を確認したうえで本文を [T] にデシリアライズする。 */
suspend inline fun <reified T> HttpResponse.garageBody(operation: String): T =
    requireSuccess(operation).body()

/**
 * serializer を明示して本文をデシリアライズする。
 *
 * `MultiResponse<List<BlockError>>` のようなジェネリック型は `reified` で解決できないため、
 * そうした場合はこちらを使う。
 */
suspend fun <T> HttpResponse.garageBodyWith(
    operation: String,
    deserializer: DeserializationStrategy<T>,
): T {
    val text = requireSuccess(operation).bodyAsText()
    return GarageJson.decodeFromString(deserializer, text)
}
