package net.brightroom.garage.server.garage

import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import net.brightroom.garage.server.plugins.GarageJson
import net.brightroom.garage.shared.api.CreateAdminTokenRequest
import net.brightroom.garage.shared.api.CreatedAdminToken
import net.brightroom.garage.shared.api.UpdateAdminTokenRequest
import net.brightroom.garage.shared.model.garage.AdminToken

/**
 * Admin token 系 operation への型付きアクセス。
 *
 * `GetCurrentAdminTokenInfo` はここには無い。トークンの有効性の確認という別の
 * 役割を持つため `TokenValidation.kt` に置いてある。
 *
 * `CreateAdminToken` の応答だけが `secretToken` を含む。サーバーはこれを保持せず、
 * ログにも出さず、応答としてのみ返す。
 */

private const val LIST_ADMIN_TOKENS = "ListAdminTokens"
private const val GET_ADMIN_TOKEN_INFO = "GetAdminTokenInfo"
private const val CREATE_ADMIN_TOKEN = "CreateAdminToken"
private const val UPDATE_ADMIN_TOKEN = "UpdateAdminToken"
private const val DELETE_ADMIN_TOKEN = "DeleteAdminToken"

suspend fun GarageAdminClient.listAdminTokens(token: String): List<AdminToken> =
    get(token, LIST_ADMIN_TOKENS).garageBodyWith(LIST_ADMIN_TOKENS, ListSerializer(AdminToken.serializer()))

suspend fun GarageAdminClient.getAdminToken(token: String, id: String): AdminToken =
    get(token, GET_ADMIN_TOKEN_INFO, mapOf("id" to id)).garageBody(GET_ADMIN_TOKEN_INFO)

suspend fun GarageAdminClient.createAdminToken(token: String, request: CreateAdminTokenRequest): CreatedAdminToken {
    val body = buildJsonObject {
        put("name", request.name)
        putScope(request.scope)
        // Garage は expiration も neverExpires も無い要求を受け付けるが、
        // その場合の既定が仕様に無い。どちらかを必ず明示する
        if (request.expiration == null) put("neverExpires", true) else put("expiration", request.expiration.toString())
    }

    // 応答は AdminToken に secretToken を足した形なので、本文を 2 回に分けて読む
    val raw = post(token, CREATE_ADMIN_TOKEN, body).requireSuccess(CREATE_ADMIN_TOKEN).bodyAsText()

    return CreatedAdminToken(
        token = GarageJson.decodeFromString(AdminToken.serializer(), raw),
        secretToken = GarageJson.decodeFromString(SecretHolder.serializer(), raw).secretToken,
    )
}

suspend fun GarageAdminClient.updateAdminToken(
    token: String,
    id: String,
    request: UpdateAdminTokenRequest,
): AdminToken {
    val body = buildJsonObject {
        request.name?.let { put("name", it) }
        request.scope?.let { putScope(it) }
        when {
            request.neverExpires -> put("neverExpires", true)
            request.expiration != null -> put("expiration", request.expiration.toString())
        }
    }

    return post(token, UPDATE_ADMIN_TOKEN, body, mapOf("id" to id)).garageBody(UPDATE_ADMIN_TOKEN)
}

suspend fun GarageAdminClient.deleteAdminToken(token: String, id: String) {
    post(token, DELETE_ADMIN_TOKEN, params = mapOf("id" to id)).requireSuccess(DELETE_ADMIN_TOKEN)
}

private fun JsonObjectBuilder.putScope(scope: List<String>) {
    putJsonArray("scope") { scope.forEach { add(it) } }
}

/** `CreateAdminTokenResponse` のうち `AdminToken` に無いフィールドだけを取り出す。 */
@Serializable
private data class SecretHolder(val secretToken: String)
