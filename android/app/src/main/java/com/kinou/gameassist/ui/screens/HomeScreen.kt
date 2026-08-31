package com.kinou.gameassist.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinou.gameassist.R
import com.kinou.gameassist.data.language.LanguageManager
import com.kinou.gameassist.data.model.GamepadDetector
import com.kinou.gameassist.data.model.GamepadDevice
import com.kinou.gameassist.data.model.GameProfile
import com.kinou.gameassist.data.updater.AppReleaseInfo
import com.kinou.gameassist.data.updater.AppUpdateManager
import com.kinou.gameassist.injector.ShizukuManager
import com.kinou.gameassist.injector.ShizukuStatus
import com.kinou.gameassist.service.OverlayService
import com.kinou.gameassist.ui.theme.*
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    profiles: List<GameProfile>,
    selectedProfile: GameProfile?,
    onSelectProfile: (GameProfile) -> Unit,
    onStartService: (GameProfile) -> Unit,
    onStopService: () -> Unit,
    isServiceRunning: Boolean,
    onNavigateToTest: () -> Unit,
    onNavigateToProfiles: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val shizukuStatus by ShizukuManager.status.collectAsState()
    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val currentLang by LanguageManager.currentLanguageFlow.collectAsState()

    // Live Gamepad Detection State
    var connectedGamepads by remember { mutableStateOf(GamepadDetector.getConnectedGamepads(context)) }

    // Periodic check for gamepads & permissions
    LaunchedEffect(Unit) {
        while (true) {
            connectedGamepads = GamepadDetector.getConnectedGamepads(context)
            hasOverlayPermission = Settings.canDrawOverlays(context)
            delay(1500)
        }
    }

    // In-App Updater State
    val currentVersion = remember {
        try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }
    var updateInfo by remember { mutableStateOf<AppReleaseInfo?>(null) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }

    // In-App Updater Download & Install States
    var isDownloadingUpdate by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadedBytes by remember { mutableLongStateOf(0L) }
    var totalBytes by remember { mutableLongStateOf(0L) }
    var downloadedApkFile by remember { mutableStateOf<File?>(null) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }

    // Automatic silent check on launch
    LaunchedEffect(Unit) {
        val release = AppUpdateManager.checkForUpdate(currentVersion)
        if (release != null && release.isNewer) {
            updateInfo = release
            showUpdateDialog = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // App Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "OpenMapper",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = NeonCyan
                )
                Text(
                    text = stringResource(R.string.subtitle_native_mapper, currentVersion),
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                // Language Dropdown Selector (FR, EN, ES, PT)
                var showLanguageMenu by remember { mutableStateOf(false) }
                val currentTag = LanguageManager.getCurrentDisplayTag(context)
                val currentFlag = LanguageManager.getCurrentFlag(context)
                val currentLangCode = LanguageManager.getCurrentLanguage(context)

                Box {
                    Surface(
                        onClick = { showLanguageMenu = true },
                        shape = RoundedCornerShape(12.dp),
                        color = DarkCard,
                        border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder),
                        modifier = Modifier.height(40.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(currentFlag, fontSize = 14.sp)
                            Text(
                                text = currentTag,
                                color = NeonCyan,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = showLanguageMenu,
                        onDismissRequest = { showLanguageMenu = false },
                        modifier = Modifier.background(DarkCard)
                    ) {
                        LanguageManager.AVAILABLE_LANGUAGES.forEach { lang ->
                            val isSelected = lang.code == currentLangCode
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(lang.flag, fontSize = 16.sp)
                                        Text(
                                            text = lang.displayName,
                                            color = if (isSelected) NeonCyan else TextPrimary,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            fontSize = 13.sp
                                        )
                                    }
                                },
                                trailingIcon = {
                                    if (isSelected) {
                                        Icon(Icons.Default.Check, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(16.dp))
                                    }
                                },
                                onClick = {
                                    showLanguageMenu = false
                                    LanguageManager.setLanguage(context, lang.code)
                                }
                            )
                        }
                    }
                }

                // Check Update Button
                IconButton(
                    onClick = {
                        coroutineScope.launch {
                            isCheckingUpdate = true
                            val release = AppUpdateManager.checkForUpdate(currentVersion)
                            isCheckingUpdate = false
                            if (release != null) {
                                updateInfo = release
                                if (release.isNewer) {
                                    showUpdateDialog = true
                                } else {
                                    Toast.makeText(context, context.getString(R.string.update_latest_version, currentVersion), Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Toast.makeText(context, context.getString(R.string.update_check_failed), Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkCard)
                        .border(1.dp, DarkCardBorder, RoundedCornerShape(12.dp))
                ) {
                    if (isCheckingUpdate) {
                        CircularProgressIndicator(
                            color = NeonCyan,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        Icon(Icons.Default.CloudSync, contentDescription = stringResource(R.string.check_update), tint = NeonCyan, modifier = Modifier.size(20.dp))
                    }
                }

                // Gamepad Tester Button
                IconButton(
                    onClick = onNavigateToTest,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkCard)
                        .border(1.dp, DarkCardBorder, RoundedCornerShape(12.dp))
                ) {
                    Icon(Icons.Default.SportsEsports, contentDescription = stringResource(R.string.test_gamepad), tint = NeonCyan, modifier = Modifier.size(20.dp))
                }
            }
        }

        // Update Notification Banner (if update available)
        AnimatedVisibility(visible = updateInfo?.isNewer == true && !showUpdateDialog) {
            updateInfo?.let { release ->
                Surface(
                    color = NeonCyan.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyan),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showUpdateDialog = true }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.NewReleases, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(24.dp))
                            Column {
                                Text(stringResource(R.string.update_available_title, release.tagName), fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 13.sp)
                                Text(stringResource(R.string.update_available_desc), color = TextSecondary, fontSize = 11.sp)
                            }
                        }
                        Icon(Icons.Default.Download, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        // ==========================================
        // WIDGET MANETTE CONNECTÉE & BATTERIE
        // ==========================================
        ConnectedGamepadWidget(
            gamepads = connectedGamepads,
            onTestClick = onNavigateToTest,
            onOpenBluetooth = {
                try {
                    val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(context, context.getString(R.string.bluetooth_open_error), Toast.LENGTH_SHORT).show()
                }
            }
        )

        // 1. Shizuku Status Card
        StatusCard(
            title = stringResource(R.string.shizuku_title),
            subtitle = when (shizukuStatus) {
                ShizukuStatus.RUNNING_AUTHORIZED -> stringResource(R.string.shizuku_authorized)
                ShizukuStatus.RUNNING_UNAUTHORIZED -> stringResource(R.string.shizuku_unauthorized)
                ShizukuStatus.DEAD -> stringResource(R.string.shizuku_dead)
            },
            statusColor = when (shizukuStatus) {
                ShizukuStatus.RUNNING_AUTHORIZED -> NeonGreen
                ShizukuStatus.RUNNING_UNAUTHORIZED -> NeonOrange
                ShizukuStatus.DEAD -> NeonPink
            },
            icon = Icons.Default.Security,
            actionButtonText = if (shizukuStatus == ShizukuStatus.RUNNING_UNAUTHORIZED) stringResource(R.string.btn_authorize) else if (shizukuStatus == ShizukuStatus.DEAD) stringResource(R.string.btn_shizuku_help) else null,
            onActionClick = {
                if (shizukuStatus == ShizukuStatus.RUNNING_UNAUTHORIZED) {
                    ShizukuManager.requestPermission()
                } else if (shizukuStatus == ShizukuStatus.DEAD) {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/guide/setup/"))
                    context.startActivity(intent)
                }
            }
        )

        // 2. Overlay Permission Card
        if (!hasOverlayPermission) {
            StatusCard(
                title = stringResource(R.string.overlay_perm_title),
                subtitle = stringResource(R.string.overlay_perm_desc),
                statusColor = NeonOrange,
                icon = Icons.Default.Layers,
                actionButtonText = stringResource(R.string.btn_enable),
                onActionClick = {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                    context.startActivity(intent)
                }
            )
        }

        // 3. Profile Selection Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkCard),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.active_profile_label), fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    Text(
                        stringResource(R.string.edit_profile_action),
                        color = NeonCyan,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { onNavigateToProfiles() }
                    )
                }

                selectedProfile?.let { prof ->
                    Surface(
                        color = DarkSurface,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(Icons.Default.Gamepad, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(32.dp))
                            Column {
                                Text(prof.name, fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 16.sp)
                                Text(prof.packageName, color = TextSecondary, fontSize = 12.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }

        // Quick Game Launcher (when service is running)
        if (isServiceRunning && selectedProfile != null) {
            OutlinedButton(
                onClick = {
                    try {
                        val launchIntent = context.packageManager.getLaunchIntentForPackage(selectedProfile.packageName)
                        if (launchIntent != null) {
                            context.startActivity(launchIntent)
                        } else {
                            Toast.makeText(context, "${selectedProfile.packageName} not installed", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(context, "Cannot launch app", Toast.LENGTH_SHORT).show()
                    }
                },
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, NeonGreen),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.PlayCircle, contentDescription = null, tint = NeonGreen, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("${selectedProfile.name}", color = NeonGreen, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Big Launch Action Button
        val canStart = shizukuStatus == ShizukuStatus.RUNNING_AUTHORIZED && hasOverlayPermission && selectedProfile != null

        Button(
            onClick = {
                if (isServiceRunning) {
                    onStopService()
                } else if (selectedProfile != null) {
                    onStartService(selectedProfile)
                }
            },
            enabled = isServiceRunning || canStart,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isServiceRunning) NeonPink else NeonCyan,
                disabledContainerColor = DarkCardBorder
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    if (isServiceRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = if (isServiceRunning) Color.White else Color.Black
                )
                Text(
                    text = if (isServiceRunning) stringResource(R.string.btn_stop_overlay) else stringResource(R.string.btn_launch_overlay),
                    color = if (isServiceRunning) Color.White else Color.Black,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }
    }

    // Update Dialog
    if (showUpdateDialog && updateInfo != null) {
        val release = updateInfo!!
        val hasInstallPerm = AppUpdateManager.canInstallPackages(context)
        val isApkDownloaded = downloadedApkFile?.exists() == true

        AlertDialog(
            onDismissRequest = {
                if (!isDownloadingUpdate) {
                    showUpdateDialog = false
                }
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = NeonCyan)
                    Text(stringResource(R.string.update_dialog_title, release.tagName), fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 16.sp)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        color = DarkSurface,
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyan.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(release.title, fontWeight = FontWeight.Bold, color = NeonCyan, fontSize = 13.sp)
                            if (release.changelog.isNotBlank()) {
                                Text(
                                    release.changelog.take(280) + if (release.changelog.length > 280) "..." else "",
                                    color = TextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    Text("v$currentVersion ➔ ${release.tagName}", color = TextMuted, fontSize = 11.sp)

                    // Download Progress Section
                    if (isDownloadingUpdate) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                            LinearProgressIndicator(
                                progress = { downloadProgress },
                                color = NeonCyan,
                                trackColor = DarkCardBorder,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val pct = (downloadProgress * 100).toInt().coerceIn(0, 100)
                                Text(
                                    stringResource(
                                        R.string.update_dialog_downloading_progress,
                                        pct,
                                        AppUpdateManager.formatFileSize(downloadedBytes),
                                        if (totalBytes > 0) AppUpdateManager.formatFileSize(totalBytes) else "-- MB"
                                    ),
                                    color = NeonCyan,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    } else if (isApkDownloaded) {
                        Surface(
                            color = if (hasInstallPerm) NeonGreen.copy(alpha = 0.12f) else NeonOrange.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (hasInstallPerm) NeonGreen else NeonOrange),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    if (hasInstallPerm) Icons.Default.CheckCircle else Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = if (hasInstallPerm) NeonGreen else NeonOrange,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    if (hasInstallPerm) stringResource(R.string.update_dialog_ready_install) else stringResource(R.string.update_dialog_permission_needed),
                                    color = TextPrimary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    } else if (downloadError != null) {
                        Surface(
                            color = NeonPink.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, NeonPink),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.Error, contentDescription = null, tint = NeonPink, modifier = Modifier.size(18.dp))
                                Text(
                                    stringResource(R.string.update_download_failed, downloadError!!),
                                    color = TextPrimary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (isDownloadingUpdate) {
                    // Downloading in progress - no confirm button
                } else if (isApkDownloaded) {
                    if (!hasInstallPerm) {
                        Button(
                            onClick = {
                                AppUpdateManager.openInstallPermissionSettings(context)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonOrange)
                        ) {
                            Icon(Icons.Default.Security, contentDescription = null, tint = DarkBackground, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.update_dialog_grant_permission), color = DarkBackground, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    } else {
                        Button(
                            onClick = {
                                val res = AppUpdateManager.installApk(context, downloadedApkFile!!)
                                if (res.isFailure) {
                                    Toast.makeText(context, "Erreur install: ${res.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonGreen)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, tint = DarkBackground, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.update_dialog_btn_install_now), color = DarkBackground, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                } else {
                    Button(
                        onClick = {
                            downloadJob = coroutineScope.launch {
                                isDownloadingUpdate = true
                                downloadProgress = 0f
                                downloadedBytes = 0L
                                totalBytes = release.apkFileSize
                                downloadError = null

                                val res = AppUpdateManager.downloadApk(
                                    context = context,
                                    downloadUrl = release.downloadUrl,
                                    targetFileName = release.apkFileName
                                ) { progress, dBytes, tBytes ->
                                    downloadProgress = progress
                                    downloadedBytes = dBytes
                                    totalBytes = tBytes
                                }
                                isDownloadingUpdate = false

                                res.onSuccess { apkFile ->
                                    downloadedApkFile = apkFile
                                    if (AppUpdateManager.canInstallPackages(context)) {
                                        AppUpdateManager.installApk(context, apkFile)
                                    }
                                }.onFailure { err ->
                                    downloadError = err.localizedMessage ?: err.message ?: "Erreur"
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, tint = DarkBackground, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.update_dialog_install), color = DarkBackground, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            },
            dismissButton = {
                if (isDownloadingUpdate) {
                    TextButton(onClick = {
                        downloadJob?.cancel()
                        isDownloadingUpdate = false
                    }) {
                        Text(stringResource(R.string.update_dialog_cancel), color = NeonPink)
                    }
                } else {
                    TextButton(onClick = { showUpdateDialog = false }) {
                        Text(stringResource(R.string.update_dialog_later), color = TextSecondary)
                    }
                }
            },
            containerColor = DarkCard
        )
    }
}

@Composable
fun ConnectedGamepadWidget(
    gamepads: List<GamepadDevice>,
    onTestClick: () -> Unit,
    onOpenBluetooth: () -> Unit
) {
    val isConnected = gamepads.isNotEmpty()
    val gamepad = gamepads.firstOrNull()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isConnected) NeonGreen.copy(alpha = 0.7f) else DarkCardBorder
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isConnected) NeonGreen.copy(alpha = 0.15f) else DarkCardBorder.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.SportsEsports,
                    contentDescription = null,
                    tint = if (isConnected) NeonGreen else TextSecondary,
                    modifier = Modifier.size(24.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isConnected) NeonGreen else NeonOrange)
                    )
                    Text(
                        if (isConnected) (gamepad?.name?.take(22) ?: stringResource(R.string.gamepad_connected)) else stringResource(R.string.gamepad_disconnected),
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        fontSize = 14.sp,
                        maxLines = 1
                    )
                }

                if (isConnected && gamepad != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Text(
                            if (gamepad.isBluetooth) "📶 Bluetooth" else "🔌 USB OTG",
                            color = NeonCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                        if (gamepad.batteryPercent != null) {
                            Text(
                                "🔋 ${gamepad.batteryPercent}%",
                                color = NeonGreen,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else {
                    Text(
                        stringResource(R.string.gamepad_widget_none_desc),
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
            }

            if (isConnected) {
                OutlinedButton(
                    onClick = onTestClick,
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyan),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Text(stringResource(R.string.gamepad_btn_test), color = NeonCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = onOpenBluetooth,
                    colors = ButtonDefaults.buttonColors(containerColor = DarkCardBorder),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Text(stringResource(R.string.gamepad_btn_bluetooth), color = TextPrimary, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun StatusCard(
    title: String,
    subtitle: String,
    statusColor: Color,
    icon: ImageVector,
    actionButtonText: String? = null,
    onActionClick: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(statusColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = statusColor, modifier = Modifier.size(24.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                Text(subtitle, color = TextSecondary, fontSize = 11.sp)
            }

            if (actionButtonText != null && onActionClick != null) {
                Button(
                    onClick = onActionClick,
                    colors = ButtonDefaults.buttonColors(containerColor = statusColor),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(actionButtonText, color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }
}
