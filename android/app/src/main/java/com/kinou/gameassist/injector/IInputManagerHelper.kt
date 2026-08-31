package com.kinou.gameassist.injector

import android.hardware.input.IInputManager
import android.os.IBinder
import android.view.InputEvent
import java.lang.reflect.Method

class IInputManagerHelper(private val binder: IBinder) {
    private enum class InjectionMode {
        UNSET,
        DIRECT_AIDL_2_PARAMS,
        REFLECTION_2_PARAMS,
        REFLECTION_3_PARAMS
    }

    private var iInputManager: IInputManager? = null
    private var injectMethod: Method? = null
    private var targetObject: Any? = null
    private var injectionMode: InjectionMode = InjectionMode.UNSET

    init {
        try {
            iInputManager = IInputManager.Stub.asInterface(binder)
            targetObject = iInputManager
        } catch (e: Throwable) {
            // Fallback via reflection
            try {
                val stubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
                val asInterfaceMethod = stubClass.getMethod("asInterface", IBinder::class.java)
                targetObject = asInterfaceMethod.invoke(null, binder)
            } catch (ex: Exception) {
                // Ignore
            }
        }

        // Locate and cache reflection method
        targetObject?.let { obj ->
            val methods = obj.javaClass.methods
            for (m in methods) {
                if (m.name == "injectInputEvent") {
                    val ptypes = m.parameterTypes
                    if (ptypes.isNotEmpty() && InputEvent::class.java.isAssignableFrom(ptypes[0])) {
                        m.isAccessible = true
                        injectMethod = m
                        break
                    }
                }
            }
        }
    }

    /**
     * Injects an input event into the system.
     * mode: 0 = INJECT_INPUT_EVENT_MODE_ASYNC (< 0.5ms non-blocking)
     */
    fun injectInputEvent(event: InputEvent, mode: Int = 0): Boolean {
        return when (injectionMode) {
            InjectionMode.DIRECT_AIDL_2_PARAMS -> {
                try {
                    iInputManager?.injectInputEvent(event, mode) ?: false
                } catch (e: Throwable) {
                    // Switch to fallback if signature changed at runtime
                    injectionMode = InjectionMode.UNSET
                    iInputManager = null
                    injectInputEventFallback(event, mode)
                }
            }
            InjectionMode.REFLECTION_3_PARAMS -> {
                try {
                    injectMethod?.invoke(targetObject, event, mode, 0) as? Boolean ?: true
                } catch (e: Throwable) {
                    false
                }
            }
            InjectionMode.REFLECTION_2_PARAMS -> {
                try {
                    injectMethod?.invoke(targetObject, event, mode) as? Boolean ?: true
                } catch (e: Throwable) {
                    false
                }
            }
            InjectionMode.UNSET -> {
                injectInputEventFallback(event, mode)
            }
        }
    }

    private fun injectInputEventFallback(event: InputEvent, mode: Int): Boolean {
        // 1. Try Direct AIDL (2 params)
        if (iInputManager != null) {
            try {
                val res = iInputManager!!.injectInputEvent(event, mode)
                injectionMode = InjectionMode.DIRECT_AIDL_2_PARAMS
                return res
            } catch (e: Throwable) {
                // Disable direct AIDL permanently to avoid repeated exceptions across frames
                iInputManager = null
            }
        }

        // 2. Try Reflection method (supports Android 11 to 15: 2 params or 3 params with displayId=0)
        val method = injectMethod
        val target = targetObject
        if (method != null && target != null) {
            val paramCount = method.parameterTypes.size
            if (paramCount == 3) {
                try {
                    val res = method.invoke(target, event, mode, 0) as? Boolean ?: true
                    injectionMode = InjectionMode.REFLECTION_3_PARAMS
                    return res
                } catch (e: Throwable) {}
            } else if (paramCount == 2) {
                try {
                    val res = method.invoke(target, event, mode) as? Boolean ?: true
                    injectionMode = InjectionMode.REFLECTION_2_PARAMS
                    return res
                } catch (e: Throwable) {}
            }
        }

        return false
    }
}
