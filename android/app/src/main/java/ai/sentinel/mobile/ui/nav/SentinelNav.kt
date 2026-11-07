package ai.sentinel.mobile.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ai.sentinel.mobile.ui.screens.AlertsScreen
import ai.sentinel.mobile.ui.screens.DashboardScreen
import ai.sentinel.mobile.ui.screens.DetectionScreen
import ai.sentinel.mobile.ui.screens.FirewallScreen
import ai.sentinel.mobile.ui.screens.IntelScreen
import ai.sentinel.mobile.ui.screens.LiveMonitorScreen
import ai.sentinel.mobile.ui.screens.ReportsScreen
import ai.sentinel.mobile.ui.screens.SettingsScreen
import ai.sentinel.mobile.ui.screens.TrafficScreen
import ai.sentinel.mobile.ui.vm.AppViewModel

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
        val wide = maxWidth >= 840.dp
        if (wide) {
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    Destination.entries.forEach { destination ->
                        NavigationRailItem(
                            selected = currentRoute == destination.route,
                            onClick = { navigate(navController, destination) },
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label) }
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
