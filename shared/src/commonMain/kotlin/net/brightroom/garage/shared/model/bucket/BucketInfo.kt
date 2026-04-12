package net.brightroom.garage.shared.model.bucket

import kotlinx.serialization.Serializable

@Serializable
data class BucketInfo(
    val id: String,
    val created: String,
    val globalAliases: List<String>,
    val websiteAccess: Boolean,
    val websiteConfig: WebsiteConfig? = null,
    val keys: List<BucketKeyInfo>,
    val objects: Long,
    val bytes: Long,
    val unfinishedUploads: Long,
    val unfinishedMultipartUploads: Long,
    val unfinishedMultipartUploadParts: Long,
    val unfinishedMultipartUploadBytes: Long,
    val quotas: BucketQuotas,
)

@Serializable
data class WebsiteConfig(
    val indexDocument: String,
    val errorDocument: String? = null,
)

@Serializable
data class BucketKeyInfo(
    val accessKeyId: String,
    val name: String,
    val permissions: BucketKeyPerm,
    val bucketLocalAliases: List<String>,
)

@Serializable
data class BucketKeyPerm(
    val read: Boolean,
    val write: Boolean,
    val owner: Boolean,
)

@Serializable
data class BucketQuotas(
    val maxSize: Long? = null,
    val maxObjects: Long? = null,
)

@Serializable
data class BucketListItem(
    val id: String,
    val created: String,
    val globalAliases: List<String>,
    val localAliases: List<BucketLocalAlias>,
)

@Serializable
data class BucketLocalAlias(
    val accessKeyId: String,
    val alias: String,
)

@Serializable
data class CreateBucketRequest(
    val globalAlias: String? = null,
    val localAlias: CreateBucketLocalAlias? = null,
)

@Serializable
data class CreateBucketLocalAlias(
    val accessKeyId: String,
    val alias: String,
    val allow: BucketKeyPerm? = null,
)

@Serializable
data class UpdateBucketRequest(
    val websiteAccess: UpdateWebsiteAccess? = null,
    val quotas: BucketQuotas? = null,
)

@Serializable
data class UpdateWebsiteAccess(
    val enabled: Boolean,
    val indexDocument: String? = null,
    val errorDocument: String? = null,
)

@Serializable
data class BucketAliasRequest(
    val bucketId: String,
    val globalAlias: String? = null,
    val localAlias: String? = null,
    val accessKeyId: String? = null,
)

@Serializable
data class BucketKeyPermChangeRequest(
    val bucketId: String,
    val accessKeyId: String,
    val permissions: BucketKeyPerm,
)

@Serializable
data class CleanupIncompleteUploadsRequest(
    val bucketId: String,
    val olderThanSecs: Long,
)

@Serializable
data class CleanupIncompleteUploadsResponse(
    val uploadsDeleted: Long,
)
