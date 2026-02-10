package com.gcc.admin.ui.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gcc.admin.navigation.AdminScreen
import com.gcc.admin.ui.theme.*

private data class NavItem(
    val label: String,
    val icon: ImageVector,
    val screen: AdminScreen,
)

private data class NavSection(
    val header: String,
    val items: List<NavItem>,
)

private val navSections = listOf(
    NavSection(
        header = "Overview",
        items = listOf(
            NavItem("Dashboard", Icons.Default.Dashboard, AdminScreen.Dashboard),
        ),
    ),
    NavSection(
        header = "Management",
        items = listOf(
            NavItem("Workers", Icons.Default.People, AdminScreen.WorkerList),
            NavItem("Sponsors", Icons.Default.Business, AdminScreen.SponsorList),
            NavItem("Jobs", Icons.Default.Work, AdminScreen.JobList),
            NavItem("Routes", Icons.Default.Route, AdminScreen.RouteList),
            NavItem("Blocks", Icons.Default.GridView, AdminScreen.BlockList),
        ),
    ),
    NavSection(
        header = "Operations",
        items = listOf(
            NavItem("Bounties", Icons.Default.EmojiEvents, AdminScreen.BountyList),
            NavItem("Service Areas", Icons.Default.Map, AdminScreen.ServiceAreaMap),
            NavItem("Disputes", Icons.Default.Gavel, AdminScreen.DisputeList),
            NavItem("Analytics", Icons.Default.BarChart, AdminScreen.Analytics),
            NavItem("Feedback", Icons.Default.Feedback, AdminScreen.FeedbackList),
        ),
    ),
    NavSection(
        header = "System",
        items = listOf(
            NavItem("Address Changes", Icons.Default.EditLocationAlt, AdminScreen.AddressChanges),
            NavItem("Settings", Icons.Default.Settings, AdminScreen.Settings),
        ),
    ),
)

/**
 * Persistent sidebar navigation for the admin dashboard.
 *
 * Renders a dark sidebar with grouped navigation items. The active screen
 * is highlighted with the primary (green) accent color.
 */
@Composable
fun AdminSidebar(
    activeScreen: AdminScreen,
    onNavigate: (AdminScreen) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(240.dp)
            .fillMaxHeight()
            .background(AdminSidebarBackground),
    ) {
        // ── Logo / Title ─────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp),
        ) {
            Text(
                text = "GCC",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                ),
                color = AdminPrimary,
            )
            Text(
                text = "Admin Dashboard",
                style = MaterialTheme.typography.bodySmall,
                color = AdminOnSurfaceVariant,
            )
        }

        HorizontalDivider(
            color = SidebarDivider,
            thickness = 1.dp,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Spacer(modifier = Modifier.height(8.dp))

        // ── Navigation Sections ──────────────────────────────────────────
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            navSections.forEach { section ->
                SidebarSection(
                    section = section,
                    activeScreen = activeScreen,
                    onNavigate = onNavigate,
                )
            }
        }

        // ── Bottom branding ──────────────────────────────────────────────
        HorizontalDivider(
            color = SidebarDivider,
            thickness = 1.dp,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Text(
            text = "v1.0.0",
            style = MaterialTheme.typography.labelSmall,
            color = SidebarSectionHeader,
            modifier = Modifier.padding(start = 20.dp, bottom = 16.dp, top = 12.dp),
        )
    }
}

@Composable
private fun SidebarSection(
    section: NavSection,
    activeScreen: AdminScreen,
    onNavigate: (AdminScreen) -> Unit,
) {
    // Section header
    Text(
        text = section.header.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
        ),
        color = SidebarSectionHeader,
        modifier = Modifier.padding(start = 8.dp, top = 20.dp, bottom = 8.dp),
    )

    section.items.forEach { item ->
        SidebarNavItem(
            item = item,
            isActive = isScreenActive(activeScreen, item.screen),
            onClick = { onNavigate(item.screen) },
        )
    }
}

@Composable
private fun SidebarNavItem(
    item: NavItem,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val backgroundColor = when {
        isActive -> SidebarItemActiveBg
        isHovered -> AdminSurfaceVariant.copy(alpha = 0.4f)
        else -> androidx.compose.ui.graphics.Color.Transparent
    }
    val contentColor = if (isActive) SidebarItemActive else SidebarItemInactive
    val fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = item.label,
            tint = contentColor,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = item.label,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = fontWeight),
            color = contentColor,
        )
    }
}

/**
 * Determines if the current [active] screen matches a sidebar [target] screen.
 * Detail screens map back to their parent list screen.
 */
private fun isScreenActive(active: AdminScreen, target: AdminScreen): Boolean {
    if (active == target) return true
    return when (active) {
        is AdminScreen.WorkerDetail -> target is AdminScreen.WorkerList
        is AdminScreen.SponsorDetail -> target is AdminScreen.SponsorList
        is AdminScreen.JobDetail -> target is AdminScreen.JobList
        is AdminScreen.RouteEditor -> target is AdminScreen.RouteList
        is AdminScreen.BountyDetail -> target is AdminScreen.BountyList
        is AdminScreen.DisputeDetail -> target is AdminScreen.DisputeList
        else -> false
    }
}
