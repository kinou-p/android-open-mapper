package com.kinou.gameassist.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinou.gameassist.R
import com.kinou.gameassist.engine.HapticManager
import com.kinou.gameassist.ui.theme.*
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

enum class TestTab {
    VISUALIZER,
    DRIFT_CALIBRATION,
    CIRCULARITY,
    POLLING_RATE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamepadTestScreen(
    onBack: () -> Unit,
    lx: Float,
    ly: Float,
    rx: Float,
    ry: Float,
    pressedButtons: Set<String>,
    pollingHz: Float = 0f,
    peakHz: Float = 0f,
    latencyMs: Float = 0f,
    jitterMs: Float = 0f,
    sampleCount: Int = 0,
    currentDeadzoneLS: Float = 0.08f,
    currentDeadzoneRS: Float = 0.08f,
    currentOuterDeadzoneLS: Float = 0.95f,
    currentOuterDeadzoneRS: Float = 0.95f,
    onApplyDeadzones: (deadzoneLS: Float, deadzoneRS: Float) -> Unit = { _, _ -> },
    onApplyOuterDeadzones: (outerLS: Float, outerRS: Float) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val hapticManager = remember { HapticManager(context) }
    var selectedTab by remember { mutableStateOf(TestTab.VISUALIZER) }

    // Track the last pressed button for live feedback
    var lastPressedButton by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pressedButtons) {
        if (pressedButtons.isNotEmpty()) {
            lastPressedButton = pressedButtons.first()
        }
    }

    // Drift Test State
    var isDriftTesting by remember { mutableStateOf(false) }
    var driftTestProgress by remember { mutableFloatStateOf(0f) }
    var maxDriftLS by remember { mutableFloatStateOf(0f) }
    var maxDriftRS by remember { mutableFloatStateOf(0f) }
    var driftTestFinished by remember { mutableStateOf(false) }

    // Circularity & Outer Reach Test State
    var isCircularityTesting by remember { mutableStateOf(false) }
    val pointsLS = remember { mutableStateListOf<Offset>() }
    val pointsRS = remember { mutableStateListOf<Offset>() }
    var circularityErrorLS by remember { mutableFloatStateOf(0f) }
    var circularityErrorRS by remember { mutableFloatStateOf(0f) }
    var maxReachLS by remember { mutableFloatStateOf(0f) }
    var maxReachRS by remember { mutableFloatStateOf(0f) }

    // Drift Sampling coroutine
    LaunchedEffect(isDriftTesting) {
        if (isDriftTesting) {
            driftTestProgress = 0f
            maxDriftLS = 0f
            maxDriftRS = 0f
            driftTestFinished = false

            val totalSteps = 60
            for (i in 1..totalSteps) {
                delay(50) // 3 seconds total
                driftTestProgress = i / totalSteps.toFloat()

                val magLS = hypot(lx.toDouble(), ly.toDouble()).toFloat()
                val magRS = hypot(rx.toDouble(), ry.toDouble()).toFloat()

                maxDriftLS = max(maxDriftLS, magLS)
                maxDriftRS = max(maxDriftRS, magRS)
            }
            isDriftTesting = false
            driftTestFinished = true
        }
    }

