package com.finsmart.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.finsmart.app.ui.navigation.FinSmartApp
import com.finsmart.app.ui.theme.FinSmartTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Запрашиваем разрешение на локальную сеть для Android 17+
        if (Build.VERSION.SDK_INT >= 37) {
            val permission = Manifest.permission.ACCESS_LOCAL_NETWORK
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                val requestPermissionLauncher = registerForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { _ -> }
                requestPermissionLauncher.launch(permission)
            }
        }

        setContent {
            FinSmartTheme {
                FinSmartApp()
            }
        }
    }
}