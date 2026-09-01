package com.kinou.gameassist.ui.screens

import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.kinou.gameassist.R
import com.kinou.gameassist.data.model.GameProfile
import com.kinou.gameassist.data.repository.ScreenshotManager
import com.kinou.gameassist.ui.overlay.HudEditorOverlayView
import com.kinou.gameassist.ui.theme.DarkBackground
import kotlinx.coroutines.launch

@Composable
fun VisualHudEditorScreen(
    profile: GameProfile,
    onSaveProfile: (GameProfile) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    // Chargement asynchrone du Bitmap HUD sur IO pour éviter un stall de 80-250ms sur le Main Thread.
    // On commence avec null (affichage vide) puis on met à jour dès que le chargement IO est terminé.
    var screenshotBitmap by remember(profile.id, profile.customScreenshotPath) {
        mutableStateOf<android.graphics.Bitmap?>(null)
    }
    LaunchedEffect(profile.id, profile.customScreenshotPath) {
        val loaded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            ScreenshotManager.loadScreenshotBitmap(context, profile.customScreenshotPath)
        }
        screenshotBitmap = loaded
    }

    var editorViewRef by remember { mutableStateOf<HudEditorOverlayView?>(null) }

    // Force landscape orientation to match the exact game screen aspect ratio
    DisposableEffect(Unit) {
        val previousOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            activity?.requestedOrientation = previousOrientation
            editorViewRef?.releaseBitmap()
            screenshotBitmap = null
        }
    }

    // System Photo Picker launcher
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val savedPath = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    ScreenshotManager.saveScreenshotFromUri(context, profile.id, uri)
                }
                if (savedPath != null) {
                    profile.customScreenshotPath = savedPath
                    onSaveProfile(profile)
                    Toast.makeText(context, context.getString(R.string.screenshot_imported_toast), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, context.getString(R.string.screenshot_import_error), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    BackHandler {
        onSaveProfile(profile)
        onBack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                HudEditorOverlayView(
                    context = ctx,
                    profile = profile,
                    onSave = { updatedProfile ->
                        onSaveProfile(updatedProfile)
                        Toast.makeText(ctx, ctx.getString(R.string.profile_copied_toast).replace("copié dans le presse-papier", "sauvegardé"), Toast.LENGTH_SHORT).show()
                    },
                    onClose = {
                        onSaveProfile(profile)
                        onBack()
                    },
                    onOpenGallery = {
                        photoPickerLauncher.launch("image/*")
                    },
                    onRemoveScreenshot = {
                        ScreenshotManager.deleteScreenshot(ctx, profile.customScreenshotPath)
                        profile.customScreenshotPath = null
                        onSaveProfile(profile)
                        Toast.makeText(ctx, ctx.getString(R.string.screenshot_removed_toast), Toast.LENGTH_SHORT).show()
                    }
                ).apply {
                    setScreenshot(screenshotBitmap)
                    editorViewRef = this
                }
            },
            update = { view ->
                view.setScreenshot(screenshotBitmap)
            }
        )
    }
}
