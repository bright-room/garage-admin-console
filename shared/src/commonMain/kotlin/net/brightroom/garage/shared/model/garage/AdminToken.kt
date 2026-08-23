package net.brightroom.garage.shared.model.garage

import kotlin.time.Instant
import kotlinx.serialization.Serializable

/** `GetCurrentAdminTokenInfo` および `GetAdminTokenInfo` のレスポンス。 */
@Serializable
data class AdminToken(
    val name: String,
    /** 許可された operation 名の一覧。`*` はすべてを許可する。 */
    val scope: List<String>,
    val expired: Boolean,
    val id: String? = null,
    val created: Instant? = null,
    val expiration: Instant? = null,
)
