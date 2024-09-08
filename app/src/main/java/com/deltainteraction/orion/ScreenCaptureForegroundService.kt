package com.deltainteraction.orion

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.NotificationCompat
import java.nio.ByteBuffer

class ScreenCaptureForegroundService : Service() {

    private var mMediaProjection: MediaProjection? = null
    private var mVirtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var displayMetrics: DisplayMetrics

    override fun onCreate() {
        super.onCreate()

        // Initialize MediaProjectionManager and DisplayMetrics
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        displayMetrics = resources.displayMetrics
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Start the foreground service
        startForegroundService()

        // Start the media projection if the data is available
        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED)
        val data = intent?.getParcelableExtra<Intent>("data")
        if (resultCode == Activity.RESULT_OK && data != null) {
            startMediaProjection(resultCode, data)
        } else {
            Log.e("ScreenCaptureService", "Invalid media projection result or data.")
        }

        return START_STICKY
    }

    private fun startMediaProjection(resultCode: Int, data: Intent) {
        mMediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
        if (mMediaProjection != null) {
            Log.i("ScreenCaptureService", "MediaProjection started successfully")
            setUpVirtualDisplay() // Set up the virtual display for screen capture
        } else {
            Log.e("ScreenCaptureService", "Failed to start MediaProjection")
            stopSelf() // Stop the service if MediaProjection fails
        }
    }

    private fun setUpVirtualDisplay() {
        if (mVirtualDisplay == null && mMediaProjection != null) {
            val width = displayMetrics.widthPixels
            val height = displayMetrics.heightPixels
            val density = displayMetrics.densityDpi

            // Initialize ImageReader for screen capture
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            val surface = imageReader?.surface

            // Create the VirtualDisplay to capture the screen
            mVirtualDisplay = mMediaProjection?.createVirtualDisplay(
                "ScreenCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, object : VirtualDisplay.Callback() {
                    override fun onPaused() {
                        Log.d("ScreenCaptureService", "VirtualDisplay paused.")
                    }

                    override fun onResumed() {
                        Log.d("ScreenCaptureService", "VirtualDisplay resumed.")
                    }

                    override fun onStopped() {
                        Log.d("ScreenCaptureService", "VirtualDisplay stopped.")
                        releaseResources(true) // Release resources when the projection stops
                    }
                }, null
            )

            if (mVirtualDisplay != null) {
                Log.d("ScreenCaptureService", "Virtual display set up successfully.")
                captureScreenshot() // Optionally, capture a screenshot right after setup
            } else {
                Log.e("ScreenCaptureService", "Failed to set up VirtualDisplay.")
                stopSelf()
            }
        }
    }

    // Capture the screenshot from the ImageReader
    private fun captureScreenshot() {
        val image = imageReader?.acquireLatestImage()
        if (image != null) {
            Log.d("ScreenCaptureService", "Capturing screenshot...")

            val planes = image.planes
            val buffer: ByteBuffer = planes[0].buffer
            val pixelStride: Int = planes[0].pixelStride
            val rowStride: Int = planes[0].rowStride
            val rowPadding: Int = rowStride - pixelStride * displayMetrics.widthPixels

            // Create a Bitmap from the captured image data (adjust row padding)
            val bitmap = android.graphics.Bitmap.createBitmap(
                displayMetrics.widthPixels + rowPadding / pixelStride,
                displayMetrics.heightPixels, android.graphics.Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            // Process the bitmap (e.g., save or analyze the image)
            image.close()

            Log.d("ScreenCaptureService", "Screenshot captured successfully.")
        } else {
            Log.e("ScreenCaptureService", "Failed to acquire image from ImageReader.")
        }
    }

    private fun startForegroundService() {
        // Create the foreground notification
        val notification = createNotification()
        startForeground(1, notification)
    }

    private fun createNotification(): Notification {
        val channelId = "ScreenCaptureServiceChannel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Screen Capture Service",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Screen Capture Running")
            .setContentText("Your screen is being captured.")
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Replace with your app icon
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // We don't need to bind the service
    }

    private fun releaseResources(stopSelf: Boolean) {
        // Clean up resources
        Log.d("ScreenCaptureService", "Releasing resources...")
        mVirtualDisplay?.release()
        imageReader?.close()
        mMediaProjection?.stop()

        // Optionally stop the service if screen capture is no longer needed
        if(stopSelf) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseResources(false)
        Log.d("ScreenCaptureService", "ScreenCaptureForegroundService destroyed.")
    }
}