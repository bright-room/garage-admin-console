package net.brightroom.garage.shared.api

import net.brightroom.garage.shared.model.garage.AdminToken
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionTest {

    @Test
    fun wildcardScopeAllowsEveryOperation() {
        val session = Session(name = "root", scope = listOf("*"), expired = false)

        assertTrue(session.allows("ListBuckets"))
        assertTrue(session.allows("PurgeBlocks"))
    }

    @Test
    fun explicitScopeAllowsOnlyListedOperations() {
        val session = Session(
            name = "readonly",
            scope = listOf("ListBuckets", "GetBucketInfo"),
            expired = false,
        )

        assertTrue(session.allows("ListBuckets"))
        assertFalse(session.allows("DeleteBucket"))
    }

    @Test
    fun convertsAdminToken() {
        val token = AdminToken(
            name = "alice",
            scope = listOf("ListKeys"),
            expired = false,
            id = "tok1",
        )

        val session = token.toSession()

        assertEquals("alice", session.name)
        assertEquals(listOf("ListKeys"), session.scope)
        assertFalse(session.expired)
    }

    @Test
    fun carriesTokenIdIntoSession() {
        val token = AdminToken(
            name = "alice",
            scope = listOf("*"),
            expired = false,
            id = "29251efb",
        )

        assertEquals("29251efb", token.toSession().id)
    }

    @Test
    fun configurationDerivedTokenHasNoId() {
        val token = AdminToken(name = "admin_token (from daemon configuration)", scope = listOf("*"), expired = false)

        assertNull(token.toSession().id)
    }
}
