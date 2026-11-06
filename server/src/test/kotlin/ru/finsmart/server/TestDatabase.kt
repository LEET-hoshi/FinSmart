package ru.finsmart.server

import com.zaxxer.hikari.HikariDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import ru.finsmart.server.common.Database
import ru.finsmart.server.common.DbConfig

object TestDatabase {
    private val container: PostgreSQLContainer by lazy {
        PostgreSQLContainer("postgres:16-alpine").apply { start() }
    }

    val dataSource: HikariDataSource by lazy {
        Database.createDataSource(DbConfig(container.jdbcUrl, container.username, container.password))
            .also { Database.migrate(it) }
    }
}
