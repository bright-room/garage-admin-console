package net.brightroom.garage.shared.navigation

import kotlin.test.Test
import kotlin.test.assertEquals

class RouteTest {

    @Test
    fun parsesRootAsOverview() {
        assertEquals(Route.Overview, Route.parse("/"))
        assertEquals(Route.Overview, Route.parse(""))
    }

    @Test
    fun parsesLogin() {
        assertEquals(Route.Login, Route.parse("/login"))
    }

    @Test
    fun ignoresTrailingSlash() {
        assertEquals(Route.Login, Route.parse("/login/"))
    }

    @Test
    fun ignoresFragmentAndIrrelevantQuery() {
        assertEquals(Route.Login, Route.parse("/login?next=%2F"))
        assertEquals(Route.Login, Route.parse("/login#section"))
        assertEquals(Route.Overview, Route.parse("/?refresh=1"))
    }

    @Test
    fun unknownPathBecomesNotFound() {
        assertEquals(Route.NotFound("/nope"), Route.parse("/nope"))
        assertEquals(Route.NotFound("/nope/deep"), Route.parse("/nope/deep"))
        // 実在するルートでもセグメントが多すぎれば未知として扱う
        assertEquals(Route.NotFound("/buckets/b1/extra"), Route.parse("/buckets/b1/extra"))
    }

    @Test
    fun parsesStorageRoutes() {
        assertEquals(Route.Buckets, Route.parse("/buckets"))
        assertEquals(Route.BucketDetail("b1"), Route.parse("/buckets/b1"))
        assertEquals(Route.Keys, Route.parse("/keys"))
        assertEquals(Route.KeyDetail("GK01"), Route.parse("/keys/GK01"))
        assertEquals(Route.Objects("b1"), Route.parse("/objects/b1"))
    }

    @Test
    fun parsesObjectPrefixFromQuery() {
        assertEquals(Route.Objects("b1", "logs/"), Route.parse("/objects/b1?prefix=logs%2F"))
        assertEquals(
            Route.Objects("b1", "日本語 フォルダ/"),
            Route.parse("/objects/b1?prefix=%E6%97%A5%E6%9C%AC%E8%AA%9E%20%E3%83%95%E3%82%A9%E3%83%AB%E3%83%80%2F"),
        )
        // 他のクエリが混ざっていても prefix を取り出せる
        assertEquals(Route.Objects("b1", "a/"), Route.parse("/objects/b1?x=1&prefix=a%2F&y=2"))
        // prefix が無ければルート直下
        assertEquals(Route.Objects("b1", ""), Route.parse("/objects/b1?other=1"))
    }

    @Test
    fun buildsObjectPathWithEncodedPrefix() {
        assertEquals("/objects/b1", Route.Objects("b1").path)
        assertEquals("/objects/b1?prefix=logs%2F", Route.Objects("b1", "logs/").path)
    }

    @Test
    fun exposesCanonicalPath() {
        assertEquals("/", Route.Overview.path)
        assertEquals("/login", Route.Login.path)
        assertEquals("/whatever", Route.NotFound("/whatever").path)
    }

    @Test
    fun parsingCanonicalPathIsStable() {
        listOf(Route.Overview, Route.Login).forEach { route ->
            assertEquals(route, Route.parse(route.path))
        }
    }

    @Test
    fun parsingCanonicalPathIsStableForEveryRoute() {
        listOf(
            Route.Overview,
            Route.Login,
            Route.Buckets,
            Route.BucketDetail("b1"),
            Route.Keys,
            Route.KeyDetail("GK01"),
            Route.Objects("b1"),
            Route.Objects("b1", "logs/2026/"),
            Route.Objects("b1", "日本語 フォルダ/"),
            Route.Objects("b1", "a&b=c/"),
            Route.Objects("b1", "a#b/"),
        ).forEach { route ->
            assertEquals(route, Route.parse(route.path))
        }
    }

    @Test
    fun parsesPhase3Routes() {
        assertEquals(Route.Nodes, Route.parse("/nodes"))
        assertEquals(Route.Layout, Route.parse("/layout"))
        assertEquals(Route.Workers, Route.parse("/workers"))
        assertEquals(Route.Blocks, Route.parse("/blocks"))
        assertEquals(Route.Tokens, Route.parse("/tokens"))
    }

    @Test
    fun phase3RoutesRoundTripThroughPath() {
        listOf(Route.Nodes, Route.Layout, Route.Workers, Route.Blocks, Route.Tokens).forEach { route ->
            assertEquals(route, Route.parse(route.path))
        }
    }

    @Test
    fun trailingSlashResolvesToTheSameRoute() {
        assertEquals(Route.Nodes, Route.parse("/nodes/"))
    }

    @Test
    fun unknownSubPathOfPhase3RouteIsNotFound() {
        assertEquals(Route.NotFound("/nodes/abc"), Route.parse("/nodes/abc"))
    }
}