    // Circularity points & Max Reach collection
    LaunchedEffect(isCircularityTesting, lx, ly, rx, ry) {
        if (isCircularityTesting) {
            val magLS = hypot(lx.toDouble(), ly.toDouble()).toFloat()
            val magRS = hypot(rx.toDouble(), ry.toDouble()).toFloat()

            maxReachLS = max(maxReachLS, magLS)
            maxReachRS = max(maxReachRS, magRS)

            if (magLS > 0.05f && pointsLS.size < 300) {
                pointsLS.add(Offset(lx, ly))
            }
            if (magRS > 0.05f && pointsRS.size < 300) {
                pointsRS.add(Offset(rx, ry))
            }

            if (pointsLS.size > 20) {
                val sumDiff = pointsLS.sumOf { abs(hypot(it.x.toDouble(), it.y.toDouble()) - 1.0) }
                circularityErrorLS = (sumDiff / pointsLS.size * 100).toFloat()
            }
            if (pointsRS.size > 20) {
                val sumDiff = pointsRS.sumOf { abs(hypot(it.x.toDouble(), it.y.toDouble()) - 1.0) }
                circularityErrorRS = (sumDiff / pointsRS.size * 100).toFloat()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diagnostic_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.btn_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkBackground,
                    titleContentColor = TextPrimary,
                    navigationIconContentColor = TextPrimary
                )
            )
        },
        containerColor = DarkBackground
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Tab Selector (2 par ligne)
            val tabs = listOf(
                TestTab.VISUALIZER to stringResource(R.string.tab_visualizer),
                TestTab.DRIFT_CALIBRATION to stringResource(R.string.tab_drift),
                TestTab.CIRCULARITY to stringResource(R.string.tab_circularity),
                TestTab.POLLING_RATE to stringResource(R.string.tab_polling_rate)
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tabs.chunked(2).forEach { rowTabs ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowTabs.forEach { (tab, title) ->
                            val active = selectedTab == tab
                            Button(
                                onClick = { selectedTab = tab },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (active) NeonCyan else DarkCardBorder
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = title,
                                    color = if (active) DarkBackground else TextPrimary,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 12.5.sp,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            when (selectedTab) {
                // TAB 1: Visualiseur général (Joysticks + Realistic Gamepad)
                TestTab.VISUALIZER -> {
                    // Analog Sticks Visualizer
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        StickVisualizer(
                            title = stringResource(R.string.stick_left),
                            x = lx, y = ly,
                            deadzone = currentDeadzoneLS,
                            outerDeadzone = currentOuterDeadzoneLS,
                            color = NeonCyan
                        )
                        StickVisualizer(
                            title = stringResource(R.string.stick_right),
                            x = rx, y = ry,
                            deadzone = currentDeadzoneRS,
                            outerDeadzone = currentOuterDeadzoneRS,
                            color = NeonGreen
                        )
                    }

                    // Realistic Gamepad Layout Visualizer
                    RealisticGamepadVisualizer(
                        pressedButtons = pressedButtons,
                        lastPressedButton = lastPressedButton
                    )
                }

                // TAB 2: Mesure & Auto-Calibration de Drift
                TestTab.DRIFT_CALIBRATION -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = DarkCard),
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Text(stringResource(R.string.drift_test_title), fontWeight = FontWeight.Bold, color = NeonCyan, fontSize = 15.sp)
                            Text(
                                stringResource(R.string.drift_test_desc),
                                color = TextSecondary,
                                fontSize = 13.sp
                            )

                            if (isDriftTesting) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(stringResource(R.string.drift_analyzing, (driftTestProgress * 100).toInt()), color = NeonCyan, fontWeight = FontWeight.Bold)
                                    LinearProgressIndicator(
                                        progress = { driftTestProgress },
                                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                        color = NeonCyan,
                                        trackColor = DarkCardBorder
                                    )
                                }
                            } else {
                                Button(
                                    onClick = { isDriftTesting = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = DarkBackground)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(stringResource(R.string.drift_start_btn), color = DarkBackground, fontWeight = FontWeight.Bold)
                                }
                            }

                            if (driftTestFinished) {
                                HorizontalDivider(color = DarkCardBorder, thickness = 1.dp)

                                val recommendedLS = (maxDriftLS + 0.02f).coerceIn(0.03f, 0.35f)
                                val recommendedRS = (maxDriftRS + 0.02f).coerceIn(0.03f, 0.35f)

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    DriftResultCard(
                                        title = stringResource(R.string.stick_left),
                                        driftPct = maxDriftLS * 100f,
                                        recommendedDeadzone = recommendedLS * 100f,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    DriftResultCard(
                                        title = stringResource(R.string.stick_right),
                                        driftPct = maxDriftRS * 100f,
                                        recommendedDeadzone = recommendedRS * 100f,
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                Button(
                                    onClick = {
                                        onApplyDeadzones(recommendedLS, recommendedRS)
                                        Toast.makeText(context, context.getString(R.string.drift_applied_toast), Toast.LENGTH_SHORT).show()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = NeonGreen),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = DarkBackground)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(stringResource(R.string.drift_apply_btn), color = DarkBackground, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // TAB 3: Test de Circularité & Calibration de Portée Max (Outer Deadzone)
                TestTab.CIRCULARITY -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = DarkCard),
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Text(stringResource(R.string.circ_test_title), fontWeight = FontWeight.Bold, color = NeonGreen, fontSize = 15.sp)
                            Text(
                                stringResource(R.string.circ_test_desc),
                                color = TextSecondary,
                                fontSize = 13.sp
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        pointsLS.clear()
                                        pointsRS.clear()
                                        maxReachLS = 0f
                                        maxReachRS = 0f
                                        circularityErrorLS = 0f
                                        circularityErrorRS = 0f
                                        isCircularityTesting = !isCircularityTesting
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isCircularityTesting) NeonPink else NeonGreen
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        if (isCircularityTesting) stringResource(R.string.circ_stop_btn) else stringResource(R.string.circ_start_btn),
                                        color = if (isCircularityTesting) Color.White else DarkBackground,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                CircularityRadar(
                                    title = stringResource(R.string.stick_left),
                                    points = pointsLS,
                                    currentPos = Offset(lx, ly),
                                    errorPct = circularityErrorLS,
                                    maxReachPct = maxReachLS * 100f,
                                    color = NeonCyan
                                )
                                CircularityRadar(
                                    title = stringResource(R.string.stick_right),
                                    points = pointsRS,
                                    currentPos = Offset(rx, ry),
                                    errorPct = circularityErrorRS,
                                    maxReachPct = maxReachRS * 100f,
                                    color = NeonGreen
                                )
                            }

                            if (pointsLS.size > 25 || pointsRS.size > 25 || maxReachRS > 0.6f) {
                                HorizontalDivider(color = DarkCardBorder, thickness = 1.dp)

                                val recommendedOuterLS = (maxReachLS * 0.98f).coerceIn(0.70f, 0.98f)
                                val recommendedOuterRS = (maxReachRS * 0.98f).coerceIn(0.70f, 0.98f)

                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(stringResource(R.string.circ_outer_title), fontWeight = FontWeight.Bold, color = NeonCyan, fontSize = 13.sp)
                                    Text(
                                        stringResource(R.string.circ_outer_desc),
                                        color = TextSecondary,
                                        fontSize = 12.sp
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(stringResource(R.string.circ_outer_ls, recommendedOuterLS * 100f), color = NeonCyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        Text(stringResource(R.string.circ_outer_rs, recommendedOuterRS * 100f), color = NeonGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                }

                                Button(
                                    onClick = {
                                        onApplyOuterDeadzones(recommendedOuterLS, recommendedOuterRS)
                                        Toast.makeText(context, context.getString(R.string.circ_saved_toast), Toast.LENGTH_SHORT).show()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Save, contentDescription = null, tint = DarkBackground)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(stringResource(R.string.circ_save_btn), color = DarkBackground, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // TAB 4: Polling Rate (Hz) & Input Lag Diagnostic
                TestTab.POLLING_RATE -> {
                    PollingRateDiagnosticView(
                        pollingHz = pollingHz,
                        peakHz = peakHz,
                        latencyMs = latencyMs,
                        jitterMs = jitterMs,
                        sampleCount = sampleCount
                    )
                }
            }
        }
    }
}

/**
 * Realistic visual layout of a gamepad with ABXY diamond, physical D-Pad +,
 * shoulder/trigger bars, and stick clicks.
 */
@Composable
fun RealisticGamepadVisualizer(
    pressedButtons: Set<String>,
    lastPressedButton: String?,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header: Title + Live Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.buttons_and_triggers),
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    fontSize = 15.sp
                )

                // Live Active Count or Last Input Pill
                Surface(
                    color = if (pressedButtons.isNotEmpty()) NeonCyan.copy(alpha = 0.15f) else DarkSurface,
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (pressedButtons.isNotEmpty()) NeonCyan else DarkCardBorder
                    )
                ) {
                    val statusText = if (pressedButtons.isNotEmpty()) {
                        val formatted = pressedButtons.joinToString(" + ") { it.replace("BUTTON_", "").replace("TRIGGER_", "") }
                        formatted
                    } else if (lastPressedButton != null) {
                        stringResource(R.string.gamepad_last_input, lastPressedButton.replace("BUTTON_", "").replace("TRIGGER_", ""))
                    } else {
                        stringResource(R.string.gamepad_none_pressed)
                    }

                    Text(
                        text = statusText,
                        color = if (pressedButtons.isNotEmpty()) NeonCyan else TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = if (pressedButtons.isNotEmpty()) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        maxLines = 1
                    )
                }
            }

            // 1. TOP SECTION: Shoulders & Triggers (L2/L1 & R2/R1)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left Shoulders (LT / LB)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TriggerBar(
                        label = "LT / L2",
                        isPressed = pressedButtons.contains("BUTTON_L2"),
                        isLeft = true
                    )
                    BumperBar(
                        label = "LB / L1",
                        isPressed = pressedButtons.contains("BUTTON_L1"),
                        isLeft = true
                    )
                }

                // Center decorative bridge
                Box(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(DarkSurface),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.SportsEsports,
                        contentDescription = null,
                        tint = if (pressedButtons.isNotEmpty()) NeonCyan else DarkCardBorder,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Right Shoulders (RT / RB)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TriggerBar(
                        label = "RT / R2",
                        isPressed = pressedButtons.contains("BUTTON_R2"),
                        isLeft = false
                    )
                    BumperBar(
                        label = "RB / R1",
                        isPressed = pressedButtons.contains("BUTTON_R1"),
                        isLeft = false
                    )
                }
            }

            HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f), thickness = 1.dp)

            // 2. MAIN BODY SECTION: D-Pad (Left), Select/Start (Center), ABXY Cluster (Right)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // LEFT: D-Pad Cross ✚ & L3 Stick Click
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    DpadCluster(pressedButtons = pressedButtons)

                    StickClickBadge(
                        label = "L3 (LS)",
                        isPressed = pressedButtons.contains("BUTTON_THUMBL"),
                        accentColor = NeonCyan
                    )
                }

                // CENTER: System Buttons (SELECT / START)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CenterPillButton(
                            label = "BACK",
                            subLabel = "⧉",
                            isPressed = pressedButtons.contains("BUTTON_SELECT")
                        )
                        CenterPillButton(
                            label = "START",
                            subLabel = "☰",
                            isPressed = pressedButtons.contains("BUTTON_START")
                        )
                    }

                    // Xbox/PS Guide emblem (visual accent)
                    Surface(
                        modifier = Modifier.size(24.dp),
                        shape = CircleShape,
                        color = if (pressedButtons.isNotEmpty()) NeonCyan.copy(alpha = 0.2f) else DarkSurface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (pressedButtons.isNotEmpty()) NeonCyan else DarkCardBorder)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (pressedButtons.isNotEmpty()) NeonCyan else TextSecondary.copy(alpha = 0.5f))
                            )
                        }
                    }
                }

                // RIGHT: ABXY Diamond Cluster & R3 Stick Click
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AbxyDiamondCluster(pressedButtons = pressedButtons)

                    StickClickBadge(
                        label = "R3 (RS)",
                        isPressed = pressedButtons.contains("BUTTON_THUMBR"),
                        accentColor = NeonGreen
                    )
                }
            }
        }
    }
}

