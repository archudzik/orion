package com.deltainteraction.orion

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Environment
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContentProviderCompat.requireContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.UUID


class ScreenCaptureForegroundService : Service() {

    private var TAG = "ScreenCaptureService"
    private var channelId = "ScreenCaptureServiceChannel"

    private var mMediaProjection: MediaProjection? = null
    private var mVirtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var displayMetrics: DisplayMetrics

    override fun onCreate() {
        super.onCreate()

        // Initialize MediaProjectionManager and DisplayMetrics
        mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        displayMetrics = resources.displayMetrics
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Start the foreground service
        startForegroundService()

        // Start the media projection if the data is available
        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED)
        val data = intent?.getParcelableExtra("data", Intent::class.java)
        if (resultCode == Activity.RESULT_OK && data != null) {
            startMediaProjection(resultCode, data)
        } else {
            Log.d(TAG, "Invalid media projection result or data.")
        }

        return START_STICKY
    }

    private fun startMediaProjection(resultCode: Int, data: Intent) {
        mMediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
        if (mMediaProjection != null) {
            Log.d(TAG, "Virtual display fresh set up.")
            setUpVirtualDisplay() // Set up the virtual display for screen capture
        } else {
            Log.e(TAG, "Failed to start MediaProjection")
            stopSelf() // Stop the service if MediaProjection fails
        }
    }

    private fun setUpVirtualDisplay() {
        if (mVirtualDisplay != null) {
            return
        }
        if (mMediaProjection == null) {
            return
        }
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels
        val density = displayMetrics.densityDpi

        // Initialize ImageReader for screen capture
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader!!.setOnImageAvailableListener({
            if (imageReader != null) {
                Log.i(TAG, "ImageReader - Image Available")
                var newImage = imageReader!!.acquireLatestImage()
                if (newImage !== null) {
                    captureScreenshot(newImage) // Capture a screenshot right after
                }
            }
            releaseResources(true)
        }, null)

        // Register MediaProjection callback
        mMediaProjection!!.registerCallback(object : MediaProjection.Callback() {

        }, null)

        // Create the VirtualDisplay to capture the screen
        mVirtualDisplay = mMediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            object : VirtualDisplay.Callback() {

                override fun onPaused() {
                    Log.i(TAG, "VirtualDisplay paused.")
                }

                override fun onResumed() {
                    Log.i(TAG, "VirtualDisplay resumed.")

                }

                override fun onStopped() {
                    Log.i(TAG, "VirtualDisplay stopped.")
                    releaseResources(true) // Release resources when the projection stops
                }
            }, null
        )
    }

    private fun resizeBitmap(bitmap: Bitmap, maxSize: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        // Determine the scale factor
        val aspectRatio: Float = if (width > height) {
            width.toFloat() / maxSize
        } else {
            height.toFloat() / maxSize
        }

        // Calculate new dimensions based on the aspect ratio
        val newWidth = (width / aspectRatio).toInt()
        val newHeight = (height / aspectRatio).toInt()

        // Log difference
        Log.i(TAG, "Resize: ${width} x ${height} => ${newWidth} x ${newHeight}")

        // Resize the bitmap
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    private fun saveBitmapToFile(bitmap: Bitmap, fileNameJpg: String): File? {
        // Get the directory for saving the file
        val directory =
            Environment.getExternalStorageDirectory().absolutePath + "/Android/data/com.deltainteraction.orion/"
        val dirFile = File(directory)

        // Create the directory if it does not exist
        if (!dirFile.exists()) {
            dirFile.mkdirs()
            dirFile.setWritable(true)
        }

        // Define the file object where the bitmap will be saved
        val file = File(dirFile, fileNameJpg)
        try {
            // Resize bitmap
            val resizedBitmap = resizeBitmap(bitmap, 1920)
            // Compress the bitmap and save it in PNG format
            val outStream = FileOutputStream(file)
            resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, outStream)
            outStream.flush()
            outStream.close()
            return file // Return the saved file
        } catch (e: IOException) {
            e.printStackTrace()
        }
        return null // Return null if saving fails
    }


    // Capture the screenshot from the ImageReader
    private fun captureScreenshot(image: Image) {
        try {
            Log.i(TAG, "Capturing screenshot...")

            val planes = image.planes
            val buffer: ByteBuffer = planes[0].buffer
            val pixelStride: Int = planes[0].pixelStride
            val rowStride: Int = planes[0].rowStride
            val rowPadding: Int = rowStride - pixelStride * displayMetrics.widthPixels

            // Create a Bitmap from the captured image data (adjust row padding)
            val bitmap = Bitmap.createBitmap(
                displayMetrics.widthPixels + rowPadding / pixelStride,
                displayMetrics.heightPixels, android.graphics.Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            // Process the bitmap (e.g., save or analyze the image)
            val createdFile = saveBitmapToFile(bitmap, "orion_${UUID.randomUUID()}.jpg")

            // Propagate
            val resultIntent = Intent("com.deltainteraction.ACTION_FRESH_SCREENSHOT")
            resultIntent.putExtra("resultCode", Activity.RESULT_OK)
            resultIntent.putExtra("path", createdFile!!.absolutePath)
            sendBroadcast(resultIntent) // Send the broadcast

            // Close
            image.close()
            Log.i(TAG, "Screenshot captured successfully.")

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startForegroundService() {
        // Create the foreground notification
        val notification = createNotification()
        startForeground(1, notification)
    }

    private fun createNotification(): Notification {
        val channel = NotificationChannel(
            channelId,
            "Screen Capture Service",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)

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
        Log.i(TAG, "Releasing resources...")
        mVirtualDisplay?.release()
        imageReader?.close()
        mMediaProjection?.stop()

        // Optionally stop the service if screen capture is no longer needed
        if (stopSelf) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseResources(false)
        Log.i(TAG, "ScreenCaptureForegroundService destroyed.")
    }
}