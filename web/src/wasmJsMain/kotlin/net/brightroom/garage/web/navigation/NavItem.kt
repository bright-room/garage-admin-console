package net.brightroom.garage.web.navigation

import net.brightroom.garage.shared.navigation.Route

/**
 * @param route この項目を押したときの行き先。
 * @param requiredOperation この画面が最低限必要とする Garage の operation。
 *   scope に含まれない場合はサイドバーで無効表示にする。
 *   これは UI ヒントであり、可否の実体は常に Garage が返す 403 で決まる（spec §6.3）。
 * @param matches この項目を選択状態にするルートの条件。行き先と現在地が
 *   一致しない項目（オブジェクトなど）があるため、等値ではなく述語で持つ。
 */
data class NavItem(
    val route: Route,
    val label: String,
    val requiredOperation: String? = null,
    val matches: (Route) -> Boolean = { it == route },
)

data class NavGroup(val title: String?, val items: List<NavItem>)

/**
 * サイドバーの構成。役割でグループ化する（spec §8.2）。
 */
val navGroups: List<NavGroup> = listOf(
    NavGroup(
        title = null,
        items = listOf(
            NavItem(Route.Overview, "概況", requiredOperation = "GetClusterHealth"),
        ),
    ),
    NavGroup(
        title = "ストレージ",
        items = listOf(
            NavItem(
                route = Route.Buckets,
                label = "バケット",
                requiredOperation = "ListBuckets",
                matches = { it == Route.Buckets || it is Route.BucketDetail },
            ),
            NavItem(
                route = Route.Keys,
                label = "アクセスキー",
                requiredOperation = "ListKeys",
                matches = { it == Route.Keys || it is Route.KeyDetail },
            ),
            NavItem(
                // オブジェクトはバケットに属するため、単体では開けない。
                // 押したらバケットを選ばせる（P2-8）
                route = Route.Buckets,
                label = "オブジェクト",
                requiredOperation = "ListBuckets",
                matches = { it is Route.Objects },
            ),
        ),
    ),
    NavGroup(
        title = "クラスタ",
        items = listOf(
            NavItem(Route.Nodes, "ノード", requiredOperation = "GetClusterStatus"),
            NavItem(Route.Layout, "レイアウト", requiredOperation = "GetClusterLayout"),
        ),
    ),
    NavGroup(
        title = "メンテナンス",
        items = listOf(
            NavItem(Route.Workers, "ワーカー", requiredOperation = "ListWorkers"),
            NavItem(Route.Blocks, "ブロック", requiredOperation = "ListBlockErrors"),
        ),
    ),
    NavGroup(
        title = "設定",
        items = listOf(
            NavItem(Route.Tokens, "Admin token", requiredOperation = "ListAdminTokens"),
        ),
    ),
)
