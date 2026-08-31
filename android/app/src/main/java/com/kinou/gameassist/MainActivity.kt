package com.kinou.gameassist

import android.content.Intent
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.kinou.gameassist.data.community.CommunityApiClient
import com.kinou.gameassist.data.language.LanguageManager
import com.kinou.gameassist.data.model.GameProfile
import com.kinou.gameassist.data.repository.ProfileRepository
import com.kinou.gameassist.injector.ShizukuManager
import com.kinou.gameassist.service.OverlayService
import com.kinou.gameassist.ui.screens.CommunityScreen
import com.kinou.gameassist.ui.screens.GamepadTestScreen
import com.kinou.gameassist.ui.screens.HomeScreen
import com.kinou.gameassist.ui.screens.ProfileEditorScreen
import com.kinou.gameassist.ui.screens.VisualHudEditorScreen
import com.kinou.gameassist.ui.theme.*

enum class Screen {
    HOME,
    PROFILES,
    COMMUNITY,
    GAMEPAD_TEST,
    VISUAL_HUD_EDITOR
}

class MainActivity : AppCompatActivity() {

    private lateinit var repository: ProfileRepository

    // Live Gamepad Tester States
    private val liveLx = mutableFloatStateOf(0f)
    private val liveLy = mutableFloatStateOf(0f)
    private val liveRx = mutableFloatStateOf(0f)
    private val liveRy = mutableFloatStateOf(0f)
    private val pressedButtons = mutableStateListOf<String>()

    // Polling Rate & Latency Tracking States
    private val livePollingHz = mutableFloatStateOf(0f)
    private val livePeakHz = mutableFloatStateOf(0f)
    private val liveLatencyMs = mutableFloatStateOf(0f)
    private val liveJitterMs = mutableFloatStateOf(0f)
    private val liveSampleCount = mutableIntStateOf(0)
    private var lastEventNano: Long = 0L
    private val recentIntervalsNs = ArrayDeque<Long>()

