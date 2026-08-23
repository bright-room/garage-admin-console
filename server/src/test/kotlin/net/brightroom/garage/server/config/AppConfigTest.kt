package net.brightroom.garage.server.config

import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.config.property
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class AppConfigTest {

    @Test
    fun deserialisesGarageSectionFromConfig() = testApplication {
        environment {
            config = MapApplicationConfig(
                "garage.admin.endpoint" to "http://garage.test:3903",
            )
        }
        application {
            val config: AppConfig = property("garage")
            assertEquals("http://garage.test:3903", config.admin.endpoint)
        }
        startApplication()
    }
}
