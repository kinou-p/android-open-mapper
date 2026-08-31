package com.kinou.gameassist.service

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.*
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.kinou.gameassist.MainActivity
import com.kinou.gameassist.R
import com.kinou.gameassist.data.model.GameProfile
import com.kinou.gameassist.data.repository.ProfileRepository
import com.kinou.gameassist.engine.GamepadEngine
import com.kinou.gameassist.injector.ShizukuTouchInjector
import com.kinou.gameassist.ui.overlay.EdgeHandleOverlayView
import com.kinou.gameassist.ui.overlay.HudEditorOverlayView

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class OverlayService : LifecycleService() {

    companion object {
        const val CHANNEL_ID = "gameassist_overlay_channel"
        const val NOTIFICATION_ID = 2026
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_PROFILE_ID = "EXTRA_PROFILE_ID"

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunningFlow: StateFlow<Boolean> = _isServiceRunning.asStateFlow()
        val isServiceRunning: Boolean get() = _isServiceRunning.value
        var instance: OverlayService? = null

        fun updateLiveProfile(profile: GameProfile) {
            instance?.let { service ->
                if (service.currentProfile?.id == profile.id) {
                    service.currentProfile = profile
                    service.engine.setProfile(profile)
                }
            }
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var repository: ProfileRepository
    private lateinit var injector: ShizukuTouchInjector
    private lateinit var hapticManager: com.kinou.gameassist.engine.HapticManager
    private lateinit var engine: GamepadEngine

    private var currentProfile: GameProfile? = null

    // Overlay Views
    private var edgeHandleView: EdgeHandleOverlayView? = null
    private var edgeHandleParams: WindowManager.LayoutParams? = null

    private var editorView: HudEditorOverlayView? = null
    private var inputInterceptorView: View? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        repository = ProfileRepository(this)
        injector = ShizukuTouchInjector()
        hapticManager = com.kinou.gameassist.engine.HapticManager(this)
        engine = GamepadEngine(injector, lifecycleScope, hapticManager)

        engine.onHotSwitchProfile = { forward ->
            cycleProfile(forward)
        }

        updateScreenMetrics()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action

        if (action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val profileId = intent?.getStringExtra(EXTRA_PROFILE_ID) ?: "codm_multiplayer_default"
        currentProfile = repository.getProfile(profileId) ?: repository.createDefaultCodmProfile()

        startForegroundService()
        _isServiceRunning.value = true

        currentProfile?.let { prof ->
            engine.setProfile(prof)
            engine.start()
        }

        showEdgeHandle()
        attachInputInterceptor()

        return START_STICKY
    }

    private fun updateScreenMetrics() {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(dm)
        injector.setScreenResolution(dm.widthPixels, dm.heightPixels)
    }

    private fun startForegroundService() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.overlay_notif_title))
            .setContentText(getString(R.string.overlay_notif_text, currentProfile?.name ?: ""))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OpenMapper Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "OpenMapper Overlay & Mapping Service"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun showEdgeHandle() {
        if (edgeHandleView != null) return

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val density = resources.displayMetrics.density
        val rWidth = (16 * density).toInt()
        val rHeight = (100 * density).toInt()

        val prefs = getSharedPreferences("overlay_prefs", Context.MODE_PRIVATE)
        val isRight = prefs.getBoolean("edge_handle_is_right", false)
        val savedY = prefs.getInt("edge_handle_y", 200)

        edgeHandleParams = WindowManager.LayoutParams(
            rWidth, rHeight,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or (if (isRight) Gravity.END else Gravity.START)
            x = 0
            y = savedY
        }

        edgeHandleView = EdgeHandleOverlayView(
            context = this,
            windowManager = windowManager,
            params = edgeHandleParams!!,
            onOpenHud = {
                openHudEditor()
            },
            onOpenApp = {
                val appIntent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(appIntent)
            },
            onStopService = {
                stopSelf()
            }
        )

        windowManager.addView(edgeHandleView, edgeHandleParams)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachInputInterceptor() {
        if (inputInterceptorView != null) return

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // Invisible 1x1 view with focus to capture Gamepad KeyEvents and MotionEvents
        val params = WindowManager.LayoutParams(
            1, 1,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        val frame = object : FrameLayout(this) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (engine.handleKeyEvent(event)) return true
                return super.dispatchKeyEvent(event)
            }

            override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
                if (engine.handleMotionEvent(event)) return true
                return super.dispatchGenericMotionEvent(event)
            }
        }
        frame.isFocusable = true
        frame.isFocusableInTouchMode = true

        inputInterceptorView = frame
        windowManager.addView(frame, params)
        frame.requestFocus()
    }

    private fun toggleHudEditor() {
        if (editorView != null) {
            closeHudEditor()
        } else {
            openHudEditor()
        }
    }

    @Suppress("DEPRECATION")
    private fun openHudEditor() {
        val prof = currentProfile ?: return
        if (editorView != null) return

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        updateScreenMetrics()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        editorView = HudEditorOverlayView(
            this, prof,
            onSave = { updatedProfile ->
                repository.saveProfile(updatedProfile)
                engine.setProfile(updatedProfile)
                closeHudEditor()
            },
            onClose = {
                closeHudEditor()
            }
        )

        edgeHandleView?.visibility = View.GONE
        windowManager.addView(editorView, params)
        editorView?.requestFocus()
    }

    private fun closeHudEditor() {
        editorView?.let {
            it.releaseBitmap()
            windowManager.removeView(it)
            editorView = null
        }
        edgeHandleView?.visibility = View.VISIBLE
        inputInterceptorView?.requestFocus()
    }

    fun cycleProfile(forward: Boolean = true) {
        val allProfiles = repository.getAllProfiles()
        if (allProfiles.isEmpty()) return
        val currentIndex = allProfiles.indexOfFirst { it.id == currentProfile?.id }
        val nextIndex = if (forward) {
            if (currentIndex < 0) 0 else (currentIndex + 1) % allProfiles.size
        } else {
            if (currentIndex <= 0) allProfiles.size - 1 else currentIndex - 1
        }
        val nextProfile = allProfiles[nextIndex]
        currentProfile = nextProfile
        engine.setProfile(nextProfile)
        hapticManager.playProfileSwitchHaptic()
        showHotSwitchToast(nextProfile.name)
        updateNotification()
    }

    private fun showHotSwitchToast(profileName: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(applicationContext, "🎮 Profil actif : $profileName", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateNotification() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.overlay_notif_title))
            .setContentText(getString(R.string.overlay_notif_text, currentProfile?.name ?: ""))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        _isServiceRunning.value = false
        if (instance == this) instance = null
        engine.stop()

        edgeHandleView?.let { windowManager.removeView(it); edgeHandleView = null }
        editorView?.let { windowManager.removeView(it); editorView = null }
        inputInterceptorView?.let { windowManager.removeView(it); inputInterceptorView = null }
    }
}
