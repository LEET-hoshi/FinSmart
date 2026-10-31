package ru.finsmart.server.offers

import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import ru.finsmart.server.auth.JWT_AUTH as AUTH
import ru.finsmart.server.common.requireCategory
import ru.finsmart.server.common.MoneySerializer
import ru.finsmart.server.common.mapAll
import ru.finsmart.server.common.notFound
import ru.finsmart.server.common.transaction as tx
import ru.finsmart.server.goals.BUSINESS_ZONE
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

@Serializable
data class OfferDto(
    val id: String,
    val sourceId: String,
    val title: String,
    val category: String,
    @Serializable(with = MoneySerializer::class) val regularPrice: BigDecimal?,
    @Serializable(with = MoneySerializer::class) val salePrice: BigDecimal?,
    val currency: String,
    val validFrom: String?,
    val validTo: String?,
    val conditions: String?,
    val url: String,
    val region: String?,
    val checkedAt: String,
    @Serializable(with = MoneySerializer::class) val potentialSaving: BigDecimal?,
)

@Serializable
data class OfferList(val items: List<OfferDto>, val catalogCheckedAt: String?)

/** Справочная выгода: только если известны обе цены и акционная ниже обычной. */
fun potentialSaving(regular: BigDecimal?, sale: BigDecimal?): BigDecimal? =
    if (regular != null && sale != null && sale < regular) regular - sale else null

// Одна категория на предложение в API: берём первую по коду (в MVP у предложения одна категория).
private const val OFFER_SELECT =
    "SELECT o.id, o.source_id, o.title, " +
        "(SELECT min(oc.category_code) FROM offer_categories oc WHERE oc.offer_id = o.id) AS category, " +
        "o.regular_price, o.sale_price, o.currency, o.valid_from, o.valid_to, o.conditions, o.url, o.region, o.checked_at " +
        "FROM offers o"

// Действует включительно по valid_to; без valid_to — бессрочно.
private const val ACTIVE = "(o.valid_to IS NULL OR o.valid_to >= ?)"

private fun java.sql.ResultSet.toOffer(): OfferDto {
    val regular = getBigDecimal("regular_price")
    val sale = getBigDecimal("sale_price")
    return OfferDto(
        id = getString("id"),
        sourceId = getString("source_id"),
        title = getString("title"),
        category = getString("category") ?: "other",
        regularPrice = regular,
        salePrice = sale,
        currency = getString("currency").trim(),
        validFrom = getDate("valid_from")?.toString(),
        validTo = getDate("valid_to")?.toString(),
        conditions = getString("conditions"),
        url = getString("url"),
        region = getString("region"),
        checkedAt = getTimestamp("checked_at").toInstant().toString(),
        potentialSaving = potentialSaving(regular, sale),
    )
}

fun Route.offerRoutes(dataSource: DataSource) {
    authenticate(AUTH) {
        get("/offers") {
            val category = call.request.queryParameters["category"]
            val region = call.request.queryParameters["region"]
            val today = java.sql.Date.valueOf(LocalDate.now(BUSINESS_ZONE))
            val result = dataSource.tx { c ->
                if (category != null) c.requireCategory(category, "category")
                val where = StringBuilder(ACTIVE)
                if (category != null) {
                    where.append(" AND EXISTS (SELECT 1 FROM offer_categories oc WHERE oc.offer_id = o.id AND oc.category_code = ?)")
                }
                // Предложение без региона действует везде.
                if (region != null) where.append(" AND (o.region = ? OR o.region IS NULL)")
                val items = c.prepareStatement(
                    "$OFFER_SELECT WHERE $where ORDER BY (o.regular_price - o.sale_price) DESC NULLS LAST, o.valid_to NULLS LAST, o.title",
                ).use {
                    var i = 1
                    it.setDate(i++, today)
                    if (category != null) it.setString(i++, category)
                    if (region != null) it.setString(i, region)
                    it.executeQuery().use { rs -> rs.mapAll { r -> r.toOffer() } }
                }
                val checked = c.createStatement().use { st ->
                    st.executeQuery("SELECT max(checked_at) FROM offers").use { rs ->
                        rs.next()
                        rs.getTimestamp(1)?.toInstant()?.toString()
                    }
                }
                OfferList(items, checked)
            }
            call.respond(result)
        }

        get("/offers/{id}") {
            val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrElse { throw notFound() }
            val today = java.sql.Date.valueOf(LocalDate.now(BUSINESS_ZONE))
            val offer = dataSource.tx { c ->
                c.prepareStatement("$OFFER_SELECT WHERE o.id = ? AND $ACTIVE").use {
                    it.setObject(1, id)
                    it.setDate(2, today)
                    it.executeQuery().use { rs -> if (rs.next()) rs.toOffer() else null }
                }
            } ?: throw notFound()
            call.respond(offer)
        }
    }
}
