package ru.finsmart.server.categories

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import ru.finsmart.server.common.mapAll
import ru.finsmart.server.common.tx
import ru.finsmart.server.common.validationError
import java.sql.Connection
import javax.sql.DataSource

@Serializable
data class CategoryDto(val code: String, val name: String)

fun Connection.categoryCodes(): Set<String> =
    createStatement().use { st ->
        st.executeQuery("SELECT code FROM categories").use { rs -> rs.mapAll { it.getString(1) }.toSet() }
    }

fun Connection.requireCategory(code: String, field: String) {
    if (code !in categoryCodes()) throw validationError(field, "Неизвестная категория")
}

fun Route.categoryRoutes(dataSource: DataSource) {
    get("/categories") {
        // «Прочее» — последней, остальные по названию.
        val items = dataSource.tx { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT code, name FROM categories ORDER BY (code = 'other'), name").use { rs ->
                    rs.mapAll { CategoryDto(it.getString(1), it.getString(2)) }
                }
            }
        }
        call.respond(items)
    }
}
