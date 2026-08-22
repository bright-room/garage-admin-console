package net.brightroom.garage.shared.api

import net.brightroom.garage.shared.model.garage.AdminTokenInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionInfoTest {

    @Test
    fun wildcardScopeAllowsEveryOperation() {
        val session = SessionInfo(name = "root", scope = listOf("*"), expired = false)

        assertTrue(session.allows("ListBuckets"))
        assertTrue(session.allows("PurgeBlocks"))
    }

    @Test
    fun explicitScopeAllowsOnlyListedOperations() {
        val session = SessionInfo(
            name = "readonly",
            scope = listOf("ListBuckets", "GetBucketInfo"),
            expired = false,
        )

        assertTrue(session.allows("ListBuckets"))
        assertFalse(session.allows("DeleteBucket"))
    }

    @Test
    fun convertsAdminTokenInfo() {
        val info = AdminTokenInfo(
            name = "alice",
            scope = listOf("ListKeys"),
            expired = false,
            id = "tok1",
        )

        val session = info.toSessionInfo()

        assertEquals("alice", session.name)
        assertEquals(listOf("ListKeys"), session.scope)
        assertFalse(session.expired)
    }
}
