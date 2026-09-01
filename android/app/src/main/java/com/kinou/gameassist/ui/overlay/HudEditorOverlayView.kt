package com.kinou.gameassist.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import com.kinou.gameassist.R
import com.kinou.gameassist.data.model.ButtonConfig
import com.kinou.gameassist.data.model.ButtonMode
import com.kinou.gameassist.data.model.ButtonRole
import com.kinou.gameassist.data.model.GameProfile
import com.kinou.gameassist.data.repository.ScreenshotManager
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

@SuppressLint("ViewConstructor")
class HudEditorOverlayView(
    context: Context,
    private val profile: GameProfile,
    private val onSave: (GameProfile) -> Unit,
    private val onClose: () -> Unit,
    private val onOpenGallery: (() -> Unit)? = null,
    private val onRemoveScreenshot: (() -> Unit)? = null
) : View(context) {

    private val density = resources.displayMetrics.density

    // Screenshot Bitmap for In-App & Overlay visual layout
    private var screenshotBitmap: Bitmap? = null
    private val bitmapSrcRect = Rect()
    private val bitmapDstRect = RectF()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun setScreenshot(bitmap: Bitmap?) {
        screenshotBitmap = bitmap
        invalidate()
    }

    // Selection & Drag State
    // selectedItem can be ButtonConfig, "JOYSTICK", "CAMERA", or null
    var selectedItem: Any? = null
    val selectedButton: ButtonConfig? get() = selectedItem as? ButtonConfig
    val isJoystickSelected: Boolean get() = selectedItem == "JOYSTICK"
    val isCameraSelected: Boolean get() = selectedItem == "CAMERA"

    private enum class CamHandle { NONE, BODY, NW, NE, SW, SE }
    private var activeCamHandle = CamHandle.NONE
    private var draggedItem: Any? = null
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    var isLearning = false

    // Opacity & Details Bar
    private val opacitySteps = floatArrayOf(0.40f, 0.65f, 0.15f, 0.0f)
    private var opacityIndex = 0
    var showDetails = true
    private val currentBgAlpha: Float
        get() = opacitySteps[opacityIndex]

    // Gamepad Live State
    private val activeGamepadKeys = mutableSetOf<String>()
    private var liveLsX = 0f
    private var liveLsY = 0f
    private var liveRsX = 0f
    private var liveRsY = 0f

    // Scale Gesture Detector (Pinch-to-Resize)
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val factor = detector.scaleFactor
            when (val item = selectedItem) {
                is ButtonConfig -> {
                    item.radius = (item.radius * factor).coerceIn(0.020f, 0.120f)
                    invalidate()
                    return true
                }
                "JOYSTICK" -> {
                    profile.joystick.radius = (profile.joystick.radius * factor).coerceIn(0.050f, 0.260f)
                    invalidate()
                    return true
                }
                "CAMERA" -> {
                    val cam = profile.camera
                    val cx = (cam.rectX1 + cam.rectX2) / 2f
                    val cy = (cam.rectY1 + cam.rectY2) / 2f
                    val halfW = ((cam.rectX2 - cam.rectX1) / 2f * factor).coerceIn(0.06f, 0.48f)
                    val halfH = ((cam.rectY2 - cam.rectY1) / 2f * factor).coerceIn(0.06f, 0.48f)
                    cam.rectX1 = (cx - halfW).coerceIn(0.01f, 0.90f)
                    cam.rectX2 = (cx + halfW).coerceIn(cam.rectX1 + 0.06f, 0.99f)
                    cam.rectY1 = (cy - halfH).coerceIn(0.02f, 0.90f)
                    cam.rectY2 = (cy + halfH).coerceIn(cam.rectY1 + 0.06f, 0.98f)
                    invalidate()
                    return true
                }
            }
            return false
        }
    })

    // Paints
    private val bgPaint = Paint().apply {
        style = Paint.Style.FILL
    }

    private val btnNormalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAA00F0FF.toInt() // Neon Cyan Translucent
        style = Paint.Style.FILL
    }

    private val btnSelectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xDDFF0055.toInt() // Neon Pink Selected
        style = Paint.Style.FILL
    }

    private val btnLivePressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xEE00FF66.toInt() // Neon Green Live Active
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 24f * (resources.displayMetrics.density / 2.75f).coerceIn(0.85f, 1.35f)
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    private val joyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x4400FF66.toInt()
        style = Paint.Style.FILL
    }

    private val joyStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00FF66.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val camPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x22FFAA00.toInt()
        style = Paint.Style.FILL
    }

    private val camStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFAA00.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(15f, 15f), 0f)
    }

    private val camSelectedStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00F0FF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 4.5f
    }

    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00F0FF.toInt()
        style = Paint.Style.FILL
    }

    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    // Reusable Paints (Zero allocation onDraw)
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFAA00.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val selectRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00F0FF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val stickLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAA00FF66.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00FF66.toInt()
        style = Paint.Style.FILL
    }
    private val livePulseRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00FF66.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }
    private val selectionRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00FF66.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 15f
        textAlign = Paint.Align.CENTER
    }
    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt()
        textSize = 13f
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val roleBadgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val roleBadgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 11f * density
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val showBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE60D131D.toInt()
        style = Paint.Style.FILL
    }
    private val showStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00F0FF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val showTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00F0FF.toInt()
        textSize = 14f * density
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val bannerBgPaint = Paint().apply {
        color = 0xEEFF0055.toInt()
        style = Paint.Style.FILL
    }
    private val bannerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14f * density
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val barBgPaint = Paint().apply {
        color = 0xF20A0E14.toInt()
        style = Paint.Style.FILL
    }
    private val uiBtnBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val uiBtnStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }
    private val uiBtnTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val contextCardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xF20F151F.toInt()
        style = Paint.Style.FILL
    }
    private val contextCardStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00F0FF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val contextLabelPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        textSize = 14f * density
        color = 0xFF00F0FF.toInt()
        isFakeBoldText = true
    }

    // Top Bar Visibility & Action Button Bounds
    var isTopBarHidden = false
    private val btnCloseRect = RectF()
    private val btnSaveRect = RectF()
    private val btnHideRect = RectF()
    private val btnOpacRect = RectF()
    private val btnDetailsRect = RectF()
    private val btnAddRect = RectF()
    private val btnShotRect = RectF()
    private val btnRemRect = RectF()
    private val btnShowRect = RectF()

    // Draggable Floating "Outils" Button State
    private var showBtnNormX = -1f
    private var showBtnNormY = -1f
    private var isDraggingShowBtn = false
    private var showBtnDragStartX = 0f
    private var showBtnDragStartY = 0f
    private var hasShowBtnMoved = false

    // Bottom Context Bar Action Button Bounds
    private val btnDelRect = RectF()
    private val btnNudgeRightRect = RectF()
    private val btnNudgeDownRect = RectF()
    private val btnNudgeUpRect = RectF()
    private val btnNudgeLeftRect = RectF()
    private val btnSizeIncRect = RectF()
    private val btnSizeDecRect = RectF()
    private val btnRoleRect = RectF()
    private val btnModeRect = RectF()
    private val btnAssignRect = RectF()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isHapticFeedbackEnabled = true

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }

    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowInsetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 1. Background Screenshot (if present)
        val bmp = screenshotBitmap
        if (bmp != null && !bmp.isRecycled) {
            try {
                bitmapSrcRect.set(0, 0, bmp.width, bmp.height)
                bitmapDstRect.set(0f, 0f, w, h)
                canvas.drawBitmap(bmp, bitmapSrcRect, bitmapDstRect, bitmapPaint)
            } catch (e: Exception) {
                // Ignore safe rendering error if bitmap was recycled concurrently
            }
        }

        // 2. Background tint (respects opacity)
        if (currentBgAlpha > 0.01f) {
            bgPaint.color = (currentBgAlpha * 255).toInt().shl(24)
            canvas.drawRect(0f, 0f, w, h, bgPaint)
        }

        // 3. Camera Zone
        val cam = profile.camera
        if (cam.enabled) {
            val cx1 = cam.rectX1 * w
            val cy1 = cam.rectY1 * h
            val cx2 = cam.rectX2 * w
            val cy2 = cam.rectY2 * h

            canvas.drawRect(cx1, cy1, cx2, cy2, camPaint)
            if (isCameraSelected) {
                canvas.drawRect(cx1, cy1, cx2, cy2, camSelectedStroke)

                // Draw 4 corner handles
                val handleRadius = 14f * density
                val corners = listOf(
                    PointF(cx1, cy1),
                    PointF(cx2, cy1),
                    PointF(cx1, cy2),
                    PointF(cx2, cy2)
                )
                for (p in corners) {
                    canvas.drawCircle(p.x, p.y, handleRadius, handlePaint)
                    canvas.drawCircle(p.x, p.y, handleRadius, handleStroke)
                }
            } else {
                canvas.drawRect(cx1, cy1, cx2, cy2, camStroke)
            }

            val camTitle = if (isCameraSelected) "🎥 CAMÉRA (SÉLECTIONNÉE - GLISSER / COINS)" else context.getString(R.string.overlay_camera_zone)
            canvas.drawText(camTitle, (cx1 + cx2) / 2f, cy1 + 35f, textPaint)

            // Live Right Stick Feedback (Aiming reticle inside camera zone)
            if (hypot(liveRsX, liveRsY) > 0.08f) {
                val reticleX = (cx1 + cx2) / 2f + liveRsX * ((cx2 - cx1) / 2f) * 0.75f
                val reticleY = (cy1 + cy2) / 2f + liveRsY * ((cy2 - cy1) / 2f) * 0.75f

                canvas.drawCircle(reticleX, reticleY, 20f * density, crossPaint)
                canvas.drawLine(reticleX - 30f, reticleY, reticleX + 30f, reticleY, crossPaint)
                canvas.drawLine(reticleX, reticleY - 30f, reticleX, reticleY + 30f, crossPaint)
            }
        }

        // 4. Joystick Zone
        val joy = profile.joystick
        if (joy.enabled) {
            val jx = joy.centerX * w
            val jy = joy.centerY * h
            val jr = joy.radius * h

            canvas.drawCircle(jx, jy, jr, joyPaint)
            canvas.drawCircle(jx, jy, jr, joyStroke)
            canvas.drawCircle(jx, jy, jr * joy.sprintThreshold, joyStroke)

            if (isJoystickSelected) {
                canvas.drawCircle(jx, jy, jr + 10f, selectRingPaint)
            }

            canvas.drawText(context.getString(R.string.overlay_joystick_zone), jx, jy + 10f, textPaint)

            // Live Left Stick Feedback (Thumbstick knob inside joystick)
            if (hypot(liveLsX, liveLsY) > 0.08f) {
                val thumbX = jx + liveLsX * jr * 0.75f
                val thumbY = jy + liveLsY * jr * 0.75f

                canvas.drawLine(jx, jy, thumbX, thumbY, stickLinePaint)
                canvas.drawCircle(thumbX, thumbY, 18f * density, thumbPaint)
                canvas.drawCircle(thumbX, thumbY, 18f * density, strokePaint)
            }
        }

        // 5. Buttons
        for (b in profile.buttons) {
            val bx = b.x * w
            val by = b.y * h
            val br = b.radius * h

            val isSelected = b == selectedButton
            val isLivePressed = b.gamepadButton in activeGamepadKeys

            val paint = when {
                isLivePressed -> btnLivePressedPaint
                isSelected -> btnSelectedPaint
                else -> btnNormalPaint
            }

            canvas.drawCircle(bx, by, br, paint)
            canvas.drawCircle(bx, by, br, strokePaint)

            if (isLivePressed) {
                canvas.drawCircle(bx, by, br + 12f, livePulseRingPaint)
            } else if (isSelected) {
                canvas.drawCircle(bx, by, br + 8f, selectionRingPaint)
            }

            val displayKey = b.gamepadButton.replace("BUTTON_", "").replace("TRIGGER_", "")
            canvas.drawText(displayKey, bx, by - 6f, textPaint)
            canvas.drawText(b.label, bx, by + 18f, subTextPaint)

            // Mode badge (HOLD or TAP)
            val modeBadge = when (b.mode) {
                ButtonMode.HOLD -> "HOLD"
                ButtonMode.TAP -> "TAP"
            }
            badgeBgPaint.color = when (b.mode) {
                ButtonMode.HOLD -> 0xCC00F0FF.toInt()
                ButtonMode.TAP -> 0xCCFFAA00.toInt()
            }
            canvas.drawRoundRect(bx - 32f, by + br + 4f, bx + 32f, by + br + 22f, 6f, 6f, badgeBgPaint)
            canvas.drawText(modeBadge, bx, by + br + 18f, badgeTextPaint)

            // Role badge (if not normal)
            if (b.role != ButtonRole.NORMAL) {
                val (roleTextStr, roleBgColor) = when (b.role) {
                    ButtonRole.FIRE -> "🔥 TIR" to 0xEEFF0055.toInt()
                    ButtonRole.RELOAD -> "🔄 RECH" to 0xEEFFAA00.toInt()
                    ButtonRole.ADS -> "🎯 ADS" to 0xEE00FF66.toInt()
                    ButtonRole.NORMAL -> "" to 0
                }
                roleBadgeBgPaint.color = roleBgColor
                val badgeW = 34f * density
                val badgeH = 16f * density
                val topY = by - br - badgeH - 2f
                canvas.drawRoundRect(bx - badgeW, topY, bx + badgeW, topY + badgeH, 6f, 6f, roleBadgeBgPaint)
                canvas.drawText(roleTextStr, bx, topY + badgeH - 3.5f * density, roleBadgeTextPaint)
            }
        }

        // 6. Top Bar UI Controls
        drawTopBar(canvas, w)

        // 7. Bottom Contextual Bar (when an item is selected and details are enabled)
        if (selectedItem != null && showDetails) {
            drawBottomContextBar(canvas, w, h)
        }
    }

    private fun drawTopBar(canvas: Canvas, w: Float) {
        val h = height.toFloat()
        if (isTopBarHidden) {
            // Clear top bar action button rects so touches don't hit them
            btnCloseRect.setEmpty()
            btnSaveRect.setEmpty()
            btnHideRect.setEmpty()
            btnOpacRect.setEmpty()
            btnDetailsRect.setEmpty()
            btnAddRect.setEmpty()
            btnShotRect.setEmpty()
            btnRemRect.setEmpty()

            // Draw sleek floating pill to reopen the top bar (Draggable anywhere)
            val showW = 110f * density
            val showH = 38f * density

            if (showBtnNormX < 0f || showBtnNormY < 0f) {
                val defaultRight = w - 16f * density
                val defaultTop = 12f * density
                showBtnNormX = (defaultRight - showW / 2f) / w
                showBtnNormY = (defaultTop + showH / 2f) / h
            }

            val minCenterX = showW / 2f + 4f * density
            val maxCenterX = w - showW / 2f - 4f * density
            val minCenterY = showH / 2f + 4f * density
            val maxCenterY = h - showH / 2f - 4f * density

            val centerX = (showBtnNormX * w).coerceIn(minCenterX, maxCenterX)
            val centerY = (showBtnNormY * h).coerceIn(minCenterY, maxCenterY)

            btnShowRect.set(centerX - showW / 2f, centerY - showH / 2f, centerX + showW / 2f, centerY + showH / 2f)

            canvas.drawRoundRect(btnShowRect, 12f * density, 12f * density, showBgPaint)
            canvas.drawRoundRect(btnShowRect, 12f * density, 12f * density, showStrokePaint)
            canvas.drawText(context.getString(R.string.overlay_btn_show_bar), btnShowRect.centerX(), btnShowRect.centerY() + 5f * density, showTextPaint)

            if (isLearning && selectedButton != null) {
                val bannerH = 40f * density
                canvas.drawRect(0f, 0f, w, bannerH, bannerBgPaint)
                canvas.drawText(
                    context.getString(R.string.overlay_learning_banner, selectedButton?.label ?: ""),
                    w / 2f, bannerH / 2f + 5f * density, bannerTextPaint
                )
            }
            return
        }

        // Expanded Top Bar
        btnShowRect.setEmpty()

        val barH = 54f * density
        val btnTop = 7f * density
        val btnBottom = barH - 7f * density
        val btnRadius = 10f * density
        val btnSpacing = 8f * density

        canvas.drawRect(0f, 0f, w, barH, barBgPaint)

        // 1. Button: 👁️ Masquer (on the far left)
        val hideLabel = context.getString(R.string.overlay_btn_hide_bar)
        uiBtnTextPaint.color = 0xFF00F0FF.toInt()
        uiBtnTextPaint.textSize = 14f * density
        val hideW = max(88f * density, uiBtnTextPaint.measureText(hideLabel) + 20f * density)
        val hideLeft = 16f * density
        btnHideRect.set(hideLeft, btnTop, hideLeft + hideW, btnBottom)
        uiBtnBgPaint.color = 0xFF1E2838.toInt()
        uiBtnStrokePaint.color = 0xFF00F0FF.toInt()
        canvas.drawRoundRect(btnHideRect, btnRadius, btnRadius, uiBtnBgPaint)
        canvas.drawRoundRect(btnHideRect, btnRadius, btnRadius, uiBtnStrokePaint)
        canvas.drawText(hideLabel, btnHideRect.centerX(), btnHideRect.centerY() + 5f * density, uiBtnTextPaint)

        // Right-aligned Buttons
        var curRight = w - 16f * density

        // 2. Button: ✖ Fermer
        val closeLabel = context.getString(R.string.overlay_btn_close)
        uiBtnTextPaint.color = Color.WHITE
        uiBtnTextPaint.textSize = 14.5f * density
        val closeW = max(78f * density, uiBtnTextPaint.measureText(closeLabel) + 20f * density)
        btnCloseRect.set(curRight - closeW, btnTop, curRight, btnBottom)
        uiBtnBgPaint.color = 0xFF333D4D.toInt()
        canvas.drawRoundRect(btnCloseRect, btnRadius, btnRadius, uiBtnBgPaint)
        canvas.drawText(closeLabel, btnCloseRect.centerX(), btnCloseRect.centerY() + 5f * density, uiBtnTextPaint)
        curRight -= (closeW + btnSpacing)

        // 3. Button: 💾 Sauvegarder
        val saveLabel = context.getString(R.string.overlay_btn_save)
        uiBtnTextPaint.color = Color.BLACK
        uiBtnTextPaint.textSize = 14.5f * density
        val saveW = max(84f * density, uiBtnTextPaint.measureText(saveLabel) + 20f * density)
        btnSaveRect.set(curRight - saveW, btnTop, curRight, btnBottom)
        uiBtnBgPaint.color = 0xFF00FF66.toInt()
        canvas.drawRoundRect(btnSaveRect, btnRadius, btnRadius, uiBtnBgPaint)
        canvas.drawText(saveLabel, btnSaveRect.centerX(), btnSaveRect.centerY() + 5f * density, uiBtnTextPaint)
        curRight -= (saveW + btnSpacing)

        // 4. Button: 🎨 Opacité Toggle
        val opacPercent = (opacitySteps[opacityIndex] * 100).toInt()
        val opacLabel = context.getString(R.string.overlay_btn_opacity, opacPercent)
        uiBtnTextPaint.color = Color.WHITE
        uiBtnTextPaint.textSize = 13.5f * density
        val opacW = max(80f * density, uiBtnTextPaint.measureText(opacLabel) + 20f * density)
        btnOpacRect.set(curRight - opacW, btnTop, curRight, btnBottom)
        uiBtnBgPaint.color = 0xFF1E2633.toInt()
        canvas.drawRoundRect(btnOpacRect, btnRadius, btnRadius, uiBtnBgPaint)
        canvas.drawText(opacLabel, btnOpacRect.centerX(), btnOpacRect.centerY() + 5f * density, uiBtnTextPaint)
        curRight -= (opacW + btnSpacing)

        // 5. Button: Details Bar Toggle
        val detailsLabel = context.getString(R.string.overlay_btn_details)
        uiBtnTextPaint.color = if (showDetails) 0xFF00F0FF.toInt() else 0xFF708090.toInt()
        uiBtnTextPaint.textSize = 14f * density
        val detailsW = max(64f * density, uiBtnTextPaint.measureText(detailsLabel) + 20f * density)
        btnDetailsRect.set(curRight - detailsW, btnTop, curRight, btnBottom)
        uiBtnBgPaint.color = if (showDetails) 0xFF243040.toInt() else 0xFF18202A.toInt()
        canvas.drawRoundRect(btnDetailsRect, btnRadius, btnRadius, uiBtnBgPaint)
        canvas.drawText(detailsLabel, btnDetailsRect.centerX(), btnDetailsRect.centerY() + 5f * density, uiBtnTextPaint)
        curRight -= (detailsW + btnSpacing)

        // 6. Button: ➕ Ajouter Bouton
        val addLabel = context.getString(R.string.overlay_btn_add)
        uiBtnTextPaint.color = Color.BLACK
        uiBtnTextPaint.textSize = 14.5f * density
        val addW = max(80f * density, uiBtnTextPaint.measureText(addLabel) + 20f * density)
        btnAddRect.set(curRight - addW, btnTop, curRight, btnBottom)
        uiBtnBgPaint.color = 0xFF00F0FF.toInt()
        canvas.drawRoundRect(btnAddRect, btnRadius, btnRadius, uiBtnBgPaint)
        canvas.drawText(addLabel, btnAddRect.centerX(), btnAddRect.centerY() + 5f * density, uiBtnTextPaint)
        curRight -= (addW + btnSpacing)

        // 7. Button: 📸 Capture / Screenshot (si callback présent)
        if (onOpenGallery != null) {
            val shotLabel = if (screenshotBitmap != null) context.getString(R.string.btn_change_screenshot) else context.getString(R.string.btn_import_screenshot)
            uiBtnTextPaint.color = 0xFF00F0FF.toInt()
            uiBtnTextPaint.textSize = 13.5f * density
            val shotW = max(95f * density, uiBtnTextPaint.measureText(shotLabel) + 22f * density)
            btnShotRect.set(curRight - shotW, btnTop, curRight, btnBottom)
            uiBtnBgPaint.color = 0xFF1E2838.toInt()
            uiBtnStrokePaint.color = 0xFF00F0FF.toInt()
            canvas.drawRoundRect(btnShotRect, btnRadius, btnRadius, uiBtnBgPaint)
            canvas.drawRoundRect(btnShotRect, btnRadius, btnRadius, uiBtnStrokePaint)
            canvas.drawText(shotLabel, btnShotRect.centerX(), btnShotRect.centerY() + 5f * density, uiBtnTextPaint)
            curRight -= (shotW + btnSpacing)
        } else {
            btnShotRect.setEmpty()
        }

        // 8. Button: 🗑️ Retirer Fond (si capture chargée et callback présent)
        if (screenshotBitmap != null && onRemoveScreenshot != null) {
            val remLabel = context.getString(R.string.btn_remove_screenshot)
            uiBtnTextPaint.color = 0xFFFF0055.toInt()
            uiBtnTextPaint.textSize = 13.5f * density
            val remW = max(86f * density, uiBtnTextPaint.measureText(remLabel) + 22f * density)
            btnRemRect.set(curRight - remW, btnTop, curRight, btnBottom)
            uiBtnBgPaint.color = 0x33FF0055.toInt()
            uiBtnStrokePaint.color = 0xFFFF0055.toInt()
            canvas.drawRoundRect(btnRemRect, btnRadius, btnRadius, uiBtnBgPaint)
            canvas.drawRoundRect(btnRemRect, btnRadius, btnRadius, uiBtnStrokePaint)
            canvas.drawText(remLabel, btnRemRect.centerX(), btnRemRect.centerY() + 5f * density, uiBtnTextPaint)
            curRight -= (remW + btnSpacing)
        } else {
            btnRemRect.setEmpty()
        }

        if (isLearning && selectedButton != null) {
            val bannerH = 40f * density
            canvas.drawRect(0f, barH, w, barH + bannerH, bannerBgPaint)
            canvas.drawText(
                context.getString(R.string.overlay_learning_banner, selectedButton?.label ?: ""),
                w / 2f, barH + bannerH / 2f + 5f * density, bannerTextPaint
            )
        }
    }

    private fun drawBottomContextBar(canvas: Canvas, w: Float, h: Float) {
        val barH = 54f * density
        val barTop = h - barH - 12f * density
        val barBottom = h - 12f * density
        val barLeft = 16f * density
        val barRight = w - 16f * density
        val btnTop = barTop + 7f * density
        val btnBottom = barBottom - 7f * density
        val btnRadius = 8f * density
        val btnSpacing = 6f * density

        val cardRect = RectF(barLeft, barTop, barRight, barBottom)
        canvas.drawRoundRect(cardRect, 14f * density, 14f * density, contextCardBgPaint)
        canvas.drawRoundRect(cardRect, 14f * density, 14f * density, contextCardStrokePaint)

        var rightX = barRight - 12f * density

        // 1. Delete (if ButtonConfig)
        if (selectedButton != null) {
            val delW = 84f * density
            btnDelRect.set(rightX - delW, btnTop, rightX, btnBottom)
            uiBtnBgPaint.color = 0xFFFF0055.toInt()
            canvas.drawRoundRect(btnDelRect, btnRadius, btnRadius, uiBtnBgPaint)
            uiBtnTextPaint.color = Color.WHITE
            uiBtnTextPaint.textSize = 13.5f * density
            canvas.drawText("🗑️ Suppr", btnDelRect.centerX(), btnDelRect.centerY() + 5f * density, uiBtnTextPaint)
            rightX -= (delW + btnSpacing)
        } else {
            btnDelRect.setEmpty()
        }

        // 2. Micro-Nudge D-Pad (⬅️ ⬆️ ⬇️ ➡️)
        val nudgeW = 38f * density
        val arrows = listOf(
            Pair(btnNudgeRightRect, "➡️"),
            Pair(btnNudgeDownRect, "⬇️"),
            Pair(btnNudgeUpRect, "⬆️"),
            Pair(btnNudgeLeftRect, "⬅️")
        )
        for ((rect, arrow) in arrows) {
            rect.set(rightX - nudgeW, btnTop, rightX, btnBottom)
            uiBtnBgPaint.color = 0xFF1B2433.toInt()
            uiBtnStrokePaint.color = 0xFF00F0FF.toInt()
            canvas.drawRoundRect(rect, btnRadius, btnRadius, uiBtnBgPaint)
            canvas.drawRoundRect(rect, btnRadius, btnRadius, uiBtnStrokePaint)
            uiBtnTextPaint.color = Color.WHITE
            uiBtnTextPaint.textSize = 14f * density
            canvas.drawText(arrow, rect.centerX(), rect.centerY() + 5f * density, uiBtnTextPaint)
            rightX -= (nudgeW + btnSpacing)
        }

        // 3. Size Buttons (+ / -)
        val sizeW = 44f * density
        btnSizeIncRect.set(rightX - sizeW, btnTop, rightX, btnBottom)
        uiBtnBgPaint.color = 0xFF243040.toInt()
        canvas.drawRoundRect(btnSizeIncRect, btnRadius, btnRadius, uiBtnBgPaint)
        uiBtnTextPaint.color = 0xFF00F0FF.toInt()
        uiBtnTextPaint.textSize = 16f * density
        canvas.drawText("➕", btnSizeIncRect.centerX(), btnSizeIncRect.centerY() + 5f * density, uiBtnTextPaint)
        rightX -= (sizeW + btnSpacing)

        btnSizeDecRect.set(rightX - sizeW, btnTop, rightX, btnBottom)
        canvas.drawRoundRect(btnSizeDecRect, btnRadius, btnRadius, uiBtnBgPaint)
        canvas.drawText("➖", btnSizeDecRect.centerX(), btnSizeDecRect.centerY() + 5f * density, uiBtnTextPaint)
        rightX -= (sizeW + btnSpacing + 4f * density)

        // 4. Button-specific actions: Role, Mode & Assign
        if (selectedButton != null) {
            val btn = selectedButton!!

            // Role Toggle
            val roleLabel = when (btn.role) {
                ButtonRole.FIRE -> "🔥 Tir"
                ButtonRole.RELOAD -> "🔄 Rech."
                ButtonRole.ADS -> "🎯 ADS"
                ButtonRole.NORMAL -> "Normal"
            }
            uiBtnBgPaint.color = when (btn.role) {
                ButtonRole.FIRE -> 0xFFFF0055.toInt()
                ButtonRole.RELOAD -> 0xFFFFAA00.toInt()
                ButtonRole.ADS -> 0xFF00FF66.toInt()
                ButtonRole.NORMAL -> 0xFF243040.toInt()
            }
            val roleW = 86f * density
            btnRoleRect.set(rightX - roleW, btnTop, rightX, btnBottom)
            canvas.drawRoundRect(btnRoleRect, btnRadius, btnRadius, uiBtnBgPaint)
            uiBtnTextPaint.color = if (btn.role == ButtonRole.NORMAL) 0xFF00F0FF.toInt() else Color.BLACK
            uiBtnTextPaint.textSize = 12.5f * density
            canvas.drawText("Rôle: $roleLabel", btnRoleRect.centerX(), btnRoleRect.centerY() + 5f * density, uiBtnTextPaint)
            rightX -= (roleW + btnSpacing)

            // Mode Toggle
            val modeLabel = when (btn.mode) {
                ButtonMode.HOLD -> "HOLD"
                ButtonMode.TAP -> "TAP"
            }
            uiBtnBgPaint.color = when (btn.mode) {
                ButtonMode.HOLD -> 0xFF00F0FF.toInt()
                ButtonMode.TAP -> 0xFFFFAA00.toInt()
            }
            val modeW = 86f * density
            btnModeRect.set(rightX - modeW, btnTop, rightX, btnBottom)
            canvas.drawRoundRect(btnModeRect, btnRadius, btnRadius, uiBtnBgPaint)
            uiBtnTextPaint.color = Color.BLACK
            uiBtnTextPaint.textSize = 13f * density
            canvas.drawText("Mode: $modeLabel", btnModeRect.centerX(), btnModeRect.centerY() + 5f * density, uiBtnTextPaint)
            rightX -= (modeW + btnSpacing)

            // Assign Button (🎮 Assigner)
            uiBtnBgPaint.color = if (isLearning) 0xEEFF0055.toInt() else 0xFF00F0FF.toInt()
            val assignW = 96f * density
            btnAssignRect.set(rightX - assignW, btnTop, rightX, btnBottom)
            canvas.drawRoundRect(btnAssignRect, btnRadius, btnRadius, uiBtnBgPaint)
            uiBtnTextPaint.color = if (isLearning) Color.WHITE else Color.BLACK
            uiBtnTextPaint.textSize = 13.5f * density
            canvas.drawText(if (isLearning) "⏳ Touche..." else "🎮 Assigner", btnAssignRect.centerX(), btnAssignRect.centerY() + 5f * density, uiBtnTextPaint)
            rightX -= (assignW + btnSpacing)
        } else {
            btnRoleRect.setEmpty()
            btnModeRect.setEmpty()
            btnAssignRect.setEmpty()
        }

        // Title on the left of bottom bar
        val titleLeft = barLeft + 16f * density
        if (rightX > titleLeft + 40f * density) {
            val title = when (val item = selectedItem) {
                is ButtonConfig -> "🎯 ${item.label} [${item.gamepadButton.replace("BUTTON_", "")}]"
                "JOYSTICK" -> "🕹️ Joystick LS"
                "CAMERA" -> "🎥 Zone Caméra RS"
                else -> ""
            }
            val availableLabelW = rightX - titleLeft - 8f * density
            val ellipTitle = android.text.TextUtils.ellipsize(
                title,
                contextLabelPaint,
                availableLabelW,
                android.text.TextUtils.TruncateAt.END
            ).toString()
            canvas.drawText(ellipTitle, titleLeft, cardRect.centerY() + 5f * density, contextLabelPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (scaleDetector.isInProgress) {
            draggedItem = null
            invalidate()
            return true
        }

        val w = width.toFloat()
        val h = height.toFloat()
        val tx = event.x
        val ty = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 1. Check Top Bar clicks
                if (isTopBarHidden) {
                    if (btnShowRect.contains(tx, ty)) {
                        isDraggingShowBtn = true
                        showBtnDragStartX = tx
                        showBtnDragStartY = ty
                        hasShowBtnMoved = false
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        return true
                    }
                } else {
                    // Hide Top Bar
                    if (btnHideRect.contains(tx, ty)) {
                        isTopBarHidden = true
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        invalidate()
                        return true
                    }

                    // ✖ Fermer
                    if (btnCloseRect.contains(tx, ty)) {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        onClose()
                        return true
                    }

                    // 💾 Sauvegarder
                    if (btnSaveRect.contains(tx, ty)) {
                        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        onSave(profile)
                        return true
                    }

                    // 🎨 Opacité Toggle
                    if (btnOpacRect.contains(tx, ty)) {
                        opacityIndex = (opacityIndex + 1) % opacitySteps.size
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        invalidate()
                        return true
                    }

                    // 📋 Details Mode
                    if (btnDetailsRect.contains(tx, ty)) {
                        showDetails = !showDetails
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        invalidate()
                        return true
                    }

                    // ➕ Ajouter Bouton
                    if (btnAddRect.contains(tx, ty)) {
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
                        selectedItem = newBtn
                        isLearning = true
                        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        invalidate()
                        return true
                    }

                    // 📸 Capture Screenshot
                    if (onOpenGallery != null && btnShotRect.contains(tx, ty)) {
                        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        onOpenGallery.invoke()
                        return true
                    }

                    // 🗑️ Retirer Fond Screenshot
                    if (screenshotBitmap != null && onRemoveScreenshot != null && btnRemRect.contains(tx, ty)) {
                        performHapticFeedback(HapticFeedbackConstants.REJECT)
                        onRemoveScreenshot.invoke()
                        return true
                    }

                    // If tap is inside the top bar background area (not on a button), consume it
                    val barH = 54f * density
                    if (ty <= barH) {
                        return true
                    }
                }

                // 2. Check Bottom Context Bar clicks (if active and details enabled)
                if (selectedItem != null && showDetails) {
                    // Delete
                    if (selectedButton != null && btnDelRect.contains(tx, ty)) {
                        profile.buttons.remove(selectedButton)
                        selectedItem = null
                        isLearning = false
                        performHapticFeedback(HapticFeedbackConstants.REJECT)
                        invalidate()
                        return true
                    }

                    // Nudge Right (➡️)
                    if (btnNudgeRightRect.contains(tx, ty)) {
                        nudgeSelectedItem(0.003f, 0f)
                        return true
                    }

                    // Nudge Down (⬇️)
                    if (btnNudgeDownRect.contains(tx, ty)) {
                        nudgeSelectedItem(0f, 0.003f)
                        return true
                    }

                    // Nudge Up (⬆️)
                    if (btnNudgeUpRect.contains(tx, ty)) {
                        nudgeSelectedItem(0f, -0.003f)
                        return true
                    }

                    // Nudge Left (⬅️)
                    if (btnNudgeLeftRect.contains(tx, ty)) {
                        nudgeSelectedItem(-0.003f, 0f)
                        return true
                    }

                    // Size Increment (➕)
                    if (btnSizeIncRect.contains(tx, ty)) {
                        adjustSelectedItemSize(1.08f)
                        return true
                    }

                    // Size Decrement (➖)
                    if (btnSizeDecRect.contains(tx, ty)) {
                        adjustSelectedItemSize(0.92f)
                        return true
                    }

                    // Button-specific actions: Role, Mode & Assign
                    if (selectedButton != null) {
                        val btn = selectedButton!!

                        // Role Toggle
                        if (btnRoleRect.contains(tx, ty)) {
                            btn.role = when (btn.role) {
                                ButtonRole.NORMAL -> ButtonRole.FIRE
                                ButtonRole.FIRE -> ButtonRole.RELOAD
                                ButtonRole.RELOAD -> ButtonRole.ADS
                                ButtonRole.ADS -> ButtonRole.NORMAL
                            }
                            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            invalidate()
                            return true
                        }

                        // Mode Toggle
                        if (btnModeRect.contains(tx, ty)) {
                            btn.mode = when (btn.mode) {
                                ButtonMode.HOLD -> ButtonMode.TAP
                                ButtonMode.TAP -> ButtonMode.HOLD
                            }
                            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            invalidate()
                            return true
                        }

                        // Assign Button
                        if (btnAssignRect.contains(tx, ty)) {
                            isLearning = !isLearning
                            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            invalidate()
                            return true
                        }
                    }

                    // If tap inside the bottom bar card, consume it
                    val barH = 54f * density
                    val barTop = h - barH - 12f * density
                    val barBottom = h - 12f * density
                    if (ty in barTop..barBottom && tx in (16f * density)..(w - 16f * density)) {
                        return true
                    }
                }

                // 3. Check Camera Corner Handles (if camera selected)
                if (isCameraSelected) {
                    val cam = profile.camera
                    val cx1 = cam.rectX1 * w
                    val cy1 = cam.rectY1 * h
                    val cx2 = cam.rectX2 * w
                    val cy2 = cam.rectY2 * h
                    val hitRadius = 26f * density

                    val handle = when {
                        hypot(tx - cx1, ty - cy1) <= hitRadius -> CamHandle.NW
                        hypot(tx - cx2, ty - cy1) <= hitRadius -> CamHandle.NE
                        hypot(tx - cx1, ty - cy2) <= hitRadius -> CamHandle.SW
                        hypot(tx - cx2, ty - cy2) <= hitRadius -> CamHandle.SE
                        else -> CamHandle.NONE
                    }

                    if (handle != CamHandle.NONE) {
                        draggedItem = "CAMERA"
                        activeCamHandle = handle
                        dragOffsetX = tx
                        dragOffsetY = ty
                        invalidate()
                        return true
                    }
                }

                // 4. Check Buttons
                for (b in profile.buttons.reversed()) {
                    val bx = b.x * w
                    val by = b.y * h
                    val br = b.radius * h
                    if (hypot(tx - bx, ty - by) <= br * 1.25) {
                        selectedItem = b
                        draggedItem = b
                        dragOffsetX = tx - bx
                        dragOffsetY = ty - by
                        isLearning = false
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        invalidate()
                        return true
                    }
                }

                // 5. Check Joystick
                val joy = profile.joystick
                val jx = joy.centerX * w
                val jy = joy.centerY * h
                val jr = joy.radius * h
                if (hypot(tx - jx, ty - jy) <= jr) {
                    selectedItem = "JOYSTICK"
                    draggedItem = "JOYSTICK"
                    dragOffsetX = tx - jx
                    dragOffsetY = ty - jy
                    isLearning = false
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    invalidate()
                    return true
                }

                // 6. Check Camera Zone Body
                val cam = profile.camera
                if (cam.enabled) {
                    val cx1 = cam.rectX1 * w
                    val cy1 = cam.rectY1 * h
                    val cx2 = cam.rectX2 * w
                    val cy2 = cam.rectY2 * h
                    if (tx in cx1..cx2 && ty in cy1..cy2) {
                        selectedItem = "CAMERA"
                        draggedItem = "CAMERA"
                        activeCamHandle = CamHandle.BODY
                        dragOffsetX = tx - cx1
                        dragOffsetY = ty - cy1
                        isLearning = false
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        invalidate()
                        return true
                    }
                }

                // Click outside
                selectedItem = null
                draggedItem = null
                activeCamHandle = CamHandle.NONE
                isLearning = false
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (isTopBarHidden && isDraggingShowBtn) {
                    val dx = tx - showBtnDragStartX
                    val dy = ty - showBtnDragStartY
                    if (hypot(dx.toDouble(), dy.toDouble()) > 6.0 * density) {
                        hasShowBtnMoved = true
                    }
                    showBtnNormX = tx / w
                    showBtnNormY = ty / h
                    invalidate()
                    return true
                }

                when (val item = draggedItem) {
                    is ButtonConfig -> {
                        item.x = ((tx - dragOffsetX) / w).coerceIn(0.02f, 0.98f)
                        item.y = ((ty - dragOffsetY) / h).coerceIn(0.02f, 0.98f)
                        invalidate()
                    }
                    "JOYSTICK" -> {
                        profile.joystick.centerX = ((tx - dragOffsetX) / w).coerceIn(0.05f, 0.50f)
                        profile.joystick.centerY = ((ty - dragOffsetY) / h).coerceIn(0.05f, 0.95f)
                        invalidate()
                    }
                    "CAMERA" -> {
                        val c = profile.camera
                        val curW = c.rectX2 - c.rectX1
                        val curH = c.rectY2 - c.rectY1

                        when (activeCamHandle) {
                            CamHandle.BODY -> {
                                val newX1 = ((tx - dragOffsetX) / w).coerceIn(0.01f, 0.99f - curW)
                                val newY1 = ((ty - dragOffsetY) / h).coerceIn(0.01f, 0.99f - curH)
                                c.rectX1 = newX1
                                c.rectY1 = newY1
                                c.rectX2 = newX1 + curW
                                c.rectY2 = newY1 + curH
                            }
                            CamHandle.NW -> {
                                c.rectX1 = (tx / w).coerceIn(0.01f, c.rectX2 - 0.06f)
                                c.rectY1 = (ty / h).coerceIn(0.01f, c.rectY2 - 0.06f)
                            }
                            CamHandle.NE -> {
                                c.rectX2 = (tx / w).coerceIn(c.rectX1 + 0.06f, 0.99f)
                                c.rectY1 = (ty / h).coerceIn(0.01f, c.rectY2 - 0.06f)
                            }
                            CamHandle.SW -> {
                                c.rectX1 = (tx / w).coerceIn(0.01f, c.rectX2 - 0.06f)
                                c.rectY2 = (ty / h).coerceIn(c.rectY1 + 0.06f, 0.99f)
                            }
                            CamHandle.SE -> {
                                c.rectX2 = (tx / w).coerceIn(c.rectX1 + 0.06f, 0.99f)
                                c.rectY2 = (ty / h).coerceIn(c.rectY1 + 0.06f, 0.99f)
                            }
                            CamHandle.NONE -> {}
                        }
                        invalidate()
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isTopBarHidden && isDraggingShowBtn) {
                    isDraggingShowBtn = false
                    if (!hasShowBtnMoved) {
                        isTopBarHidden = false
                        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        invalidate()
                    }
                    return true
                }
                draggedItem = null
                activeCamHandle = CamHandle.NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun nudgeSelectedItem(dx: Float, dy: Float) {
        when (val item = selectedItem) {
            is ButtonConfig -> {
                item.x = (item.x + dx).coerceIn(0.02f, 0.98f)
                item.y = (item.y + dy).coerceIn(0.02f, 0.98f)
            }
            "JOYSTICK" -> {
                profile.joystick.centerX = (profile.joystick.centerX + dx).coerceIn(0.05f, 0.50f)
                profile.joystick.centerY = (profile.joystick.centerY + dy).coerceIn(0.05f, 0.95f)
            }
            "CAMERA" -> {
                val c = profile.camera
                val w = c.rectX2 - c.rectX1
                val h = c.rectY2 - c.rectY1
                c.rectX1 = (c.rectX1 + dx).coerceIn(0.01f, 0.99f - w)
                c.rectY1 = (c.rectY1 + dy).coerceIn(0.01f, 0.99f - h)
                c.rectX2 = c.rectX1 + w
                c.rectY2 = c.rectY1 + h
            }
        }
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        invalidate()
    }

    private fun adjustSelectedItemSize(factor: Float) {
        when (val item = selectedItem) {
            is ButtonConfig -> {
                item.radius = (item.radius * factor).coerceIn(0.020f, 0.120f)
            }
            "JOYSTICK" -> {
                profile.joystick.radius = (profile.joystick.radius * factor).coerceIn(0.050f, 0.260f)
            }
            "CAMERA" -> {
                val c = profile.camera
                val cx = (c.rectX1 + c.rectX2) / 2f
                val cy = (c.rectY1 + c.rectY2) / 2f
                val halfW = ((c.rectX2 - c.rectX1) / 2f * factor).coerceIn(0.06f, 0.48f)
                val halfH = ((c.rectY2 - c.rectY1) / 2f * factor).coerceIn(0.06f, 0.48f)
                c.rectX1 = (cx - halfW).coerceIn(0.01f, 0.90f)
                c.rectX2 = (cx + halfW).coerceIn(c.rectX1 + 0.06f, 0.99f)
                c.rectY1 = (cy - halfH).coerceIn(0.02f, 0.90f)
                c.rectY2 = (cy + halfH).coerceIn(c.rectY1 + 0.06f, 0.98f)
            }
        }
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        invalidate()
    }

    private fun keyCodeToGamepadKey(keyCode: Int): String? = when (keyCode) {
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val keyName = keyCodeToGamepadKey(keyCode)
        if (keyName != null) {
            if (isLearning && selectedButton != null) {
                selectedButton?.gamepadButton = keyName
                isLearning = false
                performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                invalidate()
                return true
            }
            activeGamepadKeys.add(keyName)
            invalidate()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        val keyName = keyCodeToGamepadKey(keyCode)
        if (keyName != null) {
            activeGamepadKeys.remove(keyName)
            invalidate()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val ltVal = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE))
        val rtVal = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))

        val boundKey = when {
            hatY <= -0.4f -> "DPAD_UP"
            hatY >= 0.4f -> "DPAD_DOWN"
            hatX <= -0.4f -> "DPAD_LEFT"
            hatX >= 0.4f -> "DPAD_RIGHT"
            ltVal > 0.50f -> "BUTTON_L2"
            rtVal > 0.50f -> "BUTTON_R2"
            else -> null
        }

        if (isLearning && selectedButton != null && boundKey != null) {
            selectedButton?.gamepadButton = boundKey
            isLearning = false
            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            invalidate()
            return true
        }

        // Live D-pad tracking
        if (hatY <= -0.4f) activeGamepadKeys.add("DPAD_UP") else activeGamepadKeys.remove("DPAD_UP")
        if (hatY >= 0.4f) activeGamepadKeys.add("DPAD_DOWN") else activeGamepadKeys.remove("DPAD_DOWN")
        if (hatX <= -0.4f) activeGamepadKeys.add("DPAD_LEFT") else activeGamepadKeys.remove("DPAD_LEFT")
        if (hatX >= 0.4f) activeGamepadKeys.add("DPAD_RIGHT") else activeGamepadKeys.remove("DPAD_RIGHT")

        // Live Triggers tracking
        if (ltVal > 0.40f) activeGamepadKeys.add("BUTTON_L2") else activeGamepadKeys.remove("BUTTON_L2")
        if (rtVal > 0.40f) activeGamepadKeys.add("BUTTON_R2") else activeGamepadKeys.remove("BUTTON_R2")

        // Live Sticks tracking
        val rawLsX = event.getAxisValue(MotionEvent.AXIS_X)
        val rawLsY = event.getAxisValue(MotionEvent.AXIS_Y)
        liveLsX = if (abs(rawLsX) > 0.08f) rawLsX else 0f
        liveLsY = if (abs(rawLsY) > 0.08f) rawLsY else 0f

        val rawRsX = if (event.getAxisValue(MotionEvent.AXIS_Z) != 0f) event.getAxisValue(MotionEvent.AXIS_Z) else event.getAxisValue(MotionEvent.AXIS_RX)
        val rawRsY = if (event.getAxisValue(MotionEvent.AXIS_RZ) != 0f) event.getAxisValue(MotionEvent.AXIS_RZ) else event.getAxisValue(MotionEvent.AXIS_RY)
        liveRsX = if (abs(rawRsX) > 0.08f) rawRsX else 0f
        liveRsY = if (abs(rawRsY) > 0.08f) rawRsY else 0f

        invalidate()
        return super.onGenericMotionEvent(event)
    }

    fun releaseBitmap() {
        screenshotBitmap = null
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Do not recycle bitmap on detach; ownership is managed by parent container
        screenshotBitmap = null
    }
}
