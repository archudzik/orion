package com.deltainteraction.orion

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.FrameLayout

class MyAccessibilityService : AccessibilityService() {

    private var mLayout: FrameLayout? = null
    private var mMediaProjectionManager: MediaProjectionManager? = null

    // BroadcastReceiver to handle the screen capture permission result
    private val screenCaptureReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED)
            val data = intent?.getParcelableExtra<Intent>("data")

            if (resultCode == Activity.RESULT_OK && data != null) {
                // Start the foreground service with the media projection data
                val serviceIntent = Intent(this@MyAccessibilityService, ScreenCaptureForegroundService::class.java)
                serviceIntent.putExtra("resultCode", resultCode)
                serviceIntent.putExtra("data", data)
                startForegroundService(serviceIntent)
                Log.d("MyAccessibilityService", "Foreground service started.")
            } else {
                Log.e("MyAccessibilityService", "Screen capture permission was not granted.")
            }
        }
    }

    override fun onServiceConnected() {
        // Set up the overlay UI
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        mLayout = FrameLayout(this)

        // Set up WindowManager layout parameters
        val lp = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.END // Align the buttons
        }

        // Inflate your layout
        val inflater = LayoutInflater.from(this)
        inflater.inflate(R.layout.action_bar, mLayout)

        // Add the inflated layout to the WindowManager
        wm.addView(mLayout, lp)

        // Register the BroadcastReceiver to receive the result of screen capture permission
        val filter = IntentFilter("com.deltainteraction.ACTION_SCREEN_CAPTURE")
        registerReceiver(screenCaptureReceiver, filter, RECEIVER_EXPORTED)

        // Bind onClickListener to the button (once layout is inflated)
        val actionBarScreenButton: Button? = mLayout?.findViewById(R.id.action_bar_button_screen)
        actionBarScreenButton?.setOnClickListener {
            vibrate(VibrationEffect.EFFECT_CLICK)
            requestScreenCapture() // Re-trigger screen capture permission if needed
        }
    }

    private fun vibrate(effectId: Int) {
        val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator.vibrate(VibrationEffect.createPredefined(effectId))
    }

    // Request screen capture permission
    private fun requestScreenCapture() {
        val captureIntent = Intent(this, ScreenCaptureActivity::class.java)
        captureIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(captureIntent)
        Log.d("MyAccessibilityService", "Requesting screen capture permission...")
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(screenCaptureReceiver) // Unregister the receiver when the service is destroyed
        Log.d("MyAccessibilityService", "MyAccessibilityService destroyed.")
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
}

