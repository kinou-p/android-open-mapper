package com.kinou.gameassist.engine

import com.kinou.gameassist.data.model.ButtonConfig
import com.kinou.gameassist.data.model.ButtonMode
import com.kinou.gameassist.data.model.ButtonRole
import com.kinou.gameassist.data.model.GameSettings
import com.kinou.gameassist.injector.ShizukuTouchInjector
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class ButtonProcessor(
    private val injector: ShizukuTouchInjector,
    private val scope: CoroutineScope,
    var hapticManager: HapticManager? = null
) {
    companion object {
        const val POINTER_BUTTON_START = 3
        const val MAX_POINTERS = 10

        private val FIRE_KEYWORDS = arrayOf("fire", "tir", "shoot", "shot", "dispar", "tiro", "schuss", "fuego", "attak", "attack")
        private val RELOAD_KEYWORDS = arrayOf("reload", "recharg", "recarg", "recarreg", "nachlad", "ricarica", "charger")
        private val ADS_KEYWORDS = arrayOf("ads", "visee", "visée", "aim", "scope", "mira", "apuntar")

        fun isFireButtonStatic(btn: ButtonConfig): Boolean {
            val role = (btn.role as ButtonRole?) ?: ButtonRole.NORMAL
            if (role == ButtonRole.FIRE) return true
            if (role != ButtonRole.NORMAL) return false
            val matchId = FIRE_KEYWORDS.any { btn.id.contains(it, ignoreCase = true) }
            val matchLabel = FIRE_KEYWORDS.any { btn.label.contains(it, ignoreCase = true) }
            val matchGamepad = btn.gamepadButton.equals("BUTTON_R2", ignoreCase = true) ||
                               btn.gamepadButton.equals("TRIGGER_R2", ignoreCase = true) ||
                               btn.gamepadButton.equals("AXIS_GAS", ignoreCase = true) ||
                               btn.gamepadButton.equals("AXIS_RTRIGGER", ignoreCase = true)
            return matchId || matchLabel || matchGamepad
        }

        fun isReloadButtonStatic(btn: ButtonConfig): Boolean {
            val role = (btn.role as ButtonRole?) ?: ButtonRole.NORMAL
            if (role == ButtonRole.RELOAD) return true
            if (role != ButtonRole.NORMAL) return false
            val matchId = RELOAD_KEYWORDS.any { btn.id.contains(it, ignoreCase = true) }
            val matchLabel = RELOAD_KEYWORDS.any { btn.label.contains(it, ignoreCase = true) }
            val matchGamepad = btn.gamepadButton.equals("BUTTON_X", ignoreCase = true) && (matchId || matchLabel)
            return matchId || matchLabel || matchGamepad
        }

        fun isAdsButtonStatic(btn: ButtonConfig): Boolean {
            val role = (btn.role as ButtonRole?) ?: ButtonRole.NORMAL
            if (role == ButtonRole.ADS) return true
            if (role != ButtonRole.NORMAL) return false
            val matchId = ADS_KEYWORDS.any { btn.id.contains(it, ignoreCase = true) }
            val matchLabel = ADS_KEYWORDS.any { btn.label.contains(it, ignoreCase = true) }
            val matchGamepad = btn.gamepadButton.equals("BUTTON_L2", ignoreCase = true) ||
                               btn.gamepadButton.equals("TRIGGER_L2", ignoreCase = true) ||
                               btn.gamepadButton.equals("AXIS_BRAKE", ignoreCase = true) ||
                               btn.gamepadButton.equals("AXIS_LTRIGGER", ignoreCase = true)
            return matchId || matchLabel || matchGamepad
        }
    }

    private data class PendingTap(
        val pointerId: Int,
        val btnId: String,
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val moveTimeNanos: Long,
        val releaseTimeNanos: Long,
        var moved: Boolean = false
    )

    @Volatile private var buttons: List<ButtonConfig> = emptyList()
    @Volatile private var buttonsByGamepadKey: Map<String, List<ButtonConfig>> = emptyMap()
    @Volatile private var settings: GameSettings = GameSettings()
    private val activePointers = ConcurrentHashMap<String, Int>()
    private val freePointers = (POINTER_BUTTON_START until MAX_POINTERS).toMutableSet()
    private val pendingTaps = ConcurrentLinkedQueue<PendingTap>()
    private val lock = Any()

    // Pre-calculated sets for O(1) role lookup
    @Volatile private var fireButtonIds: Set<String> = emptySet()
    @Volatile private var adsButtonIds: Set<String> = emptySet()
    @Volatile private var reloadButtonIds: Set<String> = emptySet()

    // Lock-free atomic counters for ultra-fast 240Hz engine queries
    private val activeFireCount = AtomicInteger(0)
    private val activeAdsCount = AtomicInteger(0)

    fun updateButtons(list: List<ButtonConfig>) {
        synchronized(lock) {
            // Copie défensive : on fige un snapshot immuable pour que les threads du lecteur
            // /dev/input (lecture sans lock) et l'éditeur HUD (mutation en place de la liste
            // passée par le profil) ne puissent pas s'interférer. Les champs sont @Volatile
            // pour garantir la publication sûre de la nouvelle référence.
            val snapshot = list.map { it.copy() }
            buttons = snapshot
            buttonsByGamepadKey = snapshot.groupBy { it.gamepadButton.trim().uppercase() }
            fireButtonIds = snapshot.filter { isFireButtonStatic(it) }.map { it.id }.toSet()
            adsButtonIds = snapshot.filter { isAdsButtonStatic(it) }.map { it.id }.toSet()
            reloadButtonIds = snapshot.filter { isReloadButtonStatic(it) }.map { it.id }.toSet()

            // Recompute active counts in case buttons changed
            var fireCount = 0
            var adsCount = 0
            for (btnId in activePointers.keys) {
                if (fireButtonIds.contains(btnId)) fireCount++
                if (adsButtonIds.contains(btnId)) adsCount++
            }
            activeFireCount.set(fireCount)
            activeAdsCount.set(adsCount)
        }
    }

    fun updateSettings(newSettings: GameSettings) {
        synchronized(lock) {
            settings = newSettings.copy()
        }
    }

    fun onButtonDown(buttonName: String) {
        val matchedButtons = buttonsByGamepadKey[buttonName.trim().uppercase()] ?: return
        if (matchedButtons.isEmpty()) return

        for (btn in matchedButtons) {
            // Trigger haptic feedback for Fire and Reload actions
            if (settings.hapticFeedback) {
                val isFire = fireButtonIds.contains(btn.id)
                val isReload = reloadButtonIds.contains(btn.id)

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
                    val allocated = freePointers.minOrNull()
                    if (allocated == null) {
                        // Pool saturé (> 7 boutons simultanés) : on rejette proprement cet appui
                        // pour éviter la collision sur un ID déjà utilisé (bug critique pointer ID 3).
                        isAlreadyActive = true
                    } else {
                        freePointers.remove(allocated)
                        activePointers[btn.id] = allocated
                        pointerId = allocated

                        if (fireButtonIds.contains(btn.id)) activeFireCount.incrementAndGet()
                        if (adsButtonIds.contains(btn.id)) activeAdsCount.incrementAndGet()
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
                val mode = (btn.mode as ButtonMode?) ?: ButtonMode.HOLD

                when (mode) {
                    ButtonMode.HOLD -> {
                        injector.touchDown(pid, tx, ty)
                    }
                    ButtonMode.TAP -> {
                        val tapDuration = 42L + (Random.nextFloat() * 36f).toLong() // 42ms to 78ms
                        val driftX = (Random.nextFloat() * 2f - 1f) * 2.5f // +/- 2.5px micro-drift
                        val driftY = (Random.nextFloat() * 2f - 1f) * 2.5f
                        val nowNanos = System.nanoTime()
                        val moveNanos = nowNanos + (tapDuration / 2) * 1_000_000L
                        val releaseNanos = nowNanos + tapDuration * 1_000_000L

                        // Immediate touch down
                        injector.touchDown(pid, tx, ty)

                        // Remove existing pending tap for this button if any
                        pendingTaps.removeIf { it.btnId == btn.id }
                        pendingTaps.add(
                            PendingTap(
                                pointerId = pid,
                                btnId = btn.id,
                                startX = tx,
                                startY = ty,
                                endX = tx + driftX,
                                endY = ty + driftY,
                                moveTimeNanos = moveNanos,
                                releaseTimeNanos = releaseNanos
                            )
                        )
                    }
                }
            }
        }
    }

    fun onButtonUp(buttonName: String) {
        val matchedButtons = buttonsByGamepadKey[buttonName.trim().uppercase()] ?: return
        if (matchedButtons.isEmpty()) return

        for (btn in matchedButtons) {
            val mode = (btn.mode as ButtonMode?) ?: ButtonMode.HOLD
            if (mode == ButtonMode.HOLD) {
                val screenW = injector.screenWidth
                val screenH = injector.screenHeight
                val tx = btn.x * screenW
                val ty = btn.y * screenH

                var pointerId: Int?
                synchronized(lock) {
                    pointerId = activePointers.remove(btn.id)
                    pointerId?.let {
                        freePointers.add(it)
                        // updateAndGet avec max(0, ...) pour éviter les valeurs négatives
                        // en cas d'événement UP orphelin (déconnexion manette mid-game).
                        if (fireButtonIds.contains(btn.id)) activeFireCount.updateAndGet { c -> maxOf(0, c - 1) }
                        if (adsButtonIds.contains(btn.id)) activeAdsCount.updateAndGet { c -> maxOf(0, c - 1) }
                    }
                }

                pointerId?.let { pid ->
                    injector.touchUp(pid, tx, ty)
                }
            }
        }
    }

    /**
     * Called in the high-frequency engine loop to process TAP events without coroutines or GC pressure.
     */
    fun processPendingTaps(nowNanos: Long) {
        if (pendingTaps.isEmpty()) return
        val it = pendingTaps.iterator()
        while (it.hasNext()) {
            val tap = it.next()
            if (!tap.moved && nowNanos >= tap.moveTimeNanos) {
                tap.moved = true
                injector.touchMove(tap.pointerId, tap.endX, tap.endY)
            }
            if (nowNanos >= tap.releaseTimeNanos) {
                injector.touchUp(tap.pointerId, tap.endX, tap.endY)
                synchronized(lock) {
                    if (activePointers[tap.btnId] == tap.pointerId) {
                        activePointers.remove(tap.btnId)
                        freePointers.add(tap.pointerId)
                        if (fireButtonIds.contains(tap.btnId)) activeFireCount.updateAndGet { c -> maxOf(0, c - 1) }
                        if (adsButtonIds.contains(tap.btnId)) activeAdsCount.updateAndGet { c -> maxOf(0, c - 1) }
                    }
                }
                it.remove()
            }
        }
    }

    fun releaseAll() {
        synchronized(lock) {
            pendingTaps.clear()

            for ((btnId, pid) in activePointers) {
                val btn = buttons.find { it.id == btnId }
                val tx = (btn?.x ?: 0.5f) * injector.screenWidth
                val ty = (btn?.y ?: 0.5f) * injector.screenHeight
                injector.touchUp(pid, tx, ty)
            }
            activePointers.clear()
            freePointers.clear()
            freePointers.addAll(POINTER_BUTTON_START until MAX_POINTERS)
            activeFireCount.set(0)
            activeAdsCount.set(0)
        }
    }

    fun isButtonActive(predicate: (ButtonConfig) -> Boolean): Boolean {
        synchronized(lock) {
            return buttons.any { btn -> activePointers.containsKey(btn.id) && predicate(btn) }
        }
    }

    fun isFireButton(btn: ButtonConfig): Boolean = isFireButtonStatic(btn)
    fun isReloadButton(btn: ButtonConfig): Boolean = isReloadButtonStatic(btn)
    fun isAdsButton(btn: ButtonConfig): Boolean = isAdsButtonStatic(btn)

    // Ultra fast O(1) Zero-Allocation and Lock-Free queries on the 120-240Hz hot path
    fun isFireActive(): Boolean = activeFireCount.get() > 0
    fun isAdsActive(): Boolean = activeAdsCount.get() > 0
}
