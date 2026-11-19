package ru.finsmart.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import ru.finsmart.server.common.AppConfig
import java.lang.reflect.Proxy
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ApplicationTest {
    private val testConfig = AppConfig.fromEnv(mapOf("JWT_SECRET" to "test-secret-test-secret-test-secret!!"))

    private val unavailableDataSource: DataSource = Proxy.newProxyInstance(
        DataSource::class.java.classLoader,
        arrayOf(DataSource::class.java),
    ) { _, _, _ -> throw SQLException("db down") } as DataSource

    @Test
    fun health_returns_503_when_database_is_unavailable() = testApplication {
        application { module(testConfig, unavailableDataSource) }

        val response = client.get("/health")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("""{"status":"degraded","database":"unavailable","recommendations":"rules"}""", response.bodyAsText())
    }

    @Test
    fun health_reports_llm_mode_without_exposing_its_config() = testApplication {
        val config = AppConfig.fromEnv(
            mapOf(
                "JWT_SECRET" to "test-secret-test-secret-test-secret!!",
                "LLM_ENABLED" to "true",
                "LLM_ENDPOINT" to "https://llm.internal.test/v1/recommendation",
                "LLM_API_KEY" to "test-llm-key-12345",
            ),
        )
        application { module(config, unavailableDataSource) }

        val body = client.get("/health").bodyAsText()

        assertEquals("""{"status":"degraded","database":"unavailable","recommendations":"llm"}""", body)
        assertFalse(body.contains("llm.internal.test"))
        assertFalse(body.contains("test-llm-key-12345"))
    }

    @Test
    fun health_reports_rules_mode_when_llm_enabled_without_endpoint() = testApplication {
        val config = AppConfig.fromEnv(
            mapOf(
                "JWT_SECRET" to "test-secret-test-secret-test-secret!!",
                "LLM_ENABLED" to "true",
            ),
        )
        application { module(config, unavailableDataSource) }

        val body = client.get("/health").bodyAsText()

        assertEquals("""{"status":"degraded","database":"unavailable","recommendations":"rules"}""", body)
    }

    @Test
    fun unknown_route_returns_not_found_error_with_explicit_null_details() = testApplication {
        application { module(testConfig, unavailableDataSource) }

        val response = client.get("/no-such-route")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("""{"code":"not_found","message":"Объект не найден","details":null}""", response.bodyAsText())
    }
}
