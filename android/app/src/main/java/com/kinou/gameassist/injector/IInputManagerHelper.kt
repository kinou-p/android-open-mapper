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

    /**
     * Injects an input event into the system.
     * mode: 0 = INJECT_INPUT_EVENT_MODE_ASYNC (< 0.5ms non-blocking)
     *
     * Propagates RemoteException and SecurityException so higher-level injectors
     * can detect Binder death and trigger auto-reconnection.
     */
    @Throws(RemoteException::class)
    fun injectInputEvent(event: InputEvent, mode: Int = 0): Boolean {
        return when (injectionMode) {
            InjectionMode.DIRECT_AIDL_2_PARAMS -> {
                try {
                    iInputManager?.injectInputEvent(event, mode) ?: false
                } catch (e: RemoteException) {
                    throw e // Propager la mort du Binder Shizuku / service Input
                } catch (e: SecurityException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
            }
            InjectionMode.REFLECTION_3_PARAMS -> {
                val target = targetObject ?: return false
                val method = injectMethod ?: return false
                try {
                    method.invoke(target, event, mode, 0) as? Boolean ?: true
                } catch (e: InvocationTargetException) {
                    val cause = e.cause ?: e.targetException
                    if (cause is RemoteException) throw cause
                    if (cause is SecurityException) throw cause
                    false
                } catch (_: Exception) {
                    false
                }
            }
            InjectionMode.REFLECTION_2_PARAMS -> {
                val target = targetObject ?: return false
                val method = injectMethod ?: return false
                try {
                    method.invoke(target, event, mode) as? Boolean ?: true
                } catch (e: InvocationTargetException) {
                    val cause = e.cause ?: e.targetException
                    if (cause is RemoteException) throw cause
                    if (cause is SecurityException) throw cause
                    false
                } catch (_: Exception) {
                    false
                }
            }
            InjectionMode.UNSET -> false
        }
    }
}
