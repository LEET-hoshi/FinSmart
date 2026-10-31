package ru.finsmart.server.goals

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ru.finsmart.server.analytics.AnalyticsRepository
import ru.finsmart.server.auth.JWT_AUTH as AUTH
import ru.finsmart.server.auth.userId
import ru.finsmart.server.common.requireCategory
import ru.finsmart.server.common.MoneySerializer
import ru.finsmart.server.common.mapAll
import ru.finsmart.server.common.notFound
import ru.finsmart.server.common.parseMoneyInput
import ru.finsmart.server.common.percent
import ru.finsmart.server.common.transaction as tx
import ru.finsmart.server.common.validationError
import java.math.BigDecimal
import java.sql.Connection
import java.sql.Date
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
import javax.sql.DataSource

val BUSINESS_ZONE: ZoneId = ZoneId.of("Asia/Yekaterinburg")

const val TYPE_SAVING = "saving"
const val TYPE_LIMIT = "spending_limit"

@Serializable
data class GoalDto(
    val id: String,
    val title: String,
    val type: String,
    @Serializable(with = MoneySerializer::class) val targetAmount: BigDecimal,
    @Serializable(with = MoneySerializer::class) val currentAmount: BigDecimal,
    val progressPercent: Double,
    val deadline: String?,
    val category: String?,
    val createdAt: String,
    val updatedAt: String,
)

private class GoalRow(
    val id: UUID,
    val title: String,
    val type: String,
    val target: BigDecimal,
    val current: BigDecimal,
    val deadline: LocalDate?,
    val category: String?,
    val createdAt: String,
    val updatedAt: String,
)

private const val GOAL_COLUMNS =
    "id, title, type, target_amount, current_amount, deadline, category_code, created_at, updated_at"

private fun java.sql.ResultSet.toGoalRow() = GoalRow(
    id = getObject("id", UUID::class.java),
    title = getString("title"),
    type = getString("type"),
    target = getBigDecimal("target_amount"),
    current = getBigDecimal("current_amount"),
    deadline = getDate("deadline")?.toLocalDate(),
    category = getString("category_code"),
    createdAt = getTimestamp("created_at").toInstant().toString(),
    updatedAt = getTimestamp("updated_at").toInstant().toString(),
)

