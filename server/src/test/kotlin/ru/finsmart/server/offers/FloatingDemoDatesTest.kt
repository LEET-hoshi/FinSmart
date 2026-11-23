package ru.finsmart.server.offers

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FloatingDemoDatesTest {
    private val today = LocalDate.of(2026, 11, 23)

    @Test
    fun `expired demo campaign moves to today and keeps its duration`() {
        val dates = floatingDemoDates(
            validFrom = LocalDate.of(2026, 10, 29),
            validTo = LocalDate.of(2026, 11, 5),
            isTest = true,
            today = today,
        )

        assertEquals(today, dates.first)
        assertEquals(today.plusDays(7), dates.second)
    }

    @Test
    fun `current demo campaign and real catalog dates remain unchanged`() {
        val currentDemo = floatingDemoDates(
            validFrom = today.minusDays(1),
            validTo = today.plusDays(3),
            isTest = true,
            today = today,
        )
        val realOffer = floatingDemoDates(
            validFrom = LocalDate.of(2026, 10, 29),
            validTo = LocalDate.of(2026, 11, 5),
            isTest = false,
            today = today,
        )

        assertEquals(today.minusDays(1) to today.plusDays(3), currentDemo)
        assertEquals(LocalDate.of(2026, 10, 29) to LocalDate.of(2026, 11, 5), realOffer)
    }

    @Test
    fun `demo offer without an expiry stays unlimited`() {
        val dates = floatingDemoDates(
            validFrom = LocalDate.of(2026, 10, 29),
            validTo = null,
            isTest = true,
            today = today,
        )

        assertEquals(LocalDate.of(2026, 10, 29), dates.first)
        assertNull(dates.second)
    }
}
