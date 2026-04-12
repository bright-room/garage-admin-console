package net.brightroom.garage.shared.model.s3

import kotlinx.serialization.Serializable

@Serializable
data class S3ObjectList(
    val prefix: String,
    val objects: List<S3ObjectItem>,
    val commonPrefixes: List<String>,
    val isTruncated: Boolean,
    val nextContinuationToken: String? = null,
)

@Serializable
data class S3ObjectItem(
    val key: String,
    val size: Long,
    val lastModified: String,
    val etag: String? = null,
)
