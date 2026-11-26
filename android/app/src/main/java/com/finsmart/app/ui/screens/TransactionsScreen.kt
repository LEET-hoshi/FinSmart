package com.finsmart.app.ui.screens

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.finsmart.app.data.local.TransactionEntity
import com.finsmart.app.ui.viewmodel.TransactionsViewModel
import kotlinx.coroutines.flow.collect
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(
    onImportCompleted: () -> Unit,
    initialFilter: TransactionFilter = TransactionFilter.All,
    viewModel: TransactionsViewModel = viewModel()
) {
    val context = LocalContext.current
    val transactions by viewModel.transactions.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val importResult by viewModel.importResult.collectAsState()
    val error by viewModel.error.collectAsState()
    var filter by rememberSaveable(initialFilter) { mutableStateOf(initialFilter) }
    val filteredTransactions = transactions.filter { transaction ->
        when (filter) {
            TransactionFilter.All -> true
            TransactionFilter.Expenses ->
                !isInternalTransfer(transaction.description) && (transaction.amount.toBigDecimalOrNull()?.signum() ?: 0) < 0
            TransactionFilter.Income ->
                !isInternalTransfer(transaction.description) && (transaction.amount.toBigDecimalOrNull()?.signum() ?: 0) > 0
        }
    }
    val transactionsByDate = filteredTransactions.groupBy { it.date }.toSortedMap(compareByDescending { it })

    LaunchedEffect(viewModel) {
        viewModel.importCompleted.collect {
            onImportCompleted()
        }
    }

    // Открытие системного проводника для выбора файла
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            // Копируем выбранный файл во временную папку
            val inputStream = context.contentResolver.openInputStream(uri)
            val tempFile = File(context.cacheDir, "import.xlsx")
            val outputStream = FileOutputStream(tempFile)
            inputStream?.copyTo(outputStream)
            inputStream?.close()
            outputStream.close()

            viewModel.importCsv(tempFile)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Операции", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { filePickerLauncher.launch("*/*") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading
        ) {
            Text(if (isLoading) "Загрузка..." else "Импортировать CSV")
        }

        if (importResult != null) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Результат импорта:", fontWeight = FontWeight.Bold)
                    Text("Импортировано: ${importResult!!.imported}")
                    Text("Дубликаты: ${importResult!!.duplicates}")
                    Text("Ошибки: ${importResult!!.failed}")
                }
            }
        }

        if (error != null) {
            Text(error!!, color = MaterialTheme.colorScheme.error)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TransactionFilter.entries.forEach { option ->
                FilterChip(
                    selected = filter == option,
                    onClick = { filter = option },
                    label = { Text(option.label) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (filteredTransactions.isEmpty() && !isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (transactions.isEmpty()) "Операций пока нет" else "Операций в этом типе нет")
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                transactionsByDate.forEach { (date, dayTransactions) ->
                    item(key = "date-$date") {
                        Text(
                            text = displayDate(date),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    items(dayTransactions, key = { it.id }) { tx ->
                        TransactionItem(tx) { newCategory ->
                            viewModel.updateCategory(tx.id, newCategory)
                        }
                    }
                }
            }
        }
    }
}

enum class TransactionFilter(val label: String) {
    All("Все"),
    Expenses("Расходы"),
    Income("Доходы"),
}

@Composable
fun TransactionItem(tx: TransactionEntity, onCategoryChange: (String) -> Unit) {
    var categoryVisible by rememberSaveable(tx.id) { mutableStateOf(false) }
    var categoryMenuVisible by rememberSaveable(tx.id) { mutableStateOf(false) }
    var transferDetailsVisible by rememberSaveable(tx.id) { mutableStateOf(false) }
    val categories = listOf(
        "groceries" to "Продукты", "cafes" to "Кафе и рестораны", "transport" to "Транспорт",
        "health" to "Здоровье", "home" to "Дом", "entertainment" to "Развлечения", "other" to "Прочее"
    )
    val isExpense = (tx.amount.toBigDecimalOrNull()?.signum() ?: 0) < 0
    val operationType = operationType(tx)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = operationType != OperationType.Purchase) {
                if (operationType == OperationType.Transfer) transferDetailsVisible = true
                else categoryVisible = !categoryVisible
            }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(transactionTitle(tx.description), fontWeight = FontWeight.Medium)
                    Text(displayDate(tx.date), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(maskedCard(tx.description), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Категория: ${categoryLabel(tx.category)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    text = "${if (isExpense) "" else "+"}${tx.amount} ₽",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isExpense) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }

            if (categoryVisible) {
                Spacer(modifier = Modifier.height(8.dp))
                Box {
                    TextButton(onClick = { categoryMenuVisible = true }, contentPadding = PaddingValues(0.dp)) {
                        Text("Категория: ${categoryLabel(tx.category)}", fontSize = 12.sp)
                    }
                    DropdownMenu(
                        expanded = categoryMenuVisible,
                        onDismissRequest = { categoryMenuVisible = false }
                    ) {
                        categories.forEach { (code, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    onCategoryChange(code)
                                    categoryMenuVisible = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (transferDetailsVisible) {
        AlertDialog(
            onDismissRequest = { transferDetailsVisible = false },
            title = { Text(if (isInternalTransfer(tx.description)) "Перевод между своими счетами" else "Перевод в другой банк") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Дата: ${displayDate(tx.date)}")
                    Text("Сумма: ${tx.amount} ₽")
                    Text("Куда: ${transferDestination(tx.description)}")
                }
            },
            confirmButton = {
                TextButton(onClick = { transferDetailsVisible = false }) { Text("Закрыть") }
            }
        )
    }
}

private enum class OperationType { Purchase, Transfer, InternalTransfer }

private fun operationType(tx: TransactionEntity): OperationType = when {
    isInternalTransfer(tx.description) -> OperationType.InternalTransfer
    tx.description.orEmpty().contains("сбп", ignoreCase = true) ||
        tx.description.orEmpty().contains("перевод", ignoreCase = true) -> OperationType.Transfer
    else -> OperationType.Purchase
}

private fun transferDestination(description: String?): String {
    val text = description?.trim().orEmpty()
    if (isInternalTransfer(text)) return "Собственный счёт в банке"
    val match = Regex("""(?i)(?:получатель|банк|в\s+банк|кому)\s*[:—-]?\s*([^;\n]+)""").find(text)
    return match?.groupValues?.get(1)?.trim()?.take(120)?.takeIf { it.isNotBlank() }
        ?: "Банк или получатель не указан в выписке"
}

private fun displayDate(value: String): String = runCatching {
    LocalDate.parse(value).format(DateTimeFormatter.ofPattern("d MMMM", Locale("ru")))
}.getOrDefault(value)

internal fun transactionTitle(description: String?): String {
    val text = description?.trim().orEmpty()
    if (text.isEmpty()) return "Операция"
    val normalized = text.lowercase()
    if (normalized.contains("сбп")) return "Перевод в другой банк"
    if (normalized.startsWith("внутрибанковский перевод")) return "Банковский перевод"
    if (normalized.startsWith("перевод")) return "Перевод"
    merchantName(text)?.let { return it }
    if (normalized.startsWith("операция по карте")) return "Оплата картой"
    compactMerchantName(text)?.let { return it }

    return text
        .substringBefore('\n')
        .substringBefore(";")
        .removePrefix("Оплата в ")
        .removePrefix("Оплата: ")
        .trim()
        .take(52)
        .ifBlank { "Операция" }
}

private fun compactMerchantName(description: String): String? {
    val beforeMcc = description.substringBefore(", MCC:", description).trim()
    if (beforeMcc == description || beforeMcc.isBlank()) return null
    return beforeMcc
        .substringAfterLast('/')
        .trim()
        .take(52)
        .takeIf { it.isNotBlank() }
}

private fun merchantName(description: String): String? {
    val match = Regex(
        "(?i)(?:место совершения операции|торговая точка|магазин)\\s*[:—-]\\s*([^;\\n]+)"
    ).find(description) ?: return null
    return match.groupValues[1]
        .split(Regex("""(?i)\s*,?\s*\bMCC\s*:"""), limit = 2).first()
        .substringBefore(", дата")
        .substringBefore(". Дата")
        .substringAfterLast('/')
        .trim()
        .take(52)
        .takeIf { it.isNotBlank() }
}

private fun maskedCard(description: String?): String {
    val text = description.orEmpty()
    val lastDigits = Regex("(?<!\\d)\\d{6}\\*+\\d{4}(?!\\d)")
        .find(text)
        ?.value
        ?.takeLast(4)
        ?: Regex("(?<!\\d)(?:\\d[ -]?){15,18}\\d(?!\\d)")
            .find(text)
            ?.value
            ?.filter(Char::isDigit)
            ?.takeLast(4)
    return "•••• ${lastDigits ?: "••••"}"
}

private fun isInternalTransfer(description: String?): Boolean {
    val normalized = description?.lowercase().orEmpty()
    return normalized.contains("внутрибанковский перевод") || normalized.contains("перевод между счетами")
}

private fun categoryLabel(code: String): String = when (code) {
    "groceries" -> "Продукты"
    "cafes" -> "Кафе и рестораны"
    "transport" -> "Транспорт"
    "health" -> "Здоровье"
    "home" -> "Дом"
    "entertainment" -> "Развлечения"
    else -> "Прочее"
}
