package ru.finsmart.server.common

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.math.BigDecimal
import java.sql.Connection
import java.sql.ResultSet

// Мелкие помощники для целей и скидок поверх общего слоя (ApiException, Categories, Money).

fun validationError(field: String, reason: String) = ApiException.validation(mapOf(field to reason))

fun notFound() = ApiException.notFound()

fun <T> ResultSet.mapAll(row: (ResultSet) -> T): List<T> {
    val out = ArrayList<T>()
    while (next()) out += row(this)
    return out
}

/** Категория должна быть из справочника (см. [Categories.CODES], совпадает с seed в миграциях). */
@Suppress("UnusedReceiverParameter")
fun Connection.requireCategory(code: String, field: String) {
    if (code !in Categories.CODES) throw validationError(field, "Неизвестная категория")
}

/** Деньги в JSON — строка с двумя знаками после точки (см. docs/api/openapi.yaml). */
object MoneySerializer : KSerializer<BigDecimal> {
    override val descriptor = PrimitiveSerialDescriptor("Money", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: BigDecimal) = encoder.encodeString(value.toMoneyString())
    override fun deserialize(decoder: Decoder): BigDecimal = BigDecimal(decoder.decodeString())
}

private val moneyInput = Regex("""^-?\d{1,12}(\.\d{1,2})?$""")

/** Сумма из входящего запроса: целая или с 1–2 знаками после точки, не больше 12 цифр до точки. */
fun parseMoneyInput(text: String, field: String): BigDecimal {
    if (!moneyInput.matches(text.trim())) {
        throw validationError(field, "Сумма: число с не более чем 2 знаками после точки")
    }
    return BigDecimal(text.trim())
}

/** Процент part от whole с одним знаком после точки (whole > 0). */
fun percent(part: BigDecimal, whole: BigDecimal): Double = percentShare(part, whole)
