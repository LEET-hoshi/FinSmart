package ru.finsmart.server.offers

import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import ru.finsmart.server.TestDatabase

class OfferCatalogLoaderTest {
    private val dataSource = TestDatabase.dataSource

    private fun catalog(sourceId: String, isTest: Boolean): java.nio.file.Path {
        val file = Files.createTempFile("finsmart-offers-", ".json")
        Files.writeString(
            file,
            """
            [{
              "sourceId": "$sourceId",
              "title": "${if (isTest) "[ДЕМО] " else ""}Тестовое предложение",
              "category": "groceries",
              "regularPrice": "100.00",
              "salePrice": "80.00",
              "currency": "RUB",
              "validFrom": null,
              "validTo": null,
              "conditions": null,
              "url": "https://example.test/$sourceId",
              "region": "Екатеринбург",
              "checkedAt": "${Instant.now()}",
              "isTest": $isTest
            }]
            """.trimIndent(),
        )
        return file
    }

    @Test
    fun `successful live import removes demo offers`() {
        val suffix = System.nanoTime()
        val demoId = "demo-loader-$suffix"
        val liveId = "magnit-loader-$suffix"

        val demoReport = OfferCatalogLoader.load(dataSource, catalog(demoId, true))
        assertEquals(1, demoReport?.loaded)

        val liveReport = OfferCatalogLoader.load(dataSource, catalog(liveId, false))
        assertEquals(1, liveReport?.loaded)
        assertTrue((liveReport?.removedTest ?: 0) >= 1)

        val secondDemoId = "demo-loader-second-$suffix"
        val secondDemoReport = OfferCatalogLoader.load(dataSource, catalog(secondDemoId, true))
        assertEquals(1, secondDemoReport?.loaded)
        assertTrue((secondDemoReport?.removedLive ?: 0) >= 1)

        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT count(*) FROM offers WHERE source_id IN (?, ?, ?)").use {
                it.setString(1, demoId)
                it.setString(2, liveId)
                it.setString(3, secondDemoId)
                it.executeQuery().use { result ->
                    result.next()
                    assertEquals(1, result.getInt(1))
                }
            }
            connection.prepareStatement("DELETE FROM offers WHERE source_id = ?").use {
                it.setString(1, secondDemoId)
                it.executeUpdate()
            }
        }
    }
}
