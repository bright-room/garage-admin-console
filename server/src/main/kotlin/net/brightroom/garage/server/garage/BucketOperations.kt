package net.brightroom.garage.server.garage

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.brightroom.garage.server.plugins.GarageJson
import net.brightroom.garage.shared.api.CreateBucketRequest
import net.brightroom.garage.shared.api.UpdateBucketRequest
import net.brightroom.garage.shared.model.garage.BucketInfo
import net.brightroom.garage.shared.model.garage.BucketKeyPermissions
import net.brightroom.garage.shared.model.garage.BucketSummary
import net.brightroom.garage.shared.model.garage.ObjectInspection

/**
 * バケット系 operation への型付きアクセス。
 *
 * Garage の operation 名はこのファイルの外に出さない。web が見るのは
 * リソース指向の `/api/buckets` 以下だけである（spec §7）。
 */

private const val LIST_BUCKETS = "ListBuckets"
private const val GET_BUCKET_INFO = "GetBucketInfo"
private const val CREATE_BUCKET = "CreateBucket"
private const val UPDATE_BUCKET = "UpdateBucket"
private const val DELETE_BUCKET = "DeleteBucket"
private const val ADD_BUCKET_ALIAS = "AddBucketAlias"
private const val REMOVE_BUCKET_ALIAS = "RemoveBucketAlias"
private const val ALLOW_BUCKET_KEY = "AllowBucketKey"
private const val DENY_BUCKET_KEY = "DenyBucketKey"
private const val CLEANUP_INCOMPLETE_UPLOADS = "CleanupIncompleteUploads"
private const val INSPECT_OBJECT = "InspectObject"

suspend fun GarageAdminClient.listBuckets(token: String): List<BucketSummary> = get(token, LIST_BUCKETS)
    .garageBodyWith(LIST_BUCKETS, ListSerializer(BucketSummary.serializer()))

suspend fun GarageAdminClient.getBucketInfo(token: String, id: String): BucketInfo =
    get(token, GET_BUCKET_INFO, mapOf("id" to id)).garageBody(GET_BUCKET_INFO)

suspend fun GarageAdminClient.createBucket(token: String, request: CreateBucketRequest): BucketInfo {
    // local alias はコンソールでは作らない（P2-6）。alias 無しのバケットも作れる。
    val body = buildJsonObject {
        request.globalAlias?.let { put("globalAlias", it) }
    }

    return post(token, CREATE_BUCKET, body).garageBody(CREATE_BUCKET)
}

/**
 * バケットの設定を変更する。
 *
 * [request] は Garage の `UpdateBucketRequestBody` と同じ形なのでそのまま送る。
 * 省略したフィールドは変更されず、空配列はそのルールの削除を意味する。
 * `GarageJson` の `explicitNulls = false` がこの意味論を成立させている。
 */
suspend fun GarageAdminClient.updateBucket(token: String, id: String, request: UpdateBucketRequest): BucketInfo = post(
    token,
    UPDATE_BUCKET,
    GarageJson.encodeToJsonElement(UpdateBucketRequest.serializer(), request),
    mapOf("id" to id),
).garageBody(UPDATE_BUCKET)

suspend fun GarageAdminClient.deleteBucket(token: String, id: String) {
    post(token, DELETE_BUCKET, params = mapOf("id" to id)).requireSuccess(DELETE_BUCKET)
}

suspend fun GarageAdminClient.addBucketAlias(token: String, bucketId: String, alias: String): BucketInfo =
    post(token, ADD_BUCKET_ALIAS, aliasBody(bucketId, alias))
        .garageBody(ADD_BUCKET_ALIAS)

suspend fun GarageAdminClient.removeBucketAlias(token: String, bucketId: String, alias: String): BucketInfo =
    post(token, REMOVE_BUCKET_ALIAS, aliasBody(bucketId, alias))
        .garageBody(REMOVE_BUCKET_ALIAS)

suspend fun GarageAdminClient.allowBucketKey(
    token: String,
    bucketId: String,
    accessKeyId: String,
    permissions: BucketKeyPermissions,
): BucketInfo = post(token, ALLOW_BUCKET_KEY, permissionBody(bucketId, accessKeyId, permissions))
    .garageBody(ALLOW_BUCKET_KEY)

suspend fun GarageAdminClient.denyBucketKey(
    token: String,
    bucketId: String,
    accessKeyId: String,
    permissions: BucketKeyPermissions,
): BucketInfo = post(token, DENY_BUCKET_KEY, permissionBody(bucketId, accessKeyId, permissions))
    .garageBody(DENY_BUCKET_KEY)

/** @return 削除された未完了アップロードの数。 */
suspend fun GarageAdminClient.cleanupIncompleteUploads(token: String, bucketId: String, olderThanSecs: Long): Long {
    val body = buildJsonObject {
        put("bucketId", bucketId)
        put("olderThanSecs", olderThanSecs)
    }

    return post(token, CLEANUP_INCOMPLETE_UPLOADS, body)
        .garageBody<CleanupResponse>(CLEANUP_INCOMPLETE_UPLOADS)
        .uploadsDeleted
}

suspend fun GarageAdminClient.inspectObject(token: String, bucketId: String, key: String): ObjectInspection = get(
    token,
    INSPECT_OBJECT,
    mapOf("bucketId" to bucketId, "key" to key),
).garageBody(INSPECT_OBJECT)

private fun aliasBody(bucketId: String, alias: String) = buildJsonObject {
    put("bucketId", bucketId)
    put("globalAlias", alias)
}

private fun permissionBody(bucketId: String, accessKeyId: String, permissions: BucketKeyPermissions) = buildJsonObject {
    put("bucketId", bucketId)
    put("accessKeyId", accessKeyId)
    put(
        "permissions",
        GarageJson.encodeToJsonElement(BucketKeyPermissions.serializer(), permissions),
    )
}

/** `CleanupIncompleteUploads` のレスポンス。件数だけを web に返す。 */
@Serializable
private data class CleanupResponse(val uploadsDeleted: Long)
