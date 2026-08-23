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
    fun ignoresQueryAndFragment() {
        assertEquals(Route.Login, Route.parse("/login?next=%2F"))
        assertEquals(Route.Login, Route.parse("/login#section"))
        assertEquals(Route.Overview, Route.parse("/?refresh=1"))
    }

    @Test
    fun unknownPathBecomesNotFound() {
        assertEquals(Route.NotFound("/buckets"), Route.parse("/buckets"))
        assertEquals(Route.NotFound("/nope/deep"), Route.parse("/nope/deep"))
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
}
