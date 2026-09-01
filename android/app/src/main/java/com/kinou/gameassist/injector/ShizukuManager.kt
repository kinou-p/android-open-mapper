package com.kinou.gameassist.injector

import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

enum class ShizukuStatus {
    DEAD,
    RUNNING_UNAUTHORIZED,
    RUNNING_AUTHORIZED
}

object ShizukuManager {
    private val _status = MutableStateFlow(ShizukuStatus.DEAD)
    val status: StateFlow<ShizukuStatus> = _status

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        checkStatus()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        _status.value = ShizukuStatus.DEAD
    }

    private val requestPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                _status.value = ShizukuStatus.RUNNING_AUTHORIZED
            } else {
                _status.value = ShizukuStatus.RUNNING_UNAUTHORIZED
            }
        }

    private var isInitialized = false

    fun init() {
        if (!isInitialized) {
            isInitialized = true
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(requestPermissionResultListener)
        }
        checkStatus()
    }

    fun destroy() {
        if (isInitialized) {
            try {
                Shizuku.removeBinderReceivedListener(binderReceivedListener)
                Shizuku.removeBinderDeadListener(binderDeadListener)
                Shizuku.removeRequestPermissionResultListener(requestPermissionResultListener)
            } catch (_: Exception) {}
            isInitialized = false
        }
    }

    fun checkStatus() {
        if (!Shizuku.pingBinder()) {
            _status.value = ShizukuStatus.DEAD
            return
        }

        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                _status.value = ShizukuStatus.RUNNING_AUTHORIZED
            } else {
                _status.value = ShizukuStatus.RUNNING_UNAUTHORIZED
            }
        } catch (e: Exception) {
            _status.value = ShizukuStatus.DEAD
        }
    }

    fun requestPermission(requestCode: Int = 1001) {
        if (Shizuku.pingBinder()) {
            try {
                Shizuku.requestPermission(requestCode)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun isAuthorized(): Boolean {
        return _status.value == ShizukuStatus.RUNNING_AUTHORIZED
    }

    fun getInputBinder(): IBinder? {
        if (!isAuthorized()) return null
        return try {
            val rawBinder = SystemServiceHelper.getSystemService("input")
            if (rawBinder != null) ShizukuBinderWrapper(rawBinder) else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
