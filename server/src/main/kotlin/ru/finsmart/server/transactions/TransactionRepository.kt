package ru.finsmart.server.transactions

import ru.finsmart.server.common.toMoneyString
import ru.finsmart.server.common.transaction
import java.sql.Connection
import java.sql.ResultSet
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import javax.sql.DataSource

data class TransactionFilter(
    val month: YearMonth?,
    val category: String?,
    val limit: Int,
    val offset: Int,
)

class TransactionRepository(private val dataSource: DataSource) {

    suspend fun mccCategories(): Map<String, String> = dataSource.transaction { conn -> loadMccMap(conn) }

    suspend fun insertNew(userId: UUID, rows: List<ParsedTransaction>): Int = dataSource.transaction { conn ->
        if (rows.isEmpty()) return@transaction 0
        conn.prepareStatement(
            """
            INSERT INTO transactions (user_id, external_id, operation_date, amount, currency, description, mcc, category_code)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (user_id, external_id) DO NOTHING
            """.trimIndent(),
        ).use { ps ->
            for (row in rows) {
                ps.setObject(1, userId)
                ps.setString(2, row.operationId)
                ps.setObject(3, row.date)
                ps.setBigDecimal(4, row.amount)
                ps.setString(5, row.currency)
                ps.setString(6, row.description)
                ps.setString(7, row.mcc)
                ps.setString(8, row.categoryCode)
                ps.addBatch()
            }
            ps.executeBatch().count { it > 0 }
        }
    }

    suspend fun find(userId: UUID, filter: TransactionFilter): TransactionPageResponse = dataSource.transaction { conn ->
        val conditions = StringBuilder("user_id = ? AND deleted_at IS NULL")
        val params = mutableListOf<Any>(userId)
        if (filter.month != null) {
            conditions.append(" AND operation_date >= ? AND operation_date < ?")
            params += filter.month.atDay(1)
            params += filter.month.plusMonths(1).atDay(1)
        }
        if (filter.category != null) {
            conditions.append(" AND category_code = ?")
            params += filter.category
        }

        val total = conn.prepareStatement("SELECT count(*) FROM transactions WHERE $conditions").use { ps ->
            params.forEachIndexed { i, value -> ps.setObject(i + 1, value) }
            ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }

        val items = conn.prepareStatement(
            """
            SELECT $COLUMNS FROM transactions
            WHERE $conditions
            ORDER BY operation_date DESC, created_at DESC, id
            LIMIT ? OFFSET ?
            """.trimIndent(),
        ).use { ps ->
            params.forEachIndexed { i, value -> ps.setObject(i + 1, value) }
            ps.setInt(params.size + 1, filter.limit)
            ps.setInt(params.size + 2, filter.offset)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toTransaction()) } }
        }

        TransactionPageResponse(items = items, total = total, limit = filter.limit, offset = filter.offset)
    }

    suspend fun updateCategory(userId: UUID, id: UUID, category: String): TransactionResponse? =
        dataSource.transaction { conn ->
            conn.prepareStatement(
                """
                UPDATE transactions
                SET category_code = ?, category_manual = TRUE, updated_at = now()
                WHERE id = ? AND user_id = ? AND deleted_at IS NULL
                RETURNING $COLUMNS
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, category)
                ps.setObject(2, id)
                ps.setObject(3, userId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.toTransaction() else null }
            }
        }

    private fun loadMccMap(conn: Connection): Map<String, String> =
        conn.prepareStatement("SELECT mcc, category_code FROM mcc_mappings").use { ps ->
            ps.executeQuery().use { rs ->
                buildMap { while (rs.next()) put(rs.getString("mcc"), rs.getString("category_code")) }
            }
        }

    private fun ResultSet.toTransaction() = TransactionResponse(
        id = getObject("id", UUID::class.java).toString(),
        operationId = getString("external_id"),
        date = getObject("operation_date", LocalDate::class.java).toString(),
        amount = getBigDecimal("amount").toMoneyString(),
        currency = getString("currency"),
        description = getString("description"),
        mcc = getString("mcc"),
        category = getString("category_code"),
        categoryManual = getBoolean("category_manual"),
    )

    private companion object {
        const val COLUMNS =
            "id, external_id, operation_date, amount, currency, description, mcc, category_code, category_manual"
    }
}
