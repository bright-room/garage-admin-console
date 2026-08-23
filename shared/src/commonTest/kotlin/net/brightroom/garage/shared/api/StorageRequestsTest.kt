package net.brightroom.garage.shared.api

import kotlinx.serialization.json.Json
import net.brightroom.garage.shared.model.garage.BucketQuotas
import net.brightroom.garage.shared.model.garage.CorsRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StorageRequestsTest {
    // サーバーと同じ設定。省略と空配列の差が UpdateBucket の意味論そのものになる。
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun omitsUntouchedFields() {
        val encoded = json.encodeToString(
            UpdateBucketRequest(quotas = BucketQuotas(maxObjects = 10, maxSize = 20)),
        )

        assertTrue(encoded.contains("\"quotas\""))
        assertFalse(encoded.contains("corsRules"))
        assertFalse(encoded.contains("lifecycleRules"))
        assertFalse(encoded.contains("websiteAccess"))
    }

    @Test
    fun emptyListMeansDeleteRules() {
        val encoded = json.encodeToString(UpdateBucketRequest(corsRules = emptyList()))

        assertEquals("""{"corsRules":[]}""", encoded)
    }

    @Test
    fun explicitNullQuotasClearsThem() {
        // Garage は maxSize と maxObjects の両方を null にしたときだけ quota を解除する。
        // explicitNulls = false でも quotas 自体は出る（値が非 null のオブジェクトのため）。
        val encoded = json.encodeToString(UpdateBucketRequest(quotas = BucketQuotas()))

        assertEquals("""{"quotas":{}}""", encoded)
    }

    @Test
    fun encodesCorsRulesWithS3Names() {
        val encoded = json.encodeToString(
            UpdateBucketRequest(
                corsRules = listOf(CorsRule(allowedOrigins = listOf("*"), allowedMethods = listOf("GET"))),
            ),
        )

        assertTrue(encoded.contains("\"AllowedOrigin\":[\"*\"]"))
    }
}
