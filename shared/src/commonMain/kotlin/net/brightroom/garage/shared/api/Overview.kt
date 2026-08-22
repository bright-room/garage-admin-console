package net.brightroom.garage.shared.api

import kotlinx.serialization.Serializable
import net.brightroom.garage.shared.model.garage.ClusterHealth
import net.brightroom.garage.shared.model.garage.NodeResp

/**
 * 概況画面が 1 リクエストで取得する集約。
 *
 * scope 制限により一部が 403 になりうるため、セクション単位で成否を持つ。
 */
@Serializable
data class Overview(
    val health: Section<ClusterHealth>,
    val nodes: Section<List<NodeSummary>>,
    val layout: Section<LayoutSummary>,
    val storage: Section<StorageSummary>,
    val blockErrors: Section<Int>,
)

@Serializable
data class NodeSummary(
    val id: String,
    val isUp: Boolean,
    val draining: Boolean,
    val hostname: String? = null,
    val zone: String? = null,
    val capacity: Long? = null,
    val dataAvailable: Long? = null,
    val dataTotal: Long? = null,
)

@Serializable
data class LayoutSummary(
    val version: Long,
    val stagedChanges: Int,
)

@Serializable
data class StorageSummary(
    val buckets: Int,
    val keys: Int,
)

@Serializable
enum class AlertSeverity { WARNING, ERROR }

@Serializable
data class OverviewAlert(
    val severity: AlertSeverity,
    val message: String,
)

fun NodeResp.toSummary(): NodeSummary =
    NodeSummary(
        id = id,
        isUp = isUp,
        draining = draining,
        hostname = hostname,
        zone = role?.zone,
        capacity = role?.capacity,
        dataAvailable = dataPartition?.available,
        dataTotal = dataPartition?.total,
    )

/**
 * 概況画面の異常帯に出す内容。正常時は空になる。
 *
 * 判定には [Overview] が運ぶ情報しか使わない。取得できなかったセクション
 * （403 や失敗）については何も主張しない。
 */
fun Overview.alerts(): List<OverviewAlert> = buildList {
    health.dataOrNull()?.let { h ->
        when {
            h.status == "unavailable" ->
                add(OverviewAlert(AlertSeverity.ERROR, "一部のパーティションで書き込みquorumが得られていません"))

            h.partitionsQuorum < h.partitions ->
                add(
                    OverviewAlert(
                        AlertSeverity.ERROR,
                        "${h.partitions - h.partitionsQuorum} 個のパーティションでquorumが不足しています",
                    ),
                )

            h.status == "degraded" ->
                add(OverviewAlert(AlertSeverity.WARNING, "一部のストレージノードに接続できていません"))
        }
    }

    nodes.dataOrNull()?.filter { !it.isUp }?.takeIf { it.isNotEmpty() }?.let { down ->
        add(
            OverviewAlert(
                AlertSeverity.ERROR,
                "${down.size} 台のノードがダウンしています: ${down.joinToString { it.hostname ?: it.id }}",
            ),
        )
    }

    layout.dataOrNull()?.takeIf { it.stagedChanges > 0 }?.let { l ->
        add(
            OverviewAlert(
                AlertSeverity.WARNING,
                "レイアウト v${l.version} に ${l.stagedChanges} 件の未適用の変更があります",
            ),
        )
    }

    blockErrors.dataOrNull()?.takeIf { it > 0 }?.let { count ->
        add(OverviewAlert(AlertSeverity.WARNING, "$count 件のブロックで再同期エラーが発生しています"))
    }
}
