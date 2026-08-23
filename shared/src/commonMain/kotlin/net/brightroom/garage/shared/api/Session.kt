package net.brightroom.garage.shared.api

import kotlin.time.Instant
import kotlinx.serialization.Serializable
import net.brightroom.garage.shared.model.garage.AdminToken

/** ログイン中の admin token の情報。`/api/session` が返す。 */
@Serializable
data class Session(
    val name: String,
    val scope: List<String>,
    val expired: Boolean,
    val expiration: Instant? = null,
)

/**
 * この操作が scope に含まれるかを返す。
 *
 * サイドバーの無効表示に使う UI ヒントであり、権限判定の実体ではない。
 * 実際の可否は常に Garage が返す 403 で決まる。
 */
fun Session.allows(operation: String): Boolean =
    scope.contains("*") || scope.contains(operation)

fun AdminToken.toSession(): Session =
    Session(
        name = name,
        scope = scope,
        expired = expired,
        expiration = expiration,
    )
