package com.kinou.gameassist.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinou.gameassist.R
import com.kinou.gameassist.data.model.GameProfile
import com.kinou.gameassist.data.model.GyroActivation
import com.kinou.gameassist.data.model.ResponseCurve
import com.kinou.gameassist.engine.AimSettings
import com.kinou.gameassist.engine.CursorSpeed
import com.kinou.gameassist.engine.HapticManager
import com.kinou.gameassist.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditScreen(
    profile: GameProfile,
    liveRx: Float,
    liveRy: Float,
    onSaveProfile: (GameProfile) -> Unit,
    onOpenVisualEditor: (GameProfile) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val hapticManager = remember(context) { HapticManager(context) }
    DisposableEffect(hapticManager) {
        onDispose {
            hapticManager.release()
        }
    }
    BackHandler { onBack() }

    // Sauvegarde auto : le contenu déplacé appelle onSave() après chaque modification.
    val onSave: () -> Unit = { onSaveProfile(profile) }

    // Sub-tab selection state (0 = Caméra, 1 = Déplacement, 2 = Touches, 3 = Haptique)
    var selectedTab by remember(profile.id) { mutableIntStateOf(0) }

    // Reactive slider state tied to profile
    var sensX by remember(profile.id) { mutableFloatStateOf(profile.camera.sensitivityX) }
    var sensY by remember(profile.id) { mutableFloatStateOf(profile.camera.sensitivityY) }
    var deadzoneCam by remember(profile.id) { mutableFloatStateOf(profile.camera.deadzone) }
    var smoothing by remember(profile.id) { mutableFloatStateOf(profile.camera.smoothing) }
    var acceleration by remember(profile.id) { mutableFloatStateOf(profile.camera.acceleration) }
    var flickBoost by remember(profile.id) { mutableFloatStateOf(profile.camera.flickBoost) }
    var flickThreshold by remember(profile.id) { mutableFloatStateOf(profile.camera.flickThreshold) }
    var flickAdsSafety by remember(profile.id) { mutableStateOf(profile.camera.flickAdsSafety) }
    var adsEnabled by remember(profile.id) { mutableStateOf(profile.camera.adsSensitivityEnabled) }
    var adsMultiplier by remember(profile.id) { mutableFloatStateOf(profile.camera.adsSensitivityMultiplier) }
    var antiRecoilEnabled by remember(profile.id) { mutableStateOf(profile.camera.antiRecoilEnabled) }
    var antiRecoilSpeed by remember(profile.id) { mutableFloatStateOf(profile.camera.antiRecoilSpeed) }
    var antiRecoilAdsOnly by remember(profile.id) { mutableStateOf(profile.camera.antiRecoilAdsOnly) }
    var invertX by remember(profile.id) { mutableStateOf(profile.camera.invertX) }
    var invertY by remember(profile.id) { mutableStateOf(profile.camera.invertY) }
    var gyroEnabled by remember(profile.id) { mutableStateOf(profile.camera.gyroEnabled) }
    var gyroActivation by remember(profile.id) { mutableStateOf(profile.camera.gyroActivation) }
    var gyroSensitivity by remember(profile.id) { mutableFloatStateOf(profile.camera.gyroSensitivity) }
    var deadzoneJoy by remember(profile.id) { mutableFloatStateOf(profile.joystick.deadzone) }
    var raaKeepAlive by remember(profile.id) { mutableStateOf(profile.joystick.raaKeepAlive) }
    var jiggleStrafe by remember(profile.id) { mutableStateOf(profile.joystick.jiggleStrafe) }
    var jiggleRandomPattern by remember(profile.id) { mutableStateOf(profile.joystick.jiggleRandomPattern) }
    var jiggleHumanize by remember(profile.id) { mutableStateOf(profile.joystick.jiggleHumanize) }
    var jiggleRandomness by remember(profile.id) { mutableFloatStateOf(profile.joystick.jiggleRandomness) }
    var jiggleSpeed by remember(profile.id) { mutableFloatStateOf(profile.joystick.jiggleSpeed) }
    // Vitesse du curseur virtuel (L3+R3) : même valeur que le D-pad en jeu, bornée par CursorSpeed.
    var cursorSpeed by remember(profile.id) { mutableFloatStateOf(CursorSpeed.coerce(profile.settings.cursorSpeed)) }
    var aimRadius by remember(profile.id) { mutableFloatStateOf(AimSettings.coerceRadius(profile.settings.aimRadius)) }
    var aimDeadzone by remember(profile.id) { mutableFloatStateOf(AimSettings.coerceDeadzone(profile.settings.aimDeadzone)) }
    var responseCurve by remember(profile.id) { mutableStateOf(profile.camera.responseCurve) }

    // Haptic Feedback States
    var hapticFeedback by remember(profile.id) { mutableStateOf(profile.settings.hapticFeedback) }
    var hapticDevice by remember(profile.id) { mutableStateOf(profile.settings.hapticDevice) }
    var hapticController by remember(profile.id) { mutableStateOf(profile.settings.hapticController) }
    var hapticFire by remember(profile.id) { mutableStateOf(profile.settings.hapticFire) }
    var hapticReload by remember(profile.id) { mutableStateOf(profile.settings.hapticReload) }
    var hapticIntensity by remember(profile.id) { mutableFloatStateOf(profile.settings.hapticIntensity) }
    // Rename Profile States
    var profileName by remember(profile.id, profile.name) { mutableStateOf(profile.name) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameInputText by remember(profile.id, profile.name) { mutableStateOf(profile.name) }
    var showPackageDialog by remember { mutableStateOf(false) }
    var profilePackage by remember(profile.id, profile.packageName) { mutableStateOf(profile.packageName) }

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profile_edit_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.bug_report_back),
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkBackground,
                    titleContentColor = TextPrimary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // En-tête : nom et jeu éditables (ex-crayons de la carte de liste)
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = profileName,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        fontSize = 18.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    IconButton(
                        onClick = {
                            renameInputText = profile.name
                            showRenameDialog = true
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = stringResource(R.string.btn_rename),
                            tint = NeonCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { showPackageDialog = true }
                ) {
                    Text(
                        text = profilePackage,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = stringResource(R.string.select_game_app_title),
                        tint = TextSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                }
                Text(
                    text = stringResource(R.string.buttons_count_configured, profile.buttons.size),
                    color = NeonCyan,
                    fontSize = 12.sp
                )
            }
                Button(
                    onClick = { onOpenVisualEditor(profile) },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                ) {
                    Icon(Icons.Default.DashboardCustomize, contentDescription = null, tint = DarkBackground, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.btn_visual_editor),
                        color = DarkBackground,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
                HorizontalDivider(color = DarkCardBorder, thickness = 1.dp)

                // ==========================================
                // BARRE D'ONGLETS SUB-TABS PILULES
                // ==========================================
                val subTabs = listOf(
                    stringResource(R.string.tab_aim_title) to 0,
                    stringResource(R.string.tab_movement_title) to 1,
                    stringResource(R.string.tab_haptic_title) to 2
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DarkSurface, RoundedCornerShape(12.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    subTabs.forEach { (title, index) ->
                        val isTabActive = selectedTab == index
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isTabActive) NeonCyan else Color.Transparent)
                                .clickable { selectedTab = index }
                                .padding(vertical = 7.dp, horizontal = 2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = title,
                                fontSize = 11.sp,
                                maxLines = 1,
                                softWrap = false,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                fontWeight = if (isTabActive) FontWeight.Bold else FontWeight.Medium,
                                color = if (isTabActive) DarkBackground else TextSecondary
                            )
                        }
                    }
                }

                // ==========================================
                // TAB 0 : 🎯 VISÉE & CAMÉRA
                // ==========================================
                if (selectedTab == 0) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.response_curve_title), color = TextSecondary, fontSize = 13.sp)

                        // Selectors
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                ResponseCurve.DYNAMIC_BOOST to stringResource(R.string.curve_dynamic_boost),
                                ResponseCurve.DYNAMIC to stringResource(R.string.curve_dynamic)
                            ).forEach { (c, title) ->
                                val active = responseCurve == c
                                Button(
                                    onClick = {
                                        responseCurve = c
                                        profile.camera.responseCurve = c
                                        onSave()
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (active) NeonCyan else DarkCardBorder
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        title,
                                        color = if (active) DarkBackground else TextPrimary,
                                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                ResponseCurve.STANDARD to stringResource(R.string.curve_standard),
                                ResponseCurve.LINEAR to stringResource(R.string.curve_linear)
                            ).forEach { (c, title) ->
                                val active = responseCurve == c
                                Button(
                                    onClick = {
                                        responseCurve = c
                                        profile.camera.responseCurve = c
                                        onSave()
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (active) NeonCyan else DarkCardBorder
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        title,
                                        color = if (active) DarkBackground else TextPrimary,
                                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }

                        // Graphique Interactif 2D de la Courbe
                        ResponseCurveVisualizer(
                            curve = responseCurve,
                            acceleration = acceleration,
                            flickBoost = flickBoost,
                            flickThreshold = flickThreshold,
                            liveRx = liveRx,
                            liveRy = liveRy
                        )

                        val curveDesc = when (responseCurve) {
                            ResponseCurve.DYNAMIC_BOOST -> stringResource(R.string.curve_dynamic_boost_desc)
                            ResponseCurve.DYNAMIC -> stringResource(R.string.curve_dynamic_desc)
                            ResponseCurve.STANDARD -> stringResource(R.string.curve_standard_desc)
                            ResponseCurve.LINEAR -> stringResource(R.string.curve_linear_desc)
                        }
                        Text(curveDesc, color = NeonCyan, fontSize = 11.sp)

                        // Settings spécifiques Flick / Courbe positionnés juste sous le graphique
                        AnimatedVisibility(visible = responseCurve == ResponseCurve.DYNAMIC_BOOST) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                SliderSetting(
                                    label = stringResource(R.string.flick_threshold_label),
                                    value = flickThreshold,
                                    range = 0.65f..0.92f,
                                    displayText = "${(flickThreshold * 100).toInt()}%",
                                    onValueChange = {
                                        flickThreshold = it
                                        profile.camera.flickThreshold = it
                                    },
                                    onValueChangeFinished = { onSave() }
                                )

                                SliderSetting(
                                    label = stringResource(R.string.flick_boost_label),
                                    value = flickBoost,
                                    range = 1.2f..5.0f,
                                    displayText = "%.1fx".format(flickBoost),
                                    onValueChange = {
                                        flickBoost = it
                                        profile.camera.flickBoost = it
                                    },
                                    onValueChangeFinished = { onSave() }
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(stringResource(R.string.ads_safety_title), fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 13.sp)
                                        Text(stringResource(R.string.ads_safety_desc), color = TextSecondary, fontSize = 11.sp)
                                    }
                                    Switch(
                                        checked = flickAdsSafety,
                                        onCheckedChange = {
                                            flickAdsSafety = it
                                            profile.camera.flickAdsSafety = it
                                            onSave()
                                        },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = DarkBackground,
                                            checkedTrackColor = NeonCyan,
                                            uncheckedThumbColor = TextSecondary,
                                            uncheckedTrackColor = DarkSurface
                                        )
                                    )
                                }
                            }
                        }

                        AnimatedVisibility(visible = responseCurve == ResponseCurve.STANDARD) {
                            SliderSetting(
                                label = stringResource(R.string.standard_accel_label),
                                value = acceleration,
                                range = 1.0f..10.0f,
                                displayText = "%.2f".format(acceleration),
                                onValueChange = {
                                    acceleration = it
                                    profile.camera.acceleration = it
                                },
                                onValueChangeFinished = { onSave() }
                            )
                        }

                        HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f), thickness = 1.dp)

                        SliderSetting(
                            label = stringResource(R.string.sensitivity_x),
                            value = sensX,
                            range = 0.05f..5.0f,
                            displayText = "%.2fx".format(sensX),
                            onValueChange = {
                                sensX = it
                                profile.camera.sensitivityX = it
                            },
                            onValueChangeFinished = { onSave() }
                        )

                        SliderSetting(
                            label = stringResource(R.string.sensitivity_y),
                            value = sensY,
                            range = 0.05f..5.0f,
                            displayText = "%.2fx".format(sensY),
                            onValueChange = {
                                sensY = it
                                profile.camera.sensitivityY = it
                            },
                            onValueChangeFinished = { onSave() }
                        )

                        SliderSetting(
                            label = stringResource(R.string.deadzone_title),
                            value = deadzoneCam,
                            range = 0.02f..0.35f,
                            displayText = "${(deadzoneCam * 100).toInt()}%",
                            onValueChange = {
                                deadzoneCam = it
                                profile.camera.deadzone = it
                            },
                            onValueChangeFinished = { onSave() }
                        )

                        SliderSetting(
                            label = stringResource(R.string.smoothing_title),
                            value = smoothing,
                            range = 0.0f..0.60f,
                            displayText = "%.2f".format(smoothing),
                            onValueChange = {
                                smoothing = it
                                profile.camera.smoothing = it
                            },
                            onValueChangeFinished = { onSave() }
                        )

                        HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f), thickness = 1.dp)

                        // --- Sensibilité ADS Séparée (Visée LT) ---
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        stringResource(R.string.ads_multiplier_title),
                                        color = TextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        stringResource(R.string.ads_multiplier_desc),
                                        color = TextSecondary,
                                        fontSize = 11.sp
                                    )
                                }
                                Switch(
                                    checked = adsEnabled,
                                    onCheckedChange = {
                                        adsEnabled = it
                                        profile.camera.adsSensitivityEnabled = it
                                        onSave()
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = DarkBackground,
                                        checkedTrackColor = NeonCyan,
                                        uncheckedThumbColor = TextSecondary,
                                        uncheckedTrackColor = DarkSurface
                                    )
                                )
                            }

                            AnimatedVisibility(visible = adsEnabled) {
                                SliderSetting(
                                    label = stringResource(R.string.ads_multiplier_label),
                                    value = adsMultiplier,
                                    range = 0.10f..2.00f,
                                    displayText = "%.2fx".format(adsMultiplier),
                                    onValueChange = {
                                        adsMultiplier = it
                                        profile.camera.adsSensitivityMultiplier = it
                                    },
                                    onValueChangeFinished = { onSave() }
                                )
                            }
                        }

                        HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f), thickness = 1.dp)

                        // --- Anti-Recul Vertical (Compensateur de Tir) ---
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        stringResource(R.string.anti_recoil_title),
                                        color = TextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        stringResource(R.string.anti_recoil_desc),
                                        color = TextSecondary,
                                        fontSize = 11.sp
                                    )
                                }
                                Switch(
                                    checked = antiRecoilEnabled,
                                    onCheckedChange = {
                                        antiRecoilEnabled = it
                                        profile.camera.antiRecoilEnabled = it
                                        onSave()
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = DarkBackground,
                                        checkedTrackColor = NeonOrange,
                                        uncheckedThumbColor = TextSecondary,
                                        uncheckedTrackColor = DarkSurface
                                    )
                                )
                            }

                            AnimatedVisibility(visible = antiRecoilEnabled) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    SliderSetting(
                                        label = stringResource(R.string.anti_recoil_speed_label),
                                        value = antiRecoilSpeed,
                                        range = 0.1f..10.0f,
                                        displayText = "%.1fx".format(antiRecoilSpeed),
                                        onValueChange = {
                                            antiRecoilSpeed = it
                                            profile.camera.antiRecoilSpeed = it
                                        },
                                        onValueChangeFinished = { onSave() }
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                stringResource(R.string.anti_recoil_ads_only_title),
                                                color = NeonCyan,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                stringResource(R.string.anti_recoil_ads_only_desc),
                                                color = TextSecondary,
                                                fontSize = 10.sp
                                            )
                                        }
                                        Switch(
                                            checked = antiRecoilAdsOnly,
                                            onCheckedChange = {
                                                antiRecoilAdsOnly = it
                                                profile.camera.antiRecoilAdsOnly = it
                                                onSave()
                                            },
                                            colors = SwitchDefaults.colors(
                                                checkedThumbColor = DarkBackground,
                                                checkedTrackColor = NeonCyan,
                                                uncheckedThumbColor = TextSecondary,
                                                uncheckedTrackColor = DarkSurface
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f), thickness = 1.dp)

                        // --- Inversion des Axes X et Y ---
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        stringResource(R.string.invert_y_title),
                                        color = TextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        stringResource(R.string.invert_y_desc),
                                        color = TextSecondary,
                                        fontSize = 11.sp
                                    )
                                }
                                Switch(
                                    checked = invertY,
                                    onCheckedChange = {
                                        invertY = it
                                        profile.camera.invertY = it
                                        onSave()
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = DarkBackground,
                                        checkedTrackColor = NeonPink,
                                        uncheckedThumbColor = TextSecondary,
                                        uncheckedTrackColor = DarkSurface
                                    )
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        stringResource(R.string.invert_x_title),
                                        color = TextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        stringResource(R.string.invert_x_desc),
                                        color = TextSecondary,
                                        fontSize = 11.sp
                                    )
                                }
                                Switch(
                                    checked = invertX,
                                    onCheckedChange = {
                                        invertX = it
                                        profile.camera.invertX = it
                                        onSave()
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = DarkBackground,
                                        checkedTrackColor = NeonPink,
                                        uncheckedThumbColor = TextSecondary,
                                        uncheckedTrackColor = DarkSurface
                                    )
                                )
                            }
                        }

                        HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f), thickness = 1.dp)

                        // --- Controller gyro aim ---
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        stringResource(R.string.gyro_title),
                                        color = TextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        stringResource(R.string.gyro_desc),
                                        color = TextSecondary,
                                        fontSize = 11.sp
                                    )
                                }
                                Switch(
                                    checked = gyroEnabled,
                                    onCheckedChange = {
                                        gyroEnabled = it
                                        profile.camera.gyroEnabled = it
                                        onSave()
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = DarkBackground,
                                        checkedTrackColor = NeonCyan,
                                        uncheckedThumbColor = TextSecondary,
                                        uncheckedTrackColor = DarkSurface
                                    )
                                )
                            }

                            AnimatedVisibility(visible = gyroEnabled) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(stringResource(R.string.gyro_activation_label), color = TextSecondary, fontSize = 12.sp)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        listOf(
                                            GyroActivation.LT to stringResource(R.string.gyro_activation_lt),
                                            GyroActivation.ALWAYS to stringResource(R.string.gyro_activation_always)
                                        ).forEach { (mode, title) ->
                                            val active = gyroActivation == mode
                                            Button(
                                                onClick = {
                                                    gyroActivation = mode
                                                    profile.camera.gyroActivation = mode
                                                    onSave()
                                                },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = if (active) NeonCyan else DarkCardBorder
                                                ),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.weight(1f),
                                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                                            ) {
                                                Text(
                                                    title,
                                                    color = if (active) DarkBackground else TextPrimary,
                                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                                    fontSize = 11.sp
                                                )
                                            }
                                        }
                                    }

                                    SliderSetting(
                                        label = stringResource(R.string.gyro_sensitivity_label),
                                        value = gyroSensitivity,
                                        range = 0.2f..3.0f,
                                        displayText = "%.1fx".format(gyroSensitivity),
                                        onValueChange = {
                                            gyroSensitivity = it
                                            profile.camera.gyroSensitivity = it
                                        },
                                        onValueChangeFinished = { onSave() }
                                    )
                                }
                            }
                        }

                        // --- Raccourci Hot-Switch Info Banner ---
                        Surface(
                            color = NeonCyan.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyan.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = stringResource(R.string.hot_switch_hint),
                                color = NeonCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }

                // ==========================================
                // TAB 1 : 🕹️ DÉPLACEMENT & RAA
                // ==========================================
                if (selectedTab == 1) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SliderSetting(
                            label = stringResource(R.string.joystick_deadzone),
                            value = deadzoneJoy,
                            range = 0.02f..0.35f,
                            displayText = "${(deadzoneJoy * 100).toInt()}%",
                            onValueChange = {
                                deadzoneJoy = it
                                profile.joystick.deadzone = it
                            },
                            onValueChangeFinished = { onSave() }
                        )

                        // RAA Keep-Alive Switch
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.aim_assist_title), fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 13.sp)
                                Text(stringResource(R.string.aim_assist_desc), color = TextSecondary, fontSize = 11.sp)
                            }
                            Switch(
                                checked = raaKeepAlive,
                                onCheckedChange = {
                                    raaKeepAlive = it
                                    profile.joystick.raaKeepAlive = it
                                    onSave()
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = DarkBackground,
                                    checkedTrackColor = NeonGreen,
                                    uncheckedThumbColor = TextSecondary,
                                    uncheckedTrackColor = DarkSurface
                                )
                            )
                        }

                        // Jiggle Strafe Switch
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.jiggle_strafe_title), fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 13.sp)
                                Text(stringResource(R.string.jiggle_strafe_desc), color = TextSecondary, fontSize = 11.sp)
                            }
                            Switch(
                                checked = jiggleStrafe,
                                onCheckedChange = {
                                    jiggleStrafe = it
                                    profile.joystick.jiggleStrafe = it
                                    onSave()
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = DarkBackground,
                                    checkedTrackColor = NeonGreen,
                                    uncheckedThumbColor = TextSecondary,
                                    uncheckedTrackColor = DarkSurface
                                )
                            )
                        }

                        AnimatedVisibility(visible = jiggleStrafe) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // 1. Patterns Aléatoires & Feintes (Random Directions)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(stringResource(R.string.jiggle_random_pattern_title), fontWeight = FontWeight.Bold, color = NeonYellow, fontSize = 12.sp)
                                        Text(stringResource(R.string.jiggle_random_pattern_desc), color = TextSecondary, fontSize = 10.sp)
                                    }
                                    Switch(
                                        checked = jiggleRandomPattern,
                                        onCheckedChange = {
                                            jiggleRandomPattern = it
                                            profile.joystick.jiggleRandomPattern = it
                                            onSave()
                                        },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = DarkBackground,
                                            checkedTrackColor = NeonYellow,
                                            uncheckedThumbColor = TextSecondary,
                                            uncheckedTrackColor = DarkSurface
                                        )
                                    )
                                }

                                // 2. Humanisation (Timing & Distance Variations)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(stringResource(R.string.humanization_title), fontWeight = FontWeight.Bold, color = NeonCyan, fontSize = 12.sp)
                                        Text(stringResource(R.string.humanization_desc), color = TextSecondary, fontSize = 10.sp)
                                    }
                                    Switch(
                                        checked = jiggleHumanize,
                                        onCheckedChange = {
                                            jiggleHumanize = it
                                            profile.joystick.jiggleHumanize = it
                                            onSave()
                                        },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = DarkBackground,
                                            checkedTrackColor = NeonCyan,
                                            uncheckedThumbColor = TextSecondary,
                                            uncheckedTrackColor = DarkSurface
                                        )
                                    )
                                }

                                AnimatedVisibility(visible = jiggleHumanize) {
                                    SliderSetting(
                                        label = stringResource(R.string.jiggle_random_label),
                                        value = jiggleRandomness,
                                        range = 0.05f..0.85f,
                                        displayText = "${(jiggleRandomness * 100).toInt()}%",
                                        onValueChange = {
                                            jiggleRandomness = it
                                            profile.joystick.jiggleRandomness = it
                                        },
                                        onValueChangeFinished = { onSave() }
                                    )
                                }

                                SliderSetting(
                                    label = stringResource(R.string.jiggle_speed_label),
                                    value = jiggleSpeed,
                                    range = 0.6f..1.6f,
                                    displayText = "%.2fx (~%d ms)".format(jiggleSpeed, (180 / jiggleSpeed).toInt()),
                                    onValueChange = {
                                        jiggleSpeed = it
                                        profile.joystick.jiggleSpeed = it
                                    },
                                    onValueChangeFinished = { onSave() }
                                )
                            }
                        }

                        // Curseur virtuel (L3+R3) : même réglage que le D-pad en jeu.
                        SliderSetting(
                            label = stringResource(R.string.cursor_speed_setting_title),
                            value = cursorSpeed,
                            range = CursorSpeed.MIN..CursorSpeed.MAX,
                            displayText = stringResource(R.string.cursor_speed, CursorSpeed.format(cursorSpeed)),
                            onValueChange = {
                                val bounded = CursorSpeed.coerce(it)
                                cursorSpeed = bounded
                                profile.settings.cursorSpeed = bounded
                            },
                            onValueChangeFinished = { onSave() }
                        )

                        // MOBA aim buttons (role "Aim" in the HUD editor): drag range and stick deadzone.
                        HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f), thickness = 1.dp)
                        Text(stringResource(R.string.moba_section_title), fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 13.sp)
                        Text(stringResource(R.string.moba_section_desc), color = TextSecondary, fontSize = 11.sp)
                        SliderSetting(
                            label = stringResource(R.string.moba_aim_radius_label),
                            value = aimRadius,
                            range = AimSettings.RADIUS_MIN..AimSettings.RADIUS_MAX,
                            displayText = AimSettings.percent(aimRadius),
                            onValueChange = {
                                aimRadius = AimSettings.coerceRadius(it)
                                profile.settings.aimRadius = aimRadius
                            },
                            onValueChangeFinished = { onSave() }
                        )
                        SliderSetting(
                            label = stringResource(R.string.moba_aim_deadzone_label),
                            value = aimDeadzone,
                            range = AimSettings.DEADZONE_MIN..AimSettings.DEADZONE_MAX,
                            displayText = AimSettings.percent(aimDeadzone),
                            onValueChange = {
                                aimDeadzone = AimSettings.coerceDeadzone(it)
                                profile.settings.aimDeadzone = aimDeadzone
                            },
                            onValueChangeFinished = { onSave() }
                        )
                    }
                }

                // ==========================================
                // TAB 2 : 📳 HAPTIQUE & VIBRATIONS
                // ==========================================
                // TAB 3 : HAPTIQUE MANETTE (RUMBLE)
                // ==========================================
                if (selectedTab == 2) {
                    val gamepadInfos = remember { hapticManager.getConnectedGamepadsInfo() }

                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Gamepad Status Card
                        if (gamepadInfos.isNotEmpty()) {
                            gamepadInfos.forEach { info ->
                                Surface(
                                    color = if (info.hasRumble) NeonGreen.copy(alpha = 0.12f) else NeonOrange.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(10.dp),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        if (info.hasRumble) NeonGreen.copy(alpha = 0.6f) else NeonOrange.copy(alpha = 0.6f)
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.SportsEsports,
                                            contentDescription = null,
                                            tint = if (info.hasRumble) NeonGreen else NeonOrange,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = info.name,
                                                fontWeight = FontWeight.Bold,
                                                color = TextPrimary,
                                                fontSize = 12.sp
                                            )
                                            Text(
                                                text = if (info.hasRumble) {
                                                    stringResource(R.string.gamepad_rumble_detected, info.motorCount)
                                                } else {
                                                    stringResource(R.string.gamepad_rumble_not_supported)
                                                },
                                                color = if (info.hasRumble) NeonGreen else NeonOrange,
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            Surface(
                                color = DarkSurface,
                                shape = RoundedCornerShape(10.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        Icons.Default.SportsEsports,
                                        contentDescription = null,
                                        tint = TextSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.gamepad_none_connected),
                                        color = TextSecondary,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }

                        // Master Vibration Toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.haptic_title), fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 13.sp)
                                Text(stringResource(R.string.haptic_desc), color = TextSecondary, fontSize = 11.sp)
                            }
                            Switch(
                                checked = hapticFeedback,
                                onCheckedChange = {
                                    hapticFeedback = it
                                    profile.settings.hapticFeedback = it
                                    profile.settings.hapticController = it
                                    onSave()
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = DarkBackground,
                                    checkedTrackColor = NeonOrange,
                                    uncheckedThumbColor = TextSecondary,
                                    uncheckedTrackColor = DarkSurface
                                )
                            )
                        }

                        AnimatedVisibility(visible = hapticFeedback) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                HorizontalDivider(color = DarkCardBorder, thickness = 1.dp)

                                // Event 1 : Fire
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(stringResource(R.string.haptic_fire_title), fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 13.sp)
                                        Text(stringResource(R.string.haptic_fire_desc), color = TextSecondary, fontSize = 11.sp)
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        OutlinedButton(
                                            onClick = { hapticManager.playFireHaptic(hapticIntensity) },
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier.height(30.dp)
                                        ) {
                                            Text(stringResource(R.string.btn_test), fontSize = 10.sp, color = NeonCyan)
                                        }
                                        Switch(
                                            checked = hapticFire,
                                            onCheckedChange = {
                                                hapticFire = it
                                                profile.settings.hapticFire = it
                                                onSave()
                                            },
                                            colors = SwitchDefaults.colors(
                                                checkedThumbColor = DarkBackground,
                                                checkedTrackColor = NeonOrange,
                                                uncheckedThumbColor = TextSecondary,
                                                uncheckedTrackColor = DarkSurface
                                            )
                                        )
                                    }
                                }

                                // Event 2 : Reload
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(stringResource(R.string.haptic_reload_title), fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 13.sp)
                                        Text(stringResource(R.string.haptic_reload_desc), color = TextSecondary, fontSize = 11.sp)
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        OutlinedButton(
                                            onClick = { hapticManager.playReloadHaptic(hapticIntensity) },
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier.height(30.dp)
                                        ) {
                                            Text(stringResource(R.string.btn_test), fontSize = 10.sp, color = NeonCyan)
                                        }
                                        Switch(
                                            checked = hapticReload,
                                            onCheckedChange = {
                                                hapticReload = it
                                                profile.settings.hapticReload = it
                                                onSave()
                                            },
                                            colors = SwitchDefaults.colors(
                                                checkedThumbColor = DarkBackground,
                                                checkedTrackColor = NeonOrange,
                                                uncheckedThumbColor = TextSecondary,
                                                uncheckedTrackColor = DarkSurface
                                            )
                                        )
                                    }
                                }

                                SliderSetting(
                                    label = stringResource(R.string.haptic_intensity_label),
                                    value = hapticIntensity,
                                    range = 0.1f..1.0f,
                                    displayText = "${(hapticIntensity * 100).toInt()}%",
                                    onValueChange = {
                                        hapticIntensity = it
                                        profile.settings.hapticIntensity = it
                                    },
                                    onValueChangeFinished = { onSave() }
                                )
                            }
                        }
                    }
                }
        }
    }

    // ==========================================
    // DIALOG: RENOMMER LE PROFIL
    // ==========================================
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, tint = NeonCyan)
                    Text(
                        stringResource(R.string.rename_profile_title),
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        fontSize = 16.sp
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.rename_profile_label),
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                    OutlinedTextField(
                        value = renameInputText,
                        onValueChange = { renameInputText = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 14.sp,
                            color = TextPrimary,
                            fontWeight = FontWeight.Medium
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NeonCyan,
                            unfocusedBorderColor = DarkCardBorder
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = renameInputText.trim()
                        if (trimmed.isNotBlank()) {
                            profile.name = trimmed
                            profileName = trimmed
                            onSave()
                            Toast.makeText(context, context.getString(R.string.profile_renamed_toast), Toast.LENGTH_SHORT).show()
                            showRenameDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)
                ) {
                    Text(stringResource(R.string.btn_save), color = DarkBackground, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text(stringResource(R.string.btn_cancel), color = TextSecondary)
                }
            },
            containerColor = DarkCard
        )
    }

    // ==========================================
    // DIALOG: CHOISIR LE PACKAGE DU JEU
    // ==========================================
    if (showPackageDialog) {
        SelectGamePackageDialog(
            currentPackage = profilePackage,
            onDismiss = { showPackageDialog = false },
            onConfirm = { newPkg ->
                profile.packageName = newPkg
                profilePackage = newPkg
                onSave()
                Toast.makeText(context, context.getString(R.string.package_updated_toast), Toast.LENGTH_SHORT).show()
                showPackageDialog = false
            }
        )
    }
}
