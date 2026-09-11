package com.smartview.glassai.ui.navigation

import android.graphics.Bitmap
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.smartview.glassai.R
import com.smartview.glassai.debug.GlassesDisplayPreviewEntry
import com.smartview.glassai.debug.MockDeviceKitEntry
import com.smartview.glassai.glasses.NavigationRequest
import com.smartview.glassai.ui.components.WearablesErrorToast
import com.smartview.glassai.ui.screens.*
import com.smartview.glassai.ui.theme.Primary
import com.smartview.glassai.viewmodels.WearablesViewModel
import kotlinx.coroutines.flow.SharedFlow

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object LiveAI : Screen("live_ai")
    object LeanEat : Screen("lean_eat")
    object Vision : Screen("vision")
    object QuickVision : Screen("quick_vision")
    object Settings : Screen("settings")
    object Records : Screen("records")
    object Gallery : Screen("gallery")
    object LiveStream : Screen("live_stream")
    object RTMPStream : Screen("rtmp_stream")
    object QuickVisionMode : Screen("quick_vision_mode")
    object LiveAIMode : Screen("live_ai_mode")
    object MockDeviceKit : Screen("mock_device_kit")
    object GlassesDisplayPreview : Screen("glasses_display_preview")
    object OpenClaw : Screen("openclaw")
    object OpenClawSettings : Screen("openclaw_settings")
    object NotificationBridge : Screen("notification_bridge")
    object LiveTranslate : Screen("live_translate")
    object LiveTranslateSettings : Screen("live_translate_settings")
}