/**
 * Shoulder Trigger (LT / RT) with angled top styling.
 */
@Composable
fun TriggerBar(label: String, isPressed: Boolean, isLeft: Boolean) {
    val bgColor by animateColorAsState(
        targetValue = if (isPressed) NeonPink else DarkSurface,
        animationSpec = tween(120),
        label = "triggerBg"
    )
    val textColor by animateColorAsState(
        targetValue = if (isPressed) Color.White else TextSecondary,
        animationSpec = tween(120),
        label = "triggerText"
    )

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(
            topStart = if (isLeft) 14.dp else 4.dp,
            topEnd = if (!isLeft) 14.dp else 4.dp,
            bottomStart = 4.dp,
            bottomEnd = 4.dp
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isPressed) NeonPink else DarkCardBorder
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                color = textColor,
                fontSize = 11.sp,
                fontWeight = if (isPressed) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}

/**
 * Shoulder Bumper (LB / RB) with rounded bottom styling.
 */
@Composable
fun BumperBar(label: String, isPressed: Boolean, isLeft: Boolean) {
    val bgColor by animateColorAsState(
        targetValue = if (isPressed) NeonCyan else DarkSurface,
        animationSpec = tween(120),
        label = "bumperBg"
    )
    val textColor by animateColorAsState(
        targetValue = if (isPressed) DarkBackground else TextSecondary,
        animationSpec = tween(120),
        label = "bumperText"
    )

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(
            topStart = 4.dp,
            topEnd = 4.dp,
            bottomStart = if (isLeft) 12.dp else 4.dp,
            bottomEnd = if (!isLeft) 12.dp else 4.dp
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isPressed) NeonCyan else DarkCardBorder
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                color = textColor,
                fontSize = 11.sp,
                fontWeight = if (isPressed) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

/**
 * Authentic D-Pad Cross ✚ layout.
 */
@Composable
fun DpadCluster(pressedButtons: Set<String>) {
    val isUp = pressedButtons.contains("DPAD_UP")
    val isDown = pressedButtons.contains("DPAD_DOWN")
    val isLeft = pressedButtons.contains("DPAD_LEFT")
    val isRight = pressedButtons.contains("DPAD_RIGHT")

    Box(
        modifier = Modifier
            .size(116.dp)
            .clip(CircleShape)
            .background(DarkSurface.copy(alpha = 0.5f))
            .border(1.dp, DarkCardBorder.copy(alpha = 0.5f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        // UP
        DpadDirectionButton(
            arrow = "▲",
            isPressed = isUp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 4.dp)
        )
        // DOWN
        DpadDirectionButton(
            arrow = "▼",
            isPressed = isDown,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 4.dp)
        )
        // LEFT
        DpadDirectionButton(
            arrow = "◄",
            isPressed = isLeft,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 4.dp)
        )
        // RIGHT
        DpadDirectionButton(
            arrow = "►",
            isPressed = isRight,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 4.dp)
        )

        // Center Pivot
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(DarkBackground)
                .border(1.dp, DarkCardBorder, CircleShape)
        )
    }
}

