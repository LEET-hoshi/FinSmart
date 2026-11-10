package ru.finsmart.server.recommendations

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ru.finsmart.server.common.Categories
import ru.finsmart.server.common.LlmConfig
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Optional provider adapter. The request is deliberately constructed from the
 * [RecommendationInput] allow-list: no CSV, transaction text, identity, token or
 * other personal data can reach an external provider through this class.
 */
class LlmProvider(
    private val config: LlmConfig,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(config.timeout).build(),
) : RecommendationProvider {
    override suspend fun recommend(input: RecommendationInput): RecommendationResponse {
        check(config.enabled) { "LLM provider is disabled" }
        val endpoint = requireNotNull(config.endpoint) { "LLM_ENDPOINT is required when LLM is enabled" }
        val request = HttpRequest.newBuilder(URI(endpoint))
            .timeout(config.timeout)
            .header("Content-Type", "application/json")
            .apply { config.apiKey?.let { header("Authorization", "Bearer $it") } }
            .POST(HttpRequest.BodyPublishers.ofString(safePayload(input).toString()))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        require(response.statusCode() in 200..299) { "LLM returned HTTP ${response.statusCode()}" }
        return parseResponse(response.body())
    }

    private fun safePayload(input: RecommendationInput): JsonObject = buildJsonObject {
        put("totalExpense", input.totalExpense)
        put("previousTotalExpense", input.previousTotalExpense)
        input.changePercent?.let { put("changePercent", it) }
        input.goalProgressPercent?.let { put("goalProgressPercent", it) }
        put("categories", input.categories.joinToString(",") { it.code })
    }

    /** Reject unknown/missing fields rather than accepting an arbitrary LLM response. */
    internal fun parseResponse(body: String): RecommendationResponse {
        val objectValue = Json { ignoreUnknownKeys = false }.parseToJsonElement(body) as? JsonObject
            ?: throw IllegalArgumentException("LLM response must be a JSON object")
        val allowed = setOf("title", "message", "category", "actionType", "actionLabel")
        require(objectValue.keys == allowed) { "LLM response has an unexpected schema" }
        val title = objectValue.requiredString("title", 80)
        val message = objectValue.requiredString("message", 400)
        val category = objectValue.nullableString("category", 32)?.also {
            require(it in Categories.CODES) { "LLM category is invalid" }
        }
        val actionType = objectValue.requiredString("actionType", 40)
        require(actionType in ACTION_TYPES) { "LLM actionType is invalid" }
        val actionLabel = objectValue.nullableString("actionLabel", 40)
        if (actionType == "open_offers") require(category != null) { "open_offers requires category" }
        if (actionType == "none") require(actionLabel == null) { "none must not have actionLabel" }
        return RecommendationResponse(title, message, category, actionType, actionLabel, source = "llm")
    }

    private fun JsonObject.requiredString(name: String, maxLength: Int): String =
        nullableString(name, maxLength) ?: throw IllegalArgumentException("LLM $name is required")

    private fun JsonObject.nullableString(name: String, maxLength: Int): String? {
        val value = getValue(name)
        if (value is JsonNull) return null
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw IllegalArgumentException("LLM $name must be a string or null")
        require(text.isNotBlank() && text.length <= maxLength) { "LLM $name has invalid length" }
        return text
    }

    private companion object {
        val ACTION_TYPES = setOf("open_offers", "open_goals", "open_transactions", "none")
    }
}

/** Any timeout, network fault or schema failure is contained here and falls back to rules. */
class FallbackRecommendationProvider(
    private val primary: RecommendationProvider,
    private val fallback: RecommendationProvider,
) : RecommendationProvider {
    override suspend fun recommend(input: RecommendationInput): RecommendationResponse =
        try {
            primary.recommend(input)
        } catch (_: Exception) {
            fallback.recommend(input)
        }
}
