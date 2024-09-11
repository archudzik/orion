package com.deltainteraction.orion

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.VibrationEffect
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.FrameLayout
import androidx.preference.PreferenceManager
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.Locale


class MyAccessibilityService : AccessibilityService() {

    private var TAG = "MyAccessibilityService"
    private var mLayout: FrameLayout? = null
    private var actionBarScreenButton: Button? = null

    var apiKey = BuildConfig.GEMINI_API_KEY
    var generativeModel: GenerativeModel? = null
    val generativeModelName = "gemini-1.5-flash-latest"
    val generativeModelConfig = generationConfig {
        temperature = 0.7f
    }

    private var appLanguage = "PL"
    private val appStrings = mapOf(
        "PL" to mapOf(
            "tts_voice" to "pol_POL_default",
            "prompt_read" to "Jesteś asystentem AI, który pomaga osobie niewidomej. Twoje zadanie polega na odczytaniu zawartości ekranu, ekstrakcję kluczowych informacji, i poinformowania tej osoby o tym, co dzieje się na ekranie. Po Twojej analizie, aplikacja odczyta ją na głos. Bądź zwięzły. Zacznij od: 'Ekran pokazuje...'",
            "read_screen" to "Odczyt\nEkranu",
            "processing" to "Odczytuję..."
        ),
        "EN" to mapOf(
            "tts_voice" to "eng_GBR_default",
            "prompt_read" to "You are an AI assistant helping a blind person. Your task is to read the screen's content, extract key information, and inform the person about what is happening on the screen. After your analysis, the application will read it out loud. Be concise. Start with: 'The screen shows...'",
            "read_screen" to "Read\nScreen",
            "processing" to "Reading..."
        )
    )

    // TTS Service
    lateinit var textToSpeech: TextToSpeech

    // Touch events
    var autoConfirmRecording: Boolean = false

    // BroadcastReceiver to handle the screen capture permission result and screenshot path
    private val genericBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val actionIdent = intent?.action.toString()
            val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED)
            val data = intent?.getParcelableExtra("data", Intent::class.java)
            val path = intent?.getParcelableExtra("path", String::class.java)
            val actionResultIsOK = resultCode == Activity.RESULT_OK && data != null

            if (actionIdent == "com.deltainteraction.ACTION_FRESH_SCREENSHOT") {
                Log.i(TAG, "ACTION_FRESH_SCREENSHOT")
                actionBarScreenButton?.visibility = View.VISIBLE
                if (path != null) {
                    GlobalScope.launch {
                        chatWithGemini(path)
                    }
                }
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

    private fun speakToUser(outputContent: String) {
        if (textToSpeech != null) {
            Log.i(TAG, "Speaking...")
            if (textToSpeech.isSpeaking) {
                textToSpeech.stop()
            }
            textToSpeech.speak(outputContent, TextToSpeech.QUEUE_FLUSH, null, TAG)
        } else {
            Log.i(TAG, "TTS not started.")
        }
    }

    suspend fun chatWithGemini(imagePath: String) {
        try {
            appStrings[appLanguage]?.get("processing")?.let { speakToUser(it) }

            val inputContent = content {
                image(BitmapFactory.decodeFile(imagePath))
                text(appStrings[appLanguage]?.get("prompt_read").toString())
            }

            var outputContent = ""

            generativeModel?.generateContentStream(inputContent)?.collect { response ->
                outputContent += response.text
            }

            Log.i(TAG, outputContent)
            speakToUser(outputContent)

        } catch (e: Exception) {
            Log.e(TAG, e.localizedMessage)
        }
    }

    override fun onServiceConnected() {
        // Set up language and settings
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        val newLang = preferences.getString("pref_orion_language", "en").toString()
        val newApiKey = preferences.getString("pref_gemini_api_key", "").toString()
        val newAutoConfirm = preferences.getBoolean("pref_orion_auto_confirm", false)
        val newTtsSpeed = preferences.getString("pref_orion_tts_speed", "1.0")!!.toFloat()

        if (newLang.isNotEmpty()) {
            appLanguage = newLang
        }

        if (newApiKey.isNotEmpty()) {
            apiKey = newApiKey
        }

        autoConfirmRecording = newAutoConfirm

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

        // Create LLM Client
        generativeModel = GenerativeModel(
            modelName = generativeModelName,
            apiKey = apiKey,
            generationConfig = generativeModelConfig
        )

        // Set-up TTS
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech.language = Locale.forLanguageTag(appLanguage)

                val desiredVoice = appStrings[appLanguage]?.get("tts_voice").toString()
                val voices: Set<Voice> = textToSpeech.getVoices()
                val voiceList: List<Voice> = ArrayList(voices)

                for (voice in voiceList) {
                    if (voice.locale.toString().equals(desiredVoice)) {
                        textToSpeech.setVoice(voice)
                        textToSpeech.setSpeechRate(newTtsSpeed)
                    }
                }

                Log.d(TAG, "TextToSpeech Initialization Success")
            } else {
                Log.d(TAG, "TextToSpeech Initialization Failed")
            }
        }

        // Bind onClickListener to the button (once layout is inflated)
        actionBarScreenButton = mLayout?.findViewById(R.id.action_bar_button_screen)
        // Create gradient drawable programmatically
        val gradient = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,  // Set the gradient direction
            intArrayOf(Color.parseColor("#7550e1"), Color.parseColor("#748ded"))  // Colors
        )
        gradient.cornerRadius = 75f
        actionBarScreenButton?.setTextColor(Color.parseColor("#FFFFFF"))

        actionBarScreenButton?.apply {
            background = gradient
            alpha = 0.9f  // Opacity level between 0.0 and 1.0
            text = appStrings[appLanguage]?.get("read_screen")
            textSize = 20f
        }

        actionBarScreenButton?.setOnClickListener {
            actionBarScreenButton?.visibility = View.GONE
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

    private fun touchTo(x: Float, y: Float) {
        val swipePath: Path = Path()
        swipePath.moveTo(x, y)
        swipePath.lineTo(x, y)
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(StrokeDescription(swipePath, 0, 50))
        dispatchGesture(gestureBuilder.build(), null, null)
    }

    private fun getScreenWidth(): Int {
        return Resources.getSystem().displayMetrics.widthPixels
    }

    private fun getScreenHeight(): Int {
        return Resources.getSystem().displayMetrics.heightPixels
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        Log.i(TAG, event.toString())
        var source = event!!.source
        if (event != null && source != null && event!!.eventType === AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (source.getPackageName().equals("com.android.systemui")) {
                val confirm =
                    getRootInActiveWindow()
                        .findAccessibilityNodeInfosByText("casting with Orion?")
                Log.i(TAG, confirm.toString())
                if (confirm.size !== 0) {
                    val x = (getScreenWidth() - 200).toFloat()
                    val y = (getScreenHeight() - 120).toFloat()
                    Log.i(TAG, "X:${x}, Y:${y}")
                    if (autoConfirmRecording) {
                        touchTo(x, y)
                    }
                }
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(genericBroadcastReceiver) // Unregister the receiver when the service is destroyed
        Log.d(TAG, "MyAccessibilityService destroyed.")
        if (textToSpeech != null) {
            textToSpeech.shutdown()
        }

    }

}

