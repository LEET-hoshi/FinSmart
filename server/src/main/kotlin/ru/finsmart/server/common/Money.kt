package ru.finsmart.server.common

import java.math.BigDecimal
import java.math.RoundingMode

fun BigDecimal.toMoneyString(): String = setScale(2, RoundingMode.HALF_UP).toPlainString()

fun percentChange(current: BigDecimal, previous: BigDecimal): Double? =
    if (previous.signum() == 0) {
        null
    } else {
        current.subtract(previous)
            .multiply(BigDecimal(100))
            .divide(previous, 1, RoundingMode.HALF_UP)
            .toDouble()
    }

fun percentShare(part: BigDecimal, total: BigDecimal): Double =
    if (total.signum() == 0) {
        0.0
    } else {
        part.multiply(BigDecimal(100)).divide(total, 1, RoundingMode.HALF_UP).toDouble()
    }
