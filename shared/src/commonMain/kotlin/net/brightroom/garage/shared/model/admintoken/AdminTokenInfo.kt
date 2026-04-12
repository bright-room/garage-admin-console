package net.brightroom.garage.shared.model.admintoken

import kotlinx.serialization.Serializable

@Serializable
data class AdminTokenInfo(
    val id: String? = null,
    val name: String,
    val expired: Boolean,
    val created: String? = null,
    val expiration: String? = null,
    val scope: List<String>,
)

@Serializable
data class CreateAdminTokenResponse(
    val id: String? = null,
    val name: String,
    val expired: Boolean,
    val created: String? = null,
    val expiration: String? = null,
    val scope: List<String>,
    val secretToken: String,
)

@Serializable
data class UpdateAdminTokenRequest(
    val name: String? = null,
    val expiration: String? = null,
    val neverExpires: Boolean? = null,
    val scope: List<String>? = null,
)
