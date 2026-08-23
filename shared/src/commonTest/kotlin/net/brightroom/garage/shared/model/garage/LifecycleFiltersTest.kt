package net.brightroom.garage.shared.model.garage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LifecycleFiltersTest {

    @Test
    fun readsSingleConditionFromFilterItself() {
        val conditions = LifecycleFilter(prefix = "logs/").toConditions()

        assertEquals("logs/", conditions.prefix)
        assertNull(conditions.sizeGreaterThan)
    }

    @Test
    fun readsMultipleConditionsFromAnd() {
        val filter = LifecycleFilter(
            and = LifecycleFilter(prefix = "logs/", objectSizeGreaterThan = 1024),
        )

        val conditions = filter.toConditions()

        assertEquals("logs/", conditions.prefix)
        assertEquals(1024, conditions.sizeGreaterThan)
    }

    @Test
    fun nullFilterMeansNoConditions() {
        val conditions = null.toConditions()

        assertEquals(FilterConditions(), conditions)
        assertEquals(true, conditions.isEmpty)
    }

    @Test
    fun writesSingleConditionWithoutAnd() {
        val filter = FilterConditions(prefix = "logs/").toFilter()

        assertEquals(LifecycleFilter(prefix = "logs/"), filter)
    }

    @Test
    fun writesMultipleConditionsInsideAnd() {
        val filter = FilterConditions(prefix = "logs/", sizeGreaterThan = 1024).toFilter()

        assertEquals(
            LifecycleFilter(and = LifecycleFilter(prefix = "logs/", objectSizeGreaterThan = 1024)),
            filter,
        )
    }

    @Test
    fun emptyConditionsBecomeNoFilter() {
        assertNull(FilterConditions().toFilter())
    }

    @Test
    fun roundTripsEveryShape() {
        listOf(
            FilterConditions(),
            FilterConditions(prefix = "logs/"),
            FilterConditions(sizeGreaterThan = 1024),
            FilterConditions(prefix = "logs/", sizeGreaterThan = 1024, sizeLessThan = 4096),
        ).forEach { conditions ->
            assertEquals(conditions, conditions.toFilter().toConditions())
        }
    }
}
