package net.brightroom.garage.web.navigation

sealed class Screen(val title: String, val icon: String) {
    data object Dashboard : Screen("Dashboard", "dashboard")
    data object Cluster : Screen("Cluster", "dns")
    data object Layout : Screen("Layout", "grid_view")
    data object Buckets : Screen("Buckets", "folder")
    data object Keys : Screen("Keys", "key")
    data object S3Browser : Screen("S3 Browser", "cloud")
    data object AdminTokens : Screen("Tokens", "token")
    data object Nodes : Screen("Nodes", "storage")
    data object Workers : Screen("Workers", "engineering")
    data object Blocks : Screen("Blocks", "view_module")

    // Detail screens with back navigation
    data class BucketDetail(val bucketId: String) : Screen("Bucket Detail", "folder")
    data class KeyDetail(val keyId: String) : Screen("Key Detail", "key")
    data class ObjectBrowser(val bucketId: String, val bucketAlias: String) : Screen("Objects", "cloud")

    companion object {
        val sidebarItems = listOf(
            Dashboard, Cluster, Layout, Buckets, Keys, S3Browser,
            AdminTokens, Nodes, Workers, Blocks,
        )
    }
}