@Composable
fun DpadDirectionButton(arrow: String, isPressed: Boolean, modifier: Modifier = Modifier) {
    val bgColor by animateColorAsState(
        targetValue = if (isPressed) NeonCyan else DarkSurface,
        animationSpec = tween(100),
        label = "dpadBg"
    )
    val textColor by animateColorAsState(
        targetValue = if (isPressed) DarkBackground else TextSecondary,
        animationSpec = tween(100),
        label = "dpadText"
    )

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isPressed) NeonCyan else DarkCardBorder
        ),
        modifier = modifier.size(36.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = arrow,
                color = textColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Authentic ABXY Diamond Cluster with authentic gaming colors.
 */
@Composable
fun AbxyDiamondCluster(pressedButtons: Set<String>) {
    val isY = pressedButtons.contains("BUTTON_Y")
    val isB = pressedButtons.contains("BUTTON_B")
    val isA = pressedButtons.contains("BUTTON_A")
    val isX = pressedButtons.contains("BUTTON_X")

    Box(
        modifier = Modifier
            .size(116.dp)
            .clip(CircleShape)
            .background(DarkSurface.copy(alpha = 0.5f))
            .border(1.dp, DarkCardBorder.copy(alpha = 0.5f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        // Y (Top - Yellow/Amber)
        AbxyButton(
            letter = "Y",
            subSymbol = "▲",
            activeColor = Color(0xFFFFCC00),
            isPressed = isY,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 4.dp)
        )
        // B (Right - Red/Pink)
        AbxyButton(
            letter = "B",
            subSymbol = "●",
            activeColor = Color(0xFFFF0055),
            isPressed = isB,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 4.dp)
        )
        // A (Bottom - Green)
        AbxyButton(
            letter = "A",
            subSymbol = "✖",
            activeColor = Color(0xFF00FF66),
            isPressed = isA,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 4.dp)
        )
        // X (Left - Blue/Cyan)
        AbxyButton(
            letter = "X",
            subSymbol = "■",
            activeColor = Color(0xFF00F0FF),
            isPressed = isX,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 4.dp)
        )
    }
}

