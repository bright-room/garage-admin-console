package net.brightroom.garage.server.config

import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class AppConfigTest {

    @Test
    fun readsAdminEndpointFromConfig() = testApplication {
        environment {
            config = io.ktor.server.config.MapApplicationConfig(
                "garage.admin.endpoint" to "http://garage.test:3903",
            )
        }
        application {
            val config = AppConfig.from(environment)
            assertEquals("http://garage.test:3903", config.garageAdminEndpoint)
        }
        startApplication()
    }
}
