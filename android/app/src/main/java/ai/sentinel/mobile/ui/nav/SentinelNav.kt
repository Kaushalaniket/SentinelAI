package ai.sentinel.mobile.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ai.sentinel.mobile.net.TunnelState
import ai.sentinel.mobile.ui.screens.AlertsScreen
import ai.sentinel.mobile.ui.screens.DashboardScreen
import ai.sentinel.mobile.ui.screens.DetectionScreen
import ai.sentinel.mobile.ui.screens.FirewallScreen
import ai.sentinel.mobile.ui.screens.IntelScreen
import ai.sentinel.mobile.ui.screens.LiveMonitorScreen
import ai.sentinel.mobile.ui.screens.ReportsScreen
import ai.sentinel.mobile.ui.screens.SettingsScreen
import ai.sentinel.mobile.ui.screens.TrafficScreen
import ai.sentinel.mobile.ui.theme.SentinelAmber
import ai.sentinel.mobile.ui.theme.SentinelBlue
import ai.sentinel.mobile.ui.theme.SentinelCard
import ai.sentinel.mobile.ui.theme.SentinelCyan
import ai.sentinel.mobile.ui.theme.SentinelGreen
import ai.sentinel.mobile.ui.theme.SentinelGrid
import ai.sentinel.mobile.ui.theme.SentinelRed
import ai.sentinel.mobile.ui.theme.SentinelSurface
import ai.sentinel.mobile.ui.theme.SentinelText
import ai.sentinel.mobile.ui.theme.SentinelTextSecondary
import ai.sentinel.mobile.ui.vm.AppViewModel
import ai.sentinel.mobile.util.Formatters

/**
 * True when the window is wide enough for the persistent sidebar. 720 dp keeps the sidebar on
 * a 12" tablet in both orientations while phones fall back to the compact chip bar.
 */
val LocalWideLayout = staticCompositionLocalOf { false }

enum class Destination(val route: String, val label: String, val icon: ImageVector) {
    DASHBOARD("dashboard", "Dashboard", Icons.Filled.Dashboard),
    LIVE("live", "Live", Icons.Filled.Sensors),
    TRAFFIC("traffic", "Traffic", Icons.Filled.ViewList),
    DETECTION("detection", "Detection", Icons.Filled.Security),
    FIREWALL("firewall", "Firewall", Icons.Filled.Block),
    INTEL("intel", "Intel", Icons.Filled.Public),
    ALERTS("alerts", "Alerts", Icons.Filled.Notifications),
    REPORTS("reports", "Reports", Icons.Filled.ShowChart),
    SETTINGS("settings", "Settings", Icons.Filled.Settings)
}

@Composable
fun SentinelRoot(vm: AppViewModel, onEnableProtection: () -> Unit) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        CompositionLocalProvider(LocalWideLayout provides wide) {
            if (wide) {
                Row(modifier = Modifier.fillMaxSize()) {
                    SentinelSidebar(currentRoute) { navigate(navController, it) }
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(SentinelGrid.copy(alpha = 0.7f))
                    )
                    NavHost(
                        navController = navController,
                        startDestination = Destination.DASHBOARD.route,
                        modifier = Modifier.weight(1f)
                    ) {
                        graph(vm, onEnableProtection)
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surface)
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Destination.entries.forEach { destination ->
                            FilterChip(
                                selected = currentRoute == destination.route,
                                onClick = { navigate(navController, destination) },
                                label = { Text(destination.label) },
                                modifier = Modifier.padding(horizontal = 3.dp)
                            )
                        }
                    }
                    NavHost(
                        navController = navController,
                        startDestination = Destination.DASHBOARD.route,
                        modifier = Modifier.weight(1f)
                    ) {
                        graph(vm, onEnableProtection)
                    }
                }
            }
        }
    }
}

/** Persistent enterprise-style sidebar: brand, module list, live tunnel status. */
@Composable
private fun SentinelSidebar(
    currentRoute: String?,
    onNavigate: (Destination) -> Unit
) {
    val snapshot by TunnelState.state.collectAsState()
    val statusText = when {
        snapshot.isCapture -> "CAPTURE"
        snapshot.isRunning -> "PROTECTING"
        snapshot.status == TunnelState.Status.ERROR -> "ERROR"
        else -> "STANDBY"
    }
    val statusTint = when {
        snapshot.isCapture -> SentinelRed
        snapshot.isRunning -> SentinelGreen
        snapshot.status == TunnelState.Status.ERROR -> SentinelRed
        else -> SentinelAmber
    }

    Column(
        modifier = Modifier
            .width(232.dp)
            .fillMaxHeight()
            .background(SentinelSurface)
            .padding(horizontal = 12.dp, vertical = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 18.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(SentinelBlue.copy(alpha = 0.16f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Security, contentDescription = null, tint = SentinelCyan, modifier = Modifier.size(21.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    "SENTINEL",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = SentinelText,
                    letterSpacing = 1.4.sp
                )
                Text(
                    "SECURITY OPERATIONS",
                    style = MaterialTheme.typography.labelSmall,
                    color = SentinelTextSecondary,
                    letterSpacing = 0.6.sp
                )
            }
        }

        Text(
            "MODULES",
            style = MaterialTheme.typography.labelSmall,
            color = SentinelTextSecondary,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(start = 8.dp, bottom = 6.dp)
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Destination.entries.forEach { destination ->
                SidebarItem(
                    destination = destination,
                    selected = currentRoute == destination.route,
                    onClick = { onNavigate(destination) }
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SentinelCard, RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(statusTint, CircleShape))
                Spacer(Modifier.width(7.dp))
                Text(
                    statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusTint,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "${snapshot.packetsIn} pkts · ${Formatters.bytes(snapshot.bytesIn)}",
                style = MaterialTheme.typography.labelSmall,
                color = SentinelTextSecondary
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "v1.0.0 · on-device only",
                style = MaterialTheme.typography.labelSmall,
                color = SentinelTextSecondary
            )
        }
    }
}

@Composable
private fun SidebarItem(
    destination: Destination,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) SentinelBlue.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(3.dp, 18.dp)
                .background(if (selected) SentinelCyan else Color.Transparent, RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.width(9.dp))
        Icon(
            destination.icon,
            contentDescription = destination.label,
            tint = if (selected) SentinelCyan else SentinelTextSecondary,
            modifier = Modifier.size(19.dp)
        )
        Spacer(Modifier.width(11.dp))
        Text(
            destination.label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) SentinelText else SentinelTextSecondary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

private fun navigate(navController: NavHostController, destination: Destination) {
    navController.navigate(destination.route) {
        popUpTo(Destination.DASHBOARD.route) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun androidx.navigation.NavGraphBuilder.graph(
    vm: AppViewModel,
    onEnableProtection: () -> Unit
) {
    composable(Destination.DASHBOARD.route) { DashboardScreen(vm, onEnableProtection) }
    composable(Destination.LIVE.route) { LiveMonitorScreen(vm, onEnableProtection) }
    composable(Destination.TRAFFIC.route) { TrafficScreen(vm) }
    composable(Destination.DETECTION.route) { DetectionScreen(vm) }
    composable(Destination.FIREWALL.route) { FirewallScreen(vm) }
    composable(Destination.INTEL.route) { IntelScreen(vm) }
    composable(Destination.ALERTS.route) { AlertsScreen(vm) }
    composable(Destination.REPORTS.route) { ReportsScreen(vm) }
    composable(Destination.SETTINGS.route) { SettingsScreen(vm, onEnableProtection) }
}
