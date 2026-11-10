package com.finsmart.app.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DateUtils {
    fun getCurrentMonth(): String {
        // Возвращает строку вида "2026-11"
        return SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())
    }
}