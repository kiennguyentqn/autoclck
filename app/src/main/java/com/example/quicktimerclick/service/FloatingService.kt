package com.example.quicktimerclick.service

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.example.quicktimerclick.overlay.FloatingPanel
class FloatingService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: TextView
    private lateinit var floatingPanel: FloatingPanel
    private var isPanelVisible = false
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        floatingPanel = FloatingPanel(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        floatingView = TextView(this).apply {
            text = "●"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER

            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#2196F3"))
                setSize(150, 150)
            }

            width = 150
            height = 150
        }

        val params = WindowManager.LayoutParams(
            150,
            150,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 100
        params.y = 300
        floatingView.setOnTouchListener(object : android.view.View.OnTouchListener {
            private var moved = false
            private var initialX = 0
            private var initialY = 0

            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: android.view.View?, event: android.view.MotionEvent): Boolean {

                when (event.action) {

                    android.view.MotionEvent.ACTION_DOWN -> {
                        moved = false
                        initialX = params.x
                        initialY = params.y

                        initialTouchX = event.rawX
                        initialTouchY = event.rawY

                        return true
                    }

                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY

                        if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) {
                            moved = true
                        }

                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()

                        windowManager.updateViewLayout(floatingView, params)

                        return true
                    }
                    android.view.MotionEvent.ACTION_UP -> {

                        if (!moved) {

                            if (isPanelVisible) {
                                floatingPanel.hide()
                            } else {
                                floatingPanel.show()
                            }

                            isPanelVisible = !isPanelVisible
                        }

                        return true
                    }
                }

                return false
            }
        })
        windowManager.addView(floatingView, params)

    }

    override fun onDestroy() {
        super.onDestroy()

        if (::floatingPanel.isInitialized) {
            floatingPanel.hide()
        }

        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
    }
}