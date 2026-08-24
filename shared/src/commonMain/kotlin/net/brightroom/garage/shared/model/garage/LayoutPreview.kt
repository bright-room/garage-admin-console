package net.brightroom.garage.shared.model.garage

import kotlinx.serialization.Serializable

/**
 * `PreviewClusterLayoutChanges` のレスポンス。
 *
 * 計算できたかどうかで形が変わるが、HTTP ステータスはどちらも 200 である。
 */
@Serializable(with = LayoutPreviewSerializer::class)
sealed interface LayoutPreview {

    /** staged 変更ではレイアウトを計算できなかった。 */
    @Serializable
    data class Failed(val error: String) : LayoutPreview

    @Serializable
    data class Computed(
        /** 人間向けの説明行。パースしてはならないと OpenAPI に明記されている。 */
        val message: List<String> = emptyList(),
        val newLayout: ClusterLayout,
        val statistics: ComputationStat? = null,
    ) : LayoutPreview
}

/** レイアウト計算の統計。確認ダイアログに出す（spec §8.6）。 */
@Serializable
data class ComputationStat(
    val replicationFactor: Int,
    val effectiveZoneRedundancy: Int,
    val partitionSize: Long,
    val previousPartitionSize: Long? = null,
    /** パーティションが極端に小さいことの警告。 */
    val lowPartitionSize: Boolean = false,
    val usableCapacity: Long,
    val totalCapacity: Long,
    val effectiveCapacity: Long,
    /** 素の容量を活かしきれていないことの警告。 */
    val lowUsableCapacity: Boolean = false,
    /** 新しいノードへ移動するパーティションの総数。 */
    val totalMovedPartitions: Int? = null,
    val zones: List<ComputationStatZone> = emptyList(),
)

/** ゾーンごとの内訳。ノード単位の内訳は画面に出さないため取り込まない（spec D6）。 */
@Serializable
data class ComputationStatZone(
    val name: String,
    val totalReplicatedPartitions: Int,
    val uniquePartitions: Int,
    val totalCapacity: Long,
    val usableCapacity: Long,
)
