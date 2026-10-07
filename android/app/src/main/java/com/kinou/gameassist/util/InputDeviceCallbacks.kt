package com.kinou.gameassist.util

import android.os.Handler
import android.os.HandlerThread

/**
 * Dedicated background dispatcher for [android.hardware.input.InputManager.InputDeviceListener]
 * callbacks.
 *
 * Why this exists (ANR root cause):
 * Controller hotplug/changed callbacks trigger heavy synchronous work:
 *  - `GamepadEngine` tears down and restarts the Shizuku `/dev/input` readers
 *    (`LinuxInputReader.restart()`), which closes/`destroyForcibly()`+`waitFor()` child
 *    processes and re-runs `getevent -p`.
 *  - `ShizukuTouchInjector.resetAllPointers()` performs 10 synchronous Binder round-trips
 *    through the Shizuku service (`IInputManager.injectInputEvent`).
 *  - `HapticManager.refreshGamepadVibrators()` enumerates every `InputDevice` and queries its
 *    `VibratorManager`, which is Binder-backed.
 *  - `GamepadDetector.getConnectedGamepads()` enumerates devices and the `UsbManager`.
 *
 * These were previously dispatched on `Handler(Looper.getMainLooper())`, so connecting a
 * controller while Shizuku was active could block the UI thread past the 5 s input-dispatch
 * timeout. Because the mapping engine runs on its own dedicated thread, the controls kept
 * working, which is exactly why the reported symptom was an "app isn't responding" dialog
 * "even though the app is responding normally".
 *
 * All callbacks are serialized on a single background looper so the tear-down/restart of the
 * input readers never races with itself.
 */
object InputDeviceCallbacks {
    private val thread = HandlerThread("InputDeviceCallbacks").apply { start() }

    /** Handler backed by a dedicated background thread. */
    val handler: Handler = Handler(thread.looper)
}
