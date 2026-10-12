package com.kinou.gameassist.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import com.kinou.gameassist.data.model.ResponseCurve
import com.kinou.gameassist.data.repository.ProfileRepository
import com.kinou.gameassist.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.pow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditorScreen(
    profiles: List<GameProfile>,
    currentProfile: GameProfile?,
    onActivate: (GameProfile) -> Unit,
    onEdit: (GameProfile) -> Unit,
    onSaveProfile: (GameProfile) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onDuplicateProfile: (GameProfile) -> Unit,
    onCreateProfile: ((name: String, targetPackage: String) -> Unit)? = null,
    onImportProfile: (String, (Boolean) -> Unit) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var profileToDelete by remember { mutableStateOf<GameProfile?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var importJsonText by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Tune, contentDescription = null, tint = NeonCyan)
                        Text(stringResource(R.string.profiles_title), fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    if (onCreateProfile != null) {
                        TextButton(onClick = { showCreateDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = NeonGreen)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.btn_new_profile), color = NeonGreen, fontWeight = FontWeight.Bold)
                        }
                    }
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
                    titleContentColor = TextPrimary
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
                ProfileCard(
                    profile = prof,
                    isActive = prof.id == currentProfile?.id,
                    canDelete = profiles.size > 1,
                    onActivate = { onActivate(prof) },
                    onEdit = { onEdit(prof) },
                    onDuplicate = { onDuplicateProfile(prof) },
                    onDelete = { profileToDelete = prof },
                    onExport = {
                        val json = ProfileRepository.getInstance(context).exportProfileToJson(prof)

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
                                onImportProfile(importJsonText.trim()) { ok ->
                                    if (ok) {
                                        Toast.makeText(context, context.getString(R.string.profile_imported_toast), Toast.LENGTH_SHORT).show()
                                        showImportDialog = false
                                    } else {
                                        Toast.makeText(context, context.getString(R.string.profile_import_error), Toast.LENGTH_LONG).show()
                                    }
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

        // Create Profile Dialog
        if (showCreateDialog) {
            CreateProfileDialog(
                onDismiss = { showCreateDialog = false },
                onConfirm = { name, pkg ->
                    showCreateDialog = false
                    onCreateProfile?.invoke(name, pkg)
                    Toast.makeText(context, context.getString(R.string.profile_created_toast), Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}

@Composable
fun ProfileCard(
    profile: GameProfile,
    isActive: Boolean,
    canDelete: Boolean,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    var showActions by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (isActive) DarkCard else DarkSurface),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isActive) NeonCyan else DarkCardBorder
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.name,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    fontSize = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = profile.packageName,
                    color = TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.buttons_count_configured, profile.buttons.size),
                    color = NeonCyan,
                    fontSize = 12.sp
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Button(
                    onClick = onActivate,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isActive) NeonCyan else DarkCardBorder
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Text(
                        if (isActive) stringResource(R.string.btn_active) else stringResource(R.string.btn_select),
                        color = if (isActive) DarkBackground else TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }

                IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = stringResource(R.string.btn_edit_profile),
                        tint = NeonCyan,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Box {
                    IconButton(onClick = { showActions = true }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.profile_actions),
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = showActions,
                        onDismissRequest = { showActions = false },
                        modifier = Modifier.background(DarkCard)
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.btn_duplicate), color = TextPrimary) },
                            leadingIcon = {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(18.dp))
                            },
                            onClick = {
                                showActions = false
                                onDuplicate()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.btn_export), color = TextPrimary) },
                            leadingIcon = {
                                Icon(Icons.Default.Share, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp))
                            },
                            onClick = {
                                showActions = false
                                onExport()
                            }
                        )
                        if (canDelete) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.btn_delete), color = NeonPink) },
                                leadingIcon = {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = NeonPink, modifier = Modifier.size(18.dp))
                                },
                                onClick = {
                                    showActions = false
                                    onDelete()
                                }
                            )
                        }
                    }
                }
            }
        }
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
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)? = null
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
            onValueChangeFinished = onValueChangeFinished,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = NeonCyan,
                activeTrackColor = NeonCyan,
                inactiveTrackColor = DarkCardBorder
            )
        )
    }
}

data class InstalledAppItem(
    val label: String,
    val packageName: String
)

fun getInstalledLauncherApps(context: Context): List<InstalledAppItem> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN, null).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
    }
    return try {
        val resolveInfos = pm.queryIntentActivities(intent, 0)
        resolveInfos.mapNotNull { ri ->
            val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == context.packageName) return@mapNotNull null // Don't list OpenMapper itself
            val label = ri.loadLabel(pm)?.toString() ?: pkg
            InstalledAppItem(label, pkg)
        }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * Charge la liste des applications lanceur hors du thread UI.
 *
 * `PackageManager.queryIntentActivities()` et `ResolveInfo.loadLabel()` sont des requêtes
 * potentiellement lentes (dizaines à plusieurs centaines de ms sur un appareil chargé). Les
 * exécuter synchroniquement dans `remember {}` bloque le thread principal et peut déclencher
 * un ANR à l'ouverture du sélecteur de jeu.
 */
@Composable
private fun rememberInstalledLauncherApps(context: Context): List<InstalledAppItem> {
    val apps by produceState(initialValue = emptyList<InstalledAppItem>(), context) {
        value = withContext(Dispatchers.IO) { getInstalledLauncherApps(context) }
    }
    return apps
}

