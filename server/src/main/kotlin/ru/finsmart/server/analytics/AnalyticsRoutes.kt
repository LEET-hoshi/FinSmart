package ru.finsmart.server.analytics

import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import ru.finsmart.server.auth.AUTH
import ru.finsmart.server.auth.userId
import ru.finsmart.server.common.mapAll
import ru.finsmart.server.common.tx
import ru.finsmart.server.transactions.parseMonth
import java.math.BigDecimal
import java.sql.Connection
import java.sql.Date
import java.time.YearMonth
import java.util.UUID
import javax.sql.DataSource

/** Расходы (amount < 0) за календарный месяц по категориям (суммы по модулю) и число операций. */
fun Connection.expensesByCategory(userId: UUID, month: YearMonth): Pair<Map<String, BigDecimal>, Int> =
    prepareStatement(
        "SELECT category_code, sum(-amount), count(*) FROM transactions " +
            "WHERE user_id = ? AND deleted_at IS NULL AND amount < 0 AND operation_date BETWEEN ? AND ? " +
            "GROUP BY category_code",
    ).use {
        it.setObject(1, userId)
        it.setDate(2, Date.valueOf(month.atDay(1)))
        it.setDate(3, Date.valueOf(month.atEndOfMonth()))
        val rows = it.executeQuery().use { rs -> rs.mapAll { r -> Triple(r.getString(1), r.getBigDecimal(2), r.getInt(3)) } }
        rows.associate { r -> r.first to r.second } to rows.sumOf { r -> r.third }
    }

fun Route.analyticsRoutes(dataSource: DataSource) {
    authenticate(AUTH) {
        get("/analytics/summary") {
            val userId = call.userId()
            val month = parseMonth(call.request.queryParameters["month"])
            val summary = dataSource.tx { c ->
                val (current, count) = c.expensesByCategory(userId, month)
                val (previous, _) = c.expensesByCategory(userId, month.minusMonths(1))
                AnalyticsCalculator.summarize(month, current, previous, count)
            }
            call.respond(summary)
        }
    }
}
