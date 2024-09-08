package com.deltainteraction.orion

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
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

    private var TAG = "MyAccessibilityService"
    private var mLayout: FrameLayout? = null

    // BroadcastReceiver to handle the screen capture permission result
    private val genericBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val actionIdent = intent?.action.toString()
            val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED)
            val data = intent?.getParcelableExtra("data", Intent::class.java)
            val path = intent?.getParcelableExtra("path", String::class.java)
            val actionResultIsOK = resultCode == Activity.RESULT_OK && data != null

            if (actionIdent == "com.deltainteraction.ACTION_FRESH_SCREENSHOT") {
                Log.i(TAG, "ACTION_FRESH_SCREENSHOT")
                Log.i(TAG, path!!)
            }

            if (actionIdent == "com.deltainteraction.ACTION_SCREEN_CAPTURE") {
                if (actionResultIsOK) {
                    // Start the foreground service with the media projection data
                    val serviceIntent = Intent(
                        this@MyAccessibilityService,
                        ScreenCaptureForegroundService::class.java
                    )
                    serviceIntent.putExtra("resultCode", resultCode)
                    serviceIntent.putExtra("data", data)
                    startForegroundService(serviceIntent)
                    Log.d(TAG, "Foreground service started.")
                } else {
                    Log.e(TAG, "Screen capture permission was not granted.")
                }
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
        registerReceiver(
            genericBroadcastReceiver,
            IntentFilter("com.deltainteraction.ACTION_SCREEN_CAPTURE"),
            RECEIVER_EXPORTED
        )
        registerReceiver(
            genericBroadcastReceiver,
            IntentFilter("com.deltainteraction.ACTION_FRESH_SCREENSHOT"),
            RECEIVER_EXPORTED
        )

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
        Log.d(TAG, "Requesting screen capture permission...")
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(genericBroadcastReceiver) // Unregister the receiver when the service is destroyed
        Log.d(TAG, "MyAccessibilityService destroyed.")
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
}