    private fun recordEventTiming() {
        val now = System.nanoTime()
        if (lastEventNano > 0L) {
            val deltaNs = now - lastEventNano
            // Filter realistic active input intervals (0.5ms to 300ms)
            if (deltaNs in 500_000L..300_000_000L) {
                synchronized(recentIntervalsNs) {
                    recentIntervalsNs.addLast(deltaNs)
                    if (recentIntervalsNs.size > 50) recentIntervalsNs.removeFirst()

                    liveSampleCount.intValue++
                    val avgDeltaNs = recentIntervalsNs.average()
                    if (avgDeltaNs > 0) {
                        val hz = (1_000_000_000.0 / avgDeltaNs).toFloat()
                        livePollingHz.floatValue = hz
                        if (hz > livePeakHz.floatValue) {
                            livePeakHz.floatValue = hz
                        }
                        liveLatencyMs.floatValue = (avgDeltaNs / 1_000_000.0).toFloat()

                        var varianceSum = 0.0
                        for (interval in recentIntervalsNs) {
                            val diff = (interval - avgDeltaNs) / 1_000_000.0
                            varianceSum += diff * diff
                        }
                        liveJitterMs.floatValue = kotlin.math.sqrt(varianceSum / recentIntervalsNs.size).toFloat()
                    }
                }
            }
        }
        lastEventNano = now
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LanguageManager.init(this)
        repository = ProfileRepository(this)
        ShizukuManager.init()

        // Anonymous telemetry heartbeat on app launch
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                CommunityApiClient(this@MainActivity).sendTelemetryPing("1.0.1")
            } catch (_: Exception) {}
        }

        setContent {
            GameAssistTheme {
                var currentScreen by remember { mutableStateOf(Screen.HOME) }
                var profiles by remember { mutableStateOf(repository.getAllProfiles()) }
                var selectedProfile by remember { mutableStateOf(profiles.firstOrNull()) }
                val isServiceRunning by OverlayService.isServiceRunningFlow.collectAsState()

                BackHandler(enabled = currentScreen != Screen.HOME) {
                    currentScreen = if (currentScreen == Screen.VISUAL_HUD_EDITOR) Screen.PROFILES else Screen.HOME
                }

                Scaffold(
                    bottomBar = {
                        if (currentScreen != Screen.VISUAL_HUD_EDITOR) {
                            NavigationBar(
                                containerColor = DarkSurface,
                                contentColor = TextPrimary,
                                tonalElevation = 8.dp
                            ) {
                                NavigationBarItem(
                                    selected = currentScreen == Screen.HOME,
                                    onClick = { currentScreen = Screen.HOME },
                                    icon = { Icon(Icons.Default.Home, contentDescription = stringResource(R.string.nav_home)) },
                                    label = { Text(stringResource(R.string.nav_home), fontSize = 10.sp, fontWeight = if (currentScreen == Screen.HOME) FontWeight.Bold else FontWeight.Normal) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = DarkBackground,
                                        selectedTextColor = NeonCyan,
                                        indicatorColor = NeonCyan,
                                        unselectedIconColor = TextSecondary,
                                        unselectedTextColor = TextSecondary
                                    )
                                )

                                NavigationBarItem(
                                    selected = currentScreen == Screen.PROFILES,
                                    onClick = { currentScreen = Screen.PROFILES },
                                    icon = { Icon(Icons.Default.Tune, contentDescription = stringResource(R.string.nav_profiles)) },
                                    label = { Text(stringResource(R.string.nav_profiles), fontSize = 10.sp, fontWeight = if (currentScreen == Screen.PROFILES) FontWeight.Bold else FontWeight.Normal) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = DarkBackground,
                                        selectedTextColor = NeonCyan,
                                        indicatorColor = NeonCyan,
                                        unselectedIconColor = TextSecondary,
                                        unselectedTextColor = TextSecondary
                                    )
                                )

                                NavigationBarItem(
                                    selected = currentScreen == Screen.COMMUNITY,
                                    onClick = { currentScreen = Screen.COMMUNITY },
                                    icon = { Icon(Icons.Default.Public, contentDescription = "Communauté") },
                                    label = { Text("Communauté", fontSize = 10.sp, fontWeight = if (currentScreen == Screen.COMMUNITY) FontWeight.Bold else FontWeight.Normal) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = DarkBackground,
                                        selectedTextColor = NeonCyan,
                                        indicatorColor = NeonCyan,
                                        unselectedIconColor = TextSecondary,
                                        unselectedTextColor = TextSecondary
                                    )
                                )

                                NavigationBarItem(
                                    selected = currentScreen == Screen.GAMEPAD_TEST,
                                    onClick = { currentScreen = Screen.GAMEPAD_TEST },
                                    icon = { Icon(Icons.Default.SportsEsports, contentDescription = stringResource(R.string.nav_diagnostic)) },
                                    label = { Text(stringResource(R.string.nav_diagnostic), fontSize = 10.sp, fontWeight = if (currentScreen == Screen.GAMEPAD_TEST) FontWeight.Bold else FontWeight.Normal) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = DarkBackground,
                                        selectedTextColor = NeonCyan,
                                        indicatorColor = NeonCyan,
                                        unselectedIconColor = TextSecondary,
                                        unselectedTextColor = TextSecondary
                                    )
                                )
                            }
                        }
                    },
                    containerColor = DarkBackground
                ) { paddingValues ->
                    Box(modifier = Modifier.padding(if (currentScreen == Screen.VISUAL_HUD_EDITOR) PaddingValues(0.dp) else paddingValues)) {
                        when (currentScreen) {
                            Screen.HOME -> HomeScreen(
                                profiles = profiles,
                                selectedProfile = selectedProfile,
                                onSelectProfile = { selectedProfile = it },
                                onStartService = { prof ->
                                    startOverlayService(prof.id)
                                },
                                onStopService = {
                                    stopOverlayService()
                                },
                                isServiceRunning = isServiceRunning,
                                onNavigateToTest = { currentScreen = Screen.GAMEPAD_TEST },
                                onNavigateToProfiles = { currentScreen = Screen.PROFILES }
                            )

                            Screen.GAMEPAD_TEST -> GamepadTestScreen(
                                onBack = { currentScreen = Screen.HOME },
                                lx = liveLx.floatValue,
                                ly = liveLy.floatValue,
                                rx = liveRx.floatValue,
                                ry = liveRy.floatValue,
                                pressedButtons = pressedButtons.toSet(),
                                pollingHz = livePollingHz.floatValue,
                                peakHz = livePeakHz.floatValue,
                                latencyMs = liveLatencyMs.floatValue,
                                jitterMs = liveJitterMs.floatValue,
                                sampleCount = liveSampleCount.intValue,
                                currentDeadzoneLS = selectedProfile?.joystick?.deadzone ?: 0.08f,
                                currentDeadzoneRS = selectedProfile?.camera?.deadzone ?: 0.08f,
                                currentOuterDeadzoneLS = selectedProfile?.joystick?.outerDeadzone ?: 0.95f,
                                currentOuterDeadzoneRS = selectedProfile?.camera?.outerDeadzone ?: 0.95f,
                                onApplyDeadzones = { deadzoneLS, deadzoneRS ->
                                    selectedProfile?.let { prof ->
                                        prof.joystick.deadzone = deadzoneLS
                                        prof.camera.deadzone = deadzoneRS
                                        repository.saveProfile(prof)
                                        OverlayService.updateLiveProfile(prof)
                                    }
                                },
                                onApplyOuterDeadzones = { outerLS, outerRS ->
                                    selectedProfile?.let { prof ->
                                        prof.joystick.outerDeadzone = outerLS
                                        prof.camera.outerDeadzone = outerRS
                                        repository.saveProfile(prof)
                                        OverlayService.updateLiveProfile(prof)
                                    }
                                }
                            )

                            Screen.PROFILES -> ProfileEditorScreen(
                                profiles = profiles,
                                currentProfile = selectedProfile,
                                onSelectProfile = {
                                    selectedProfile = it
                                    OverlayService.updateLiveProfile(it)
                                },
                                onSaveProfile = { prof ->
                                    repository.saveProfile(prof)
                                    OverlayService.updateLiveProfile(prof)
                                },
                                onDeleteProfile = { id ->
                                    repository.deleteProfile(id)
                                    profiles = repository.getAllProfiles()
                                    selectedProfile = profiles.firstOrNull()
                                },
                                onDuplicateProfile = { prof ->
                                    val copy = repository.duplicateProfile(prof)
                                    profiles = repository.getAllProfiles()
                                    selectedProfile = copy
                                },
                                onImportProfile = { json ->
                                    val imp = repository.importProfileFromJson(json)
                                    if (imp != null) {
                                        profiles = repository.getAllProfiles()
                                        selectedProfile = imp
                                        true
                                    } else false
                                },
                                onOpenVisualEditor = { prof ->
                                    selectedProfile = prof
                                    currentScreen = Screen.VISUAL_HUD_EDITOR
                                },
                                liveRx = liveRx.floatValue,
                                liveRy = liveRy.floatValue,
                                onBack = { currentScreen = Screen.HOME }
                            )

                            Screen.VISUAL_HUD_EDITOR -> {
                                selectedProfile?.let { prof ->
                                    VisualHudEditorScreen(
                                        profile = prof,
                                        onSaveProfile = { updated ->
                                            repository.saveProfile(updated)
                                            OverlayService.updateLiveProfile(updated)
                                            profiles = repository.getAllProfiles()
                                        },
                                        onBack = { currentScreen = Screen.PROFILES }
                                    )
                                } ?: run {
                                    currentScreen = Screen.PROFILES
                                }
                            }

                            Screen.COMMUNITY -> CommunityScreen(
                                repository = repository,
                                localProfiles = profiles,
                                onProfileImported = { imp ->
                                    profiles = repository.getAllProfiles()
                                    selectedProfile = imp
                                    OverlayService.updateLiveProfile(imp)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ShizukuManager.checkStatus()
    }

    private fun startOverlayService(profileId: String) {
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_START
            putExtra(OverlayService.EXTRA_PROFILE_ID, profileId)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopOverlayService() {
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_STOP
        }
        stopService(intent)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val btnName = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> "BUTTON_A"
            KeyEvent.KEYCODE_BUTTON_B -> "BUTTON_B"
            KeyEvent.KEYCODE_BUTTON_X -> "BUTTON_X"
            KeyEvent.KEYCODE_BUTTON_Y -> "BUTTON_Y"
            KeyEvent.KEYCODE_BUTTON_L1 -> "BUTTON_L1"
            KeyEvent.KEYCODE_BUTTON_R1 -> "BUTTON_R1"
            KeyEvent.KEYCODE_BUTTON_L2 -> "BUTTON_L2"
            KeyEvent.KEYCODE_BUTTON_R2 -> "BUTTON_R2"
            KeyEvent.KEYCODE_BUTTON_THUMBL -> "BUTTON_THUMBL"
            KeyEvent.KEYCODE_BUTTON_THUMBR -> "BUTTON_THUMBR"
            KeyEvent.KEYCODE_BUTTON_START -> "BUTTON_START"
            KeyEvent.KEYCODE_BUTTON_SELECT -> "BUTTON_SELECT"
            KeyEvent.KEYCODE_DPAD_UP -> "DPAD_UP"
            KeyEvent.KEYCODE_DPAD_DOWN -> "DPAD_DOWN"
            KeyEvent.KEYCODE_DPAD_LEFT -> "DPAD_LEFT"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "DPAD_RIGHT"
            else -> null
        }

        if (btnName != null) {
            recordEventTiming()
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (!pressedButtons.contains(btnName)) pressedButtons.add(btnName)
            } else if (event.action == KeyEvent.ACTION_UP) {
                pressedButtons.remove(btnName)
            }
            return true
        }

        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_JOYSTICK) != 0 ||
            (event.source and InputDevice.SOURCE_GAMEPAD) != 0) {

            recordEventTiming()

            liveLx.floatValue = event.getAxisValue(MotionEvent.AXIS_X)
            liveLy.floatValue = event.getAxisValue(MotionEvent.AXIS_Y)

            var rx = event.getAxisValue(MotionEvent.AXIS_Z)
            var ry = event.getAxisValue(MotionEvent.AXIS_RZ)
            if (rx == 0.0f && ry == 0.0f) {
                rx = event.getAxisValue(MotionEvent.AXIS_RX)
                ry = event.getAxisValue(MotionEvent.AXIS_RY)
            }
            liveRx.floatValue = rx
            liveRy.floatValue = ry

            val ltVal = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE))
            val rtVal = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))

            if (ltVal > 0.40f) {
                if (!pressedButtons.contains("BUTTON_L2")) pressedButtons.add("BUTTON_L2")
            } else {
                pressedButtons.remove("BUTTON_L2")
            }

            if (rtVal > 0.40f) {
                if (!pressedButtons.contains("BUTTON_R2")) pressedButtons.add("BUTTON_R2")
            } else {
                pressedButtons.remove("BUTTON_R2")
            }

            // HAT D-Pad
            val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)

            if (hatY <= -0.4f) {
                if (!pressedButtons.contains("DPAD_UP")) pressedButtons.add("DPAD_UP")
            } else {
                pressedButtons.remove("DPAD_UP")
            }

            if (hatY >= 0.4f) {
                if (!pressedButtons.contains("DPAD_DOWN")) pressedButtons.add("DPAD_DOWN")
            } else {
                pressedButtons.remove("DPAD_DOWN")
            }

            if (hatX <= -0.4f) {
                if (!pressedButtons.contains("DPAD_LEFT")) pressedButtons.add("DPAD_LEFT")
            } else {
                pressedButtons.remove("DPAD_LEFT")
            }

            if (hatX >= 0.4f) {
                if (!pressedButtons.contains("DPAD_RIGHT")) pressedButtons.add("DPAD_RIGHT")
            } else {
                pressedButtons.remove("DPAD_RIGHT")
            }

            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }
}
