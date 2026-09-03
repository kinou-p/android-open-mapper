package com.kinou.gameassist.engine

import com.kinou.gameassist.data.model.ButtonConfig
import com.kinou.gameassist.data.model.ButtonMode
import com.kinou.gameassist.data.model.ButtonRole
import com.kinou.gameassist.data.model.GameSettings
import com.kinou.gameassist.injector.ShizukuTouchInjector
import kotlinx.coroutines.*
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

    private class PendingTapSlot {
        @Volatile var active: Boolean = false
        var pointerId: Int = 0
        var btnId: String = ""
        var startX: Float = 0f
        var startY: Float = 0f
        var endX: Float = 0f
        var endY: Float = 0f
        var moveTimeNanos: Long = 0L
        var releaseTimeNanos: Long = 0L
        var moved: Boolean = false
    }

    @Volatile private var buttons: List<ButtonConfig> = emptyList()
    @Volatile private var buttonsByGamepadKey: Map<String, List<ButtonConfig>> = emptyMap()
    @Volatile private var settings: GameSettings = GameSettings()
    private val activePointers = ConcurrentHashMap<String, Int>()
    private val freePointers = (POINTER_BUTTON_START until MAX_POINTERS).toMutableSet()
    private val pendingTapSlots = Array(16) { PendingTapSlot() }
    private val activeTapCount = AtomicInteger(0)
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
            val oldButtonsById = buttons.associateBy { it.id }
            val snapshot = list.map { it.copy() }
            val newIds = snapshot.map { it.id }.toSet()

            // Réconciliation : libération et touchUp des pointeurs orphelins après un hot-switch
            val orphaned = activePointers.keys.filter { it !in newIds }
            for (id in orphaned) {
                val pid = activePointers.remove(id)
                if (pid != null) {
                    freePointers.add(pid)
                    val oldBtn = oldButtonsById[id]
                    val tx = (oldBtn?.x ?: 0.5f) * injector.screenWidth
                    val ty = (oldBtn?.y ?: 0.5f) * injector.screenHeight
                    injector.touchUp(pid, tx, ty)
                }
            }

            // Nettoie également les taps asynchrones et rapid fire en cours pour les boutons supprimés
            for (slot in pendingTapSlots) {
                if (slot.active && slot.btnId !in newIds) {
                    slot.active = false
                    activeTapCount.decrementAndGet()
                }
            }
            val removedRapidJobs = activeRapidFireJobs.keys.filter { it !in newIds }
            for (id in removedRapidJobs) {
                activeRapidFireJobs.remove(id)?.cancel()
            }

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

    // Tactical shortcuts callbacks
    var onToggleRecoil: (() -> Unit)? = null
    var onToggleStrafe: (() -> Unit)? = null
    var onSwitchProfile: (() -> Unit)? = null

    private val activeRapidFireJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    fun updateSettings(newSettings: GameSettings) {
        synchronized(lock) {
            settings = newSettings.copy()
        }
    }

    fun onButtonDown(buttonName: String) {
        val matchedButtons = buttonsByGamepadKey[buttonName.trim().uppercase()] ?: return
        if (matchedButtons.isEmpty()) return

        for (btn in matchedButtons) {
            // Handle Tactical Shortcut Roles (No touch injection)
            when (btn.role) {
                ButtonRole.TOGGLE_RECOIL -> {
                    onToggleRecoil?.invoke()
                    if (settings.hapticFeedback) {
                        hapticManager?.playProfileSwitchHaptic()
                    }
                    continue
                }
                ButtonRole.TOGGLE_STRAFE -> {
                    onToggleStrafe?.invoke()
                    if (settings.hapticFeedback) {
                        hapticManager?.playProfileSwitchHaptic()
                    }
                    continue
                }
                ButtonRole.SWITCH_PROFILE -> {
                    onSwitchProfile?.invoke()
                    if (settings.hapticFeedback) {
                        hapticManager?.playProfileSwitchHaptic()
                    }
                    continue
                }
                else -> {}
            }

            // Trigger haptic feedback for Fire and Reload actions
            if (settings.hapticFeedback) {
                val isFire = fireButtonIds.contains(btn.id)
                val isReload = reloadButtonIds.contains(btn.id)

                if (isFire && settings.hapticFire) {
                    hapticManager?.playFireHaptic(intensity = settings.hapticIntensity)
                } else if (isReload && settings.hapticReload) {
                    hapticManager?.playReloadHaptic(intensity = settings.hapticIntensity)
                }
            }

            val mode = (btn.mode as ButtonMode?) ?: ButtonMode.HOLD

            // Mode 1: RAPID FIRE (Turbo semi-auto loop)
            if (mode == ButtonMode.RAPID_FIRE) {
                activeRapidFireJobs.remove(btn.id)?.cancel()

                var pid: Int? = null
                synchronized(lock) {
                    if (!activePointers.containsKey(btn.id)) {
                        val allocated = freePointers.minOrNull()
                        if (allocated != null) {
                            freePointers.remove(allocated)
                            activePointers[btn.id] = allocated
                            pid = allocated
                            if (fireButtonIds.contains(btn.id)) activeFireCount.incrementAndGet()
                            if (adsButtonIds.contains(btn.id)) activeAdsCount.incrementAndGet()
                        }
                    } else {
                        pid = activePointers[btn.id]
                    }
                }

                val assignedPid = pid ?: continue

                val job = scope.launch(kotlinx.coroutines.Dispatchers.Default) {
                    val rateHz = btn.rapidFireRateHz.coerceIn(4, 30)
                    val cycleNanos = 1_000_000_000L / rateHz
                    val downNanos = (cycleNanos * 0.45f).toLong()
                    val screenW = injector.screenWidth
                    val screenH = injector.screenHeight

                    try {
                        while (isActive) {
                            val driftX = (Random.nextFloat() * 2f - 1f) * 2.5f
                            val driftY = (Random.nextFloat() * 2f - 1f) * 2.5f
                            val tx = btn.x * screenW + driftX
                            val ty = btn.y * screenH + driftY

                            injector.touchDown(assignedPid, tx, ty)
                            if (settings.hapticFeedback && settings.hapticFire && fireButtonIds.contains(btn.id)) {
                                hapticManager?.playFireHaptic(intensity = settings.hapticIntensity)
                            }

                            val actualDownMs = ((downNanos / 1_000_000L) + Random.nextLong(-4, 5)).coerceAtLeast(15L)
                            kotlinx.coroutines.delay(actualDownMs)

                            injector.touchUp(assignedPid, tx, ty)

                            val upNanos = cycleNanos - downNanos
                            val actualUpMs = ((upNanos / 1_000_000L) + Random.nextLong(-4, 5)).coerceAtLeast(15L)
                            kotlinx.coroutines.delay(actualUpMs)
                        }
                    } finally {
                        withContext(NonCancellable) {
                            var shouldTouchUp = false
                            val tx = btn.x * screenW
                            val ty = btn.y * screenH
                            val currentJob = coroutineContext[Job]
                            synchronized(lock) {
                                if (activeRapidFireJobs[btn.id] === currentJob) {
                                    activeRapidFireJobs.remove(btn.id)
                                    if (activePointers[btn.id] == assignedPid) {
                                        activePointers.remove(btn.id)
                                        freePointers.add(assignedPid)
                                        if (fireButtonIds.contains(btn.id)) activeFireCount.updateAndGet { c -> maxOf(0, c - 1) }
                                        if (adsButtonIds.contains(btn.id)) activeAdsCount.updateAndGet { c -> maxOf(0, c - 1) }
                                        shouldTouchUp = true
                                    }
                                }
                            }
                            if (shouldTouchUp) {
                                injector.touchUp(assignedPid, tx, ty)
                            }
                        }
                    }
                }
                activeRapidFireJobs[btn.id] = job
                continue
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

                        // Remove existing pending tap slot for this button if any
                        for (slot in pendingTapSlots) {
                            if (slot.active && slot.btnId == btn.id) {
                                slot.active = false
                                activeTapCount.decrementAndGet()
                            }
                        }
                        val freeSlot = pendingTapSlots.firstOrNull { !it.active }
                        if (freeSlot != null) {
                            freeSlot.pointerId = pid
                            freeSlot.btnId = btn.id
                            freeSlot.startX = tx
                            freeSlot.startY = ty
                            freeSlot.endX = tx + driftX
                            freeSlot.endY = ty + driftY
                            freeSlot.moveTimeNanos = moveNanos
                            freeSlot.releaseTimeNanos = releaseNanos
                            freeSlot.moved = false
                            freeSlot.active = true
                            activeTapCount.incrementAndGet()
                        }
                    }
                    ButtonMode.RAPID_FIRE -> {}
                }
            }
        }
    }

    fun onButtonUp(buttonName: String) {
        val matchedButtons = buttonsByGamepadKey[buttonName.trim().uppercase()] ?: return
        if (matchedButtons.isEmpty()) return

        for (btn in matchedButtons) {
            val mode = (btn.mode as ButtonMode?) ?: ButtonMode.HOLD
            if (mode == ButtonMode.RAPID_FIRE) {
                activeRapidFireJobs.remove(btn.id)?.cancel()
                val screenW = injector.screenWidth
                val screenH = injector.screenHeight
                val tx = btn.x * screenW
                val ty = btn.y * screenH
                var pointerId: Int?
                synchronized(lock) {
                    pointerId = activePointers.remove(btn.id)
                    pointerId?.let {
                        freePointers.add(it)
                        if (fireButtonIds.contains(btn.id)) activeFireCount.updateAndGet { c -> maxOf(0, c - 1) }
                        if (adsButtonIds.contains(btn.id)) activeAdsCount.updateAndGet { c -> maxOf(0, c - 1) }
                    }
                }
                pointerId?.let { pid ->
                    injector.touchUp(pid, tx, ty)
                }
                continue
            }

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
        if (activeTapCount.get() <= 0) return

        for (i in pendingTapSlots.indices) {
            val tap = pendingTapSlots[i]
            if (!tap.active) continue

            if (!tap.moved && nowNanos >= tap.moveTimeNanos) {
                tap.moved = true
                injector.touchMove(tap.pointerId, tap.endX, tap.endY)
            }
            if (nowNanos >= tap.releaseTimeNanos) {
                injector.touchUp(tap.pointerId, tap.endX, tap.endY)
                val btnId = tap.btnId
                val pid = tap.pointerId
                tap.active = false
                activeTapCount.decrementAndGet()
                synchronized(lock) {
                    if (activePointers[btnId] == pid) {
                        activePointers.remove(btnId)
                        freePointers.add(pid)
                        if (fireButtonIds.contains(btnId)) activeFireCount.updateAndGet { c -> maxOf(0, c - 1) }
                        if (adsButtonIds.contains(btnId)) activeAdsCount.updateAndGet { c -> maxOf(0, c - 1) }
                    }
                }
            }
        }
    }

    fun releaseAll() {
        synchronized(lock) {
            for (slot in pendingTapSlots) {
                slot.active = false
            }
            activeTapCount.set(0)
            for ((_, job) in activeRapidFireJobs) {
                job.cancel()
            }
            activeRapidFireJobs.clear()

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
