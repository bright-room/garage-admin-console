package net.brightroom.garage.shared.model.admintoken

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AdminTokenInfoTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodeAdminTokenInfo() {
        val raw = """
            {
                "id": "token-123",
                "name": "admin",
                "expired": false,
                "created": "2024-01-01T00:00:00Z",
                "scope": ["Admin", "ReadOnly"]
            }
        """.trimIndent()

        val result = json.decodeFromString<AdminTokenInfo>(raw)

        assertEquals("token-123", result.id)
        assertEquals("admin", result.name)
        assertEquals(2, result.scope.size)
    }

    @Test
    fun decodeCreateAdminTokenResponse() {
        val raw = """
            {
                "id": "new-token",
                "name": "ci-token",
                "expired": false,
                "scope": ["Admin"],
                "secretToken": "super-secret"
            }
        """.trimIndent()

        val result = json.decodeFromString<CreateAdminTokenResponse>(raw)

        assertEquals("super-secret", result.secretToken)
        assertNull(result.created)
    }
}
