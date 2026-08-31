package com.kinou.gameassist.engine

import com.kinou.gameassist.data.model.ButtonConfig
import com.kinou.gameassist.data.model.ButtonMode
import com.kinou.gameassist.data.model.ButtonRole
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
                val isFire = isFireButton(btn)
                val isReload = isReloadButton(btn)

                if (isFire && settings.hapticFire) {
                    hapticManager?.playFireHaptic(
                        intensity = settings.hapticIntensity,
                        targetDevice = settings.hapticDevice,
                        targetController = settings.hapticController
                    )
                } else if (isReload && settings.hapticReload) {
                    hapticManager?.playReloadHaptic(
                        intensity = settings.hapticIntensity,
                        targetDevice = settings.hapticDevice,
                        targetController = settings.hapticController
                    )
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

    fun isFireButton(btn: ButtonConfig): Boolean {
        if (btn.role == ButtonRole.FIRE) return true
        if (btn.role != ButtonRole.NORMAL) return false
        // Legacy fallback if role wasn't explicitly set
        val fireKeywords = arrayOf("fire", "tir", "shoot", "shot", "dispar", "tiro", "schuss", "fuego", "attak", "attack")
        val matchId = fireKeywords.any { btn.id.contains(it, ignoreCase = true) }
        val matchLabel = fireKeywords.any { btn.label.contains(it, ignoreCase = true) }
        val matchGamepad = btn.gamepadButton.equals("BUTTON_R2", ignoreCase = true) ||
                           btn.gamepadButton.equals("TRIGGER_R2", ignoreCase = true) ||
                           btn.gamepadButton.equals("AXIS_GAS", ignoreCase = true) ||
                           btn.gamepadButton.equals("AXIS_RTRIGGER", ignoreCase = true)
        return matchId || matchLabel || matchGamepad
    }

    fun isReloadButton(btn: ButtonConfig): Boolean {
        if (btn.role == ButtonRole.RELOAD) return true
        if (btn.role != ButtonRole.NORMAL) return false
        // Legacy fallback if role wasn't explicitly set
        val reloadKeywords = arrayOf("reload", "recharg", "recarg", "recarreg", "nachlad", "ricarica", "charger")
        val matchId = reloadKeywords.any { btn.id.contains(it, ignoreCase = true) }
        val matchLabel = reloadKeywords.any { btn.label.contains(it, ignoreCase = true) }
        val matchGamepad = btn.gamepadButton.equals("BUTTON_X", ignoreCase = true) && (matchId || matchLabel)
        return matchId || matchLabel || matchGamepad
    }

    fun isAdsButton(btn: ButtonConfig): Boolean {
        if (btn.role == ButtonRole.ADS) return true
        if (btn.role != ButtonRole.NORMAL) return false
        // Legacy fallback if role wasn't explicitly set
        val adsKeywords = arrayOf("ads", "visee", "visée", "aim", "scope", "mira", "apuntar")
        val matchId = adsKeywords.any { btn.id.contains(it, ignoreCase = true) }
        val matchLabel = adsKeywords.any { btn.label.contains(it, ignoreCase = true) }
        val matchGamepad = btn.gamepadButton.equals("BUTTON_L2", ignoreCase = true) ||
                           btn.gamepadButton.equals("TRIGGER_L2", ignoreCase = true) ||
                           btn.gamepadButton.equals("AXIS_BRAKE", ignoreCase = true) ||
                           btn.gamepadButton.equals("AXIS_LTRIGGER", ignoreCase = true)
        return matchId || matchLabel || matchGamepad
    }

    fun isFireActive(): Boolean {
        return isButtonActive { isFireButton(it) }
    }

    fun isAdsActive(): Boolean {
        return isButtonActive { isAdsButton(it) }
    }
}