@Composable
fun SelectGamePackageDialog(
    currentPackage: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val context = LocalContext.current
    val installedApps = rememberInstalledLauncherApps(context)
    var searchQuery by remember { mutableStateOf("") }
    var customPackageText by remember { mutableStateOf(currentPackage) }

    val filteredApps = remember(searchQuery, installedApps) {
        if (searchQuery.isBlank()) installedApps
        else installedApps.filter {
            it.label.contains(searchQuery, ignoreCase = true) ||
            it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.SportsEsports, contentDescription = null, tint = NeonCyan)
                Text(
                    stringResource(R.string.select_game_app_title),
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    fontSize = 16.sp
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    stringResource(R.string.select_game_app_desc),
                    color = TextSecondary,
                    fontSize = 12.sp
                )

                // Search field
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(stringResource(R.string.search_installed_apps_placeholder), fontSize = 12.sp, color = TextSecondary) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = DarkCardBorder
                    ),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = TextPrimary)
                )

                // Installed apps scrollable list
                Surface(
                    color = DarkBackground,
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                ) {
                    if (filteredApps.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                stringResource(R.string.no_installed_apps_found),
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize().padding(4.dp)) {
                            items(filteredApps, key = { it.packageName }) { app ->
                                val isSelected = app.packageName == customPackageText
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) NeonCyan.copy(alpha = 0.15f) else Color.Transparent)
                                        .clickable { customPackageText = app.packageName }
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            app.label,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) NeonCyan else TextPrimary,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            app.packageName,
                                            color = TextSecondary,
                                            fontSize = 10.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (isSelected) {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = NeonCyan,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Custom or selected package field
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.custom_package_label),
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                    OutlinedTextField(
                        value = customPackageText,
                        onValueChange = { customPackageText = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NeonCyan,
                            unfocusedBorderColor = DarkCardBorder
                        ),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = TextPrimary)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val pkg = customPackageText.trim()
                    if (pkg.isNotBlank()) {
                        onConfirm(pkg)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)
            ) {
                Text(stringResource(R.string.btn_save), color = DarkBackground, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel), color = TextSecondary)
            }
        },
        containerColor = DarkCard
    )
}

@Composable
fun CreateProfileDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, targetPackage: String) -> Unit
) {
    val context = LocalContext.current
    val installedApps = rememberInstalledLauncherApps(context)
    var profileNameText by remember { mutableStateOf("") }
    var selectedPackageText by remember { mutableStateOf("com.game.app") }
    var searchQuery by remember { mutableStateOf("") }

    val filteredApps = remember(searchQuery, installedApps) {
        if (searchQuery.isBlank()) installedApps
        else installedApps.filter {
            it.label.contains(searchQuery, ignoreCase = true) ||
            it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.AddCircle, contentDescription = null, tint = NeonGreen)
                Text(
                    stringResource(R.string.create_profile_title),
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    fontSize = 16.sp
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Profile Name
                Text(
                    stringResource(R.string.rename_profile_label),
                    color = TextSecondary,
                    fontSize = 12.sp
                )
                OutlinedTextField(
                    value = profileNameText,
                    onValueChange = { profileNameText = it },
                    placeholder = { Text("Mon Jeu", fontSize = 13.sp, color = TextSecondary) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonGreen,
                        unfocusedBorderColor = DarkCardBorder
                    ),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = TextPrimary)
                )

                // Choose Target Game
                Text(
                    stringResource(R.string.select_game_app_title),
                    color = TextSecondary,
                    fontSize = 12.sp
                )

                // Search field
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(stringResource(R.string.search_installed_apps_placeholder), fontSize = 11.sp, color = TextSecondary) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp)) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonGreen,
                        unfocusedBorderColor = DarkCardBorder
                    ),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = TextPrimary)
                )

                // Installed apps list
                Surface(
                    color = DarkBackground,
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                ) {
                    if (filteredApps.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                stringResource(R.string.no_installed_apps_found),
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize().padding(4.dp)) {
                            items(filteredApps, key = { it.packageName }) { app ->
                                val isSelected = app.packageName == selectedPackageText
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) NeonGreen.copy(alpha = 0.15f) else Color.Transparent)
                                        .clickable {
                                            selectedPackageText = app.packageName
                                            if (profileNameText.isBlank()) {
                                                profileNameText = app.label
                                            }
                                        }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            app.label,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) NeonGreen else TextPrimary,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            app.packageName,
                                            color = TextSecondary,
                                            fontSize = 10.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (isSelected) {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = NeonGreen,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Custom package name input
                OutlinedTextField(
                    value = selectedPackageText,
                    onValueChange = { selectedPackageText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonGreen,
                        unfocusedBorderColor = DarkCardBorder
                    ),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = TextPrimary)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalName = if (profileNameText.isNotBlank()) profileNameText.trim() else "Nouveau Profil"
                    val finalPkg = if (selectedPackageText.isNotBlank()) selectedPackageText.trim() else "com.game.app"
                    onConfirm(finalName, finalPkg)
                },
                colors = ButtonDefaults.buttonColors(containerColor = NeonGreen)
            ) {
                Text(stringResource(R.string.btn_add), color = DarkBackground, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel), color = TextSecondary)
            }
        },
        containerColor = DarkCard
    )
}
