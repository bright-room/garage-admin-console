package net.brightroom.garage.shared.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 概況のように複数の operation を集約するレスポンスで、セクション単位の成否を運ぶ。
 *
 * admin token は scope を限定できるため 403 は正常系であり、
 * 1 つの operation が拒否されても他のセクションは通常どおり描画する。
 */
@Serializable
sealed interface Section<out T> {

    @Serializable
    @SerialName("loaded")
    data class Loaded<out T>(val data: T) : Section<T>

    /** scope 不足で拒否された（HTTP 403）。 */
    @Serializable
    @SerialName("denied")
    data class Denied(val operation: String) : Section<Nothing>

    /** 403 以外の理由で取得できなかった。 */
    @Serializable
    @SerialName("failed")
    data class Failed(val message: String) : Section<Nothing>

    fun dataOrNull(): T? = (this as? Loaded)?.data
}
