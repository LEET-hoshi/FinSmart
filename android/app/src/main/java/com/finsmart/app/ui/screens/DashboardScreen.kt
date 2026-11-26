package com.finsmart.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.finsmart.app.ui.viewmodel.DashboardViewModel
import com.finsmart.app.utils.DateUtils
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNavigateToOffers: (String?) -> Unit,
    onNavigateToTransactions: (TransactionFilter) -> Unit,
    onNavigateToGoals: () -> Unit,
    viewModel: DashboardViewModel = viewModel()
) {
    val analytics by viewModel.analytics.collectAsState()
    val recommendation by viewModel.recommendation.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    var selectedMonth by rememberSaveable { mutableStateOf(DateUtils.getCurrentMonth()) }

    LaunchedEffect(selectedMonth) { viewModel.loadMonth(selectedMonth) }

    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Дашборд", fontSize = 24.sp, fontWeight = FontWeight.Bold)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { selectedMonth = shiftMonth(selectedMonth, -1) }) { Text("‹") }
            Text(monthLabel(selectedMonth), fontWeight = FontWeight.SemiBold)
            TextButton(
                onClick = { selectedMonth = shiftMonth(selectedMonth, 1) },
                enabled = selectedMonth < DateUtils.getCurrentMonth()
            ) { Text("›") }
        }

        if (error != null) {
            Text(error!!, color = MaterialTheme.colorScheme.error)
        }

        // Карточка с расходами
        analytics?.let { summary ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToTransactions(TransactionFilter.Expenses) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SpendingDonut(summary.categories)
                    Column {
                        Text("Расходы за месяц", style = MaterialTheme.typography.labelLarge)
                        Text("${summary.totalExpense} ₽", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        summary.topCategory?.let { Text("Больше всего · ${categoryLabel(it)}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }

            // Разбивка по категориям (простой список)
            Text("Операции", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = { onNavigateToTransactions(TransactionFilter.Expenses) },
                    modifier = Modifier.weight(1f)
                ) { Text("Расходы") }
                FilledTonalButton(
                    onClick = { onNavigateToTransactions(TransactionFilter.Income) },
                    modifier = Modifier.weight(1f)
                ) { Text("Доходы") }
            }

            var expanded by rememberSaveable(selectedMonth) { mutableStateOf(false) }
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Свернуть категории" else "Расходы по категориям")
            }
            if (expanded) SpendingLegend(summary.categories)
        } ?: Text("Нет данных для отображения")

        // Карточка рекомендации ИИ
        Spacer(modifier = Modifier.height(8.dp))
        Text("Рекомендация", fontSize = 18.sp, fontWeight = FontWeight.Bold)

        if (recommendation != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        // ПО ТЗ P0: всегда переходим к скидкам выбранной категории
                        onNavigateToOffers(recommendation!!.category)
                    },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(recommendation!!.title, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(recommendation!!.message)
                    if (recommendation!!.actionLabel != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = recommendation!!.actionLabel!!,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        } else {
            Text("Рекомендаций пока нет", fontSize = 14.sp)
        }
    }
}

@Composable
private fun SpendingDonut(categories: List<com.finsmart.app.data.model.CategorySummary>) {
    val visibleCategories = categories.filter { it.sharePercent > 0.0 }
    val track = MaterialTheme.colorScheme.surfaceVariant
        Canvas(modifier = Modifier.size(60.dp)) {
            drawCircle(track, radius = size.minDimension / 2 - 5.dp.toPx(), style = Stroke(10.dp.toPx()))
            var startAngle = -90f
            visibleCategories.forEach { category ->
                val sweep = (category.sharePercent * 3.6f).toFloat()
                drawArc(
                    color = categoryColor(category.category),
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(5.dp.toPx(), 5.dp.toPx()),
                    size = Size(size.width - 10.dp.toPx(), size.height - 10.dp.toPx()),
                    style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Butt)
                )
                startAngle += sweep
            }
        }
}

@Composable
private fun SpendingLegend(categories: List<com.finsmart.app.data.model.CategorySummary>) {
    categories.filter { it.sharePercent > 0 }.forEach { category ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(modifier = Modifier.size(12.dp)) { drawCircle(categoryColor(category.category)) }
                Spacer(modifier = Modifier.width(8.dp))
                Text(categoryLabel(category.category))
            }
            Text("${category.amount} ₽ · ${"%.1f".format(Locale.US, category.sharePercent)}%")
        }
    }
}

private fun categoryColor(code: String): Color = when (code) {
    "groceries" -> Color(0xFF2E7D32)
    "cafes" -> Color(0xFFE65100)
    "transport" -> Color(0xFF1565C0)
    "health" -> Color(0xFFAD1457)
    "home" -> Color(0xFF6A1B9A)
    "entertainment" -> Color(0xFFFF8F00)
    else -> Color(0xFF607D8B)
}

private fun shiftMonth(value: String, delta: Long): String =
    YearMonth.parse(value).plusMonths(delta).toString()

private fun monthLabel(value: String): String =
    YearMonth.parse(value).atDay(1).format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale("ru")))

private fun categoryLabel(code: String): String = when (code) {
    "groceries" -> "Продукты"
    "cafes" -> "Кафе и рестораны"
    "transport" -> "Транспорт"
    "health" -> "Здоровье"
    "home" -> "Дом"
    "entertainment" -> "Развлечения"
    else -> "Прочее"
}
