package com.kinou.gameassist.data.model

import android.content.Context
import android.hardware.input.InputManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.kinou.gameassist.util.InputDeviceCallbacks
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class GamepadDevice(
    val id: Int,
    val name: String,
    val isBluetooth: Boolean,
    val batteryPercent: Int?
)

object GamepadDetector {

    /**
     * Observe les connexions / déconnexions de manettes de manière purement événementielle
     * via InputManager.InputDeviceListener, sans polling CPU.
     */
    fun observeConnectedGamepads(context: Context): Flow<List<GamepadDevice>> = callbackFlow {
        val appContext = context.applicationContext
        val inputManager = appContext.getSystemService(Context.INPUT_SERVICE) as? InputManager

        // Publie la liste tout de suite (batterie lue depuis le cache, jamais bloquant),
        // puis rafraîchit la batterie en arrière-plan et republie quand elle est connue.
        fun publish() {
            val gamepads = getConnectedGamepads(appContext)
            trySend(gamepads)
            refreshBatteryAsync(gamepads.map { it.id }) {
                trySend(getConnectedGamepads(appContext))
            }
        }

        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) = publish()

            override fun onInputDeviceRemoved(deviceId: Int) {
                batteryCache.remove(deviceId)
                publish()
            }

            override fun onInputDeviceChanged(deviceId: Int) = publish()
        }

        inputManager?.registerInputDeviceListener(listener, InputDeviceCallbacks.handler)
        // Émettre l'état initial immédiatement
        publish()

        awaitClose {
            inputManager?.unregisterInputDeviceListener(listener)
        }
    }

    /**
     * Battery level cache (deviceId -> percent).
     *
     * ANR root cause: `InputDevice.getBatteryState()` is a synchronous Binder call that makes
     * system_server read the controller's power_supply sysfs node
     * (`EventHub::getBatteryStatus` -> `ReadFileToString`). For Bluetooth controllers that read
     * round-trips to the controller and can block for 10 s or more. It used to run inside
     * [getConnectedGamepads], which is called on the main thread (HomeScreen flow collection,
     * `GamepadEngine.start()` from `OverlayService.onStartCommand`), so every stall produced an
     * "Input dispatching timed out" ANR while the engine thread kept mapping normally.
     *
     * The query now only ever runs on [batteryExecutor]; [getConnectedGamepads] reads this cache.
     */
    private val batteryCache = ConcurrentHashMap<Int, Int>()
    private val batteryRefreshRunning = AtomicBoolean(false)
    private val batteryExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "GamepadBatteryQuery").apply { isDaemon = true }
    }

    /**
     * Queries the battery level of [deviceIds] off the calling thread and invokes [onUpdated]
     * (on the worker thread) if any cached value changed. At most one query runs at a time, so a
     * stalled controller can never pile up blocked threads.
     */
    private fun refreshBatteryAsync(deviceIds: List<Int>, onUpdated: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || deviceIds.isEmpty()) return
        if (!batteryRefreshRunning.compareAndSet(false, true)) return
        try {
            batteryExecutor.execute {
                try {
                    var changed = false
                    for (id in deviceIds) {
                        val percent = try {
                            val cap = InputDevice.getDevice(id)?.batteryState?.capacity
                            if (cap != null && cap in 0.0f..1.0f) (cap * 100).toInt() else null
                        } catch (_: Throwable) {
                            null
                        }
                        val previous = if (percent != null) batteryCache.put(id, percent) else batteryCache.remove(id)
                        if (previous != percent) changed = true
                    }
                    if (changed) onUpdated()
                } finally {
                    batteryRefreshRunning.set(false)
                }
            }
        } catch (_: Throwable) {
            batteryRefreshRunning.set(false)
        }
    }

    private val IGNORED_DEVICE_KEYWORDS = listOf(
        "uinput",
        "xiaomi",
        "touchscreen",
        "touch_dev",
        "fts_ts",
        "goodix",
        "synaptics",
        "focaltech",
        "novatek",
        "sensor",
        "gpio",
        "qpnp",
        "power",
        "volume",
        "headset",
        "jack",
        "dummy",
        "loopback",
        "virtual",
        "hotplug"
    )

    private val STANDARD_GAMEPAD_KEYS = intArrayOf(
        KeyEvent.KEYCODE_BUTTON_A,
        KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_BUTTON_X,
        KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_BUTTON_L1,
        KeyEvent.KEYCODE_BUTTON_R1,
        KeyEvent.KEYCODE_BUTTON_L2,
        KeyEvent.KEYCODE_BUTTON_R2,
        KeyEvent.KEYCODE_BUTTON_THUMBL,
        KeyEvent.KEYCODE_BUTTON_THUMBR,
        KeyEvent.KEYCODE_BUTTON_START,
        KeyEvent.KEYCODE_BUTTON_SELECT,
        KeyEvent.KEYCODE_BUTTON_MODE,
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT
    )

    fun getConnectedGamepads(context: Context): List<GamepadDevice> {
        val list = mutableListOf<GamepadDevice>()
        try {
            val deviceIds = try {
                InputDevice.getDeviceIds()
            } catch (_: Throwable) {
                IntArray(0)
            }
            val usbManager = try {
                context.getSystemService(Context.USB_SERVICE) as? UsbManager
            } catch (_: Throwable) {
                null
            }

            for (id in deviceIds) {
                try {
                    val dev = InputDevice.getDevice(id) ?: continue

                    // 1. Android virtual flag check
                    if (dev.isVirtual) continue

                    val devName = dev.name ?: continue

                    // 2. Ignore known virtual / touch / sensor driver names
                    val isIgnored = IGNORED_DEVICE_KEYWORDS.any { keyword ->
                        devName.contains(keyword, ignoreCase = true)
                    }
                    if (isIgnored) continue

                    val sources = dev.sources
                    val isGamepadSource = (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    val isJoystickSource = (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

                    if (!isGamepadSource && !isJoystickSource) continue

                    // 3. If it is purely a touchscreen claiming joystick axes, discard
                    val isTouchscreen = (sources and InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN
                    if (isTouchscreen && !isGamepadSource) continue

                    // 4. Validate physical gamepad capabilities
                    val hasKeysResult = try {
                        dev.hasKeys(*STANDARD_GAMEPAD_KEYS)
                    } catch (e: Throwable) {
                        BooleanArray(STANDARD_GAMEPAD_KEYS.size) { false }
                    }
                    val hasAnyGamepadKey = hasKeysResult.any { it }

                    val hasStickAxes = (dev.getMotionRange(MotionEvent.AXIS_X) != null && dev.getMotionRange(MotionEvent.AXIS_Y) != null) &&
                            (dev.getMotionRange(MotionEvent.AXIS_Z) != null ||
                             dev.getMotionRange(MotionEvent.AXIS_RZ) != null ||
                             dev.getMotionRange(MotionEvent.AXIS_RX) != null ||
                             dev.getMotionRange(MotionEvent.AXIS_RY) != null ||
                             dev.getMotionRange(MotionEvent.AXIS_HAT_X) != null ||
                             dev.getMotionRange(MotionEvent.AXIS_HAT_Y) != null)

                    // Must have either real gamepad keys or analog stick axes
                    if (!hasAnyGamepadKey && !hasStickAxes) continue

                    // 5. Battery info (Bluetooth controllers on Android 12+).
                    // Read from the cache only: InputDevice.getBatteryState() can block for
                    // seconds and must never run here (see batteryCache).
                    val battery: Int? = batteryCache[id]

                    // 6. Accurately detect USB vs Bluetooth safely
                    var isUsbByManager = false
                    try {
                        val usbList = usbManager?.deviceList
                        if (usbList != null) {
                            isUsbByManager = usbList.values.any { usbDev ->
                                (usbDev.vendorId == dev.vendorId && usbDev.productId == dev.productId && dev.vendorId != 0) ||
                                usbDev.deviceName.equals(devName, ignoreCase = true)
                            }
                        }
                    } catch (_: Throwable) {}

                    val isUsbByName = devName.contains("usb", ignoreCase = true) ||
                                      devName.contains("wired", ignoreCase = true) ||
                                      devName.contains("cable", ignoreCase = true) ||
                                      devName.contains("scrcpy", ignoreCase = true)

                    val isUsb = isUsbByManager || isUsbByName
                    val isBt = !isUsb

                    // Clean up name for display
                    val cleanName = devName.trim()
                    list.add(GamepadDevice(id, cleanName, isBt, battery))
                } catch (_: Throwable) {
                    // Skip device if disconnected concurrently
                }
            }
        } catch (_: Throwable) {}
        return list
    }
}