sealed class BottomNavItem(
    val route: String,
    val icon: ImageVector,
    val labelResId: Int
) {
    object Home : BottomNavItem("home", Icons.Default.Home, R.string.home)
    object Records : BottomNavItem("records", Icons.AutoMirrored.Filled.Message, R.string.records)
    object Gallery : BottomNavItem("gallery", Icons.Default.Photo, R.string.gallery)
    object Settings : BottomNavItem("settings", Icons.Default.Person, R.string.settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TurboMetaNavigation(
    wearablesViewModel: WearablesViewModel,
    onRequestWearablesPermission: suspend (Permission) -> PermissionStatus,
    navigationRequests: SharedFlow<NavigationRequest>
) {
    val navController = rememberNavController()
    val lifecycleOwner = LocalLifecycleOwner.current
    // Photo handoff is RAM-only, outside navigation saved state.
    val photoHandoff = remember { PhotoHandoff<Bitmap>() }

    LaunchedEffect(navigationRequests, lifecycleOwner) {
        // This owner is the Activity (outside NavHost). Cancel the collector on STOP so
        // replay-free background taps cannot navigate after the Activity starts again.
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            navigationRequests.collect { request ->
                val route = when (request) {
                    NavigationRequest.LiveAI -> Screen.LiveAI.route
                    NavigationRequest.LeanEat -> Screen.LeanEat.route
                    NavigationRequest.OpenClaw -> Screen.OpenClaw.route
                }
                navController.navigate(route) { launchSingleTop = true }
            }
        }
    }

    val bottomNavItems = listOf(
        BottomNavItem.Home,
        BottomNavItem.Records,
        BottomNavItem.Gallery,
        BottomNavItem.Settings
    )

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Determine if we should show bottom nav
    val showBottomNav = currentRoute in bottomNavItems.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBottomNav) {
                NavigationBar {
                    bottomNavItems.forEach { item ->
                        NavigationBarItem(
                            icon = {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = stringResource(item.labelResId)
                                )
                            },
                            label = { Text(stringResource(item.labelResId)) },
                            selected = currentRoute == item.route,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Primary,
                                selectedTextColor = Primary,
                                indicatorColor = Primary.copy(alpha = 0.1f)
                            )
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        // One toast for every glasses error, regardless of the screen (Phase B Task 8).
        WearablesErrorToast(wearablesViewModel)

        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(paddingValues)
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    wearablesViewModel = wearablesViewModel,
                    onRequestWearablesPermission = onRequestWearablesPermission,
                    onNavigateToLiveAI = {
                        navController.navigate(Screen.LiveAI.route)
                    },
                    onNavigateToLeanEat = {
                        navController.navigate(Screen.LeanEat.route)
                    },
                    onNavigateToVision = {
                        navController.navigate(Screen.QuickVision.route)
                    },
                    onNavigateToSettings = {
                        navController.navigate(Screen.Settings.route)
                    },
                    onNavigateToLiveStream = {
                        navController.navigate(Screen.LiveStream.route)
                    },
                    onNavigateToRTMPStream = {
                        navController.navigate(Screen.RTMPStream.route)
                    },
                    onNavigateToOpenClaw = {
                        navController.navigate(Screen.OpenClaw.route)
                    },
                    onNavigateToNotificationBridge = {
                        navController.navigate(Screen.NotificationBridge.route) { launchSingleTop = true }
                    },
                    onNavigateToTranslate = { navController.navigate(Screen.LiveTranslate.route) }
                )
            }

            composable(Screen.LiveAI.route) {
                LiveAIScreen(
                    wearablesViewModel = wearablesViewModel,
                    onRequestWearablesPermission = onRequestWearablesPermission,
                    onBackClick = {
                        navController.popBackStack()
                    }
                )
            }

            composable(Screen.LeanEat.route) {
                val photo = remember { photoHandoff.take(Screen.LeanEat.route) }
                AnalysisPhotoRoute(wearablesViewModel, photo, onRequestWearablesPermission, keepDisplaySession = true) { fresh, frame, capture ->
                    LeanEatScreen(currentFrame = frame, initialPhoto = fresh,
                        onBackClick = { navController.popBackStack() }, onTakePhoto = capture)
                }
            }

            composable(Screen.Vision.route) {
                val photo = remember { photoHandoff.take(Screen.Vision.route) }
                AnalysisPhotoRoute(wearablesViewModel, photo, onRequestWearablesPermission) { fresh, frame, capture ->
                    VisionScreen(currentFrame = frame, initialPhoto = fresh,
                        onBackClick = { navController.popBackStack() }, onTakePhoto = capture)
                }
            }

            composable(Screen.QuickVision.route) {
                QuickVisionScreen(
                    wearablesViewModel = wearablesViewModel,
                    onBackClick = {
                        navController.popBackStack()
                    }
                )
            }

            composable(Screen.Settings.route) {
                SettingsScreen(
                    onBackClick = {
                        navController.popBackStack()
                    },
                    onNavigateToRecords = {
                        navController.navigate(Screen.Records.route)
                    },
                    onNavigateToQuickVisionMode = {
                        navController.navigate(Screen.QuickVisionMode.route)
                    },
                    onNavigateToLiveAIMode = {
                        navController.navigate(Screen.LiveAIMode.route)
                    },
                    onNavigateToMockDeviceKit = {
                        navController.navigate(Screen.MockDeviceKit.route)
                    },
                    onNavigateToOpenClawSettings = {
                        navController.navigate(Screen.OpenClawSettings.route)
                    },
                    onNavigateToGlassesDisplayPreview = {
                        if (GlassesDisplayPreviewEntry.isAvailable) {
                            navController.navigate(Screen.GlassesDisplayPreview.route)
                        }
                    },
                    onNavigateToNotificationBridge = {
                        navController.navigate(Screen.NotificationBridge.route) { launchSingleTop = true }
                    },
                    onNavigateToTranslateSettings = { navController.navigate(Screen.LiveTranslateSettings.route) }
                )
            }

            composable(Screen.NotificationBridge.route) {
                NotificationBridgeScreen(onBackClick = { navController.popBackStack() })
            }

            composable(Screen.LiveTranslate.route) {
                LiveTranslateScreen(wearablesViewModel = wearablesViewModel,
                    onBackClick = { navController.popBackStack() },
                    onSettingsClick = { navController.navigate(Screen.LiveTranslateSettings.route) },
                    onRequestWearablesPermission = onRequestWearablesPermission)
            }

            composable(Screen.LiveTranslateSettings.route) {
                LiveTranslateSettingsScreen(onBackClick = { navController.popBackStack() })
            }

            composable(Screen.Records.route) {
                RecordsScreen(
                    onBackClick = {
                        navController.popBackStack()
                    }
                )
            }

            composable(Screen.Gallery.route) {
                GalleryScreen(
                    onBackClick = {
                        navController.popBackStack()
                    }
                )
            }

            composable(Screen.LiveStream.route) {
                CameraScreen(
                    wearablesViewModel = wearablesViewModel,
                    onBackClick = { navController.popBackStack() },
                    onAnalyzePhoto = { photo ->
                        photoHandoff.put(Screen.Vision.route, photo)
                        navController.navigate(Screen.Vision.route)
                    },
                    onNutritionPhoto = { photo ->
                        photoHandoff.put(Screen.LeanEat.route, photo)
                        navController.navigate(Screen.LeanEat.route)
                    },
                    onRequestWearablesPermission = onRequestWearablesPermission
                )
            }

            composable(Screen.RTMPStream.route) {
                RTMPStreamingScreen(
                    onBackClick = {
                        navController.popBackStack()
                    }
                )
            }

            composable(Screen.QuickVisionMode.route) {
                QuickVisionModeScreen(
                    onBackClick = {
                        navController.popBackStack()
                    }
                )
            }

            composable(Screen.LiveAIMode.route) {
                LiveAIModeScreen(
                    onBackClick = {
                        navController.popBackStack()
                    }
                )
            }

            composable(Screen.MockDeviceKit.route) {
                MockDeviceKitEntry.Screen(
                    onBackClick = {
                        navController.popBackStack()
                    }
                )
            }

            if (GlassesDisplayPreviewEntry.isAvailable) {
                composable(Screen.GlassesDisplayPreview.route) {
                    GlassesDisplayPreviewEntry.Screen(onBackClick = { navController.popBackStack() })
                }
            }

            composable(Screen.OpenClaw.route) {
                OpenClawChatScreen(
                    onBackClick = {
                        navController.popBackStack()
                    },
                    onOpenSettings = {
                        navController.navigate(Screen.OpenClawSettings.route)
                    }
                )
            }

            composable(Screen.OpenClawSettings.route) {
                OpenClawSettingsScreen(
                    onBackClick = {
                        navController.popBackStack()
                    }
                )
            }
        }
    }
}
