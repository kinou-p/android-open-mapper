package com.kinou.gameassist.service

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.*
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.kinou.gameassist.MainActivity
import com.kinou.gameassist.R
import com.kinou.gameassist.data.model.GameProfile
import com.kinou.gameassist.data.model.deepCopy
import com.kinou.gameassist.data.repository.ProfileRepository
import com.kinou.gameassist.engine.GamepadEngine
import com.kinou.gameassist.injector.ShizukuManager
import com.kinou.gameassist.injector.ShizukuStatus
import com.kinou.gameassist.injector.ShizukuTouchInjector
import com.kinou.gameassist.ui.overlay.EdgeHandleOverlayView
import com.kinou.gameassist.ui.overlay.HudEditorOverlayView

import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

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

        private val _liveProfileUpdateFlow = MutableStateFlow<GameProfile?>(null)
        val liveProfileUpdateFlow: StateFlow<GameProfile?> = _liveProfileUpdateFlow.asStateFlow()

        fun updateLiveProfile(profile: GameProfile) {
            _liveProfileUpdateFlow.value = profile
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
    private var currentEditorBitmap: Bitmap? = null
    private var inputInterceptorView: View? = null
    private var screenshotLoadJob: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        repository = ProfileRepository.getInstance(this)
        injector = ShizukuTouchInjector()
        hapticManager = com.kinou.gameassist.engine.HapticManager(this)
        engine = GamepadEngine(this, injector, lifecycleScope, hapticManager)

        engine.onHotSwitchProfile = { forward ->
            cycleProfile(forward)
        }
        engine.onTacticalToggle = { message ->
            showHotSwitchToast(message)
        }

        lifecycleScope.launch {
            liveProfileUpdateFlow.collect { profile ->
                if (profile != null) {
                    val isDifferent = currentProfile?.id != profile.id
                    currentProfile = profile
                    engine.setProfile(profile)
                    if (isDifferent) {
                        updateNotification()
                    }
                }
            }
        }

        lifecycleScope.launch {
            ShizukuManager.status.collect { status ->
                if (status == ShizukuStatus.RUNNING_AUTHORIZED) {
                    // Rebranche l'injecteur ET relance le lecteur /dev/input si le moteur
                    // tourne (ses sous-processus `cat` meurent avec le binder Shizuku).
                    // Exécuté hors du thread UI (Dispatchers.IO) pour éviter tout jank/ANR lié aux IPC Binder synchrones.
                    withContext(Dispatchers.IO) {
                        engine.onShizukuReconnected()
                    }
                    // Détacher l'intercepteur si Shizuku vient d'être autorisé après le démarrage
                    // (cas : service lancé avant autorisation, puis l'utilisateur accepte dans Shizuku).
                    // Sans ce détachement, la vue 1×1 reste attachée indéfiniment.
                    val interceptor = inputInterceptorView
                    if (interceptor != null) {
                        safeRemoveView(interceptor)
                        inputInterceptorView = null
                    }
                } else if (_isServiceRunning.value && status == ShizukuStatus.DEAD) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(
                            applicationContext,
                            "⚠️ Connexion Shizuku perdue. Vérifiez l'application Shizuku.",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        updateScreenMetrics()
        createNotificationChannel()

        val btFilter = android.content.IntentFilter().apply {
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        try {
            androidx.core.content.ContextCompat.registerReceiver(
                this,
                bluetoothDisconnectReceiver,
                btFilter,
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED
            )
        } catch (_: Exception) {}
    }

    private val bluetoothDisconnectReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED,
                android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    engine.resetAllInputs()
                    hapticManager.stopAllVibrations()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        // Garde critique : ne jamais ajouter de vue overlay sans la permission SYSTEM_ALERT_WINDOW.
        // En cas de redémarrage START_STICKY après révocation de la permission (MIUI/EMUI),
        // windowManager.addView() lèverait une BadTokenException et crasherait l'app en pleine partie.
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

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
        if (!ShizukuManager.isAuthorized()) {
            attachInputInterceptor()
        }

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
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
            },
            isStrafeActive = {
                currentProfile?.joystick?.jiggleStrafe == true
            },
            onToggleStrafe = {
                currentProfile?.let { prof ->
                    prof.joystick.jiggleStrafe = !prof.joystick.jiggleStrafe
                    engine.setProfile(prof)
                    lifecycleScope.launch(Dispatchers.IO) {
                        repository.saveProfileAsync(prof)
                    }
                    val stateMsg = if (prof.joystick.jiggleStrafe) "⚡ Auto Jiggle Strafe ACTIVÉ" else "Auto Jiggle Strafe DÉSACTIVÉ"
                    android.widget.Toast.makeText(this, stateMsg, android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            isAntiRecoilActive = {
                currentProfile?.camera?.antiRecoilEnabled == true
            },
            onToggleAntiRecoil = {
                currentProfile?.let { prof ->
                    prof.camera.antiRecoilEnabled = !prof.camera.antiRecoilEnabled
                    engine.setProfile(prof)
                    lifecycleScope.launch(Dispatchers.IO) {
                        repository.saveProfileAsync(prof)
                    }
                    val stateMsg = if (prof.camera.antiRecoilEnabled) {
                        "🎯 Anti-Recul (${String.format(java.util.Locale.US, "%.1fx", prof.camera.antiRecoilSpeed)}) ACTIVÉ"
                    } else {
                        "Anti-Recul DÉSACTIVÉ"
                    }
                    android.widget.Toast.makeText(this, stateMsg, android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            getAntiRecoilSpeed = {
                currentProfile?.camera?.antiRecoilSpeed ?: 1.0f
            },
            onSetAntiRecoilSpeed = { speed ->
                currentProfile?.let { prof ->
                    prof.camera.antiRecoilSpeed = speed
                    engine.setProfile(prof)
                }
            },
            onPersistAntiRecoilSpeed = { speed ->
                currentProfile?.let { prof ->
                    prof.camera.antiRecoilSpeed = speed
                    engine.setProfile(prof)
                    lifecycleScope.launch(Dispatchers.IO) {
                        repository.saveProfileAsync(prof)
                    }
                }
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

        // Invisible 1x1 fallback interceptor for non-Shizuku mode.
        // IMPORTANT: FLAG_NOT_FOCUSABLE must NOT be set here, because the Android WindowManager
        // only delivers Gamepad KeyEvents and GenericMotionEvents to the focused window.
        // We include FLAG_ALT_FOCUSABLE_IM so this window does not steal IME focus or dismiss
        // the soft keyboard in the underlying game.
        val params = WindowManager.LayoutParams(
            1, 1,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
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

    private fun openHudEditor() {
        val prof = currentProfile ?: return
        if (editorView != null) return

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        updateScreenMetrics()

        val baseFlags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS

        val windowFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            baseFlags
        } else {
            @Suppress("DEPRECATION")
            baseFlags or WindowManager.LayoutParams.FLAG_FULLSCREEN
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            windowFlags,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        // Copie défensive : l'éditeur mutera sa propre copie. Le profil actif (utilisé en jeu)
        // ne change qu'au moment de la sauvegarde, rendant l'annulation logique possible.
        val editorProfile = prof.deepCopy()

        val newEditor = HudEditorOverlayView(
            this, editorProfile,
            onSave = { updatedProfile ->
                currentProfile = updatedProfile
                engine.setProfile(updatedProfile)
                closeHudEditor()
                updateLiveProfile(updatedProfile)
                lifecycleScope.launch {
                    repository.saveProfileAsync(updatedProfile)
                }
            },
            onClose = {
                closeHudEditor()
            }
        )

        // Load screenshot asynchronously without blocking main UI thread
        screenshotLoadJob?.cancel()
        if (editorProfile.customScreenshotPath != null) {
            screenshotLoadJob = lifecycleScope.launch(Dispatchers.IO) {
                val bmp = com.kinou.gameassist.data.repository.ScreenshotManager.loadScreenshotBitmapAsync(this@OverlayService, editorProfile.customScreenshotPath)
                if (!isActive) {
                    bmp?.let { if (!it.isRecycled) it.recycle() }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    if (editorView == newEditor) {
                        val old = currentEditorBitmap
                        currentEditorBitmap = bmp
                        newEditor.setScreenshot(bmp)
                        if (old != null && !old.isRecycled && old != bmp) {
                            old.recycle()
                        }
                    } else {
                        bmp?.let { if (!it.isRecycled) it.recycle() }
                    }
                }
            }
        }

        edgeHandleView?.visibility = View.GONE
        try {
            windowManager.addView(newEditor, params)
            editorView = newEditor
            newEditor.requestFocus()
        } catch (e: Exception) {
            // En cas d'échec d'ajout de la fenêtre, restaurer la poignée pour ne pas laisser l'UI verrouillée
            edgeHandleView?.visibility = View.VISIBLE
        }
    }

    private fun safeRemoveView(view: View?) {
        if (view != null) {
            try {
                if (view.isAttachedToWindow) {
                    windowManager.removeViewImmediate(view)
                }
            } catch (_: Exception) {
                // Ignore if view was already removed or detached by system
            }
        }
    }

    private fun closeHudEditor() {
        screenshotLoadJob?.cancel()
        screenshotLoadJob = null
        editorView?.let {
            it.releaseBitmap()
            safeRemoveView(it)
            editorView = null
        }
        currentEditorBitmap?.let {
            if (!it.isRecycled) {
                it.recycle()
            }
        }
        currentEditorBitmap = null
        edgeHandleView?.visibility = View.VISIBLE
        inputInterceptorView?.requestFocus()
    }

    fun cycleProfile(forward: Boolean = true) {
        lifecycleScope.launch {
            val allProfiles = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                repository.getAllProfiles()
            }
            if (allProfiles.isEmpty()) return@launch
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
        _isServiceRunning.value = false
        try {
            unregisterReceiver(bluetoothDisconnectReceiver)
        } catch (_: Exception) {}
        engine.stop()
        hapticManager.destroy()

        screenshotLoadJob?.cancel()
        screenshotLoadJob = null

        editorView?.releaseBitmap()
        currentEditorBitmap?.let {
            if (!it.isRecycled) {
                it.recycle()
            }
        }
        currentEditorBitmap = null
        safeRemoveView(edgeHandleView)
        edgeHandleView = null
        safeRemoveView(editorView)
        editorView = null
        safeRemoveView(inputInterceptorView)
        inputInterceptorView = null

        super.onDestroy()
    }
}
