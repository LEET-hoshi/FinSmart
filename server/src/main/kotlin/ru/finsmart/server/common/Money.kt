package ru.finsmart.server.common

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.math.BigDecimal
import java.math.RoundingMode

/** Деньги в JSON — строка с двумя знаками после точки (см. docs/api/openapi.yaml). */
object MoneySerializer : KSerializer<BigDecimal> {
    override val descriptor = PrimitiveSerialDescriptor("Money", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: BigDecimal) =
        encoder.encodeString(value.setScale(2, RoundingMode.HALF_UP).toPlainString())

    override fun deserialize(decoder: Decoder): BigDecimal = BigDecimal(decoder.decodeString())
}

private val moneyInput = Regex("""^-?\d+(\.\d{1,2})?$""")

/** Сумма из входящего запроса: целая или с 1–2 знаками после точки. */
fun parseMoneyInput(text: String, field: String): BigDecimal {
    if (!moneyInput.matches(text.trim())) {
        throw validationError(field, "Сумма: число с не более чем 2 знаками после точки")
    }
    return BigDecimal(text.trim())
}

/** Процент с одним знаком после точки. */
fun percent(part: BigDecimal, whole: BigDecimal): Double =
    part.multiply(BigDecimal(100)).divide(whole, 1, RoundingMode.HALF_UP).toDouble()
