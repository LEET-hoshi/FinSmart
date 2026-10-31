package ru.finsmart.server.common

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.ResultSet
import javax.sql.DataSource

/** Выполняет блок в транзакции на IO-потоке; при исключении откатывает. */
suspend fun <T> DataSource.tx(block: (Connection) -> T): T = withContext(Dispatchers.IO) {
    connection.use { c ->
        c.autoCommit = false
        try {
            block(c).also { c.commit() }
        } catch (e: Throwable) {
            c.rollback()
            throw e
        }
    }
}

fun <T> ResultSet.mapAll(row: (ResultSet) -> T): List<T> {
    val out = ArrayList<T>()
    while (next()) out += row(this)
    return out
}

const val UNIQUE_VIOLATION = "23505"
