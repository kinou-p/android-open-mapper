package com.kinou.gameassist.engine

import com.kinou.gameassist.data.model.ButtonConfig
import com.kinou.gameassist.data.model.ButtonMode
import com.kinou.gameassist.injector.ShizukuTouchInjector
import kotlinx.coroutines.*
import java.util.Random
import java.util.concurrent.ConcurrentHashMap

import com.kinou.gameassist.data.model.GameSettings

class ButtonProcessor(
    private val injector: ShizukuTouchInjector,
    private val scope: CoroutineScope,
    var hapticManager: HapticManager? = null
) {
    companion object {
        const val POINTER_BUTTON_START = 3
        const val MAX_POINTERS = 10
    }

    private val random = Random()
    private var buttons: List<ButtonConfig> = emptyList()
    private var settings: GameSettings = GameSettings()
    private val activePointers = ConcurrentHashMap<String, Int>()
    private val freePointers = (POINTER_BUTTON_START until MAX_POINTERS).toMutableSet()
    private val lock = Any()

    fun updateButtons(list: List<ButtonConfig>) {
        synchronized(lock) {
            buttons = list
        }
    }

    fun updateSettings(newSettings: GameSettings) {
        synchronized(lock) {
            settings = newSettings
        }
    }

    fun onButtonDown(buttonName: String) {
        val matchedButtons = buttons.filter { it.gamepadButton.equals(buttonName, ignoreCase = true) }
        if (matchedButtons.isEmpty()) return

        for (btn in matchedButtons) {
            // Trigger haptic feedback for Fire and Reload actions
            if (settings.hapticFeedback) {
                val isFire = btn.id.contains("fire", ignoreCase = true) ||
                             btn.label.contains("tir", ignoreCase = true) ||
                             btn.gamepadButton.equals("BUTTON_R2", ignoreCase = true)
                val isReload = btn.id.contains("reload", ignoreCase = true) ||
                               btn.label.contains("recharg", ignoreCase = true) ||
                               btn.gamepadButton.equals("BUTTON_X", ignoreCase = true)

                if (isFire && settings.hapticFire) {
                    hapticManager?.playFireHaptic(settings.hapticIntensity)
                } else if (isReload && settings.hapticReload) {
                    hapticManager?.playReloadHaptic(settings.hapticIntensity)
                }
            }

            var isAlreadyActive = false
            var pointerId: Int? = null

            synchronized(lock) {
                if (activePointers.containsKey(btn.id)) {
                    isAlreadyActive = true
                } else {
                    if (freePointers.isNotEmpty()) {
                        val pid = freePointers.minOrNull() ?: POINTER_BUTTON_START
                        freePointers.remove(pid)
                        activePointers[btn.id] = pid
                        pointerId = pid
                    } else {
                        pointerId = POINTER_BUTTON_START
                        activePointers[btn.id] = pointerId
                    }
                }
            }

            if (isAlreadyActive) {
                continue
            }

            pointerId?.let { pid ->
                val screenW = injector.screenWidth
                val screenH = injector.screenHeight
                val tx = btn.x * screenW
                val ty = btn.y * screenH

                when (btn.mode) {
                    ButtonMode.HOLD -> {
                        injector.touchDown(pid, tx, ty)
                    }
                    ButtonMode.TAP -> {
                        val tapDuration = 42L + (random.nextFloat() * 36f).toLong() // 42ms to 78ms
                        val driftX = (random.nextFloat() * 2f - 1f) * 2.5f // +/- 2.5px micro-drift
                        val driftY = (random.nextFloat() * 2f - 1f) * 2.5f

                        injector.touchDown(pid, tx, ty)
                        scope.launch {
                            delay(tapDuration / 2)
                            injector.touchMove(pid, tx + driftX, ty + driftY)
                            delay(tapDuration - (tapDuration / 2))
                            injector.touchUp(pid, tx + driftX, ty + driftY)
                            synchronized(lock) {
                                activePointers.remove(btn.id)
                                freePointers.add(pid)
                            }
                        }
                    }
                    ButtonMode.SLIDE_CANCEL -> {
                        scope.launch {
                            val tap1Duration = 38L + (random.nextFloat() * 20f).toLong()
                            val tap1DriftX = (random.nextFloat() * 2f - 1f) * 2.0f
                            val tap1DriftY = (random.nextFloat() * 2f - 1f) * 2.0f

                            // 1. Initial slide tap with micro-drift
                            injector.touchDown(pid, tx, ty)
                            delay(tap1Duration / 2)
                            injector.touchMove(pid, tx + tap1DriftX, ty + tap1DriftY)
                            delay(tap1Duration - (tap1Duration / 2))
                            injector.touchUp(pid, tx + tap1DriftX, ty + tap1DriftY)

                            // 2. CoD Mobile slide animation trigger window (randomized 120ms - 145ms)
                            val windowDelay = 120L + (random.nextFloat() * 25f).toLong()
                            delay(windowDelay)

                            // 3. Second tap with micro-drift
                            val tap2Duration = 38L + (random.nextFloat() * 20f).toLong()
                            val tap2DriftX = (random.nextFloat() * 2f - 1f) * 2.0f
                            val tap2DriftY = (random.nextFloat() * 2f - 1f) * 2.0f

                            injector.touchDown(pid, tx, ty)
                            delay(tap2Duration / 2)
                            injector.touchMove(pid, tx + tap2DriftX, ty + tap2DriftY)
                            delay(tap2Duration - (tap2Duration / 2))
                            injector.touchUp(pid, tx + tap2DriftX, ty + tap2DriftY)

                            synchronized(lock) {
                                activePointers.remove(btn.id)
                                freePointers.add(pid)
                            }
                        }
                    }
                }
            }
        }
    }

    fun onButtonUp(buttonName: String) {
        val matchedButtons = buttons.filter { it.gamepadButton.equals(buttonName, ignoreCase = true) }
        if (matchedButtons.isEmpty()) return

        for (btn in matchedButtons) {
            if (btn.mode == ButtonMode.HOLD) {
                val screenW = injector.screenWidth
                val screenH = injector.screenHeight
                val tx = btn.x * screenW
                val ty = btn.y * screenH

                var pointerId: Int?
                synchronized(lock) {
                    pointerId = activePointers.remove(btn.id)
                    pointerId?.let { freePointers.add(it) }
                }

                pointerId?.let { pid ->
                    injector.touchUp(pid, tx, ty)
                }
            }
        }
    }

    fun releaseAll() {
        synchronized(lock) {
            for ((btnId, pid) in activePointers) {
                val btn = buttons.find { it.id == btnId }
                val tx = (btn?.x ?: 0.5f) * injector.screenWidth
                val ty = (btn?.y ?: 0.5f) * injector.screenHeight
                injector.touchUp(pid, tx, ty)
            }
            activePointers.clear()
            freePointers.clear()
            freePointers.addAll(POINTER_BUTTON_START until MAX_POINTERS)
        }
    }

    fun isButtonActive(predicate: (ButtonConfig) -> Boolean): Boolean {
        synchronized(lock) {
            return buttons.any { btn -> activePointers.containsKey(btn.id) && predicate(btn) }
        }
    }

    fun isFireActive(): Boolean {
        return isButtonActive {
            it.id.contains("fire", ignoreCase = true) ||
            it.label.contains("tir", ignoreCase = true) ||
            it.gamepadButton.equals("BUTTON_R2", ignoreCase = true)
        }
    }
}
