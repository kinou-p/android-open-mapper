package com.kinou.gameassist.injector

import android.hardware.input.IInputManager
import android.os.IBinder
import android.os.RemoteException
import android.view.InputEvent
import java.lang.reflect.InvocationTargetException
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
        } catch (_: Throwable) {
            // Fallback via reflection Stub
            try {
                val stubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
                val asInterfaceMethod = stubClass.getMethod("asInterface", IBinder::class.java)
                targetObject = asInterfaceMethod.invoke(null, binder)
            } catch (_: Exception) {
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

        // Déterminer de manière déterministe le mode d'injection dès l'initialisation
        val method = injectMethod
        if (method != null && method.parameterTypes.size == 3) {
            // Android 11 à 15+: injectInputEvent(InputEvent, int mode, int displayId)
            injectionMode = InjectionMode.REFLECTION_3_PARAMS
        } else if (iInputManager != null) {
            // AIDL direct compilé 2 params: injectInputEvent(InputEvent, int mode)
            injectionMode = InjectionMode.DIRECT_AIDL_2_PARAMS
        } else if (method != null && method.parameterTypes.size == 2) {
            // Réflexion 2 params: injectInputEvent(InputEvent, int mode)
            injectionMode = InjectionMode.REFLECTION_2_PARAMS
        }
    }

    companion object {
        private val ZERO_INTEGER = java.lang.Integer.valueOf(0)
    }

    private val cachedArgs3 = arrayOfNulls<Any>(3).apply {
        this[1] = ZERO_INTEGER
        this[2] = ZERO_INTEGER
    }
    private val cachedArgs2 = arrayOfNulls<Any>(2).apply {
        this[1] = ZERO_INTEGER
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
                    injectionMode = InjectionMode.UNSET
                    iInputManager = null
                    injectInputEventFallback(event, mode)
                }
            }
            InjectionMode.REFLECTION_3_PARAMS -> {
                val target = targetObject
                val method = injectMethod
                if (target != null && method != null) {
                    try {
                        cachedArgs3[0] = event
                        cachedArgs3[1] = if (mode == 0) ZERO_INTEGER else mode
                        val res = method.invoke(target, cachedArgs3) as? Boolean ?: true
                        cachedArgs3[0] = null
                        res
                    } catch (e: Throwable) {
                        cachedArgs3[0] = null
                        injectionMode = InjectionMode.UNSET
                        injectInputEventFallback(event, mode)
                    }
                } else {
                    false
                }
            }
            InjectionMode.REFLECTION_2_PARAMS -> {
                val target = targetObject
                val method = injectMethod
                if (target != null && method != null) {
                    try {
                        cachedArgs2[0] = event
                        cachedArgs2[1] = if (mode == 0) ZERO_INTEGER else mode
                        val res = method.invoke(target, cachedArgs2) as? Boolean ?: true
                        cachedArgs2[0] = null
                        res
                    } catch (e: Throwable) {
                        cachedArgs2[0] = null
                        injectionMode = InjectionMode.UNSET
                        injectInputEventFallback(event, mode)
                    }
                } else {
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
            } catch (_: Throwable) {
                // Disable direct AIDL to avoid repeated exceptions across frames
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
                    cachedArgs3[0] = event
                    cachedArgs3[1] = if (mode == 0) ZERO_INTEGER else mode
                    val res = method.invoke(target, cachedArgs3) as? Boolean ?: true
                    cachedArgs3[0] = null
                    injectionMode = InjectionMode.REFLECTION_3_PARAMS
                    return res
                } catch (_: Throwable) {
                    cachedArgs3[0] = null
                }
            } else if (paramCount == 2) {
                try {
                    cachedArgs2[0] = event
                    cachedArgs2[1] = if (mode == 0) ZERO_INTEGER else mode
                    val res = method.invoke(target, cachedArgs2) as? Boolean ?: true
                    cachedArgs2[0] = null
                    injectionMode = InjectionMode.REFLECTION_2_PARAMS
                    return res
                } catch (_: Throwable) {
                    cachedArgs2[0] = null
                }
            }
        }

        return false
    }
}
