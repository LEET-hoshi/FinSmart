package com.finsmart.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.finsmart.app.data.model.Goal
import com.finsmart.app.ui.viewmodel.GoalsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalsScreen(viewModel: GoalsViewModel = viewModel()) {
    val goals by viewModel.goals.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    var showDialog by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var targetAmount by remember { mutableStateOf("") }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Новая цель") },
            text = {
                Column {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Название (например, Отпуск)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = targetAmount,
                        onValueChange = { targetAmount = it },
                        label = { Text("Сумма (например, 100000)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (title.isNotBlank() && targetAmount.isNotBlank()) {
                        viewModel.createGoal(title, targetAmount)
                        showDialog = false
                        title = ""
                        targetAmount = ""
                    }
                }) {
                    Text("Создать")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Мои цели", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Button(onClick = { showDialog = true }) {
                Text("Добавить")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (goals.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("У вас пока нет целей")
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(goals) { goal ->
                    GoalItem(goal)
                }
            }
        }
    }
}

@Composable
fun GoalItem(goal: Goal) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(goal.title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text("Цель: ${goal.targetAmount} ₽", fontSize = 14.sp)
            Text("Накоплено: ${goal.currentAmount} ₽", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { (goal.progressPercent / 100.0).toFloat() },
                modifier = Modifier.fillMaxWidth()
            )
            Text("${goal.progressPercent}%", fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}