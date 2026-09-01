package com.kinou.gameassist.engine

import com.kinou.gameassist.data.model.JoystickConfig
import com.kinou.gameassist.injector.ShizukuTouchInjector
import kotlin.random.Random
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class MovementProcessor(
    private val injector: ShizukuTouchInjector,
    @Volatile var config: JoystickConfig = JoystickConfig()
) {
    companion object {
        const val POINTER_JOYSTICK = 0
        const val BASE_HALF_CYCLE_MS = 180L
        const val BASE_AMPLITUDE_X = 0.75f
    }

    @Volatile private var isActive = false
    @Volatile private var ditherPhase = 0f

    // Organic humanization state
    @Volatile private var isJigglingActive = false
    @Volatile private var currentDirection = 1 // 1 = Right, -1 = Left
    @Volatile private var lastFinishedDirection = -1 // Memory of last direction completed across bursts
    @Volatile private var halfCycleStartTime = 0L
    @Volatile private var currentHalfCycleDuration = BASE_HALF_CYCLE_MS
    @Volatile private var currentTargetAmplitudeX = BASE_AMPLITUDE_X
    @Volatile private var currentTargetDriftY = 0f
    @Volatile private var startAmplitudeX = 0f
    @Volatile private var startDriftY = 0f
    private val random = Random

    fun process(
        lx: Float,
        ly: Float,
        isAimingOrCameraActive: Boolean = false,
        isFiring: Boolean = false
    ) {
        // Snapshot local immuable de la config pour toute la frame :
        // évite de mixer plusieurs snapshots si setProfile() échange la référence.
        val cfg = config
        if (!cfg.enabled) {
            if (isActive) release()
            return
        }

        val screenW = injector.screenWidth
        val screenH = injector.screenHeight

        val centerX = cfg.centerX * screenW
        val centerY = cfg.centerY * screenH
        val radiusPx = cfg.radius * screenH

        val innerDeadzone = cfg.deadzone
        val outerDeadzone = cfg.outerDeadzone.coerceIn(innerDeadzone + 0.05f, 1.0f)

        // 1. Calculate Jiggle Strafe modifier if enabled and firing
        val isJiggling = cfg.jiggleStrafe && isFiring
        var effectiveLx = lx
        var effectiveLy = ly

        if (isJiggling) {
            val now = System.currentTimeMillis()
            val speedFactor = cfg.jiggleSpeed.coerceIn(0.5f, 2.0f)
            val baseDuration = (BASE_HALF_CYCLE_MS / speedFactor).toLong()

            if (!isJigglingActive) {
                // Initialize a new jiggle burst
                isJigglingActive = true
                halfCycleStartTime = now
                currentDirection = if (cfg.jiggleRandomPattern) {
                    if (random.nextBoolean()) 1 else -1
                } else {
                    // Strict 1:1 alternating mode: alternate from the last finished direction
                    if (lastFinishedDirection == 1) -1 else 1
                }
                startAmplitudeX = 0f
                startDriftY = 0f
                computeNextHalfCycle(baseDuration, cfg)
            }

            var elapsed = now - halfCycleStartTime
            if (elapsed >= currentHalfCycleDuration) {
                // Step transition to the next side
                halfCycleStartTime = now
                startAmplitudeX = currentTargetAmplitudeX
                startDriftY = currentTargetDriftY
                lastFinishedDirection = currentDirection

                if (cfg.jiggleRandomPattern) {
                    // Random pattern / feint / multiple strafes on the same side
                    val doFeint = random.nextFloat() < 0.25f
                    currentDirection = if (doFeint) currentDirection else -currentDirection
                } else {
                    // Strict 1:1 alternating mode: strictly alternate 1 side then the other
                    currentDirection = -currentDirection
                }

                computeNextHalfCycle(baseDuration, cfg)
                elapsed = 0L
            }

            // Smooth cosine interpolation between previous point and target point
            val progress = (elapsed.toFloat() / currentHalfCycleDuration.toFloat()).coerceIn(0f, 1f)
            val smoothT = 0.5f * (1f - cos(progress * Math.PI.toFloat()))

            val jiggleAmountX = startAmplitudeX + (currentTargetAmplitudeX - startAmplitudeX) * smoothT
            val jiggleAmountY = startDriftY + (currentTargetDriftY - startDriftY) * smoothT

            if (hypot(lx, ly) <= innerDeadzone) {
                // Standing still: pure lateral strafe oscillation + subtle human Y-drift
                effectiveLx = jiggleAmountX
                effectiveLy = jiggleAmountY
            } else {
                // Moving: blend manual stick input with humanized lateral oscillation
                effectiveLx = (lx + jiggleAmountX * 0.50f).coerceIn(-1f, 1f)
                effectiveLy = (ly + jiggleAmountY * 0.50f).coerceIn(-1f, 1f)
            }
        } else {
            if (isJigglingActive) {
                isJigglingActive = false
                lastFinishedDirection = currentDirection
                startAmplitudeX = 0f
                startDriftY = 0f
            }
        }

        val mag = hypot(effectiveLx, effectiveLy)

        if (mag > innerDeadzone) {
            ditherPhase = 0f
            val normalizedMag = ((mag - innerDeadzone) / (outerDeadzone - innerDeadzone)).coerceIn(0f, 1f)
            val dirX = effectiveLx / mag
            val dirY = effectiveLy / mag

            // Sprint acceleration factor
            val sprintFactor = if (mag >= cfg.sprintThreshold) 1.25f else 1.0f
            val currentRadius = radiusPx * normalizedMag * sprintFactor

            val targetX = centerX + (dirX * currentRadius)
            val targetY = centerY + (dirY * currentRadius)

            if (!isActive) {
                injector.touchDown(POINTER_JOYSTICK, centerX, centerY)
                isActive = true
            }
            injector.touchMove(POINTER_JOYSTICK, targetX, targetY)
        } else if (cfg.raaKeepAlive && isAimingOrCameraActive) {
            // Rotational Aim Assist (RAA) Keep-Alive:
            // Injects sub-pixel micro-strafe oscillations (3.5% radius) to maintain active in-game tracking bubble
            ditherPhase += 0.40f
            val ditherOffset = sin(ditherPhase.toDouble()).toFloat() * (radiusPx * 0.035f)
            val targetX = centerX + ditherOffset
            val targetY = centerY

            if (!isActive) {
                injector.touchDown(POINTER_JOYSTICK, centerX, centerY)
                isActive = true
            }
            injector.touchMove(POINTER_JOYSTICK, targetX, targetY)
        } else {
            if (isActive) {
                release()
            }
        }
    }

    private fun computeNextHalfCycle(baseDuration: Long, cfg: JoystickConfig) {
        if (cfg.jiggleHumanize) {
            val rand = cfg.jiggleRandomness.coerceIn(0f, 1f)
            // Duration jitter: +/- 40% of randomness (e.g. at 0.35 randomness -> +/- 14% timing jitter)
            val durationNoise = (random.nextFloat() * 2f - 1f) * (0.40f * rand)
            currentHalfCycleDuration = (baseDuration * (1.0f + durationNoise)).toLong().coerceIn(60L, 450L)

            // Amplitude variance: +/- 25% of randomness
            val ampNoise = (random.nextFloat() * 2f - 1f) * (0.25f * rand)
            val amplitude = (BASE_AMPLITUDE_X * (1.0f + ampNoise)).coerceIn(0.40f, 0.95f)
            currentTargetAmplitudeX = currentDirection * amplitude

            // Micro Y-axis drift (subtle thumb tilt): up to +/- 10%
            currentTargetDriftY = (random.nextFloat() * 2f - 1f) * (0.10f * rand)
        } else {
            currentHalfCycleDuration = baseDuration
            currentTargetAmplitudeX = currentDirection * BASE_AMPLITUDE_X
            currentTargetDriftY = 0f
        }
    }

    fun release() {
        synchronized(this) {
            if (isActive) {
                val cfg = config
                val screenW = injector.screenWidth
                val screenH = injector.screenHeight
                val centerX = cfg.centerX * screenW
                val centerY = cfg.centerY * screenH
                injector.touchUp(POINTER_JOYSTICK, centerX, centerY)
                isActive = false
                ditherPhase = 0f
                isJigglingActive = false
                startAmplitudeX = 0f
                startDriftY = 0f
            }
        }
    }
}

