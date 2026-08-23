package net.brightroom.garage.server.garage

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.brightroom.garage.shared.api.CreateKeyRequest
import net.brightroom.garage.shared.api.ImportKeyRequest
import net.brightroom.garage.shared.api.UpdateKeyRequest
import net.brightroom.garage.shared.model.garage.KeyInfo
import net.brightroom.garage.shared.model.garage.KeySummary

/**
 * アクセスキー系 operation への型付きアクセス。
 *
 * Garage は `createBucket` 権限を allow / deny の 2 つのオブジェクトで表すが、
 * コンソールの DTO は真偽値 1 つである。その振り分けはここで閉じる。
 */

private const val LIST_KEYS = "ListKeys"
private const val GET_KEY_INFO = "GetKeyInfo"
private const val CREATE_KEY = "CreateKey"
private const val IMPORT_KEY = "ImportKey"
private const val UPDATE_KEY = "UpdateKey"
private const val DELETE_KEY = "DeleteKey"

suspend fun GarageAdminClient.listKeys(token: String): List<KeySummary> =
    get(token, LIST_KEYS).garageBodyWith(LIST_KEYS, ListSerializer(KeySummary.serializer()))

/**
 * @param showSecret true のときだけ secret access key を取得する。
 *   既定で付けないのは、必要のない機密をサーバーに流さないため（spec §6.4）。
 */
suspend fun GarageAdminClient.getKeyInfo(token: String, id: String, showSecret: Boolean = false): KeyInfo {
    val params = buildMap {
        put("id", id)
        if (showSecret) put("showSecretKey", "true")
    }

    return get(token, GET_KEY_INFO, params).garageBody(GET_KEY_INFO)
}

suspend fun GarageAdminClient.createKey(token: String, request: CreateKeyRequest): KeyInfo {
    val body = buildJsonObject {
        put("name", request.name)
        putPermission(request.allowCreateBucket)
        putExpiration(request.expiration?.toString(), neverExpires = request.expiration == null)
    }

    return post(token, CREATE_KEY, body).garageBody(CREATE_KEY)
}

suspend fun GarageAdminClient.importKey(token: String, request: ImportKeyRequest): KeyInfo {
    val body = buildJsonObject {
        put("name", request.name)
        put("accessKeyId", request.accessKeyId)
        put("secretAccessKey", request.secretAccessKey)
    }

    return post(token, IMPORT_KEY, body).garageBody(IMPORT_KEY)
}

suspend fun GarageAdminClient.updateKey(token: String, id: String, request: UpdateKeyRequest): KeyInfo {
    val body = buildJsonObject {
        request.name?.let { put("name", it) }
        request.allowCreateBucket?.let { putPermission(it) }
        putExpiration(request.expiration?.toString(), neverExpires = request.neverExpires)
    }

    return post(token, UPDATE_KEY, body, mapOf("id" to id)).garageBody(UPDATE_KEY)
}

suspend fun GarageAdminClient.deleteKey(token: String, id: String) {
    post(token, DELETE_KEY, params = mapOf("id" to id)).requireSuccess(DELETE_KEY)
}

/** `createBucket` の可否を Garage の allow / deny に振り分ける。 */
private fun JsonObjectBuilder.putPermission(allowed: Boolean) {
    val field = if (allowed) "allow" else "deny"

    putJsonObject(field) { put("createBucket", true) }
}

private fun JsonObjectBuilder.putExpiration(expiration: String?, neverExpires: Boolean) {
    when {
        neverExpires -> put("neverExpires", true)
        expiration != null -> put("expiration", expiration)
    }
}
