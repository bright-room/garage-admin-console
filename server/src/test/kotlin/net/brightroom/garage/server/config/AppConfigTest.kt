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
                "garage.s3.endpoint" to "http://garage.test:3900",
                "garage.s3.region" to "garage",
                "garage.s3.pathStyle" to "true",
            )
        }
        application {
            val config: AppConfig = property("garage")
            assertEquals("http://garage.test:3903", config.admin.endpoint)
        }
        startApplication()
    }

    @Test
    fun readsS3Settings() = testApplication {
        environment {
            config = MapApplicationConfig(
                "garage.admin.endpoint" to "http://garage.test:3903",
                "garage.s3.endpoint" to "http://garage.test:3900",
                "garage.s3.region" to "garage",
                "garage.s3.pathStyle" to "true",
            )
        }
        application {
            val config: AppConfig = property("garage")
            assertEquals("http://garage.test:3900", config.s3.endpoint)
            assertEquals("garage", config.s3.region)
            assertEquals(true, config.s3.pathStyle)
        }
        startApplication()
    }

    @Test
    fun readsPathStyleAsFalse() = testApplication {
        environment {
            config = MapApplicationConfig(
                "garage.admin.endpoint" to "http://garage.test:3903",
                "garage.s3.endpoint" to "http://garage.test:3900",
                "garage.s3.region" to "garage",
                "garage.s3.pathStyle" to "false",
            )
        }
        application {
            val config: AppConfig = property("garage")
            assertEquals(false, config.s3.pathStyle)
        }
        startApplication()
    }
}
