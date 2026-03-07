package com.gcc.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.theme.DividerColor
import com.gcc.app.ui.theme.GccGold
import com.gcc.app.ui.theme.GccGreen

@Composable
fun GccBottomNavBar(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    isAdmin: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // Tab indices:
    // 0 = Dashboard, 1 = Map, 2 = Admin (only when isAdmin), last = Profile
    val profileTab = if (isAdmin) 3 else 2
    val adminTab = 2 // only used when isAdmin is true

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(DividerColor)
        )
        NavigationBar(
            containerColor = Color.White,
            contentColor = GccGreen,
        ) {
            NavigationBarItem(
                selected = selectedTab == 0,
                onClick = { onTabSelected(0) },
                icon = { Text("\uD83C\uDFE0") },
                label = { Text("Dashboard") },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = GccGreen,
                    selectedTextColor = GccGreen,
                    indicatorColor = GccGreen.copy(alpha = 0.12f),
                ),
            )
            NavigationBarItem(
                selected = selectedTab == 1,
                onClick = { onTabSelected(1) },
                icon = { Text("\uD83C\uDF0D") },
                label = { Text("Map") },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = GccGreen,
                    selectedTextColor = GccGreen,
                    indicatorColor = GccGreen.copy(alpha = 0.12f),
                ),
            )
            if (isAdmin) {
                NavigationBarItem(
                    selected = selectedTab == adminTab,
                    onClick = { onTabSelected(adminTab) },
                    icon = { Text("\uD83D\uDEE1\uFE0F") },
                    label = { Text("Admin") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = GccGold,
                        selectedTextColor = GccGold,
                        indicatorColor = GccGold.copy(alpha = 0.12f),
                    ),
                )
            }
            NavigationBarItem(
                selected = selectedTab == profileTab,
                onClick = { onTabSelected(profileTab) },
                icon = { Text("\uD83D\uDC64") },
                label = { Text("Profile") },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = GccGreen,
                    selectedTextColor = GccGreen,
                    indicatorColor = GccGreen.copy(alpha = 0.12f),
                ),
            )
        }
    }
}
