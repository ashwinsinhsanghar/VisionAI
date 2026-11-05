package com.ashwin.visionai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Plain XML-layout activity (no Jetpack Compose) so every control is a
 * standard View that TalkBack already knows how to announce.
 *
 * Gesture mapping below was reconstructed from the manufacturer app's
 * decompiled logic (ClientHandler.handleHeader, SwipeManager.select_swipe_mode,
 * ModeConfirmManager.manage_clicks/pauseFunction/get_confirm_modes), not
 * guessed:
 *   - forward/backward swipe -> cycle to next/previous mode
 *   - double tap / 1s hold   -> activate (capture + run) the selected mode
 *   - single tap             -> pause/resume current spoken output
 *   - long tap                -> "Ask Smarty": listen for a spoken question,
 *                                 send it + the latest photo to Gemini
 *   - 5s hold                 -> Emergency SOS call
 *   - dedicated Walking button -> jump straight to Walking Assistant and activate
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var glassesStatusText: TextView
    private var glassesConnectedState: Boolean = false
    private var glassesBatteryPercent: Int? = null
    private lateinit var resultText: TextView
    private lateinit var pairingGroup: View
    private lateinit var qrImage: ImageView
    private lateinit var networkInfoText: TextView
    private lateinit var capturePreviewGroup: View
    private lateinit var capturePreviewImage: ImageView
    private var lastCapturedJpeg: ByteArray? = null
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private val bg = Executors.newSingleThreadExecutor()
    private lateinit var voiceAssistant: VoiceAssistant

    private val glassesClient = GlassesClient { GlassesConnectionService.glassesIp }
    private lateinit var walkingMonitor: WalkingMonitor
    private var currencyClassifier: CurrencyClassifier? = null
    private var objectDetector: ObjectDetectorHelper? = null
    private lateinit var aiClient: AIClient

    private val modes = listOf(
        Mode("describe", "Describe Surroundings"),
        Mode("read", "Read Text"),
        Mode("currency", "Identify Currency"),
        Mode("walk", "Walking Assistant")
    )
    private var modeIndex = 0
    private var isPaused = false

    data class Mode(val id: String, val label: String)
    private val mode: Mode get() = modes[modeIndex]

    private val glassesListener: (GlassesProtocol.Event) -> Unit = { event ->
        runOnUiThread { handleGlassesEvent(event) }
    }

    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { startGlassesService() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        glassesStatusText = findViewById(R.id.glassesStatusText)
        resultText = findViewById(R.id.resultText)
        pairingGroup = findViewById(R.id.pairingGroup)
        qrImage = findViewById(R.id.qrImage)
        networkInfoText = findViewById(R.id.networkInfoText)
        capturePreviewGroup = findViewById(R.id.capturePreviewGroup)
        capturePreviewImage = findViewById(R.id.capturePreviewImage)
        resultText.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        statusText.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE

        aiClient = AIClient(this)
        voiceAssistant = VoiceAssistant(this)
        walkingMonitor = WalkingMonitor(this, glassesClient) { message ->
            runOnUiThread { resultText.text = message; speak(message) }
        }
        initTts()

        findViewById<Button>(R.id.btnConnect).setOnClickListener { requestPermissionsAndConnect() }
        findViewById<Button>(R.id.btnDescribe).setOnClickListener { selectMode("describe"); runPipeline() }
        findViewById<Button>(R.id.btnRead).setOnClickListener { selectMode("read"); runPipeline() }
        findViewById<Button>(R.id.btnCurrency).setOnClickListener { selectMode("currency"); runPipeline() }
        findViewById<Button>(R.id.btnWalk).setOnClickListener { selectMode("walk"); runPipeline() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btnCapturePhoto).setOnClickListener { capturePhoto() }
        findViewById<Button>(R.id.btnSavePhoto).setOnClickListener { savePhoto() }
        findViewById<Button>(R.id.btnDiscardPhoto).setOnClickListener {
            lastCapturedJpeg = null
            capturePreviewGroup.visibility = View.GONE
        }

        GlassesConnectionService.addListener(glassesListener)

        bg.execute {
            currencyClassifier = CurrencyClassifier.loadOrNull(this, "currency_model.tflite", "currency_labels.txt")
            objectDetector = ObjectDetectorHelper.loadOrNull(this)
        }

        // Auto-connect on open if we already have permission, so the person
        // doesn't have to press Connect every single time - only the very
        // first run (or after denying a permission) needs the button.
        if (hasAllPermissions()) startGlassesService()
    }

    override fun onResume() {
        super.onResume()
        applyTtsSettings()
    }

    override fun onDestroy() {
        GlassesConnectionService.removeListener(glassesListener)
        walkingMonitor.stop()
        currencyClassifier?.close()
        objectDetector?.close()
        voiceAssistant.destroy()
        tts.shutdown()
        super.onDestroy()
    }

    // ---------------- Text-to-speech, driven entirely by Settings ----------------
    private fun initTts() {
        val enginePkg = Prefs.getTtsEnginePackage(this)
        tts = if (enginePkg != null) TextToSpeech(this, { status -> onTtsInit(status) }, enginePkg)
        else TextToSpeech(this) { status -> onTtsInit(status) }
    }

    private fun onTtsInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            tts.language = Locale.US
            applyTtsSettings()
        }
    }

    private fun applyTtsSettings() {
        if (!ttsReady) return
        tts.setSpeechRate(Prefs.getTtsRate(this))
        tts.setPitch(Prefs.getTtsPitch(this))
    }

    // ---------------- Permissions & connection ----------------
    private fun requiredPermissions(): List<String> {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= 33) {
            perms += Manifest.permission.NEARBY_WIFI_DEVICES
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        return perms
    }

    private fun hasAllPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissionsAndConnect() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startGlassesService() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startGlassesService() {
        ContextCompat.startForegroundService(this, Intent(this, GlassesConnectionService::class.java))
        if (!GlassesConnectionService.isConnected) {
            statusText.text = "Starting network, please wait..."
            // Our Wi-Fi Direct network name/password are fixed (same every
            // launch), so glasses that joined once before can often rejoin
            // on their own without a fresh QR scan. For a device that has
            // paired before, give that a silent chance first instead of
            // immediately throwing the QR code up - it's only shown as a
            // fallback if auto-reconnect doesn't happen in time.
            val graceAttempts = if (Prefs.hasEverPaired(this)) 10 else 0
            waitForAutoReconnect(graceAttempts)
        }
    }

    /** Silently waits (no QR shown) for up to graceAttempts seconds in case
     *  the glasses rejoin the fixed network on their own; falls through to
     *  pollForPairingInfo() (which shows the QR) once that window passes. */
    private fun waitForAutoReconnect(attemptsLeft: Int) {
        if (GlassesConnectionService.isConnected) return
        if (attemptsLeft <= 0) {
            pollForPairingInfo(attemptsLeft = 15)
            return
        }
        statusText.text = "Reconnecting to glasses..."
        statusText.postDelayed({ waitForAutoReconnect(attemptsLeft - 1) }, 1000)
    }

    /** Wi-Fi Direct group creation is async, so we poll briefly (only to
     *  fetch the SSID/password, not to detect a failure) until they're
     *  ready, then show the QR code. The network itself starts within a
     *  second or two; after that we simply wait for the glasses to join -
     *  there's no "timeout failure" for that part, since the glasses need
     *  a physical action first (see showPairingQr). */
    private fun pollForPairingInfo(attemptsLeft: Int) {
        if (GlassesConnectionService.isConnected) return
        if (attemptsLeft <= 0) {
            runOnUiThread { statusText.text = "Could not start the Wi-Fi network - check Wi-Fi is on and try Connect again" }
            return
        }
        GlassesConnectionService.requestGroupInfo { ssid, password ->
            if (GlassesConnectionService.isConnected) return@requestGroupInfo
            if (ssid != null && password != null) {
                showPairingQr(ssid, password)
            } else {
                statusText.postDelayed({ pollForPairingInfo(attemptsLeft - 1) }, 1000)
            }
        }
    }

    private fun showPairingQr(ssid: String, password: String) {
        val bmp = QrUtil.wifiQrBitmap(ssid, password) ?: return
        runOnUiThread {
            qrImage.setImageBitmap(bmp)
            networkInfoText.text = "Network: $ssid\nPassword: $password\n\n" +
                "On the glasses: double-press the power button to enter pairing mode. " +
                "Wait for it to beep, then hold it up facing this QR code."
            pairingGroup.visibility = View.VISIBLE
            statusText.text = "Double-press the glasses' power button, then scan this QR"
        }
    }

    // ---------------- Glasses events & gestures ----------------
    private fun handleGlassesEvent(event: GlassesProtocol.Event) {
        when (event) {
            is GlassesProtocol.Event.DeviceIp -> {
                glassesConnectedState = true
                Prefs.setHasEverPaired(this, true)
                pairingGroup.visibility = View.GONE
                speak("Glasses connected")
            }
            is GlassesProtocol.Event.PowerOff -> glassesConnectedState = false
            is GlassesProtocol.Event.Battery -> glassesBatteryPercent = event.percent
            is GlassesProtocol.Event.Tap -> onGlassesTap(event.kind)
            else -> {}
        }
        updateGlassesStatusLine()
    }

    /** One always-accurate place showing connection + battery together,
     *  separate from statusText (which shows what the app is currently
     *  doing - mode, capturing, etc.) so neither ever gets stale or
     *  overwritten by the other. */
    private fun updateGlassesStatusLine() {
        val conn = if (glassesConnectedState) "Connected" else "Not connected"
        val batt = glassesBatteryPercent?.let { " • Battery: $it%" } ?: ""
        glassesStatusText.text = "Glasses: $conn$batt"
    }

    private fun onGlassesTap(kind: String) {
        when (kind) {
            "forward" -> { modeIndex = (modeIndex + 1) % modes.size; announceAndSpeakMode() }
            "backward" -> { modeIndex = (modeIndex - 1 + modes.size) % modes.size; announceAndSpeakMode() }
            "double", "hold_1s" -> runPipeline()
            "single" -> togglePauseResume()
            "long" -> askSmarty()
            "hold_5s" -> emergencyCall()
            "walking" -> { selectMode("walk"); runPipeline() }
        }
    }

    private fun announceAndSpeakMode() {
        if (mode.id != "walk") walkingMonitor.stop()
        statusText.text = "Mode: ${mode.label}"
        statusText.announceForAccessibility("Mode: ${mode.label}")
        speak(mode.label)
    }

    private fun selectMode(id: String) {
        val idx = modes.indexOfFirst { it.id == id }
        if (idx >= 0) modeIndex = idx
        if (mode.id != "walk") walkingMonitor.stop()
        statusText.text = "Mode: ${mode.label}"
        statusText.announceForAccessibility("Mode: ${mode.label}")
    }

    private fun togglePauseResume() {
        if (mode.id == "walk") {
            // speak() always resets isPaused to false, so set the real
            // value AFTER calling it, not before.
            if (isPaused) { walkingMonitor.start(); speak("Walking Assistant resumed"); isPaused = false }
            else { walkingMonitor.stop(); speak("Walking Assistant paused"); isPaused = true }
            return
        }
        if (!ttsReady) return
        if (tts.isSpeaking) {
            tts.stop()
            isPaused = true
            statusText.text = "Paused"
        } else if (isPaused) {
            isPaused = false
            speak(mode.label)
        }
    }

    private fun askSmarty() {
        if (!GlassesConnectionService.isConnected) { speak("Glasses are not connected"); return }
        speak("Go ahead, I'm listening")
        voiceAssistant.listenOnce(onResult = { question ->
            if (question.isNullOrBlank()) {
                speak("Sorry, I didn't catch that")
                return@listenOnce
            }
            runOnUiThread { statusText.text = "Thinking..." }
            bg.execute {
                val jpeg = glassesClient.captureFrame()
                val said = if (jpeg != null) {
                    aiClient.describeImage(jpeg, "A blind person is asking about what's in front of them, through their glasses camera. Their question: \"$question\". Answer briefly and clearly. $noFillerRule")
                        ?: "No response received"
                } else {
                    aiClient.askTextOnly(question) ?: "No response received"
                }
                runOnUiThread {
                    resultText.text = said
                    speak(said)
                }
            }
        })
    }

    private fun emergencyCall() {
        val number = Prefs.getEmergencyNumber(this)
        if (number.isNullOrBlank()) {
            speak("No emergency number set. Please add one in Settings")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), 42)
            return
        }
        speak("Calling your emergency contact")
        startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")))
    }

    // ---------------- Capture Mode: full-resolution photo, preview, save ----------------
    private fun capturePhoto() {
        if (!GlassesConnectionService.isConnected) {
            speak("Glasses are not connected")
            return
        }
        statusText.text = "Capturing at highest resolution..."
        capturePreviewGroup.visibility = View.GONE
        bg.execute {
            val jpeg = glassesClient.captureHighRes()
            runOnUiThread {
                if (jpeg == null) {
                    statusText.text = "Ready"
                    speak("Could not get a photo, please try again")
                    return@runOnUiThread
                }
                lastCapturedJpeg = jpeg
                val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                capturePreviewImage.setImageBitmap(bmp)
                capturePreviewGroup.visibility = View.VISIBLE
                statusText.text = "Photo captured - review it below"
                speak("Photo captured. Save it if it looks good, or discard it.")
            }
        }
    }

    /** Writes the exact JPEG bytes the glasses sent - no Bitmap re-encoding,
     *  so whatever quality the camera captured is exactly what gets saved. */
    private fun savePhoto() {
        val jpeg = lastCapturedJpeg ?: return
        bg.execute {
            val saved = try {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "VisionAI_${System.currentTimeMillis()}.jpg")
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= 29) {
                        put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/VisionAI")
                    }
                }
                val uri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                uri?.let { u ->
                    contentResolver.openOutputStream(u)?.use { out -> out.write(jpeg) }
                    true
                } ?: false
            } catch (e: Exception) { false }
            runOnUiThread {
                if (saved) {
                    speak("Saved to gallery")
                    lastCapturedJpeg = null
                    capturePreviewGroup.visibility = View.GONE
                } else {
                    speak("Could not save the photo")
                }
            }
        }
    }

    // ---------------- Capture pipeline ----------------
    private fun runPipeline() {
        if (!GlassesConnectionService.isConnected) {
            speak("Glasses are not connected")
            return
        }
        isPaused = false
        statusText.text = "Capturing..."
        bg.execute {
            val jpeg = glassesClient.captureFrame()
            if (jpeg == null) {
                runOnUiThread { speak("Could not get a photo, please try again") }
                return@execute
            }
            when (mode.id) {
                "currency" -> runCurrency(jpeg)
                "walk" -> runWalk(jpeg)
                "read" -> runGemini(jpeg, readPrompt())
                else -> runGemini(jpeg, describePrompt())
            }
        }
    }

    private fun runCurrency(jpeg: ByteArray) {
        var classifier = currencyClassifier
        if (classifier == null) {
            classifier = CurrencyClassifier.loadOrNull(this, "currency_model.tflite", "currency_labels.txt")
            currencyClassifier = classifier
        }
        if (classifier == null) {
            val detail = CurrencyClassifier.lastError
            runOnUiThread { speak(if (detail != null) "Currency model error: $detail" else "Currency model did not load - try reopening the app") }
            return
        }
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        val result = classifier.classify(bmp)
        runOnUiThread {
            if (result == null || result.label == "background") {
                speak("Note not clear, bring it closer and try again")
            } else {
                val said = currencyLabelToEnglish(result.label)
                resultText.text = said
                speak(said)
            }
        }
    }

    private fun runWalk(jpeg: ByteArray) {
        walkingMonitor.start()
        var detector = objectDetector
        if (detector == null) {
            detector = ObjectDetectorHelper.loadOrNull(this)
            objectDetector = detector
        }
        if (detector == null) {
            val detail = ObjectDetectorHelper.lastError
            val msg = when {
                detail != null -> "Walking detector error: $detail"
                !ObjectDetectorHelper.isModelDownloaded(this) -> "Walking Assistant model not downloaded yet. Go to Settings and download it first, while you have internet."
                else -> "Walking detector did not load - try reopening the app"
            }
            runOnUiThread { speak(msg) }
            return
        }
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        val detections = detector.detect(bmp)
        runOnUiThread {
            if (detections.isEmpty()) {
                speak("Path looks clear")
            } else {
                val top = detections.sortedByDescending { it.confidence }.take(3)
                    .joinToString(", ") { it.label }
                val said = "Ahead: $top"
                resultText.text = said
                speak(said)
            }
        }
    }

    /** Common rule for every AI prompt: never start with "Sure," "Here is,"
     *  "This image shows," or any other preamble/excuse - go straight to
     *  the point, since that's being read aloud to someone walking. */
    private val noFillerRule = "Never start with an introductory phrase like 'Sure,' 'Here is,' 'This image shows,' or any preamble - go straight to the answer."

    private fun describePrompt(): String {
        val base = "This photo was taken by a blind person's glasses camera. Describe what's in front of them, focusing on what helps them move around safely."
        val length = if (Prefs.getShortDescription(this))
            "Answer in one short sentence, point to point, nothing extra."
        else
            "Give a clear, fuller description in a short paragraph."
        return "$base $length $noFillerRule"
    }

    private fun readPrompt(): String {
        val base = "Read aloud whatever text appears in this photo. Only read the text, don't add anything else."
        return "$base $noFillerRule"
    }

    private fun runGemini(jpeg: ByteArray, prompt: String) {
        val said = aiClient.describeImage(jpeg, prompt) ?: "No response received"
        runOnUiThread {
            resultText.text = said
            speak(said)
        }
    }

    private fun currencyLabelToEnglish(label: String): String = when (label) {
        "ten_new", "ten_old" -> "Ten rupees"
        "twenty_new", "twenty_old" -> "Twenty rupees"
        "fifty_new", "fifty_old" -> "Fifty rupees"
        "hundred_new", "hundred_old" -> "One hundred rupees"
        "two_hundred" -> "Two hundred rupees"
        "five_hundred" -> "Five hundred rupees"
        "two_thousand" -> "Two thousand rupees"
        else -> label
    }

    private fun speak(text: String) {
        isPaused = false
        statusText.text = "Ready"
        if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utt")
    }
}
