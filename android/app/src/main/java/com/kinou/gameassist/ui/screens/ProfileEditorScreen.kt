package com.kinou.gameassist.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinou.gameassist.R
import com.kinou.gameassist.data.model.ButtonConfig
import com.kinou.gameassist.data.model.ButtonMode
import com.kinou.gameassist.data.model.GameProfile
import com.kinou.gameassist.data.model.ResponseCurve
import com.kinou.gameassist.engine.HapticManager
import com.kinou.gameassist.ui.theme.*
import java.util.UUID
import kotlin.math.pow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditorScreen(
    profiles: List<GameProfile>,
    currentProfile: GameProfile?,
    onSelectProfile: (GameProfile) -> Unit,
    onSaveProfile: (GameProfile) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onDuplicateProfile: (GameProfile) -> Unit,
    onImportProfile: (String) -> Boolean,
    liveRx: Float = 0f,
    liveRy: Float = 0f,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var editingProfileId by remember(currentProfile?.id) { mutableStateOf(currentProfile?.id) }
    var profileToDelete by remember { mutableStateOf<GameProfile?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var importJsonText by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profiles_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.btn_back))
                    }
                },
                actions = {
                    TextButton(onClick = {
                        importJsonText = ""
                        showImportDialog = true
                    }) {
                        Icon(Icons.Default.Download, contentDescription = null, tint = NeonCyan)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.btn_import), color = NeonCyan, fontWeight = FontWeight.Bold)
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(profiles, key = { it.id }) { prof ->
                val isSelected = prof.id == editingProfileId
                ProfileCard(
                    profile = prof,
                    isSelected = isSelected,
                    canDelete = profiles.size > 1,
                    liveRx = if (isSelected) liveRx else 0f,
                    liveRy = if (isSelected) liveRy else 0f,
                    onSelect = {
                        editingProfileId = prof.id
                        onSelectProfile(prof)
                    },
                    onSave = { onSaveProfile(prof) },
                    onDelete = { profileToDelete = prof },
                    onDuplicate = { onDuplicateProfile(prof) },
                    onExport = {
                        val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
                        val json = gson.toJson(prof)

                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("OpenMapper Profile", json))
                        Toast.makeText(context, context.getString(R.string.profile_copied_toast), Toast.LENGTH_SHORT).show()

                        val sendIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, json)
                            putExtra(Intent.EXTRA_TITLE, "${prof.name}.json")
                            type = "text/plain"
                        }
                        context.startActivity(Intent.createChooser(sendIntent, context.getString(R.string.btn_export)))
                    }
                )
            }
        }

        // Delete Confirmation Dialog
        if (profileToDelete != null) {
            val prof = profileToDelete!!
            AlertDialog(
                onDismissRequest = { profileToDelete = null },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.DeleteForever, contentDescription = null, tint = NeonPink)
                        Text(stringResource(R.string.delete_profile_title), fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 16.sp)
                    }
                },
                text = {
                    Text(
                        stringResource(R.string.delete_profile_confirm, prof.name),
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val deletedId = prof.id
                            profileToDelete = null
                            onDeleteProfile(deletedId)
                            if (editingProfileId == deletedId) {
                                editingProfileId = profiles.firstOrNull { it.id != deletedId }?.id
                            }
                            Toast.makeText(context, context.getString(R.string.profile_deleted_toast), Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonPink)
                    ) {
                        Text(stringResource(R.string.btn_delete), color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { profileToDelete = null }) {
                        Text(stringResource(R.string.btn_cancel), color = TextSecondary)
                    }
                },
                containerColor = DarkCard
            )
        }

        // Import Profile Dialog
        if (showImportDialog) {
            AlertDialog(
                onDismissRequest = { showImportDialog = false },
                title = { Text(stringResource(R.string.import_profile_title), fontWeight = FontWeight.Bold, color = TextPrimary) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.import_profile_desc), color = TextSecondary, fontSize = 13.sp)
                        OutlinedTextField(
                            value = importJsonText,
                            onValueChange = { importJsonText = it },
                            placeholder = { Text("{\n  \"name\": \"...\",\n  ...\n}") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = TextPrimary),
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
                            if (importJsonText.isNotBlank()) {
                                val ok = onImportProfile(importJsonText.trim())
                                if (ok) {
                                    Toast.makeText(context, context.getString(R.string.profile_imported_toast), Toast.LENGTH_SHORT).show()
                                    showImportDialog = false
                                } else {
                                    Toast.makeText(context, context.getString(R.string.profile_import_error), Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)
                    ) {
                        Text(stringResource(R.string.btn_import), color = DarkBackground, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showImportDialog = false }) {
                        Text(stringResource(R.string.btn_cancel), color = TextSecondary)
                    }
                },
                containerColor = DarkCard
            )
        }
    }
}