/** Для лимита «потрачено» считает backend по расходам категории за текущий месяц; для накопления — введённая сумма. */
private fun GoalRow.toDto(spentThisMonth: (String) -> BigDecimal): GoalDto {
    val current = if (type == TYPE_LIMIT && category != null) spentThisMonth(category) else current
    return GoalDto(
        id = id.toString(),
        title = title,
        type = type,
        targetAmount = target,
        currentAmount = current,
        progressPercent = minOf(100.0, percent(current, target)),
        deadline = deadline?.toString(),
        category = category,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

/** Расходы по категориям за текущий месяц считает общий слой аналитики; здесь берём готовую карту. */
private suspend fun AnalyticsRepository.spentThisMonth(userId: UUID): (String) -> BigDecimal {
    val byCategory = expensesByCategory(userId, YearMonth.now(BUSINESS_ZONE)).associate { it.category to it.current }
    return { category -> byCategory[category] ?: BigDecimal.ZERO }
}

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

private fun JsonElement?.isExplicitNull() = this is JsonNull

private fun validateTitle(title: String?): String {
    val t = title?.trim()
    if (t.isNullOrEmpty() || t.length > 100) throw validationError("title", "Название от 1 до 100 символов")
    return t
}

private fun parseDeadline(text: String?): LocalDate? = text?.let {
    runCatching { LocalDate.parse(it) }.getOrElse { throw validationError("deadline", "Формат даты — YYYY-MM-DD") }
}

private fun checkMoney(target: BigDecimal, current: BigDecimal) {
    if (target.signum() <= 0) throw validationError("targetAmount", "Должна быть больше 0")
    if (current.signum() < 0) throw validationError("currentAmount", "Не может быть отрицательной")
}

fun Route.goalRoutes(dataSource: DataSource, analytics: AnalyticsRepository) {
    authenticate(AUTH) {
        get("/goals") {
            val userId = call.userId()
            val spent = analytics.spentThisMonth(userId)
            val goals = dataSource.tx { c ->
                c.prepareStatement(
                    "SELECT $GOAL_COLUMNS FROM goals WHERE user_id = ? AND deleted_at IS NULL ORDER BY created_at DESC, id",
                ).use {
                    it.setObject(1, userId)
                    it.executeQuery().use { rs -> rs.mapAll { r -> r.toGoalRow().toDto(spent) } }
                }
            }
            call.respond(goals)
        }

        post("/goals") {
            val userId = call.userId()
            val body = call.receive<JsonObject>()
            val title = validateTitle(body.text("title"))
            val type = body.text("type") ?: TYPE_SAVING
            if (type != TYPE_SAVING && type != TYPE_LIMIT) throw validationError("type", "saving или spending_limit")
            val target = parseMoneyInput(body.text("targetAmount") ?: throw validationError("targetAmount", "Обязательное поле"), "targetAmount")
            val current = if (type == TYPE_LIMIT) BigDecimal.ZERO
            else body.text("currentAmount")?.let { parseMoneyInput(it, "currentAmount") } ?: BigDecimal.ZERO
            checkMoney(target, current)
            val deadline = parseDeadline(body.text("deadline"))
            val category = body.text("category")
            if (type == TYPE_LIMIT && category == null) throw validationError("category", "Для лимита категория обязательна")

            val spent = analytics.spentThisMonth(userId)
            val created = dataSource.tx { c ->
                if (category != null) c.requireCategory(category, "category")
                c.prepareStatement(
                    "INSERT INTO goals (user_id, title, type, target_amount, current_amount, deadline, category_code) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING $GOAL_COLUMNS",
                ).use {
                    it.setObject(1, userId)
                    it.setString(2, title)
                    it.setString(3, type)
                    it.setBigDecimal(4, target)
                    it.setBigDecimal(5, current)
                    it.setDate(6, deadline?.let(Date::valueOf))
                    it.setString(7, category)
                    it.executeQuery().use { rs -> rs.next(); rs.toGoalRow().toDto(spent) }
                }
            }
            call.respond(HttpStatusCode.Created, created)
        }

        patch("/goals/{id}") {
            val userId = call.userId()
            val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrElse { throw notFound() }
            val body = call.receive<JsonObject>()
            if (body.isEmpty()) throw validationError("body", "Передайте хотя бы одно поле")

            val spent = analytics.spentThisMonth(userId)
            val updated = dataSource.tx { c ->
                val existing = c.prepareStatement(
                    "SELECT $GOAL_COLUMNS FROM goals WHERE id = ? AND user_id = ? AND deleted_at IS NULL FOR UPDATE",
                ).use {
                    it.setObject(1, id)
                    it.setObject(2, userId)
                    it.executeQuery().use { rs -> if (rs.next()) rs.toGoalRow() else null }
                } ?: throw notFound()

                val title = if ("title" in body) validateTitle(body.text("title")) else existing.title
                val target = if ("targetAmount" in body) {
                    parseMoneyInput(body.text("targetAmount") ?: throw validationError("targetAmount", "Не может быть null"), "targetAmount")
                } else existing.target
                val current = when {
                    existing.type == TYPE_LIMIT -> BigDecimal.ZERO
                    "currentAmount" in body ->
                        parseMoneyInput(body.text("currentAmount") ?: throw validationError("currentAmount", "Не может быть null"), "currentAmount")
                    else -> existing.current
                }
                checkMoney(target, current)
                val deadline = if ("deadline" in body) parseDeadline(body.text("deadline")) else existing.deadline
                val category = if ("category" in body) body.text("category") else existing.category
                if (existing.type == TYPE_LIMIT && category == null) {
                    throw validationError("category", "Для лимита категория обязательна")
                }
                if (category != null) c.requireCategory(category, "category")

                c.prepareStatement(
                    "UPDATE goals SET title = ?, target_amount = ?, current_amount = ?, deadline = ?, category_code = ?, " +
                        "updated_at = now() WHERE id = ? AND user_id = ? RETURNING $GOAL_COLUMNS",
                ).use {
                    it.setString(1, title)
                    it.setBigDecimal(2, target)
                    it.setBigDecimal(3, current)
                    it.setDate(4, deadline?.let(Date::valueOf))
                    it.setString(5, category)
                    it.setObject(6, id)
                    it.setObject(7, userId)
                    it.executeQuery().use { rs -> rs.next(); rs.toGoalRow().toDto(spent) }
                }
            }
            call.respond(updated)
        }
    }
}
