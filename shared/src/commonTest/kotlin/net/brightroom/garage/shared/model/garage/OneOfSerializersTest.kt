package net.brightroom.garage.shared.model.garage

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OneOfSerializersTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun decodesWorkerStateStrings() {
        assertEquals(WorkerState.Idle, json.decodeFromString<WorkerState>("\"idle\""))
        assertEquals(WorkerState.Busy, json.decodeFromString<WorkerState>("\"busy\""))
        assertEquals(WorkerState.Done, json.decodeFromString<WorkerState>("\"done\""))
    }

    @Test
    fun decodesThrottledWorkerState() {
        val state = json.decodeFromString<WorkerState>("""{"throttled":{"durationSecs":1.5}}""")

        assertEquals(WorkerState.Throttled(1.5), state)
    }

    @Test
    fun roundTripsEveryWorkerStateShape() {
        listOf(
            "\"idle\"",
            "\"busy\"",
            "\"done\"",
            """{"throttled":{"durationSecs":1.5}}""",
        ).forEach { raw ->
            val decoded = json.decodeFromString<WorkerState>(raw)

            assertEquals(raw, json.encodeToString(decoded))
        }
    }

    @Test
    fun rejectsUnknownWorkerState() {
        assertFailsWith<kotlinx.serialization.SerializationException> {
            json.decodeFromString<WorkerState>("\"sleeping\"")
        }
    }

    @Test
    fun decodesWorkerInfoFromLiveShape() {
        val worker = json.decodeFromString<WorkerInfo>(
            """
            {"id":9,"name":"Block scrub worker","state":"idle","errors":0,"consecutiveErrors":0,
             "lastError":null,"tranquility":4,"progress":null,"queueLength":null,
             "persistentErrors":0,"freeform":["Last scrub completed at 1970-01-01T00:00:00.000Z"]}
            """.trimIndent(),
        )

        assertEquals(9, worker.id)
        assertEquals(WorkerState.Idle, worker.state)
        assertEquals(4, worker.tranquility)
        assertEquals(null, worker.queueLength)
        assertEquals(1, worker.freeform.size)
    }

    @Test
    fun decodesWorkerInfoWithLastError() {
        val worker = json.decodeFromString<WorkerInfo>(
            """
            {"id":1,"name":"Block resync worker #1","state":{"throttled":{"durationSecs":0.25}},
             "errors":3,"consecutiveErrors":2,"lastError":{"message":"connection refused","secsAgo":12},
             "tranquility":2,"progress":"42%","queueLength":7,"persistentErrors":1,"freeform":[]}
            """.trimIndent(),
        )

        assertEquals(WorkerState.Throttled(0.25), worker.state)
        assertEquals("connection refused", worker.lastError?.message)
        assertEquals(12, worker.lastError?.secsAgo)
        assertEquals("42%", worker.progress)
    }
}
