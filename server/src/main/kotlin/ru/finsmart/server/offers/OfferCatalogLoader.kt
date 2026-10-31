package ru.finsmart.server.offers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.Date
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import javax.sql.DataSource

private val log = LoggerFactory.getLogger("OfferCatalogLoader")

data class LoadReport(val loaded: Int, val skipped: Int)

private class OfferRecord(
    val sourceId: String,
    val title: String,
    val category: String,
    val regularPrice: BigDecimal?,
    val salePrice: BigDecimal?,
    val currency: String,
    val validFrom: LocalDate?,
    val validTo: LocalDate?,
    val conditions: String?,
    val url: String,
    val region: String?,
    val checkedAt: Instant,
    val isTest: Boolean,
)

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.takeIf { it.isNotBlank() }

private fun JsonObject.price(key: String): BigDecimal? = str(key)?.let(::BigDecimal)?.also {
    require(it.signum() >= 0) { "$key < 0" }
}

private fun parseRecord(o: JsonObject, categories: Set<String>): OfferRecord {
    val category = requireNotNull(o.str("category")) { "category" }
    require(category in categories) { "unknown category $category" }
    return OfferRecord(
        sourceId = requireNotNull(o.str("sourceId")) { "sourceId" },
        title = requireNotNull(o.str("title")) { "title" },
        category = category,
        // Цены разбираются сразу в BigDecimal (из строки или числа), без Double.
        regularPrice = o.price("regularPrice"),
        salePrice = o.price("salePrice"),
        currency = o.str("currency") ?: "RUB",
        validFrom = o.str("validFrom")?.let(LocalDate::parse),
        validTo = o.str("validTo")?.let(LocalDate::parse),
        conditions = o.str("conditions"),
        url = requireNotNull(o.str("url")) { "url" },
        region = o.str("region"),
        checkedAt = Instant.parse(requireNotNull(o.str("checkedAt")) { "checkedAt" }),
        isTest = o.str("isTest")?.toBooleanStrictOrNull() ?: false,
    )
}

/**
 * Загрузка каталога из JSON с upsert по sourceId. Пустой, повреждённый или нечитаемый файл
 * каталог не меняет; отдельные некорректные записи пропускаются.
 */
object OfferCatalogLoader {
    fun load(dataSource: DataSource, file: Path): LoadReport? {
        val root = try {
            Json.parseToJsonElement(Files.readString(file))
        } catch (e: Exception) {
            log.error("Каталог скидок не загружен: файл {} нечитаем ({}); прежний каталог сохранён", file, e.message)
            return null
        }
        val array = root as? JsonArray
        if (array == null || array.isEmpty()) {
            log.error("Каталог скидок не загружен: в {} нет предложений; прежний каталог сохранён", file)
            return null
        }
        return dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                val report = upsertAll(c, array)
                c.commit()
                log.info("Каталог скидок: загружено {}, пропущено {}", report.loaded, report.skipped)
                report
            } catch (e: Exception) {
                c.rollback()
                log.error("Каталог скидок не загружен: {}; прежний каталог сохранён", e.message)
                null
            }
        }
    }

    private fun upsertAll(c: Connection, array: JsonArray): LoadReport {
        val categories = c.createStatement().use { st ->
            st.executeQuery("SELECT code FROM categories").use { rs ->
                buildSet { while (rs.next()) add(rs.getString(1)) }
            }
        }
        var loaded = 0
        var skipped = 0
        for (element in array) {
            val record = try {
                parseRecord(element as JsonObject, categories)
            } catch (e: Exception) {
                skipped++
                log.warn("Предложение пропущено: {}", e.message)
                continue
            }
            val offerId = c.prepareStatement(
                "INSERT INTO offers (source_id, title, regular_price, sale_price, currency, valid_from, valid_to, " +
                    "conditions, url, region, is_test, checked_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT (source_id) DO UPDATE SET title = EXCLUDED.title, regular_price = EXCLUDED.regular_price, " +
                    "sale_price = EXCLUDED.sale_price, currency = EXCLUDED.currency, valid_from = EXCLUDED.valid_from, " +
                    "valid_to = EXCLUDED.valid_to, conditions = EXCLUDED.conditions, url = EXCLUDED.url, " +
                    "region = EXCLUDED.region, is_test = EXCLUDED.is_test, checked_at = EXCLUDED.checked_at, updated_at = now() " +
                    "RETURNING id",
            ).use {
                it.setString(1, record.sourceId)
                it.setString(2, record.title)
                it.setBigDecimal(3, record.regularPrice)
                it.setBigDecimal(4, record.salePrice)
                it.setString(5, record.currency)
                it.setDate(6, record.validFrom?.let(Date::valueOf))
                it.setDate(7, record.validTo?.let(Date::valueOf))
                it.setString(8, record.conditions)
                it.setString(9, record.url)
                it.setString(10, record.region)
                it.setBoolean(11, record.isTest)
                it.setTimestamp(12, Timestamp.from(record.checkedAt))
                it.executeQuery().use { rs -> rs.next(); rs.getObject(1) }
            }
            c.prepareStatement("DELETE FROM offer_categories WHERE offer_id = ?").use {
                it.setObject(1, offerId)
                it.executeUpdate()
            }
            c.prepareStatement("INSERT INTO offer_categories (offer_id, category_code) VALUES (?, ?)").use {
                it.setObject(1, offerId)
                it.setString(2, record.category)
                it.executeUpdate()
            }
            loaded++
        }
        return LoadReport(loaded, skipped)
    }
}
