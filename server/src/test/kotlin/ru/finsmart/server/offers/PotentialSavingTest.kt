package ru.finsmart.server.offers

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PotentialSavingTest {
    @Test
    fun saving_is_regular_minus_sale_when_both_prices_known() {
        assertEquals(BigDecimal("25.00"), potentialSaving(BigDecimal("109.99"), BigDecimal("84.99")))
    }

    @Test
    fun no_saving_when_regular_price_unknown() {
        assertNull(potentialSaving(null, BigDecimal("69.99")))
    }

    @Test
    fun no_saving_when_sale_price_unknown() {
        assertNull(potentialSaving(BigDecimal("259.99"), null))
    }

    @Test
    fun no_saving_when_sale_price_is_not_lower() {
        assertNull(potentialSaving(BigDecimal("199.99"), BigDecimal("199.99")))
        assertNull(potentialSaving(BigDecimal("100.00"), BigDecimal("120.00")))
    }
}
