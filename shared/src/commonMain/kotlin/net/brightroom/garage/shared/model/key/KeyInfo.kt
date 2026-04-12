package net.brightroom.garage.shared.model.key

import net.brightroom.garage.shared.model.bucket.BucketKeyPerm
import kotlinx.serialization.Serializable

@Serializable
data class KeyInfo(
    val accessKeyId: String,
    val name: String,
    val expired: Boolean,
    val created: String? = null,
    val expiration: String? = null,
    val permissions: KeyPerm,
    val secretAccessKey: String? = null,
    val buckets: List<KeyBucketInfo>,
)

@Serializable
data class KeyPerm(
    val createBucket: Boolean = false,
)

@Serializable
data class KeyBucketInfo(
    val id: String,
    val globalAliases: List<String>,
    val localAliases: List<String>,
    val permissions: BucketKeyPerm,
)

@Serializable
data class KeyListItem(
    val id: String,
    val name: String,
    val expired: Boolean,
    val created: String? = null,
    val expiration: String? = null,
)

@Serializable
data class UpdateKeyRequest(
    val name: String? = null,
    val expiration: String? = null,
    val neverExpires: Boolean? = null,
    val allow: KeyPerm? = null,
    val deny: KeyPerm? = null,
)

@Serializable
data class ImportKeyRequest(
    val accessKeyId: String,
    val secretAccessKey: String,
    val name: String? = null,
)
