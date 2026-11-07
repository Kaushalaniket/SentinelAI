package ai.sentinel.mobile.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ai.sentinel.mobile.net.TunnelState
import ai.sentinel.mobile.ui.adaptive.Adaptively
import ai.sentinel.mobile.ui.adaptive.WindowSize
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

/** Modules that get their own slot in the compact bottom bar; the rest live behind "More". */
private val compactPrimary = listOf(
    Destination.DASHBOARD,
    Destination.LIVE,
    Destination.TRAFFIC,
    Destination.ALERTS
)

/**
 * One UI, three navigation shapes:
 *  - COMPACT  → bottom navigation bar (four modules) with a "More" sheet for the rest
 *  - MEDIUM   → scrollable icon-and-label rail (phones in landscape, 7–8" tablets, foldables)
 *  - EXPANDED → persistent 220–248 dp sidebar (10–13" tablets such as the Idea Tab Pro)
 * Each branch gets the whole screen minus its own navigation, so content never overlaps it.
 */
@Composable
fun SentinelRoot(
    vm: AppViewModel,
    windowSize: WindowSize,
    onEnableProtection: () -> Unit
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Adaptively(windowSize) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val shortHeight = maxHeight < 560.dp
            val sidebarWidth = if (maxWidth >= 1100.dp) 248.dp else 220.dp
            when (windowSize) {
                WindowSize.COMPACT -> Column(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f)) {
                        SentinelGraph(navController, vm, onEnableProtection)
                    }
                    CompactNavBar(currentRoute) { navigate(navController, it) }
                }

                WindowSize.MEDIUM -> Row(modifier = Modifier.fillMaxSize()) {
                    SentinelRail(currentRoute, shortHeight) { navigate(navController, it) }
                    VerticalRule()
                    Box(modifier = Modifier.weight(1f)) {
                        SentinelGraph(navController, vm, onEnableProtection)
                    }
                }

                WindowSize.EXPANDED -> Row(modifier = Modifier.fillMaxSize()) {
                    SentinelSidebar(currentRoute, sidebarWidth, shortHeight) {
                        navigate(navController, it)
                    }
                    VerticalRule()
                    Box(modifier = Modifier.weight(1f)) {
                        SentinelGraph(navController, vm, onEnableProtection)
                    }
                }
            }
        }
    }
}

@Composable
private fun SentinelGraph(
    navController: NavHostController,
    vm: AppViewModel,
    onEnableProtection: () -> Unit
) {
    NavHost(
        navController = navController,
        startDestination = Destination.DASHBOARD.route,
        modifier = Modifier.fillMaxSize()
    ) {
        graph(vm, onEnableProtection)
    }
}

@Composable
private fun VerticalRule() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .fillMaxHeight()
            .background(SentinelGrid.copy(alpha = 0.7f))
    )
}

/** Compact: bottom bar for the four daily modules, everything else in a sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactNavBar(currentRoute: String?, onNavigate: (Destination) -> Unit) {
    var showMore by remember { mutableStateOf(false) }
    val onPrimary = compactPrimary.any { it.route == currentRoute }

    NavigationBar(containerColor = SentinelSurface, tonalElevation = 0.dp) {
        compactPrimary.forEach { destination ->
            NavigationBarItem(
                selected = currentRoute == destination.route,
                onClick = { onNavigate(destination) },
                icon = {
                    Icon(
                        imageVector = destination.icon,
                        contentDescription = destination.label,
                        modifier = Modifier.size(22.dp)
                    )
                },
                label = {
                    Text(
                        destination.label,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
        }
        NavigationBarItem(
            selected = !onPrimary || showMore,
            onClick = { showMore = true },
            icon = {
                Icon(
                    imageVector = Icons.Filled.MoreHoriz,
                    contentDescription = "More modules",
                    modifier = Modifier.size(22.dp)
                )
            },
            label = {
                Text("More", style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        )
    }

    if (showMore) {
        ModalBottomSheet(
            onDismissRequest = { showMore = false },
            containerColor = SentinelSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp)
            ) {
                Text(
                    "ALL MODULES",
                    style = MaterialTheme.typography.labelSmall,
                    color = SentinelTextSecondary,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(start = 8.dp, bottom = 6.dp)
                )
                Destination.entries.forEach { destination ->
                    SheetItem(
                        destination = destination,
                        selected = currentRoute == destination.route,
                        onClick = {
                            showMore = false
                            onNavigate(destination)
                        }
                    )
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun SheetItem(
    destination: Destination,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) SentinelBlue.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            destination.icon,
            contentDescription = null,
            tint = if (selected) SentinelCyan else SentinelTextSecondary,
            modifier = Modifier.size(21.dp)
        )
        Spacer(Modifier.width(14.dp))
        Text(
            destination.label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) SentinelText else SentinelTextSecondary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Text(
                "current",
                style = MaterialTheme.typography.labelSmall,
                color = SentinelCyan
            )
        }
    }
}

/** Medium: a narrow rail that scrolls, so all nine modules stay reachable in landscape. */
@Composable
private fun SentinelRail(
    currentRoute: String?,
    shortHeight: Boolean,
    onNavigate: (Destination) -> Unit
) {
    Column(
        modifier = Modifier
            .width(92.dp)
            .fillMaxHeight()
            .background(SentinelSurface)
            .verticalScroll(rememberScrollState())
            .padding(vertical = if (shortHeight) 6.dp else 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(SentinelBlue.copy(alpha = 0.16f), RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Security,
                contentDescription = null,
                tint = SentinelCyan,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.height(if (shortHeight) 6.dp else 12.dp))
        Destination.entries.forEach { destination ->
            RailItem(
                destination = destination,
                selected = currentRoute == destination.route,
                onClick = { onNavigate(destination) }
            )
        }
    }
}

@Composable
private fun RailItem(
    destination: Destination,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) SentinelBlue.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            destination.icon,
            contentDescription = null,
            tint = if (selected) SentinelCyan else SentinelTextSecondary,
            modifier = Modifier.size(21.dp)
        )
        Spacer(Modifier.height(3.dp))
        Text(
            destination.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) SentinelText else SentinelTextSecondary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Expanded: persistent enterprise-style sidebar with brand, modules and live tunnel status. */
@Composable
private fun SentinelSidebar(
    currentRoute: String?,
    width: androidx.compose.ui.unit.Dp,
    shortHeight: Boolean,
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
            .width(width)
            .fillMaxHeight()
            .background(SentinelSurface)
            .padding(horizontal = 12.dp, vertical = if (shortHeight) 10.dp else 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = if (shortHeight) 10.dp else 18.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(SentinelBlue.copy(alpha = 0.16f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Security,
                    contentDescription = null,
                    tint = SentinelCyan,
                    modifier = Modifier.size(21.dp)
                )
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

        if (!shortHeight) {
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
                    "v1.1.0 · on-device only",
                    style = MaterialTheme.typography.labelSmall,
                    color = SentinelTextSecondary
                )
            }
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
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) SentinelBlue.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
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
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
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
