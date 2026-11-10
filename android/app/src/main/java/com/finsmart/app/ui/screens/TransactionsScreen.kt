package com.finsmart.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.finsmart.app.data.local.TransactionEntity
import com.finsmart.app.ui.viewmodel.TransactionsViewModel
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(viewModel: TransactionsViewModel = viewModel()) {
    val context = LocalContext.current
    val transactions by viewModel.transactions.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val importResult by viewModel.importResult.collectAsState()
    val error by viewModel.error.collectAsState()

    // Открытие системного проводника для выбора файла
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            // Копируем выбранный файл во временную папку
            val inputStream = context.contentResolver.openInputStream(uri)
            val tempFile = File(context.cacheDir, "import.csv")
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
            onClick = { filePickerLauncher.launch("text/*") },
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

        Spacer(modifier = Modifier.height(16.dp))

        if (transactions.isEmpty() && !isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Операций пока нет")
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(transactions) { tx ->
                    TransactionItem(tx) { newCategory ->
                        viewModel.updateCategory(tx.id, newCategory)
                    }
                }
            }
        }
    }
}

@Composable
fun TransactionItem(tx: TransactionEntity, onCategoryChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val categories = listOf("groceries", "cafes", "transport", "health", "home", "entertainment", "other")

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(tx.description ?: "Без описания", fontWeight = FontWeight.Medium)
                Text(tx.date, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                // Выпадающий список
                Box {
                    TextButton(onClick = { expanded = true }, contentPadding = PaddingValues(0.dp)) {
                        Text("Категория: ${tx.category}", fontSize = 12.sp)
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        categories.forEach { cat ->
                            DropdownMenuItem(
                                text = { Text(cat) },
                                onClick = {
                                    onCategoryChange(cat)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
            Text(
                text = "${tx.amount} ₽",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = if (tx.amount.startsWith("-")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        }
    }
}