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
import com.portafolios.app.ui.screens.PlanEditorScreen
import com.portafolios.app.ui.screens.PositionScreen
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
                onCreate = { nav.navigate("create") },
            )
        }
        composable("create") {
            PlanEditorScreen(container, null, onDone = { id -> nav.navigate("portfolio/$id") { popUpTo("portfolios") } }, onBack = { nav.popBackStack() })
        }
        composable("plan/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            PlanEditorScreen(container, entry.arguments!!.getLong("id"), onDone = { nav.popBackStack() }, onBack = { nav.popBackStack() })
        }
        composable("portfolio/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            val id = entry.arguments!!.getLong("id")
            PortfolioDetailScreen(
                container, id,
                onBack = { nav.popBackStack() },
                onImport = { nav.navigate("import/$id") },
                onAlerts = { nav.navigate("alerts") },
                onPlan = { nav.navigate("plan/$id") },
                onPosition = { sym -> nav.navigate("position/$id/${android.net.Uri.encode(sym)}") },
            )
        }
        composable("import/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            ImportScreen(container, entry.arguments!!.getLong("id"), onBack = { nav.popBackStack() })
        }
        composable(
            "position/{id}/{symbol}",
            arguments = listOf(navArgument("id") { type = NavType.LongType }, navArgument("symbol") { type = NavType.StringType }),
        ) { entry ->
            PositionScreen(container, entry.arguments!!.getLong("id"), entry.arguments!!.getString("symbol")!!, onBack = { nav.popBackStack() })
        }
        composable("alerts") { AlertsScreen(container, onBack = { nav.popBackStack() }) }
        composable("settings") { SettingsScreen(container, onBack = { nav.popBackStack() }) }
    }
}
