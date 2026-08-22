package net.brightroom.garage.shared.model.garage

import kotlinx.serialization.Serializable

/**
 * 複数ノードに問い合わせる operation のレスポンス。
 * 一部のノードだけが失敗しうるため、成功と失敗を潰さずに保持する。
 */
@Serializable
data class MultiResponse<T>(
    val success: Map<String, T> = emptyMap(),
    val error: Map<String, String> = emptyMap(),
)
