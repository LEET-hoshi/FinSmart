package com.finsmart.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.viewmodel.compose.viewModel
import com.finsmart.app.ui.screens.*
import com.finsmart.app.ui.viewmodel.AuthViewModel
sealed class Screen(val route: String, val label: String) {
    object Dashboard : Screen("dashboard", "Главная")
    object Transactions : Screen("transactions", "Операции")
    object Offers : Screen("offers", "Скидки")
    object Goals : Screen("goals", "Цели")
}

val items = listOf(
    Screen.Dashboard,
    Screen.Transactions,
    Screen.Offers,
    Screen.Goals
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinSmartApp() {
    // Управляем тем, показывать ли экран входа или основной экран
    val authViewModel: AuthViewModel = viewModel()
    val isLoggedIn by authViewModel.isLoggedIn.collectAsState()

    if (isLoggedIn == null) {
        // Пока проверяем токен - показываем загрузку
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            CircularProgressIndicator()
        }
    } else if (isLoggedIn == false) {
        // Не вошел - показываем экран входа
        AuthScreen(onAuthSuccess = {
            // После успешного входа обновляем состояние
        })
    } else {
        // Вошел - показываем основной экран с вкладками
        AppMainScreen()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppMainScreen() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                items.forEach { screen ->
                    NavigationBarItem(
                        icon = {
                            when (screen) {
                                Screen.Dashboard -> Icon(Icons.Filled.Home, contentDescription = null)
                                Screen.Transactions -> Icon(Icons.Filled.List, contentDescription = null)
                                Screen.Offers -> Icon(Icons.Filled.LocalOffer, contentDescription = null)
                                Screen.Goals -> Icon(Icons.Filled.Star, contentDescription = null)
                            }
                        },
                        label = { Text(screen.label) },
                        selected = currentRoute == screen.route,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Dashboard.route) { DashboardScreen() }
            composable(Screen.Transactions.route) { TransactionsScreen() }
            composable(Screen.Offers.route) { OffersScreen() }
            composable(Screen.Goals.route) { GoalsScreen() }
        }
    }
}