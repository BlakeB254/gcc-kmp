package com.gcc.app

import androidx.compose.runtime.Composable
import com.gcc.app.ui.theme.GccTheme
import com.gcc.app.ui.screens.DashboardScreen

@Composable
fun App() {
    GccTheme {
        DashboardScreen()
    }
}