@Composable
fun AbxyButton(
    letter: String,
    subSymbol: String,
    activeColor: Color,
    isPressed: Boolean,
    modifier: Modifier = Modifier
) {
    val bgColor by animateColorAsState(
        targetValue = if (isPressed) activeColor else DarkSurface,
        animationSpec = tween(100),
        label = "abxyBg"
    )
    val textColor by animateColorAsState(
        targetValue = if (isPressed) Color.Black else activeColor,
        animationSpec = tween(100),
        label = "abxyText"
    )

    Surface(
        color = bgColor,
        shape = CircleShape,
        border = androidx.compose.foundation.BorderStroke(
            1.5.dp,
            if (isPressed) activeColor else DarkCardBorder
        ),
        modifier = modifier.size(36.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = letter,
                color = textColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Center Pill Button (Select / Start)
 */
@Composable
fun CenterPillButton(label: String, subLabel: String, isPressed: Boolean) {
    val bgColor by animateColorAsState(
        targetValue = if (isPressed) NeonCyan else DarkSurface,
        animationSpec = tween(100),
        label = "centerPillBg"
    )
    val textColor by animateColorAsState(
        targetValue = if (isPressed) DarkBackground else TextSecondary,
        animationSpec = tween(100),
        label = "centerPillText"
    )

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isPressed) NeonCyan else DarkCardBorder
        ),
        modifier = Modifier
            .width(52.dp)
            .height(26.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = subLabel,
                color = textColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = label,
                color = textColor,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Stick Click Indicator (L3 / R3)
 */
@Composable
fun StickClickBadge(label: String, isPressed: Boolean, accentColor: Color) {
    val bgColor by animateColorAsState(
        targetValue = if (isPressed) accentColor else DarkSurface,
        animationSpec = tween(100),
        label = "stickClickBg"
    )
    val textColor by animateColorAsState(
        targetValue = if (isPressed) DarkBackground else TextSecondary,
        animationSpec = tween(100),
        label = "stickClickText"
    )

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isPressed) accentColor else DarkCardBorder
        ),
        modifier = Modifier
            .width(88.dp)
            .height(28.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                color = textColor,
                fontSize = 11.sp,
                fontWeight = if (isPressed) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}

@Composable
fun DriftResultCard(title: String, driftPct: Float, recommendedDeadzone: Float, modifier: Modifier = Modifier) {
    Surface(
        color = DarkSurface,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 13.sp)

            val statusColor = when {
                driftPct < 4f -> NeonGreen
                driftPct < 9f -> NeonCyan
                driftPct < 15f -> Color(0xFFFFAA00)
                else -> NeonPink
            }
            val statusLabel = when {
                driftPct < 4f -> stringResource(R.string.drift_grade_new)
                driftPct < 9f -> stringResource(R.string.drift_grade_good)
                driftPct < 15f -> stringResource(R.string.drift_grade_medium)
                else -> stringResource(R.string.drift_grade_bad)
            }

            Text(stringResource(R.string.drift_measured, driftPct), color = statusColor, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text(statusLabel, color = TextSecondary, fontSize = 11.sp)
            HorizontalDivider(color = DarkCardBorder, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 2.dp))
            Text(stringResource(R.string.deadzone_ideal, recommendedDeadzone), color = NeonCyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}

@Composable
fun CircularityRadar(title: String, points: List<Offset>, currentPos: Offset, errorPct: Float, maxReachPct: Float, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Box(
            modifier = Modifier
                .size(130.dp)
                .clip(CircleShape)
                .background(DarkSurface)
                .border(2.dp, DarkCardBorder, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2, size.height / 2)
                val maxRadius = size.width / 2 - 12.dp.toPx()

                // Theoretical outer circle
                drawCircle(Color(0x3300F0FF), radius = maxRadius, center = center, style = Stroke(2f))

                // Inner deadzone circle
                drawCircle(Color(0x33FF0055), radius = maxRadius * 0.10f, center = center, style = Stroke(2f))

                // Points cloud
                for (p in points) {
                    val px = center.x + (p.x * maxRadius)
                    val py = center.y + (p.y * maxRadius)
                    drawCircle(color, radius = 3f, center = Offset(px, py))
                }

                // Live position
                val liveX = center.x + (currentPos.x * maxRadius)
                val liveY = center.y + (currentPos.y * maxRadius)
                drawCircle(Color.White, radius = 8f, center = Offset(liveX, liveY))
            }
        }
        val errColor = if (errorPct < 8f) NeonGreen else if (errorPct < 14f) Color(0xFFFFAA00) else NeonPink
        Text(
            text = if (points.isEmpty()) stringResource(R.string.waiting_status) else stringResource(R.string.error_and_reach, errorPct, maxReachPct),
            color = errColor,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp
        )
    }
}

@Composable
fun StickVisualizer(title: String, x: Float, y: Float, deadzone: Float = 0.08f, outerDeadzone: Float = 0.95f, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Box(
            modifier = Modifier
                .size(130.dp)
                .clip(CircleShape)
                .background(DarkSurface)
                .border(2.dp, DarkCardBorder, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2, size.height / 2)
                val maxRadius = size.width / 2 - 16.dp.toPx()

                // Center cross
                drawLine(DarkCardBorder, Offset(center.x, 0f), Offset(center.x, size.height), 2f)
                drawLine(DarkCardBorder, Offset(0f, center.y), Offset(size.width, center.y), 2f)

                // Outer reach ring (Cyan)
                drawCircle(Color(0x3300F0FF), radius = maxRadius * outerDeadzone, center = center, style = Stroke(2f))

                // Deadzone ring (Red)
                drawCircle(Color(0x55FF0055), radius = maxRadius * deadzone, center = center)
                drawCircle(NeonPink, radius = maxRadius * deadzone, center = center, style = Stroke(2f))

                // Stick dot position
                val dotX = center.x + (x.coerceIn(-1f, 1f) * maxRadius)
                val dotY = center.y + (y.coerceIn(-1f, 1f) * maxRadius)

                val mag = hypot(x.toDouble(), y.toDouble()).toFloat()
                val isInsideDeadzone = mag <= deadzone

                val dotColor = if (isInsideDeadzone) Color.Gray else color
                drawCircle(dotColor, radius = 12.dp.toPx(), center = Offset(dotX, dotY))
            }
        }
        val mag = hypot(x.toDouble(), y.toDouble()).toFloat()
        Text("Mag: ${(mag * 100).toInt()}%  (X: ${"%.2f".format(x)}, Y: ${"%.2f".format(y)})", color = TextSecondary, fontSize = 11.sp)
    }
}

