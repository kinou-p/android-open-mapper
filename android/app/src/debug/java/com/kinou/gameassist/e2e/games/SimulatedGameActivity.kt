package com.kinou.gameassist.e2e.games

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowManager

/** Full-screen immersive host, so view coordinates equal screen coordinates. */
abstract class SimulatedGameActivity<V : GameView> : Activity() {

    lateinit var gameView: V
        private set

    protected abstract fun createView(): V

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        gameView = createView()
        setContentView(gameView)
        @Suppress("DEPRECATION")
        gameView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }
}
