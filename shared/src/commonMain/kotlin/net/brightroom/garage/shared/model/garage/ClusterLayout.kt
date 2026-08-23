package net.brightroom.garage.shared.model.garage

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * `GetClusterLayout` のレスポンスのうち Phase 1 が使う範囲。
 *
 * Phase 1 は staged 変更の「件数」しか必要としないため、要素は [JsonElement] のまま受ける。
 * Layout 画面を作る Phase 3 で型を付ける。
 */
@Serializable
data class ClusterLayout(
    val version: Long,
    val stagedRoleChanges: List<JsonElement> = emptyList(),
)
