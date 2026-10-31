package ru.finsmart.server.common

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import javax.sql.DataSource

suspend fun <T> DataSource.transaction(block: (Connection) -> T): T = withContext(Dispatchers.IO) {
    connection.use { conn ->
        conn.autoCommit = false
        try {
            val result = block(conn)
            conn.commit()
            result
        } catch (e: Throwable) {
            conn.rollback()
            throw e
        }
    }
}
