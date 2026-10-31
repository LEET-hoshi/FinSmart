package ru.finsmart.server.transactions

import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import ru.finsmart.server.auth.AUTH
import ru.finsmart.server.auth.userId
import ru.finsmart.server.categories.requireCategory
import ru.finsmart.server.common.ApiException
import ru.finsmart.server.common.MoneySerializer
import ru.finsmart.server.common.mapAll
import ru.finsmart.server.common.notFound
import ru.finsmart.server.common.tx
import ru.finsmart.server.common.validationError
import java.math.BigDecimal
import java.sql.Date
import java.time.YearMonth
import java.util.UUID
import javax.sql.DataSource

@Serializable
data class ImportResult(val imported: Int, val duplicates: Int, val failed: Int, val errors: List<ImportRowError>)

@Serializable
data class TransactionDto(
    val id: String,
    val operationId: String,
    val date: String,
    @Serializable(with = MoneySerializer::class) val amount: BigDecimal,
    val currency: String,
    val description: String?,
    val mcc: String?,
    val category: String,
    val categoryManual: Boolean,
)

@Serializable
data class TransactionPage(val items: List<TransactionDto>, val total: Int, val limit: Int, val offset: Int)

@Serializable
data class CategoryChange(val category: String)

private const val TX_COLUMNS =
    "id, external_id, operation_date, amount, currency, description, mcc, category_code, category_manual"

private fun java.sql.ResultSet.toTransaction() = TransactionDto(
    id = getString("id"),
    operationId = getString("external_id"),
    date = getDate("operation_date").toString(),
    amount = getBigDecimal("amount"),
    currency = getString("currency").trim(),
    description = getString("description"),
    mcc = getString("mcc"),
    category = getString("category_code"),
    categoryManual = getBoolean("category_manual"),
)

fun parseMonth(text: String?, field: String = "month"): YearMonth {
    if (text == null) throw validationError(field, "Укажите месяц в формате YYYY-MM")
    return runCatching { YearMonth.parse(text) }.getOrElse { throw validationError(field, "Формат месяца — YYYY-MM") }
}

fun Route.transactionRoutes(dataSource: DataSource) {
    authenticate(AUTH) {
        route("/transactions") {
            post("/import") {
                val userId = call.userId()
                var bytes: ByteArray? = null
                call.receiveMultipart().forEachPart { part ->
                    if (part is PartData.FileItem && part.name == "file" && bytes == null) {
                        bytes = part.provider().readRemaining(CSV_MAX_BYTES + 1L).readByteArray()
                    }
                    part.dispose()
                }
                val content = bytes ?: throw ApiException(HttpStatusCode.BadRequest, "invalid_csv", "Файл не передан")

                val result = try {
                    importCsv(dataSource, userId, content)
                } catch (e: InvalidCsvException) {
                    throw ApiException(HttpStatusCode.BadRequest, "invalid_csv", e.message ?: "Неверный CSV", e.details)
                } catch (e: CsvTooLargeException) {
                    throw ApiException(HttpStatusCode.PayloadTooLarge, "file_too_large", "Файл слишком большой")
                }
                call.respond(result)
            }

            get {
                val userId = call.userId()
                val params = call.request.queryParameters
                val month = params["month"]?.let { parseMonth(it) }
                val category = params["category"]
                val limit = (params["limit"] ?: "50").toIntOrNull()?.takeIf { it in 1..200 }
                    ?: throw validationError("limit", "От 1 до 200")
                val offset = (params["offset"] ?: "0").toIntOrNull()?.takeIf { it >= 0 }
                    ?: throw validationError("offset", "Не меньше 0")

                val page = dataSource.tx { c ->
                    if (category != null) c.requireCategory(category, "category")
                    val where = StringBuilder("user_id = ? AND deleted_at IS NULL")
                    if (month != null) where.append(" AND operation_date BETWEEN ? AND ?")
                    if (category != null) where.append(" AND category_code = ?")

                    fun bind(st: java.sql.PreparedStatement): Int {
                        var i = 1
                        st.setObject(i++, userId)
                        if (month != null) {
                            st.setDate(i++, Date.valueOf(month.atDay(1)))
                            st.setDate(i++, Date.valueOf(month.atEndOfMonth()))
                        }
                        if (category != null) st.setString(i++, category)
                        return i
                    }

                    val total = c.prepareStatement("SELECT count(*) FROM transactions WHERE $where").use {
                        bind(it)
                        it.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
                    }
                    val items = c.prepareStatement(
                        "SELECT $TX_COLUMNS FROM transactions WHERE $where " +
                            "ORDER BY operation_date DESC, created_at DESC, id LIMIT ? OFFSET ?",
                    ).use {
                        val next = bind(it)
                        it.setInt(next, limit)
                        it.setInt(next + 1, offset)
                        it.executeQuery().use { rs -> rs.mapAll { r -> r.toTransaction() } }
                    }
                    TransactionPage(items, total, limit, offset)
                }
                call.respond(page)
            }

            patch("/{id}/category") {
                val userId = call.userId()
                val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrElse { throw notFound() }
                val body = call.receive<CategoryChange>()
                val updated = dataSource.tx { c ->
                    c.requireCategory(body.category, "category")
                    c.prepareStatement(
                        "UPDATE transactions SET category_code = ?, category_manual = TRUE, updated_at = now() " +
                            "WHERE id = ? AND user_id = ? AND deleted_at IS NULL RETURNING $TX_COLUMNS",
                    ).use {
                        it.setString(1, body.category)
                        it.setObject(2, id)
                        it.setObject(3, userId)
                        it.executeQuery().use { rs -> if (rs.next()) rs.toTransaction() else null }
                    }
                } ?: throw notFound()
                call.respond(updated)
            }
        }
    }
}

private suspend fun importCsv(dataSource: DataSource, userId: UUID, content: ByteArray): ImportResult =
    dataSource.tx { c ->
        val mcc = c.createStatement().use { st ->
            st.executeQuery("SELECT mcc, category_code FROM mcc_mappings").use { rs ->
                rs.mapAll { it.getString(1) to it.getString(2) }.toMap()
            }
        }
        val parsed = CsvImportParser.parse(content, mcc)
        var imported = 0
        var skipped = 0
        c.prepareStatement(
            "INSERT INTO transactions (user_id, external_id, operation_date, amount, currency, description, mcc, category_code) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (user_id, external_id) DO NOTHING",
        ).use { st ->
            for (row in parsed.rows) {
                st.setObject(1, userId)
                st.setString(2, row.operationId)
                st.setDate(3, Date.valueOf(row.date))
                st.setBigDecimal(4, row.amount)
                st.setString(5, row.currency)
                st.setString(6, row.description)
                st.setString(7, row.mcc)
                st.setString(8, row.categoryCode)
                if (st.executeUpdate() == 1) imported++ else skipped++
            }
        }
        ImportResult(imported, parsed.duplicatesInFile + skipped, parsed.errors.size, parsed.errors)
    }