@Composable
fun PollingRateDiagnosticView(
    pollingHz: Float,
    peakHz: Float,
    latencyMs: Float,
    jitterMs: Float,
    sampleCount: Int
) {
    val hzHistory = remember { mutableStateListOf<Float>() }

    LaunchedEffect(pollingHz) {
        if (pollingHz > 10f) {
            hzHistory.add(pollingHz)
            if (hzHistory.size > 80) {
                hzHistory.removeAt(0)
            }
        }
    }

    val connQualityText = when {
        pollingHz >= 400f || peakHz >= 450f -> stringResource(R.string.conn_quality_usb)
        pollingHz >= 200f || peakHz >= 220f -> stringResource(R.string.conn_quality_bt_fast)
        pollingHz >= 90f || peakHz >= 100f -> stringResource(R.string.conn_quality_bt_std)
        pollingHz > 0f -> stringResource(R.string.conn_quality_slow)
        else -> "🕹️ Bougez les joysticks ou appuyez sur des touches..."
    }

    val connQualityColor = when {
        pollingHz >= 400f || peakHz >= 450f -> NeonCyan
        pollingHz >= 200f || peakHz >= 220f -> NeonGreen
        pollingHz >= 90f || peakHz >= 100f -> Color(0xFFFFAA00)
        pollingHz > 0f -> NeonPink
        else -> TextSecondary
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // 1. Header Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkCard),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Bolt, contentDescription = null, tint = NeonCyan)
                    Text(
                        stringResource(R.string.polling_test_title),
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        fontSize = 16.sp
                    )
                }
                Text(
                    stringResource(R.string.polling_test_desc),
                    color = TextSecondary,
                    fontSize = 12.sp
                )

                // Connection Quality Pill
                Surface(
                    color = connQualityColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, connQualityColor.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(connQualityColor)
                        )
                        Text(
                            connQualityText,
                            color = connQualityColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // 2. Metrics 2x2 Grid Cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Polling Rate Card
            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyan.copy(alpha = 0.4f))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(stringResource(R.string.polling_live_rate), color = TextSecondary, fontSize = 11.sp)
                    Text(
                        "${pollingHz.toInt()} Hz",
                        color = NeonCyan,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        "${(pollingHz / 1000f * 100f).coerceIn(0f, 100f).toInt()}% de 1000Hz",
                        color = TextSecondary.copy(alpha = 0.7f),
                        fontSize = 10.sp
                    )
                }
            }

            // Peak Frequency Card
            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, NeonPink.copy(alpha = 0.4f))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(stringResource(R.string.polling_peak_rate), color = TextSecondary, fontSize = 11.sp)
                    Text(
                        "${peakHz.toInt()} Hz",
                        color = NeonPink,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        "Max enregistré",
                        color = TextSecondary.copy(alpha = 0.7f),
                        fontSize = 10.sp
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Latency Card
            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(stringResource(R.string.polling_latency), color = TextSecondary, fontSize = 11.sp)
                    val latText = if (latencyMs > 0f) "%.1f ms".format(latencyMs) else "-- ms"
                    Text(
                        latText,
                        color = TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Intervalle moyen",
                        color = TextSecondary.copy(alpha = 0.7f),
                        fontSize = 10.sp
                    )
                }
            }

            // Jitter / Stability Card
            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(stringResource(R.string.polling_jitter), color = TextSecondary, fontSize = 11.sp)
                    val jitText = if (jitterMs > 0f) "±%.2f ms".format(jitterMs) else "±0.00 ms"
                    Text(
                        jitText,
                        color = TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Écart-type deltas",
                        color = TextSecondary.copy(alpha = 0.7f),
                        fontSize = 10.sp
                    )
                }
            }
        }

        // 3. Real-Time Oscilloscope Waveform Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkCard),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("📊 Flux de Fréquence en Direct", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("Paquets : $sampleCount", color = TextSecondary, fontSize = 11.sp)
                }

                Surface(
                    color = DarkBackground,
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                    ) {
                        val w = size.width
                        val h = size.height
                        val maxDisplayHz = 600f

                        // Draw Grid Lines (125Hz, 250Hz, 500Hz)
                        val gridLevels = listOf(125f, 250f, 500f)
                        for (lvl in gridLevels) {
                            val y = h - (lvl / maxDisplayHz * h)
                            drawLine(
                                color = Color(0x22FFFFFF),
                                start = Offset(0f, y),
                                end = Offset(w, y),
                                strokeWidth = 1f
                            )
                        }

                        // Draw Waveform from hzHistory
                        if (hzHistory.size >= 2) {
                            val path = Path()
                            val fillPath = Path()
                            fillPath.moveTo(0f, h)

                            val stepX = w / (hzHistory.size - 1).toFloat()
                            for (i in hzHistory.indices) {
                                val currentHz = hzHistory[i].coerceIn(0f, maxDisplayHz)
                                val x = i * stepX
                                val y = h - (currentHz / maxDisplayHz * h)

                                if (i == 0) {
                                    path.moveTo(x, y)
                                    fillPath.lineTo(x, y)
                                } else {
                                    path.lineTo(x, y)
                                    fillPath.lineTo(x, y)
                                }
                            }

                            fillPath.lineTo(w, h)
                            fillPath.close()

                            // Gradient Fill
                            drawPath(
                                path = fillPath,
                                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                    colors = listOf(NeonCyan.copy(alpha = 0.35f), NeonCyan.copy(alpha = 0.02f))
                                )
                            )

                            // Waveform Stroke Line
                            drawPath(
                                path = path,
                                color = NeonCyan,
                                style = Stroke(width = 2.5f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("0 Hz", fontSize = 10.sp, color = TextSecondary)
                    Text("125 Hz (BT Standard)", fontSize = 10.sp, color = TextSecondary)
                    Text("250 Hz (BT Pro)", fontSize = 10.sp, color = TextSecondary)
                    Text("500 Hz (USB)", fontSize = 10.sp, color = TextSecondary)
                }
            }
        }
    }
}
