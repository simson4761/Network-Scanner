package com.example.sample.navigation

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.sample.screens.DeviceListScreen
import com.example.sample.screens.DeviceScreen
import com.example.sample.viewModel.NetworkScanViewModel
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Composable
fun NetworkAppNavigation() {
    val navController = rememberNavController()
    val scanViewModel: NetworkScanViewModel = viewModel()

    NavHost(navController = navController, startDestination = "device_list") {

        // 1. Network Device Scan Screen
        composable("device_list") {
            DeviceListScreen(
                viewModel = scanViewModel,
                onDeviceSelected = { ip, ports ->
                    val encodedIp = URLEncoder.encode(ip, StandardCharsets.UTF_8.toString())
                    val encodedPorts = URLEncoder.encode(ports, StandardCharsets.UTF_8.toString())

                    navController.navigate("device_screen/$encodedIp/$encodedPorts")
                }
            )
        }

        // 2. Main HL7 Device Monitor Screen
        composable(
            route = "device_screen/{ip}/{ports}",
            arguments = listOf(
                navArgument("ip") { type = NavType.StringType },
                navArgument("ports") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val encodedIp = backStackEntry.arguments?.getString("ip") ?: ""
            val encodedPorts = backStackEntry.arguments?.getString("ports") ?: ""

            val ip = URLDecoder.decode(encodedIp, StandardCharsets.UTF_8.toString())
            val rawPorts = URLDecoder.decode(encodedPorts, StandardCharsets.UTF_8.toString())

            val ports = rawPorts.split(",")
                .mapNotNull { it.toIntOrNull() }

            Log.d(
                "NetworkAppNavigation",
                "ip: $ip, ports: $ports"
            )

            DeviceScreen(
                ipAddress = ip,
                openPorts = ports
            )
        }
    }
}