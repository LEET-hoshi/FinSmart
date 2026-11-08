package ru.finsmart.server.common

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import kotlin.time.Duration.Companion.minutes

val AUTH_RATE_LIMIT = RateLimitName("auth")
val IMPORT_RATE_LIMIT = RateLimitName("import")

fun Application.configureRateLimits(config: RateLimitConfig) {
    install(RateLimit) {
        register(AUTH_RATE_LIMIT) {
            rateLimiter(limit = config.authPerMinute, refillPeriod = 1.minutes)
            requestKey { call -> call.request.origin.remoteAddress }
        }
        register(IMPORT_RATE_LIMIT) {
            rateLimiter(limit = config.importPerMinute, refillPeriod = 1.minutes)
            requestKey { call -> call.principal<JWTPrincipal>()?.subject ?: call.request.origin.remoteAddress }
        }
    }
}
