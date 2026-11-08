package com.finsmart.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.finsmart.app.data.model.Recommendation
import com.finsmart.app.ui.viewmodel.DashboardViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(viewModel: DashboardViewModel = viewModel()) {
    val analytics by viewModel.analytics.collectAsState()
    val recommendation by viewModel.recommendation.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

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

        if (error != null) {
            Text(error!!, color = MaterialTheme.colorScheme.error)
        }

        // Карточка с расходами
        analytics?.let { summary ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Расходы за ${summary.month}", fontSize = 14.sp)
                    Text("${summary.totalExpense} ₽", fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    if (summary.topCategory != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Больше всего: ${summary.topCategory}", fontSize = 14.sp)
                    }
                }
            }

            // Разбивка по категориям (простой список)
            Text("По категориям", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            summary.categories.forEach { cat ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(cat.category, fontSize = 16.sp)
                    Text("${cat.amount} ₽", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
                LinearProgressIndicator(
                    progress = { (cat.sharePercent / 100f).toFloat() },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
            }
        } ?: Text("Нет данных для отображения")

        // Карточка рекомендации ИИ
        Spacer(modifier = Modifier.height(8.dp))
        Text("Рекомендация", fontSize = 18.sp, fontWeight = FontWeight.Bold)

        if (recommendation != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(recommendation!!.title, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(recommendation!!.message)
                }
            }
        } else {
            Text("Рекомендаций пока нет", fontSize = 14.sp)
        }
    }
}