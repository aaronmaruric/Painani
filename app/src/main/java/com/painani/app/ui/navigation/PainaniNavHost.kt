package com.painani.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.painani.app.AppContainer
import com.painani.app.R
import com.painani.app.ui.calendar.CalendarScreen
import com.painani.app.ui.calendar.DayDetailScreen
import com.painani.app.ui.run.RunScreen
import com.painani.app.ui.settings.SettingsScreen
import com.painani.app.ui.stats.StatsScreen
import com.painani.app.ui.strength.StrengthScreen
import com.painani.app.ui.theme.NothingRed
import java.time.LocalDate

sealed class Route(val path: String) {
    data object Calendar : Route("calendar")
    data object Run : Route("run")
    data object Strength : Route("strength")
    data object Stats : Route("stats")
    data object Settings : Route("settings")
    data object DayDetail : Route("day/{date}") {
        fun build(date: LocalDate) = "day/$date"
    }
}

private data class TopLevel(val route: Route, val labelRes: Int, val icon: ImageVector)

private val topLevel = listOf(
    TopLevel(Route.Calendar, R.string.nav_calendar, Icons.Default.CalendarMonth),
    TopLevel(Route.Run, R.string.nav_run, Icons.AutoMirrored.Filled.DirectionsRun),
    TopLevel(Route.Strength, R.string.nav_strength, Icons.Default.FitnessCenter),
    TopLevel(Route.Stats, R.string.nav_stats, Icons.Default.BarChart),
)

@Composable
fun PainaniNavHost(container: AppContainer) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentDestination = backStack?.destination

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
                topLevel.forEach { item ->
                    val selected = currentDestination?.hierarchy?.any { it.route == item.route.path } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(item.route.path) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(stringResource(item.labelRes).uppercase(), style = MaterialTheme.typography.labelSmall) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = NothingRed,
                            selectedTextColor = NothingRed,
                            indicatorColor = Color.Transparent,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Route.Calendar.path,
            modifier = Modifier.padding(padding),
        ) {
            composable(Route.Calendar.path) {
                CalendarScreen(
                    sessionRepository = container.sessionRepository,
                    eventRepository = container.calendarEventRepository,
                    onDayClick = { date -> navController.navigate(Route.DayDetail.build(date)) },
                )
            }
            composable(Route.DayDetail.path) { entry ->
                val date = LocalDate.parse(entry.arguments?.getString("date"))
                DayDetailScreen(
                    initialDate = date,
                    repository = container.sessionRepository,
                    eventRepository = container.calendarEventRepository,
                    healthConnect = container.healthConnect,
                    healthSync = container.healthSync,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Route.Run.path) {
                RunScreen(
                    repository = container.sessionRepository,
                    tracker = container.runTracker,
                    healthSync = container.healthSync,
                )
            }
            composable(Route.Strength.path) {
                StrengthScreen(repository = container.sessionRepository, healthSync = container.healthSync)
            }
            composable(Route.Stats.path) {
                StatsScreen(
                    sessionRepository = container.sessionRepository,
                    bodyStatsRepository = container.bodyStatsRepository,
                    healthDataRepository = container.healthDataRepository,
                    healthConnect = container.healthConnect,
                    healthSync = container.healthSync,
                    onOpenSettings = { navController.navigate(Route.Settings.path) { launchSingleTop = true } },
                )
            }
            composable(Route.Settings.path) {
                SettingsScreen(
                    profileRepository = container.profileRepository,
                    bodyStatsRepository = container.bodyStatsRepository,
                    eventRepository = container.calendarEventRepository,
                    healthConnect = container.healthConnect,
                    healthSync = container.healthSync,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
