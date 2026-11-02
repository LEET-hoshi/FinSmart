package com.finsmart.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.finsmart.app.ui.navigation.FinSmartApp // <--- Импортируем FinSmartApp, а не AppMainScreen
import com.finsmart.app.ui.theme.FinSmartTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FinSmartTheme {
                FinSmartApp()
            }
        }
    }
}