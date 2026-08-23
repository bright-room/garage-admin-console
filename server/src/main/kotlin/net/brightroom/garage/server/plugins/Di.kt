package net.brightroom.garage.server.plugins

import io.ktor.server.application.Application
import io.ktor.server.config.property
import io.ktor.server.plugins.di.dependencies
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.brightroom.garage.server.api.OverviewService
import net.brightroom.garage.server.config.AppConfig
import net.brightroom.garage.server.garage.GarageAdminClient
import net.brightroom.garage.server.s3.S3CredentialResolver
import net.brightroom.garage.server.s3.S3ObjectStore
import net.brightroom.garage.server.s3.SecretCache
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** SecretCache.sweepExpired() を呼ぶ間隔。 */
private val SWEEP_INTERVAL: Duration = 1.minutes

fun Application.configureDi() {
    val appConfig: AppConfig = property("garage")
    val secretCache = SecretCache()

    // TTL はあくまで論理的な有効期限にすぎない。誰も cache に触らなくなった後も
    // 平文 secret をヒープに残さないよう、定期的に掃く。Application は CoroutineScope
    // であり、この job は dispose() で applicationJob ごと止まる
    launch {
        while (isActive) {
            delay(SWEEP_INTERVAL)
            secretCache.sweepExpired()
        }
    }

    dependencies {
        provide<AppConfig> { appConfig }
        provide<GarageAdminClient> { GarageAdminClient(appConfig.admin.endpoint) }
        provide<OverviewService> { OverviewService(resolve<GarageAdminClient>()) }
        provide<SecretCache> { secretCache }
        provide<S3ObjectStore> { S3ObjectStore(appConfig.s3) }
        provide<S3CredentialResolver> {
            S3CredentialResolver(resolve<GarageAdminClient>(), resolve<SecretCache>())
        }
    }
}
