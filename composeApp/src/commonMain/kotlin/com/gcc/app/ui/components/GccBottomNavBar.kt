package com.gcc.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
            NavTab(selected = selectedTab == 0, onClick = { onTabSelected(0) }, icon = Icons.Default.Home, label = "Dashboard")
            NavTab(selected = selectedTab == 1, onClick = { onTabSelected(1) }, icon = Icons.Default.LocationOn, label = "Map")
            if (isAdmin) {
                NavTab(selected = selectedTab == 2, onClick = { onTabSelected(2) }, icon = Icons.Default.Settings, label = "Admin", color = GccGold)
                NavTab(selected = selectedTab == 3, onClick = { onTabSelected(3) }, icon = Icons.Default.Person, label = "Profile")
            } else {
                NavTab(selected = selectedTab == 2, onClick = { onTabSelected(2) }, icon = Icons.Default.Person, label = "Profile")
            }
        }
    }
}

@Composable
private fun RowScope.NavTab(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    color: Color = GccGreen,
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = label) },
        label = { Text(label) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = color,
            selectedTextColor = color,
            indicatorColor = color.copy(alpha = 0.12f),
        ),
    )
}
