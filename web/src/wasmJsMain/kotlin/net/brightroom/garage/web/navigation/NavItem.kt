package net.brightroom.garage.web.navigation

import net.brightroom.garage.shared.navigation.Route

/**
 * @param requiredOperation この画面が最低限必要とする Garage の operation。
 *   scope に含まれない場合はサイドバーで無効表示にする。
 *   これは UI ヒントであり、可否の実体は常に Garage が返す 403 で決まる（spec §6.3）。
 */
data class NavItem(
    val route: Route,
    val label: String,
    val requiredOperation: String? = null,
)

data class NavGroup(
    val title: String?,
    val items: List<NavItem>,
)

/**
 * サイドバーの構成。役割でグループ化する（spec §8.2）。
 *
 * Phase 1 では概況のみ。ストレージ・クラスタ・メンテナンス・設定の各グループは
 * 対応する画面を実装する Phase 2・3 で追加する。
 */
val navGroups: List<NavGroup> = listOf(
    NavGroup(
        title = null,
        items = listOf(
            NavItem(Route.Overview, "概況", requiredOperation = "GetClusterHealth"),
        ),
    ),
)
