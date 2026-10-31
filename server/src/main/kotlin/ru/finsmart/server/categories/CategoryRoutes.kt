package ru.finsmart.server.categories

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import ru.finsmart.server.common.transaction
import javax.sql.DataSource

@Serializable
data class CategoryResponse(
    val code: String,
    val name: String,
)

class CategoryRepository(private val dataSource: DataSource) {
    suspend fun findAll(): List<CategoryResponse> = dataSource.transaction { conn ->
        conn.prepareStatement("SELECT code, name FROM categories ORDER BY code").use { ps ->
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(CategoryResponse(rs.getString("code"), rs.getString("name")))
                }
            }
        }
    }
}

fun Route.categoryRoutes(repository: CategoryRepository) {
    get("/categories") {
        call.respond(repository.findAll())
    }
}
