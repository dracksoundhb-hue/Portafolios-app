package com.portafolios.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.portafolios.app.AppContainer
import com.portafolios.app.ui.screens.AlertsScreen
import com.portafolios.app.ui.screens.ImportScreen
import com.portafolios.app.ui.screens.PortfolioDetailScreen
import com.portafolios.app.ui.screens.PortfolioListScreen
import com.portafolios.app.ui.screens.SettingsScreen

@Composable
fun AppRoot(container: AppContainer) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "portfolios") {
        composable("portfolios") {
            PortfolioListScreen(
                container,
                onOpen = { nav.navigate("portfolio/$it") },
                onSettings = { nav.navigate("settings") },
                onAlerts = { nav.navigate("alerts") },
            )
        }
        composable("portfolio/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            val id = entry.arguments!!.getLong("id")
            PortfolioDetailScreen(
                container, id,
                onBack = { nav.popBackStack() },
                onImport = { nav.navigate("import/$id") },
                onAlerts = { nav.navigate("alerts") },
            )
        }
        composable("import/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            ImportScreen(container, entry.arguments!!.getLong("id"), onBack = { nav.popBackStack() })
        }
        composable("alerts") { AlertsScreen(container, onBack = { nav.popBackStack() }) }
        composable("settings") { SettingsScreen(container, onBack = { nav.popBackStack() }) }
    }
}