@Composable
fun ProfileCard(
    profile: GameProfile,
    isSelected: Boolean,
    canDelete: Boolean,
    liveRx: Float = 0f,
    liveRy: Float = 0f,
    onSelect: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit
) {
    val context = LocalContext.current
    val hapticManager = remember { HapticManager(context) }

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
    var deadzoneJoy by remember(profile.id) { mutableFloatStateOf(profile.joystick.deadzone) }
    var raaKeepAlive by remember(profile.id) { mutableStateOf(profile.joystick.raaKeepAlive) }
    var jiggleStrafe by remember(profile.id) { mutableStateOf(profile.joystick.jiggleStrafe) }
    var jiggleHumanize by remember(profile.id) { mutableStateOf(profile.joystick.jiggleHumanize) }
    var jiggleRandomness by remember(profile.id) { mutableFloatStateOf(profile.joystick.jiggleRandomness) }
    var jiggleSpeed by remember(profile.id) { mutableFloatStateOf(profile.joystick.jiggleSpeed) }
    var responseCurve by remember(profile.id) { mutableStateOf(profile.camera.responseCurve) }

    // Haptic Feedback States
    var hapticFeedback by remember(profile.id) { mutableStateOf(profile.settings.hapticFeedback) }
    var hapticFire by remember(profile.id) { mutableStateOf(profile.settings.hapticFire) }
    var hapticReload by remember(profile.id) { mutableStateOf(profile.settings.hapticReload) }
    var hapticIntensity by remember(profile.id) { mutableFloatStateOf(profile.settings.hapticIntensity) }

    // Button Mapping States
    var buttonToEditKey by remember { mutableStateOf<ButtonConfig?>(null) }
    var showKeyPickerDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (isSelected) DarkCard else DarkSurface),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isSelected) NeonCyan else DarkCardBorder
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelect() }
                ) {
                    Text(profile.name, fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 16.sp)
                    Text(profile.packageName, color = TextSecondary, fontSize = 12.sp)
                    Text(stringResource(R.string.buttons_count_configured, profile.buttons.size), color = NeonCyan, fontSize = 12.sp)
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Button(
                        onClick = onSelect,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSelected) NeonCyan else DarkCardBorder
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text(
                            if (isSelected) stringResource(R.string.btn_active) else stringResource(R.string.btn_select),
                            color = if (isSelected) DarkBackground else TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }

                    if (canDelete) {
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.DeleteOutline,
                                contentDescription = stringResource(R.string.btn_delete),
                                tint = NeonPink,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            if (isSelected) {
                HorizontalDivider(color = DarkCardBorder, thickness = 1.dp)

                // Actions: Exporter, Dupliquer, Supprimer
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onExport,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.btn_export), color = NeonCyan, fontSize = 11.sp)
                    }

                    OutlinedButton(
                        onClick = onDuplicate,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.btn_duplicate), color = TextPrimary, fontSize = 11.sp)
                    }

                    if (canDelete) {
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.btn_delete), tint = NeonPink)
                        }
                    }
                }

                HorizontalDivider(color = DarkCardBorder, thickness = 1.dp)

                // ==========================================
                // BARRE D'ONGLETS SUB-TABS PILULES
                // ==========================================
                val subTabs = listOf(
                    stringResource(R.string.tab_aim_title) to 0,
                    stringResource(R.string.tab_movement_title) to 1,
                    stringResource(R.string.tab_buttons_title) to 2,
                    stringResource(R.string.tab_haptic_title) to 3
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
                                        onSave()
                                    }
                                )

                                SliderSetting(
                                    label = stringResource(R.string.flick_boost_label),
                                    value = flickBoost,
                                    range = 1.2f..5.0f,
                                    displayText = "%.1fx".format(flickBoost),
                                    onValueChange = {
                                        flickBoost = it
                                        profile.camera.flickBoost = it
                                        onSave()
                                    }
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
                                range = 1.0f..5.0f,
                                displayText = "%.2f".format(acceleration),
                                onValueChange = {
                                    acceleration = it
                                    profile.camera.acceleration = it
                                    onSave()
                                }
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
                                onSave()
                            }
                        )

                        SliderSetting(
                            label = stringResource(R.string.sensitivity_y),
                            value = sensY,
                            range = 0.05f..5.0f,
                            displayText = "%.2fx".format(sensY),
                            onValueChange = {
                                sensY = it
                                profile.camera.sensitivityY = it
                                onSave()
                            }
                        )

                        SliderSetting(
                            label = stringResource(R.string.deadzone_title),
                            value = deadzoneCam,
                            range = 0.02f..0.35f,
                            displayText = "${(deadzoneCam * 100).toInt()}%",
                            onValueChange = {
                                deadzoneCam = it
                                profile.camera.deadzone = it
                                onSave()
                            }
                        )

                        SliderSetting(
                            label = stringResource(R.string.smoothing_title),
                            value = smoothing,
                            range = 0.0f..0.60f,
                            displayText = "%.2f".format(smoothing),
                            onValueChange = {
                                smoothing = it
                                profile.camera.smoothing = it
                                onSave()
                            }
                        )
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
                                onSave()
                            }
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
                                            onSave()
                                        }
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
                                        onSave()
                                    }
                                )
                            }
                        }
                    }
                }

                // ==========================================
                // TAB 2 : 🎮 TOUCHES & REMAPPING
                // ==========================================
                if (selectedTab == 2) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.key_mapping_title), fontWeight = FontWeight.Bold, color = NeonCyan, fontSize = 13.sp)
                            IconButton(
                                onClick = {
                                    val newBtn = ButtonConfig(
                                        id = "btn_${UUID.randomUUID().toString().take(6)}",
                                        label = "Action ${profile.buttons.size + 1}",
                                        gamepadButton = "BUTTON_A",
                                        x = 0.5f,
                                        y = 0.5f,
                                        radius = 0.045f,
                                        mode = ButtonMode.TAP
                                    )
                                    profile.buttons.add(newBtn)
                                    onSave()
                                    buttonToEditKey = newBtn
                                    showKeyPickerDialog = true
                                }
                            ) {
                                Icon(Icons.Default.AddCircle, contentDescription = stringResource(R.string.btn_add), tint = NeonCyan)
                            }
                        }

                        profile.buttons.forEach { btn ->
                            val isFire = btn.id.contains("fire", ignoreCase = true) || btn.label.contains("tir", ignoreCase = true) || btn.label.contains("fire", ignoreCase = true)
                            val isReload = btn.id.contains("reload", ignoreCase = true) || btn.label.contains("recharg", ignoreCase = true)

                            val highlightColor = when {
                                isFire -> NeonPink
                                isReload -> NeonOrange
                                else -> DarkCardBorder
                            }

                            Surface(
                                color = DarkSurface,
                                shape = RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, highlightColor),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(btn.label, fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 13.sp)
                                            if (isFire) {
                                                Text(stringResource(R.string.btn_tag_fire), color = NeonPink, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            } else if (isReload) {
                                                Text(stringResource(R.string.btn_tag_reload), color = NeonOrange, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                        Text(stringResource(R.string.btn_mode_label, btn.mode.name), color = TextSecondary, fontSize = 11.sp)
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Surface(
                                            color = when (btn.mode) {
                                                ButtonMode.HOLD -> Color(0x3300F0FF)
                                                ButtonMode.TAP -> Color(0x33FFAA00)
                                                ButtonMode.SLIDE_CANCEL -> Color(0x33FF0055)
                                            },
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier.clickable {
                                                btn.mode = when (btn.mode) {
                                                    ButtonMode.HOLD -> ButtonMode.TAP
                                                    ButtonMode.TAP -> ButtonMode.SLIDE_CANCEL
                                                    ButtonMode.SLIDE_CANCEL -> ButtonMode.HOLD
                                                }
                                                onSave()
                                            }
                                        ) {
                                            Text(
                                                btn.mode.name.take(5),
                                                color = when (btn.mode) {
                                                    ButtonMode.HOLD -> NeonCyan
                                                    ButtonMode.TAP -> NeonOrange
                                                    ButtonMode.SLIDE_CANCEL -> NeonPink
                                                },
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                                            )
                                        }

                                        Button(
                                            onClick = {
                                                buttonToEditKey = btn
                                                showKeyPickerDialog = true
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (isFire) NeonPink else if (isReload) NeonOrange else NeonCyan
                                            ),
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            Text(
                                                btn.gamepadButton.replace("BUTTON_", "").replace("TRIGGER_", ""),
                                                color = Color.Black,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 11.sp
                                            )
                                        }

                                        if (profile.buttons.size > 1) {
                                            IconButton(
                                                onClick = {
                                                    profile.buttons.remove(btn)
                                                    onSave()
                                                },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.btn_delete), tint = TextSecondary, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ==========================================
                // TAB 3 : 📳 HAPTIQUE & VIBRATIONS
                // ==========================================
                if (selectedTab == 3) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                                        onSave()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ==========================================
    // DIALOG: SÉLECTEUR DE TOUCHE MANETTE
    // ==========================================
    if (showKeyPickerDialog && buttonToEditKey != null) {
        val btn = buttonToEditKey!!
        AlertDialog(
            onDismissRequest = {
                showKeyPickerDialog = false
                buttonToEditKey = null
            },
            title = {
                Text(stringResource(R.string.key_picker_title, btn.label), fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 16.sp)
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(stringResource(R.string.key_picker_desc), color = TextSecondary, fontSize = 12.sp)

                    val keyOptions = listOf(
                        "BUTTON_R2" to "RT / R2 (Trigger R)",
                        "BUTTON_L2" to "LT / L2 (Trigger L)",
                        "BUTTON_R1" to "RB / R1 (Bumper R)",
                        "BUTTON_L1" to "LB / L1 (Bumper L)",
                        "BUTTON_A" to "A / Cross (Bottom)",
                        "BUTTON_B" to "B / Circle (Right)",
                        "BUTTON_X" to "X / Square (Left)",
                        "BUTTON_Y" to "Y / Triangle (Top)",
                        "BUTTON_THUMBL" to "L3 (Left Stick Click)",
                        "BUTTON_THUMBR" to "R3 (Right Stick Click)",
                        "DPAD_UP" to "D-Pad Up",
                        "DPAD_DOWN" to "D-Pad Down",
                        "DPAD_LEFT" to "D-Pad Left",
                        "DPAD_RIGHT" to "D-Pad Right",
                        "BUTTON_START" to "Start / Menu / Options",
                        "BUTTON_SELECT" to "Select / Back / Share"
                    )

                    keyOptions.forEach { (k, label) ->
                        val isCurrent = btn.gamepadButton.equals(k, ignoreCase = true)
                        Surface(
                            color = if (isCurrent) NeonCyan.copy(alpha = 0.15f) else DarkSurface,
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (isCurrent) NeonCyan else DarkCardBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    btn.gamepadButton = k
                                    onSave()
                                    showKeyPickerDialog = false
                                    buttonToEditKey = null
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    label,
                                    color = if (isCurrent) NeonCyan else TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                                )
                                if (isCurrent) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showKeyPickerDialog = false
                    buttonToEditKey = null
                }) {
                    Text(stringResource(R.string.btn_close), color = TextSecondary)
                }
            },
            containerColor = DarkCard
        )
    }
}

@Composable
fun ResponseCurveVisualizer(
    curve: ResponseCurve,
    acceleration: Float,
    flickBoost: Float,
    flickThreshold: Float,
    liveRx: Float = 0f,
    liveRy: Float = 0f,
    modifier: Modifier = Modifier
) {
    val stickDeflection = kotlin.math.hypot(liveRx, liveRy).coerceIn(0f, 1f)
    val maxOut = when (curve) {
        ResponseCurve.DYNAMIC_BOOST -> flickBoost
        ResponseCurve.STANDARD -> 1.0f
        else -> 1.0f
    }

    val liveYVal = when (curve) {
        ResponseCurve.LINEAR -> stickDeflection
        ResponseCurve.STANDARD -> stickDeflection.pow(acceleration)
        ResponseCurve.DYNAMIC -> (0.30f * stickDeflection + 0.70f * stickDeflection.pow(2.2f))
        ResponseCurve.DYNAMIC_BOOST -> {
            val thresh = flickThreshold.coerceIn(0.65f, 0.95f)
            if (stickDeflection <= thresh) {
                val scale = stickDeflection / thresh
                (0.25f * scale + 0.75f * scale.pow(2.2f)) * 0.85f
            } else {
                val turboT = (stickDeflection - thresh) / (1.0f - thresh)
                0.85f + (turboT * (flickBoost - 0.85f))
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Surface(
            color = DarkBackground,
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder),
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp)
        ) {
            androidx.compose.foundation.Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                val w = size.width
                val h = size.height

                // 1. Grid Lines (25%, 50%, 75%)
                val gridColor = Color(0x1800F0FF)
                for (i in 1..3) {
                    val frac = i / 4f
                    drawLine(
                        color = gridColor,
                        start = androidx.compose.ui.geometry.Offset(w * frac, 0f),
                        end = androidx.compose.ui.geometry.Offset(w * frac, h),
                        strokeWidth = 1.dp.toPx()
                    )
                    drawLine(
                        color = gridColor,
                        start = androidx.compose.ui.geometry.Offset(0f, h * frac),
                        end = androidx.compose.ui.geometry.Offset(w, h * frac),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                // 2. Axes
                drawLine(
                    color = Color(0x44FFFFFF),
                    start = androidx.compose.ui.geometry.Offset(0f, h),
                    end = androidx.compose.ui.geometry.Offset(w, h),
                    strokeWidth = 1.5.dp.toPx()
                )
                drawLine(
                    color = Color(0x44FFFFFF),
                    start = androidx.compose.ui.geometry.Offset(0f, 0f),
                    end = androidx.compose.ui.geometry.Offset(0f, h),
                    strokeWidth = 1.5.dp.toPx()
                )

                // 3. Compute Curve Path
                val path = androidx.compose.ui.graphics.Path()
                val fillPath = androidx.compose.ui.graphics.Path()
                fillPath.moveTo(0f, h)

                val samples = 60
                for (i in 0..samples) {
                    val t = i.toFloat() / samples
                    val yVal = when (curve) {
                        ResponseCurve.LINEAR -> t
                        ResponseCurve.STANDARD -> t.pow(acceleration)
                        ResponseCurve.DYNAMIC -> (0.30f * t + 0.70f * t.pow(2.2f))
                        ResponseCurve.DYNAMIC_BOOST -> {
                            val thresh = flickThreshold.coerceIn(0.65f, 0.95f)
                            if (t <= thresh) {
                                val scale = t / thresh
                                (0.25f * scale + 0.75f * scale.pow(2.2f)) * 0.85f
                            } else {
                                val turboT = (t - thresh) / (1.0f - thresh)
                                0.85f + (turboT * (flickBoost - 0.85f))
                            }
                        }
                    }

                    val px = t * w
                    val py = h - ((yVal / maxOut).coerceIn(0f, 1f) * h)

                    if (i == 0) {
                        path.moveTo(px, py)
                    } else {
                        path.lineTo(px, py)
                    }
                    fillPath.lineTo(px, py)
                }

                fillPath.lineTo(w, h)
                fillPath.close()

                // Draw Gradient Area under Curve
                drawPath(
                    path = fillPath,
                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(NeonCyan.copy(alpha = 0.30f), NeonCyan.copy(alpha = 0.02f))
                    )
                )

                // Draw Curve Line
                drawPath(
                    path = path,
                    color = NeonCyan,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 2.5.dp.toPx(),
                        cap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                )

                // 4. Threshold Marker for Dynamic Boost
                if (curve == ResponseCurve.DYNAMIC_BOOST) {
                    val threshX = flickThreshold * w
                    drawLine(
                        color = NeonPink,
                        start = androidx.compose.ui.geometry.Offset(threshX, 0f),
                        end = androidx.compose.ui.geometry.Offset(threshX, h),
                        strokeWidth = 1.5.dp.toPx(),
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                    )
                }

                // 5. Live Right Stick Position Marker & Tracer (si manette connectée / active)
                if (stickDeflection > 0.01f) {
                    val livePx = stickDeflection * w
                    val livePy = h - ((liveYVal / maxOut).coerceIn(0f, 1f) * h)

                    // Vertical tracking line
                    drawLine(
                        color = NeonPink.copy(alpha = 0.85f),
                        start = androidx.compose.ui.geometry.Offset(livePx, h),
                        end = androidx.compose.ui.geometry.Offset(livePx, livePy),
                        strokeWidth = 2.dp.toPx(),
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                    )

                    // Horizontal tracking line to output axis
                    drawLine(
                        color = NeonCyan.copy(alpha = 0.45f),
                        start = androidx.compose.ui.geometry.Offset(0f, livePy),
                        end = androidx.compose.ui.geometry.Offset(livePx, livePy),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)
                    )

                    // Outer pulse glow
                    drawCircle(
                        color = NeonPink.copy(alpha = 0.30f),
                        radius = 10.dp.toPx(),
                        center = androidx.compose.ui.geometry.Offset(livePx, livePy)
                    )

                    // Middle ring
                    drawCircle(
                        color = NeonPink,
                        radius = 5.5.dp.toPx(),
                        center = androidx.compose.ui.geometry.Offset(livePx, livePy)
                    )

                    // Inner bright core
                    drawCircle(
                        color = Color.White,
                        radius = 2.5.dp.toPx(),
                        center = androidx.compose.ui.geometry.Offset(livePx, livePy)
                    )
                }
            }
        }

        // Pourcentages en dessous du graphique (0%, 25%, 50%, 75%, 100%)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("0%", fontSize = 10.sp, color = TextSecondary, fontWeight = FontWeight.SemiBold)
            Text("25%", fontSize = 10.sp, color = TextSecondary, fontWeight = FontWeight.SemiBold)
            Text("50%", fontSize = 10.sp, color = TextSecondary, fontWeight = FontWeight.SemiBold)
            Text("75%", fontSize = 10.sp, color = TextSecondary, fontWeight = FontWeight.SemiBold)
            Text("100%", fontSize = 10.sp, color = TextSecondary, fontWeight = FontWeight.SemiBold)
        }

        // Live Stick RS Telemetry Badge if stick is moving
        if (stickDeflection > 0.01f) {
            val liveInputPercent = (stickDeflection * 100).toInt()
            val liveOutputPercent = ((liveYVal / maxOut) * 100).toInt()
            Surface(
                color = DarkSurface,
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, NeonPink.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(NeonPink)
                        )
                        Text(
                            "🕹️ Position Stick RS : $liveInputPercent%",
                            fontSize = 11.sp,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        "Sortie : $liveOutputPercent%",
                        fontSize = 11.sp,
                        color = NeonCyan,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun SliderSetting(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    displayText: String,
    onValueChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = TextSecondary, fontSize = 13.sp)
            Text(displayText, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = NeonCyan,
                activeTrackColor = NeonCyan,
                inactiveTrackColor = DarkCardBorder
            )
        )
    }
}
