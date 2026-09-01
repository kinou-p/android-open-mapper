package com.kinou.gameassist.engine

import com.kinou.gameassist.data.model.CameraConfig
import com.kinou.gameassist.injector.ShizukuTouchInjector
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sign

class CameraProcessor(
    private val injector: ShizukuTouchInjector,
    @Volatile var config: CameraConfig = CameraConfig()
) {
    companion object {
        const val POINTER_CAM_A = 1
        const val POINTER_CAM_B = 2
    }

    @Volatile private var isActive = false
    @Volatile private var activePointerId = POINTER_CAM_A

    @Volatile private var currentX = 0.0f
    @Volatile private var currentY = 0.0f

    @Volatile private var prevSmoothDx = 0.0f
    @Volatile private var prevSmoothDy = 0.0f

    fun process(rx: Float, ry: Float, isAiming: Boolean = false, isFiring: Boolean = false) {
        // Snapshot local immuable de la config pour toute la frame.
        val cfg = config
        if (!cfg.enabled) {
            if (isActive) release()
            return
        }

        val screenW = injector.screenWidth
        val screenH = injector.screenHeight

        val x1 = cfg.rectX1 * screenW
        val y1 = cfg.rectY1 * screenH
        val x2 = cfg.rectX2 * screenW
        val y2 = cfg.rectY2 * screenH

        val origX = (x1 + x2) / 2.0f
        val origY = (y1 + y2) / 2.0f

        val mag = hypot(rx, ry)
        val innerDeadzone = cfg.deadzone
        val outerDeadzone = cfg.outerDeadzone.coerceIn(innerDeadzone + 0.05f, 1.0f)

        // Fine-grained dynamic resolution scaling: ~17.6px per frame at max tilt for 3200px screen
        val baseStep = screenW * 0.0055f

        // Anti-Recoil computation: pulls camera downwards (positive delta Y) during weapon fire
        val isRecoilActive = cfg.antiRecoilEnabled && isFiring && (!cfg.antiRecoilAdsOnly || isAiming)
        val recoilDy = if (isRecoilActive) {
            cfg.antiRecoilSpeed.coerceIn(0.0f, 20.0f) * (baseStep * 0.12f)
        } else {
            0.0f
        }

        if (mag > innerDeadzone || recoilDy > 0f) {
            val adsFactor = if (cfg.adsSensitivityEnabled && isAiming) cfg.adsSensitivityMultiplier.coerceIn(0.05f, 3.0f) else 1.0f

            var targetDx = 0.0f
            var targetDy = recoilDy

            if (mag > innerDeadzone) {
                val normMag = ((mag - innerDeadzone) / (outerDeadzone - innerDeadzone)).coerceIn(0f, 1f)
                val rawDirX = rx / mag
                val rawDirY = ry / mag
                val dirX = if (cfg.invertX) -rawDirX else rawDirX
                val dirY = if (cfg.invertY) -rawDirY else rawDirY

                var curvedMagX: Float
                var curvedMagY: Float

                when (cfg.responseCurve) {
                    com.kinou.gameassist.data.model.ResponseCurve.LINEAR -> {
                        curvedMagX = normMag
                        curvedMagY = normMag
                    }
                    com.kinou.gameassist.data.model.ResponseCurve.STANDARD -> {
                        val curved = normMag.pow(cfg.acceleration)
                        curvedMagX = curved
                        curvedMagY = curved
                    }
                    com.kinou.gameassist.data.model.ResponseCurve.DYNAMIC -> {
                        val curved = 0.30f * normMag + 0.70f * normMag.pow(2.2f)
                        curvedMagX = curved
                        curvedMagY = curved
                    }
                    com.kinou.gameassist.data.model.ResponseCurve.DYNAMIC_BOOST -> {
                        val threshold = cfg.flickThreshold.coerceIn(0.65f, 0.95f)
                        if (normMag <= threshold) {
                            val scale = normMag / threshold
                            val base = (0.25f * scale + 0.75f * scale.pow(2.2f)) * 0.85f
                            curvedMagX = base
                            curvedMagY = base
                        } else {
                            val turboT = (normMag - threshold) / (1.0f - threshold)
                            // ADS Safety: If aiming in ADS (LT held) and safety is enabled, dampen turbo boost
                            val effectiveBoost = if (cfg.flickAdsSafety && isAiming) 1.20f else cfg.flickBoost.coerceIn(1.2f, 5.0f)

                            // Horizontal (X) gets full turbo boost for 180° turns
                            curvedMagX = 0.85f + (turboT * (effectiveBoost - 0.85f))

                            // Vertical (Y) stays stable and level to prevent aiming at the sky/ground
                            curvedMagY = 0.85f + (turboT * 0.15f)
                        }
                    }
                }

                targetDx = dirX * curvedMagX * cfg.sensitivityX * adsFactor * baseStep
                targetDy += dirY * curvedMagY * cfg.sensitivityY * adsFactor * baseStep
            }

            // Exponential moving average (EMA) smoothing
            val alpha = (1.0f - cfg.smoothing.coerceIn(0f, 0.8f))
            val smoothDx = alpha * targetDx + (1.0f - alpha) * prevSmoothDx
            val smoothDy = alpha * targetDy + (1.0f - alpha) * prevSmoothDy
            prevSmoothDx = smoothDx
            prevSmoothDy = smoothDy

            if (!isActive) {
                activePointerId = POINTER_CAM_A
                currentX = origX
                currentY = origY
                injector.touchDown(activePointerId, currentX, currentY)
                isActive = true
            }

            currentX += smoothDx
            currentY += smoothDy

            val marginX = (x2 - x1) * 0.15f
            val marginY = (y2 - y1) * 0.15f

            val hitBorder = (currentX <= x1 + marginX || currentX >= x2 - marginX ||
                             currentY <= y1 + marginY || currentY >= y2 - marginY)

            if (hitBorder) {
                // Dual-Pointer Interlaced Handoff for 100% stutter-free 360° rotation (Atomic Transaction)
                val nextPointerId = if (activePointerId == POINTER_CAM_A) POINTER_CAM_B else POINTER_CAM_A

                injector.handoff(nextPointerId, origX, origY, activePointerId, currentX, currentY)

                activePointerId = nextPointerId
                currentX = origX
                currentY = origY
            } else {
                injector.touchMove(activePointerId, currentX, currentY)
            }
        } else {
            prevSmoothDx = 0.0f
            prevSmoothDy = 0.0f
            if (isActive) {
                release()
            }
        }
    }

    fun release() {
        synchronized(this) {
            if (isActive) {
                injector.touchUp(activePointerId, currentX, currentY)
                isActive = false
                prevSmoothDx = 0.0f
                prevSmoothDy = 0.0f
            }
        }
    }
}
