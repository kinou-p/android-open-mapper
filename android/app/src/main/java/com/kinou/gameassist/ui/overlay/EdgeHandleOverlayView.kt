package com.kinou.gameassist.ui.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.max

@SuppressLint("ViewConstructor")
class EdgeHandleOverlayView(
    context: Context,
    private val windowManager: WindowManager,
    private val params: WindowManager.LayoutParams,
    private val onOpenHud: () -> Unit,
    private val onOpenApp: () -> Unit,
    private val onStopService: () -> Unit,
    private val isStrafeActive: (() -> Boolean)? = null,
    private val onToggleStrafe: (() -> Unit)? = null,
    private val isAntiRecoilActive: (() -> Boolean)? = null,
    private val onToggleAntiRecoil: (() -> Unit)? = null,
    private val getAntiRecoilSpeed: (() -> Float)? = null,
    private val onSetAntiRecoilSpeed: ((Float) -> Unit)? = null
) : View(context) {

    enum class ScreenEdge { LEFT, RIGHT }

    private val density = resources.displayMetrics.density

    // Dimensions
    val restingWidth = (16 * density).toInt()
    val restingHeight = (90 * density).toInt()
    val menuWidth = (210 * density).toInt()
    val menuHeight = (268 * density).toInt()

    // Dynamic height when anti-recoil is active
    val currentMenuHeight: Int
        get() = if (isAntiRecoilActive?.invoke() == true) (316 * density).toInt() else menuHeight

    private val triggerThreshold = 55 * density
    private val cornerRadius = 14 * density
    private val btnCornerRadius = 10 * density

    // State
    var currentEdge = ScreenEdge.LEFT
        private set
    var isMenuOpen = false
        private set
    private var menuProgress = 0f // 0f (collapsed) -> 1f (fully open menu)
    private var pullDistance = 0f
    private var currentAlpha = 0.35f
    private var isInteracting = false
    private var isDraggingHandle = false
    private var isSlidingToOpen = false
    private var hasHapticPlayed = false

    private enum class MenuButton { HUD, STRAFE, RECOIL, RECOIL_DEC, RECOIL_INC, APP, STOP, CLOSE }
    private var pressedButton: MenuButton? = null

    // Recoil slider drag tracking & button rects
    private val btnRecoilDecRect = RectF()
    private val btnRecoilIncRect = RectF()
    private val sliderTrackRect = RectF()
    private var isDraggingRecoilSlider = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val dimRunnable = Runnable {
        if (!isInteracting && !isMenuOpen) {
            animateAlphaTo(0.25f)
        }
    }

    // Paints
    private val handleBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xEA0A0E14.toInt() // Cyber Dark Translucent
        style = Paint.Style.FILL
    }

    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00F0FF.toInt() // Neon Cyan
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x5500F0FF.toInt()
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val btnBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val btnStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }

    private val handleBarIndicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAA00F0FF.toInt()
        style = Paint.Style.FILL
    }

    private var initialTouchRawX = 0f
    private var initialTouchRawY = 0f
    private var initialWindowY = 0
    private var alphaAnimator: ValueAnimator? = null
    private var menuAnimator: ValueAnimator? = null

    init {
        val prefs = context.getSharedPreferences("overlay_prefs", Context.MODE_PRIVATE)
        val isRight = prefs.getBoolean("edge_handle_is_right", false)
        currentEdge = if (isRight) ScreenEdge.RIGHT else ScreenEdge.LEFT

        isHapticFeedbackEnabled = true
        scheduleDimming(2500)
    }

    private fun saveEdgePrefs() {
        context.getSharedPreferences("overlay_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("edge_handle_is_right", currentEdge == ScreenEdge.RIGHT)
            .putInt("edge_handle_y", params.y)
            .apply()
    }

    private fun scheduleDimming(delayMs: Long) {
        mainHandler.removeCallbacks(dimRunnable)
        mainHandler.postDelayed(dimRunnable, delayMs)
    }

    private fun animateAlphaTo(targetAlpha: Float) {
        alphaAnimator?.cancel()
        alphaAnimator = ValueAnimator.ofFloat(currentAlpha, targetAlpha).apply {
            duration = 300
            addUpdateListener { animator ->
                currentAlpha = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun safeUpdateViewLayout() {
        if (isAttachedToWindow) {
            try {
                windowManager.updateViewLayout(this, params)
            } catch (e: Exception) {
                android.util.Log.w("EdgeHandleOverlayView", "updateViewLayout failed: ${e.message}")
            }
        }
    }

    private fun ensureWindowSize(w: Int, h: Int) {
        if (params.width != w || params.height != h) {
            params.width = w
            params.height = h
            safeUpdateViewLayout()
        }
    }

    fun openMenu() {
        isMenuOpen = true
        ensureWindowSize(menuWidth, currentMenuHeight)
        menuAnimator?.cancel()
        menuAnimator = ValueAnimator.ofFloat(menuProgress, 1f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                menuProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun closeMenu(onClosed: (() -> Unit)? = null) {
        isMenuOpen = false
        pressedButton = null
        menuAnimator?.cancel()
        menuAnimator = ValueAnimator.ofFloat(menuProgress, 0f).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                menuProgress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    ensureWindowSize(restingWidth, restingHeight)
                    scheduleDimming(2500)
                    onClosed?.invoke()
                }
            })
            start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        mainHandler.removeCallbacksAndMessages(null)
        alphaAnimator?.cancel()
        alphaAnimator = null
        menuAnimator?.cancel()
        menuAnimator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val isRightEdge = currentEdge == ScreenEdge.RIGHT

        // Global alpha calculation
        val effectiveAlpha = if (isMenuOpen || menuProgress > 0f) 1.0f else currentAlpha
        val alphaInt = (effectiveAlpha * 255).toInt().coerceIn(0, 255)

        // Interpolated drawer size
        val currentDrawerW = restingWidth + (menuWidth - restingWidth) * menuProgress
        val currentDrawerH = restingHeight + (currentMenuHeight - restingHeight) * menuProgress

        // Draw outer card
        val path = Path()
        val cardLeft = if (isRightEdge) w - currentDrawerW else 0f
        val cardRight = if (isRightEdge) w else currentDrawerW
        val rect = RectF(cardLeft, 0f, cardRight, currentDrawerH)

        val radii = if (isRightEdge) {
            floatArrayOf(
                cornerRadius, cornerRadius,           // Top-Left (rounded inside)
                0f, 0f,                               // Top-Right (docked to right screen edge)
                0f, 0f,                               // Bottom-Right (docked to right screen edge)
                cornerRadius, cornerRadius            // Bottom-Left (rounded inside)
            )
        } else {
            floatArrayOf(
                0f, 0f,                               // Top-Left (docked to left screen edge)
                cornerRadius, cornerRadius,           // Top-Right (rounded inside)
                cornerRadius, cornerRadius,           // Bottom-Right (rounded inside)
                0f, 0f                                // Bottom-Left (docked to left screen edge)
            )
        }
        path.addRoundRect(rect, radii, Path.Direction.CW)

        handleBgPaint.alpha = (0xEE * effectiveAlpha).toInt()
        canvas.drawPath(path, handleBgPaint)

        // Draw stroke outline
        handleStrokePaint.color = 0xFF00F0FF.toInt()
        handleStrokePaint.alpha = alphaInt
        canvas.drawPath(path, handleStrokePaint)

        if (menuProgress < 0.35f) {
            // Draw resting handle grip bar
            handleBarIndicatorPaint.alpha = (alphaInt * 0.85f).toInt()
            val gripX = if (isRightEdge) w - restingWidth * 0.45f else restingWidth * 0.45f
            val gripH = 22 * density
            val gripW = 3 * density
            val gripRect = RectF(gripX - gripW / 2f, (currentDrawerH - gripH) / 2f, gripX + gripW / 2f, (currentDrawerH + gripH) / 2f)
            canvas.drawRoundRect(gripRect, gripW / 2f, gripW / 2f, handleBarIndicatorPaint)

            if (menuProgress > 0.05f) {
                glowPaint.alpha = (menuProgress * 160).toInt()
                canvas.drawPath(path, glowPaint)
            }
        } else {
            // Draw Expanded Menu Content
            val contentAlpha = ((menuProgress - 0.35f) / 0.65f).coerceIn(0f, 1f)
            drawMenuContent(canvas, cardLeft, cardRight, currentDrawerH, contentAlpha)
        }
    }

    private fun drawMenuContent(canvas: Canvas, left: Float, right: Float, h: Float, alpha: Float) {
        val a = (alpha * 255).toInt().coerceIn(0, 255)

        // 1. Header: Title & Close
        textPaint.color = 0xFF00F0FF.toInt()
        textPaint.alpha = a
        textPaint.textSize = 13 * density
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("🎮 GAMEASSIST", left + 14 * density, 24 * density, textPaint)

        // Close button (✖)
        textPaint.color = if (pressedButton == MenuButton.CLOSE) 0xFFFF0055.toInt() else 0xFF888888.toInt()
        textPaint.alpha = a
        textPaint.textSize = 14 * density
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("✖", right - 18 * density, 24 * density, textPaint)

        // 2. Action Buttons
        val btnLeft = left + 12 * density
        val btnRight = right - 12 * density
        val btnHeight = 38 * density

        // Button 1: 🎮 Éditeur HUD
        val btn1Top = 36 * density
        val btn1Bottom = btn1Top + btnHeight
        drawMenuButton(
            canvas, RectF(btnLeft, btn1Top, btnRight, btn1Bottom),
            label = context.getString(com.kinou.gameassist.R.string.overlay_hud_editor), icon = "🎮",
            accentColor = 0xFF00F0FF.toInt(),
            isPressed = pressedButton == MenuButton.HUD,
            alpha = a
        )

        // Button 2: ⚡ Auto Strafe Toggle
        val btn2Top = 78 * density
        val btn2Bottom = btn2Top + btnHeight
        val strafeOn = isStrafeActive?.invoke() ?: false
        val strafeLabel = if (strafeOn) "Strafe: ACTIF" else "Strafe: OFF"
        val strafeColor = if (strafeOn) 0xFF00FF66.toInt() else 0xFF8899AA.toInt()
        drawMenuButton(
            canvas, RectF(btnLeft, btn2Top, btnRight, btn2Bottom),
            label = strafeLabel, icon = "⚡",
            accentColor = strafeColor,
            isPressed = pressedButton == MenuButton.STRAFE,
            alpha = a
        )

        // Button 3: 🎯 Anti-Recul Toggle
        val btn3Top = 120 * density
        val btn3Bottom = btn3Top + btnHeight
        val recoilOn = isAntiRecoilActive?.invoke() ?: false
        val recoilLabel = if (recoilOn) "Recul: ACTIF" else "Recul: OFF"
        val recoilColor = if (recoilOn) 0xFFFF9900.toInt() else 0xFF8899AA.toInt()
        drawMenuButton(
            canvas, RectF(btnLeft, btn3Top, btnRight, btn3Bottom),
            label = recoilLabel, icon = "🎯",
            accentColor = recoilColor,
            isPressed = pressedButton == MenuButton.RECOIL,
            alpha = a
        )

        var curTop = 162 * density

        // Anti-Recoil Speed Slider (if Anti-Recoil is active)
        if (recoilOn) {
            val sliderBoxH = 42 * density
            val sliderBoxRect = RectF(btnLeft, curTop, btnRight, curTop + sliderBoxH)

            // Slider container background
            btnBgPaint.color = 0xFF101620.toInt()
            btnBgPaint.alpha = (0xEE * (alpha / 255f)).toInt()
            canvas.drawRoundRect(sliderBoxRect, btnCornerRadius, btnCornerRadius, btnBgPaint)
            btnStrokePaint.color = 0xFFFF9900.toInt()
            btnStrokePaint.alpha = (alpha * 0.4f).toInt()
            canvas.drawRoundRect(sliderBoxRect, btnCornerRadius, btnCornerRadius, btnStrokePaint)

            val curSpeed = getAntiRecoilSpeed?.invoke() ?: 1.0f

            // Speed Title Text
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.textSize = 11.5f * density
            textPaint.color = 0xFFFF9900.toInt()
            textPaint.alpha = a
            canvas.drawText("Vitesse : ${String.format(java.util.Locale.US, "%.1fx", curSpeed)}", btnLeft + 10 * density, curTop + 14 * density, textPaint)

            // Minus button [ ➖ ]
            val stepBtnW = 24 * density
            val stepBtnH = 20 * density
            val stepBtnY = curTop + 18 * density
            btnRecoilDecRect.set(btnLeft + 8 * density, stepBtnY, btnLeft + 8 * density + stepBtnW, stepBtnY + stepBtnH)
            btnBgPaint.color = if (pressedButton == MenuButton.RECOIL_DEC) 0xFFFF9900.toInt() else 0xFF1C2430.toInt()
            btnBgPaint.alpha = a
            canvas.drawRoundRect(btnRecoilDecRect, 4 * density, 4 * density, btnBgPaint)
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = 11 * density
            textPaint.color = if (pressedButton == MenuButton.RECOIL_DEC) Color.BLACK else Color.WHITE
            canvas.drawText("➖", btnRecoilDecRect.centerX(), btnRecoilDecRect.centerY() + 4 * density, textPaint)

            // Plus button [ ➕ ]
            btnRecoilIncRect.set(btnRight - 8 * density - stepBtnW, stepBtnY, btnRight - 8 * density, stepBtnY + stepBtnH)
            btnBgPaint.color = if (pressedButton == MenuButton.RECOIL_INC) 0xFFFF9900.toInt() else 0xFF1C2430.toInt()
            btnBgPaint.alpha = a
            canvas.drawRoundRect(btnRecoilIncRect, 4 * density, 4 * density, btnBgPaint)
            textPaint.color = if (pressedButton == MenuButton.RECOIL_INC) Color.BLACK else 0xFFFF9900.toInt()
            canvas.drawText("➕", btnRecoilIncRect.centerX(), btnRecoilIncRect.centerY() + 4 * density, textPaint)

            // Track & Thumb
            val trackLeft = btnRecoilDecRect.right + 8 * density
            val trackRight = btnRecoilIncRect.left - 8 * density
            val trackY = stepBtnY + stepBtnH / 2f
            sliderTrackRect.set(trackLeft, trackY - 10 * density, trackRight, trackY + 10 * density)

            // Background Track Line
            btnStrokePaint.color = 0xFF2A3644.toInt()
            btnStrokePaint.strokeWidth = 3.5f * density
            btnStrokePaint.alpha = a
            canvas.drawLine(trackLeft, trackY, trackRight, trackY, btnStrokePaint)

            // Active Track Line
            val speedT = ((curSpeed - 0.1f) / 9.9f).coerceIn(0f, 1f)
            val thumbX = trackLeft + speedT * (trackRight - trackLeft)
            btnStrokePaint.color = 0xFFFF9900.toInt()
            canvas.drawLine(trackLeft, trackY, thumbX, trackY, btnStrokePaint)

            // Draggable Thumb Circle
            btnBgPaint.color = 0xFFFF9900.toInt()
            btnBgPaint.alpha = a
            canvas.drawCircle(thumbX, trackY, 5.5f * density, btnBgPaint)
            btnStrokePaint.color = Color.WHITE
            btnStrokePaint.strokeWidth = 1.5f * density
            canvas.drawCircle(thumbX, trackY, 5.5f * density, btnStrokePaint)

            curTop += sliderBoxH + 6 * density
        } else {
            btnRecoilDecRect.setEmpty()
            btnRecoilIncRect.setEmpty()
            sliderTrackRect.setEmpty()
        }

        // Button 4: 📱 Ouvrir l'App
        val btn4Top = curTop
        val btn4Bottom = btn4Top + btnHeight
        drawMenuButton(
            canvas, RectF(btnLeft, btn4Top, btnRight, btn4Bottom),
            label = context.getString(com.kinou.gameassist.R.string.overlay_open_app), icon = "📱",
            accentColor = 0xFF00E5FF.toInt(),
            isPressed = pressedButton == MenuButton.APP,
            alpha = a
        )

        // Button 5: ⏹ Arrêter
        val btn5Top = btn4Bottom + 4 * density
        val btn5Bottom = btn5Top + btnHeight
        drawMenuButton(
            canvas, RectF(btnLeft, btn5Top, btnRight, btn5Bottom),
            label = context.getString(com.kinou.gameassist.R.string.overlay_stop_mapping), icon = "⏹",
            accentColor = 0xFFFF0055.toInt(),
            isPressed = pressedButton == MenuButton.STOP,
            alpha = a
        )
    }

    private fun drawMenuButton(
        canvas: Canvas,
        rect: RectF,
        label: String,
        icon: String,
        accentColor: Int,
        isPressed: Boolean,
        alpha: Int
    ) {
        // Background
        btnBgPaint.color = if (isPressed) accentColor else 0xFF141A22.toInt()
        btnBgPaint.alpha = if (isPressed) (0xDD * (alpha / 255f)).toInt() else (0xBB * (alpha / 255f)).toInt()
        canvas.drawRoundRect(rect, btnCornerRadius, btnCornerRadius, btnBgPaint)

        // Border stroke
        btnStrokePaint.color = accentColor
        btnStrokePaint.alpha = if (isPressed) alpha else (alpha * 0.6f).toInt()
        canvas.drawRoundRect(rect, btnCornerRadius, btnCornerRadius, btnStrokePaint)

        // Icon & Label text
        val textY = rect.centerY() + 5 * density
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 15 * density
        textPaint.color = if (isPressed) 0xFF000000.toInt() else Color.WHITE
        textPaint.alpha = alpha
        canvas.drawText(icon, rect.left + 12 * density, textY, textPaint)

        textPaint.textSize = 13 * density
        textPaint.color = if (isPressed) 0xFF000000.toInt() else accentColor
        textPaint.alpha = alpha
        canvas.drawText(label, rect.left + 38 * density, textY, textPaint)
    }

    private fun getButtonAt(x: Float, y: Float): MenuButton? {
        val w = width.toFloat()
        val left = if (currentEdge == ScreenEdge.RIGHT) w - menuWidth else 0f
        val right = if (currentEdge == ScreenEdge.RIGHT) w else menuWidth.toFloat()
        val btnLeft = left + 12 * density
        val btnRight = right - 12 * density

        // Close button at top right of menu
        if (x in (right - 36 * density)..right && y in 0f..(34 * density)) {
            return MenuButton.CLOSE
        }

        val recoilOn = isAntiRecoilActive?.invoke() ?: false

        val btnHeight = 38 * density
        val btn1Top = 36 * density
        if (x in btnLeft..btnRight && y in btn1Top..(btn1Top + btnHeight)) return MenuButton.HUD

        val btn2Top = 78 * density
        if (x in btnLeft..btnRight && y in btn2Top..(btn2Top + btnHeight)) return MenuButton.STRAFE

        val btn3Top = 120 * density
        if (x in btnLeft..btnRight && y in btn3Top..(btn3Top + btnHeight)) return MenuButton.RECOIL

        if (recoilOn) {
            if (btnRecoilDecRect.contains(x, y)) return MenuButton.RECOIL_DEC
            if (btnRecoilIncRect.contains(x, y)) return MenuButton.RECOIL_INC

            val btn4Top = 162 * density + 42 * density + 6 * density
            if (x in btnLeft..btnRight && y in btn4Top..(btn4Top + btnHeight)) return MenuButton.APP

            val btn5Top = btn4Top + btnHeight + 4 * density
            if (x in btnLeft..btnRight && y in btn5Top..(btn5Top + btnHeight)) return MenuButton.STOP
        } else {
            val btn4Top = 162 * density
            if (x in btnLeft..btnRight && y in btn4Top..(btn4Top + btnHeight)) return MenuButton.APP

            val btn5Top = btn4Top + btnHeight + 4 * density
            if (x in btnLeft..btnRight && y in btn5Top..(btn5Top + btnHeight)) return MenuButton.STOP
        }
        return null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isMenuOpen) {
            val recoilOn = isAntiRecoilActive?.invoke() ?: false

            // Touch handling when menu is OPEN
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Check if touching inside the slider track
                    if (recoilOn && sliderTrackRect.contains(event.x, event.y)) {
                        isDraggingRecoilSlider = true
                        val progress = ((event.x - sliderTrackRect.left) / sliderTrackRect.width()).coerceIn(0f, 1f)
                        val newSpeed = Math.round((0.1f + progress * 9.9f) * 10f) / 10f
                        onSetAntiRecoilSpeed?.invoke(newSpeed)
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        invalidate()
                        return true
                    }

                    val btn = getButtonAt(event.x, event.y)
                    if (btn != null) {
                        pressedButton = btn
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        invalidate()
                        return true
                    } else {
                        val isInsideMenu = if (currentEdge == ScreenEdge.RIGHT) {
                            event.x >= width - menuWidth && event.y <= currentMenuHeight
                        } else {
                            event.x <= menuWidth && event.y <= currentMenuHeight
                        }
                        if (!isInsideMenu) {
                            closeMenu()
                            return true
                        }
                    }
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (isDraggingRecoilSlider && recoilOn && sliderTrackRect.width() > 0) {
                        val progress = ((event.x - sliderTrackRect.left) / sliderTrackRect.width()).coerceIn(0f, 1f)
                        val newSpeed = Math.round((0.1f + progress * 9.9f) * 10f) / 10f
                        onSetAntiRecoilSpeed?.invoke(newSpeed)
                        invalidate()
                        return true
                    }

                    val btn = getButtonAt(event.x, event.y)
                    if (btn != pressedButton) {
                        pressedButton = btn
                        invalidate()
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    if (isDraggingRecoilSlider) {
                        isDraggingRecoilSlider = false
                        invalidate()
                        return true
                    }

                    val targetBtn = pressedButton
                    pressedButton = null
                    invalidate()

                    when (targetBtn) {
                        MenuButton.HUD -> closeMenu { onOpenHud() }
                        MenuButton.STRAFE -> {
                            onToggleStrafe?.invoke()
                            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            invalidate()
                        }
                        MenuButton.RECOIL -> {
                            onToggleAntiRecoil?.invoke()
                            ensureWindowSize(menuWidth, currentMenuHeight)
                            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            invalidate()
                        }
                        MenuButton.RECOIL_DEC -> {
                            val cur = getAntiRecoilSpeed?.invoke() ?: 1.0f
                            val newSpd = Math.round((cur - 0.2f).coerceIn(0.1f, 10.0f) * 10f) / 10f
                            onSetAntiRecoilSpeed?.invoke(newSpd)
                            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            invalidate()
                        }
                        MenuButton.RECOIL_INC -> {
                            val cur = getAntiRecoilSpeed?.invoke() ?: 1.0f
                            val newSpd = Math.round((cur + 0.2f).coerceIn(0.1f, 10.0f) * 10f) / 10f
                            onSetAntiRecoilSpeed?.invoke(newSpd)
                            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            invalidate()
                        }
                        MenuButton.APP -> closeMenu { onOpenApp() }
                        MenuButton.STOP -> closeMenu { onStopService() }
                        MenuButton.CLOSE -> closeMenu()
                        null -> {
                            val isInsideMenu = if (currentEdge == ScreenEdge.RIGHT) {
                                event.x >= width - menuWidth && event.y <= currentMenuHeight
                            } else {
                                event.x <= menuWidth && event.y <= currentMenuHeight
                            }
                            if (!isInsideMenu) {
                                closeMenu()
                            }
                        }
                    }
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    pressedButton = null
                    isDraggingRecoilSlider = false
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        // Touch handling when menu is COLLAPSED (Slide gesture / drag & reposition / edge switch)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mainHandler.removeCallbacks(dimRunnable)
                alphaAnimator?.cancel()
                currentAlpha = 1.0f
                isInteracting = true
                isDraggingHandle = false
                isSlidingToOpen = false
                hasHapticPlayed = false
                pullDistance = 0f

                initialTouchRawX = event.rawX
                initialTouchRawY = event.rawY
                initialWindowY = params.y

                ensureWindowSize(restingWidth, restingHeight)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dxRaw = event.rawX - initialTouchRawX
                val dyRaw = event.rawY - initialTouchRawY
                val screenWidth = resources.displayMetrics.widthPixels
                val screenHeight = resources.displayMetrics.heightPixels

                if (!isDraggingHandle && !isSlidingToOpen) {
                    val isPullingAway = if (currentEdge == ScreenEdge.LEFT) {
                        dxRaw > 12 * density && dxRaw > abs(dyRaw) * 1.2f
                    } else {
                        -dxRaw > 12 * density && -dxRaw > abs(dyRaw) * 1.2f
                    }

                    if (isPullingAway) {
                        isSlidingToOpen = true
                        ensureWindowSize(menuWidth, menuHeight)
                    } else if (abs(dyRaw) > 10 * density || abs(dxRaw) > 10 * density) {
                        isDraggingHandle = true
                        ensureWindowSize(restingWidth, restingHeight)
                    }
                }

                if (isDraggingHandle) {
                    // 1. Check if rawX crossed screen midpoint to switch edge
                    val targetEdge = if (event.rawX > screenWidth / 2f) ScreenEdge.RIGHT else ScreenEdge.LEFT
                    if (targetEdge != currentEdge) {
                        currentEdge = targetEdge
                        params.gravity = Gravity.TOP or (if (currentEdge == ScreenEdge.RIGHT) Gravity.END else Gravity.START)
                        params.x = 0
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    }

                    // 2. Update Y position
                    val newY = (event.rawY - restingHeight / 2f).toInt().coerceIn(0, max(0, screenHeight - restingHeight))
                    params.y = newY
                    safeUpdateViewLayout()
                    menuProgress = 0f
                } else if (isSlidingToOpen) {
                    pullDistance = if (currentEdge == ScreenEdge.LEFT) max(0f, dxRaw) else max(0f, -dxRaw)
                    menuProgress = (pullDistance / triggerThreshold).coerceIn(0f, 1f)

                    if (pullDistance >= triggerThreshold && !hasHapticPlayed) {
                        hasHapticPlayed = true
                        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    } else if (pullDistance < triggerThreshold && hasHapticPlayed) {
                        hasHapticPlayed = false
                    }
                }

                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isInteracting = false
                if (isDraggingHandle) {
                    isDraggingHandle = false
                    saveEdgePrefs()
                    ensureWindowSize(restingWidth, restingHeight)
                    scheduleDimming(2500)
                    invalidate()
                } else if (isSlidingToOpen) {
                    isSlidingToOpen = false
                    if (pullDistance >= triggerThreshold * 0.7f) {
                        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        openMenu()
                    } else {
                        closeMenu()
                    }
                } else {
                    // Tap on resting handle -> Open Menu
                    performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    openMenu()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
