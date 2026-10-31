package ru.finsmart.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.lang.reflect.Proxy
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationTest {
    private val unavailableDataSource: DataSource = Proxy.newProxyInstance(
        DataSource::class.java.classLoader,
        arrayOf(DataSource::class.java),
    ) { _, _, _ -> throw SQLException("db down") } as DataSource

    @Test
    fun health_returns_503_when_database_is_unavailable() = testApplication {
        application { module(unavailableDataSource) }

        val response = client.get("/health")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("""{"status":"degraded","database":"unavailable"}""", response.bodyAsText())
    }

    @Test
    fun unknown_route_returns_not_found_error_with_explicit_null_details() = testApplication {
        application { module(unavailableDataSource) }

        val response = client.get("/no-such-route")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("""{"code":"not_found","message":"Объект не найден","details":null}""", response.bodyAsText())
    }
}
