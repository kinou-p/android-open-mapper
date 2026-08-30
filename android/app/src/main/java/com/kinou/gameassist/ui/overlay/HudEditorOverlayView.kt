package com.kinou.gameassist.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.kinou.gameassist.data.model.ButtonConfig
import com.kinou.gameassist.data.model.ButtonMode
import com.kinou.gameassist.data.model.GameProfile
import java.util.UUID
import kotlin.math.hypot

@SuppressLint("ViewConstructor")
class HudEditorOverlayView(
    context: Context,
    private val profile: GameProfile,
    private val onSave: (GameProfile) -> Unit,
    private val onClose: () -> Unit
) : View(context) {

    private val bgPaint = Paint().apply {
        color = 0x66000000 // 40% transparent dark overlay
        style = Paint.Style.FILL
    }

    private val btnNormalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAA00F0FF.toInt() // Neon Cyan Translucent
        style = Paint.Style.FILL
    }

    private val btnSelectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xEEFF0055.toInt() // Neon Pink Selected
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 26f
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    private val joyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x4400FF66.toInt() // Neon Green Translucent
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

    private var selectedButton: ButtonConfig? = null
    private var draggedItem: Any? = null // ButtonConfig or "JOYSTICK" or null
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    var isLearning = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // 1. Background tint
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // 2. Camera Zone
        val cam = profile.camera
        if (cam.enabled) {
            val cx1 = cam.rectX1 * w
            val cy1 = cam.rectY1 * h
            val cx2 = cam.rectX2 * w
            val cy2 = cam.rectY2 * h
            canvas.drawRect(cx1, cy1, cx2, cy2, camPaint)
            canvas.drawRect(cx1, cy1, cx2, cy2, camStroke)
            canvas.drawText(context.getString(com.kinou.gameassist.R.string.overlay_camera_zone), (cx1 + cx2) / 2f, cy1 + 35f, textPaint)
        }

        // 3. Joystick Zone
        val joy = profile.joystick
        if (joy.enabled) {
            val jx = joy.centerX * w
            val jy = joy.centerY * h
            val jr = joy.radius * h
            canvas.drawCircle(jx, jy, jr, joyPaint)
            canvas.drawCircle(jx, jy, jr, joyStroke)
            canvas.drawCircle(jx, jy, jr * joy.sprintThreshold, joyStroke)
            canvas.drawText(context.getString(com.kinou.gameassist.R.string.overlay_joystick_zone), jx, jy + 10f, textPaint)
        }

        // 4. Buttons
        for (b in profile.buttons) {
            val bx = b.x * w
            val by = b.y * h
            val br = b.radius * h

            val isSelected = b == selectedButton
            val paint = if (isSelected) btnSelectedPaint else btnNormalPaint
            canvas.drawCircle(bx, by, br, paint)
            canvas.drawCircle(bx, by, br, strokePaint)

            if (isSelected) {
                val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFF00FF66.toInt()
                    style = Paint.Style.STROKE
                    strokeWidth = 5f
                }
                canvas.drawCircle(bx, by, br + 8f, ringPaint)
            }

            val displayKey = b.gamepadButton.replace("BUTTON_", "").replace("TRIGGER_", "")
            canvas.drawText(displayKey, bx, by - 6f, textPaint)

            val subTextPaint = Paint(textPaint).apply { textSize = 18f; isFakeBoldText = false }
            canvas.drawText(b.label, bx, by + 20f, subTextPaint)

            // Mode badge (HOLD, TAP, or SLIDE)
            val modeBadge = when (b.mode) {
                ButtonMode.HOLD -> "HOLD"
                ButtonMode.TAP -> "TAP"
                ButtonMode.SLIDE_CANCEL -> "SLIDE"
            }
            val badgePaint = Paint().apply {
                color = when (b.mode) {
                    ButtonMode.HOLD -> 0xCC00F0FF.toInt()
                    ButtonMode.TAP -> 0xCCFFAA00.toInt()
                    ButtonMode.SLIDE_CANCEL -> 0xCCFF0055.toInt()
                }
            }
            canvas.drawRoundRect(bx - 32f, by + br + 4f, bx + 32f, by + br + 24f, 6f, 6f, badgePaint)
            val badgeText = Paint(textPaint).apply { color = 0xFF000000.toInt(); textSize = 14f }
            canvas.drawText(modeBadge, bx, by + br + 19f, badgeText)
        }

        // 5. Top Bar UI Controls
        drawTopBar(canvas, w)
    }

    private fun drawTopBar(canvas: Canvas, w: Float) {
        val barPaint = Paint().apply { color = 0xF0070A0F.toInt() }
        canvas.drawRect(0f, 0f, w, 84f, barPaint)

        val titlePaint = Paint(textPaint).apply { textAlign = Paint.Align.LEFT; textSize = 28f }
        canvas.drawText(context.getString(com.kinou.gameassist.R.string.overlay_editor_title, profile.name), 24f, 52f, titlePaint)

        // Button: ➕ Ajouter Bouton
        val addPaint = Paint().apply { color = 0xFF00F0FF.toInt() }
        canvas.drawRoundRect(w - 740f, 16f, w - 590f, 68f, 12f, 12f, addPaint)
        val addText = Paint(textPaint).apply { color = 0xFF000000.toInt(); textSize = 22f }
        canvas.drawText(context.getString(com.kinou.gameassist.R.string.overlay_btn_add), w - 665f, 48f, addText)

        // Contextual buttons when a button is selected
        if (selectedButton != null) {
            val btn = selectedButton!!

            // Button: 🗑️ Supprimer
            val delPaint = Paint().apply { color = 0xFFFF0055.toInt() }
            canvas.drawRoundRect(w - 570f, 16f, w - 430f, 68f, 12f, 12f, delPaint)
            val delText = Paint(textPaint).apply { color = 0xFFFFFFFF.toInt(); textSize = 22f }
            canvas.drawText(context.getString(com.kinou.gameassist.R.string.overlay_btn_delete), w - 500f, 48f, delText)

            // Button: Mode Toggle (HOLD / TAP / SLIDE)
            val modePaint = Paint().apply {
                color = when (btn.mode) {
                    ButtonMode.HOLD -> 0xFF00F0FF.toInt()
                    ButtonMode.TAP -> 0xFFFFAA00.toInt()
                    ButtonMode.SLIDE_CANCEL -> 0xFFFF0055.toInt()
                }
            }
            canvas.drawRoundRect(w - 410f, 16f, w - 280f, 68f, 12f, 12f, modePaint)
            val modeLabel = when (btn.mode) {
                ButtonMode.HOLD -> "Mode: HOLD"
                ButtonMode.TAP -> "Mode: TAP"
                ButtonMode.SLIDE_CANCEL -> "Mode: SLIDE"
            }
            val modeText = Paint(textPaint).apply { color = 0xFF000000.toInt(); textSize = 18f }
            canvas.drawText(modeLabel, w - 345f, 48f, modeText)
        }

        // Button: 💾 Sauvegarder
        val savePaint = Paint().apply { color = 0xFF00FF66.toInt() }
        canvas.drawRoundRect(w - 260f, 16f, w - 140f, 68f, 12f, 12f, savePaint)
        val saveText = Paint(textPaint).apply { color = 0xFF000000.toInt(); textSize = 22f }
        canvas.drawText(context.getString(com.kinou.gameassist.R.string.overlay_btn_save), w - 200f, 48f, saveText)

        // Button: ✖ Fermer
        val closePaint = Paint().apply { color = 0xFF333D4D.toInt() }
        canvas.drawRoundRect(w - 120f, 16f, w - 20f, 68f, 12f, 12f, closePaint)
        val closeText = Paint(textPaint).apply { color = 0xFFFFFFFF.toInt(); textSize = 22f }
        canvas.drawText(context.getString(com.kinou.gameassist.R.string.overlay_btn_close), w - 70f, 48f, closeText)

        if (isLearning && selectedButton != null) {
            val bannerPaint = Paint().apply { color = 0xEEFF0055.toInt() }
            canvas.drawRect(0f, 84f, w, 144f, bannerPaint)
            canvas.drawText(context.getString(com.kinou.gameassist.R.string.overlay_learning_banner, selectedButton?.label ?: ""), w / 2f, 122f, textPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width.toFloat()
        val h = height.toFloat()
        val tx = event.x
        val ty = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Check top bar clicks (y in 0..84)
                if (ty <= 84f) {
                    // 1. ➕ Ajouter
                    if (tx in (w - 740f)..(w - 590f)) {
                        val newBtn = ButtonConfig(
                            id = "btn_${UUID.randomUUID().toString().take(6)}",
                            label = "Bouton ${profile.buttons.size + 1}",
                            gamepadButton = "BUTTON_A",
                            x = 0.5f,
                            y = 0.5f,
                            radius = 0.045f,
                            mode = ButtonMode.HOLD
                        )
                        profile.buttons.add(newBtn)
                        selectedButton = newBtn
                        isLearning = true
                        invalidate()
                        return true
                    }

                    // 2. 🗑️ Supprimer (if button selected)
                    if (selectedButton != null && tx in (w - 570f)..(w - 430f)) {
                        profile.buttons.remove(selectedButton)
                        selectedButton = null
                        isLearning = false
                        invalidate()
                        return true
                    }

                    // 3. Mode Toggle (if button selected)
                    if (selectedButton != null && tx in (w - 410f)..(w - 280f)) {
                        selectedButton?.let { b ->
                            b.mode = when (b.mode) {
                                ButtonMode.HOLD -> ButtonMode.TAP
                                ButtonMode.TAP -> ButtonMode.SLIDE_CANCEL
                                ButtonMode.SLIDE_CANCEL -> ButtonMode.HOLD
                            }
                            invalidate()
                        }
                        return true
                    }

                    // 4. 💾 Sauver
                    if (tx in (w - 260f)..(w - 140f)) {
                        onSave(profile)
                        return true
                    }

                    // 5. ✖ Fermer
                    if (tx in (w - 120f)..(w - 20f)) {
                        onClose()
                        return true
                    }
                }

                // Check button clicks
                for (b in profile.buttons.reversed()) {
                    val bx = b.x * w
                    val by = b.y * h
                    val br = b.radius * h
                    if (hypot((tx - bx).toDouble(), (ty - by).toDouble()) <= br * 1.2) {
                        selectedButton = b
                        draggedItem = b
                        dragOffsetX = tx - bx
                        dragOffsetY = ty - by
                        isLearning = true
                        invalidate()
                        return true
                    }
                }

                // Check joystick drag
                val joy = profile.joystick
                val jx = joy.centerX * w
                val jy = joy.centerY * h
                val jr = joy.radius * h
                if (hypot((tx - jx).toDouble(), (ty - jy).toDouble()) <= jr) {
                    draggedItem = "JOYSTICK"
                    dragOffsetX = tx - jx
                    dragOffsetY = ty - jy
                    selectedButton = null
                    isLearning = false
                    invalidate()
                    return true
                }

                // Click outside
                selectedButton = null
                draggedItem = null
                isLearning = false
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                when (val item = draggedItem) {
                    is ButtonConfig -> {
                        item.x = ((tx - dragOffsetX) / w).coerceIn(0.02f, 0.98f)
                        item.y = ((ty - dragOffsetY) / h).coerceIn(0.12f, 0.98f)
                        invalidate()
                    }
                    "JOYSTICK" -> {
                        profile.joystick.centerX = ((tx - dragOffsetX) / w).coerceIn(0.05f, 0.50f)
                        profile.joystick.centerY = ((ty - dragOffsetY) / h).coerceIn(0.20f, 0.90f)
                        invalidate()
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                draggedItem = null
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isLearning && selectedButton != null) {
            val keyName = when (keyCode) {
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
            if (keyName != null) {
                selectedButton?.gamepadButton = keyName
                isLearning = false
                invalidate()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (isLearning && selectedButton != null) {
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

            if (boundKey != null) {
                selectedButton?.gamepadButton = boundKey
                isLearning = false
                invalidate()
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }
}
