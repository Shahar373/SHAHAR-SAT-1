package com.shahar.shaharsat.ui.nav

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.shahar.shaharsat.di.AppContainer
import com.shahar.shaharsat.di.ViewModelFactory
import com.shahar.shaharsat.ui.attitude.AttitudeScreen
import com.shahar.shaharsat.ui.attitude.AttitudeViewModel
import com.shahar.shaharsat.ui.connection.ConnectionScreen
import com.shahar.shaharsat.ui.connection.ConnectionViewModel
import com.shahar.shaharsat.ui.dashboard.DashboardScreen
import com.shahar.shaharsat.ui.dashboard.DashboardViewModel
import com.shahar.shaharsat.ui.debug.EngineeringScreen
import com.shahar.shaharsat.ui.debug.EngineeringViewModel

private object Routes {
    const val CONNECTION = "connection"
    const val DASHBOARD = "dashboard"
    const val ATTITUDE = "attitude"
    const val ENGINEERING = "engineering"
}

@Composable
fun SatNavHost(container: AppContainer) {
    val navController = rememberNavController()
    val factory = ViewModelFactory(container)

    NavHost(navController = navController, startDestination = Routes.CONNECTION) {
        composable(Routes.CONNECTION) {
            val vm: ConnectionViewModel = viewModel(factory = factory)
            ConnectionScreen(vm) {
                navController.navigate(Routes.DASHBOARD) {
                    popUpTo(Routes.CONNECTION) { inclusive = true }
                }
            }
        }
        composable(Routes.DASHBOARD) {
            val vm: DashboardViewModel = viewModel(factory = factory)
            DashboardScreen(
                viewModel = vm,
                onOpenAttitude = { navController.navigate(Routes.ATTITUDE) },
                onOpenEngineering = { navController.navigate(Routes.ENGINEERING) }
            )
        }
        composable(Routes.ATTITUDE) {
            val vm: AttitudeViewModel = viewModel(factory = factory)
            AttitudeScreen(vm, onBack = { navController.popBackStack() })
        }
        composable(Routes.ENGINEERING) {
            val vm: EngineeringViewModel = viewModel(factory = factory)
            EngineeringScreen(vm, onBack = { navController.popBackStack() })
        }
    }
}
