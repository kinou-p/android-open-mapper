package com.kinou.gameassist.injector

import android.hardware.input.IInputManager
import android.os.IBinder
import android.view.InputEvent
import java.lang.reflect.Method

class IInputManagerHelper(private val binder: IBinder) {
    private var iInputManager: IInputManager? = null
    private var injectMethod: Method? = null
    private var targetObject: Any? = null

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
                ex.printStackTrace()
            }
        }

        // Cache reflection method
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
        // Try AIDL interface direct call first
        iInputManager?.let { mgr ->
            try {
                return mgr.injectInputEvent(event, mode)
            } catch (e: Throwable) {
                // Pass through to reflection fallback
            }
        }

        // Fallback via reflection
        val method = injectMethod ?: return false
        val target = targetObject ?: return false

        return try {
            val params = method.parameterTypes
            when (params.size) {
                2 -> method.invoke(target, event, mode) as? Boolean ?: true
                3 -> method.invoke(target, event, mode, 0) as? Boolean ?: true
                else -> false
            }
        } catch (e: Exception) {
            false
        }
    }
}
