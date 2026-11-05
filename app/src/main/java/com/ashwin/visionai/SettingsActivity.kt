package com.ashwin.visionai

import android.app.AlertDialog
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private lateinit var engineSpinner: Spinner
    private lateinit var speedSeekBar: SeekBar
    private lateinit var pitchSeekBar: SeekBar
    private lateinit var speedLabel: TextView
    private lateinit var pitchLabel: TextView
    private var engines: List<TextToSpeech.EngineInfo> = emptyList()

    private lateinit var aiServiceSpinner: Spinner
    private lateinit var apiKeyLabel: TextView
    private lateinit var apiKeyInput: EditText
    private lateinit var aiModelStatus: TextView
    private val services = listOf("Google", "OpenRouter", "Groq")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        setupAiService()
        setupWalkingModelDownload()

        val shortDescSwitch = findViewById<Switch>(R.id.shortDescriptionSwitch)
        shortDescSwitch.isChecked = Prefs.getShortDescription(this)

        val emergencyInput = findViewById<EditText>(R.id.emergencyNumberInput)
        emergencyInput.setText(Prefs.getEmergencyNumber(this).orEmpty())

        engineSpinner = findViewById(R.id.ttsEngineSpinner)
        speedSeekBar = findViewById(R.id.speedSeekBar)
        pitchSeekBar = findViewById(R.id.pitchSeekBar)
        speedLabel = findViewById(R.id.speedLabel)
        pitchLabel = findViewById(R.id.pitchLabel)

        // SeekBar 0..35 maps to speech rate/pitch 0.5x..4.0x in steps of 0.1x
        val savedRate = Prefs.getTtsRate(this)
        val savedPitch = Prefs.getTtsPitch(this)
        speedSeekBar.progress = ((savedRate - 0.5f) / 0.1f).toInt().coerceIn(0, 35)
        pitchSeekBar.progress = ((savedPitch - 0.5f) / 0.1f).toInt().coerceIn(0, 35)
        updateSpeedLabel(savedRate)
        updatePitchLabel(savedPitch)

        speedSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                updateSpeedLabel(progressToValue(progress))
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                if (ttsReady) tts.setSpeechRate(progressToValue(sb?.progress ?: 5))
            }
        })
        pitchSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                updatePitchLabel(progressToValue(progress))
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                if (ttsReady) tts.setPitch(progressToValue(sb?.progress ?: 5))
            }
        })

        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts.setSpeechRate(savedRate)
                tts.setPitch(savedPitch)
                populateEngines()
            }
        }

        findViewById<Button>(R.id.btnTestVoice).setOnClickListener {
            if (ttsReady) tts.speak("This is how I will sound", TextToSpeech.QUEUE_FLUSH, null, "test")
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            saveCurrentApiKey()
            Prefs.setShortDescription(this, shortDescSwitch.isChecked)
            Prefs.setEmergencyNumber(this, emergencyInput.text.toString().trim())
            Prefs.setTtsRate(this, progressToValue(speedSeekBar.progress))
            Prefs.setTtsPitch(this, progressToValue(pitchSeekBar.progress))
            val selectedEngine = engines.getOrNull(engineSpinner.selectedItemPosition - 1)?.name
            Prefs.setTtsEnginePackage(this, selectedEngine)
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    // ---------------- AI Service (Google / OpenRouter / Groq) ----------------
    private fun setupAiService() {
        aiServiceSpinner = findViewById(R.id.aiServiceSpinner)
        apiKeyLabel = findViewById(R.id.apiKeyLabel)
        apiKeyInput = findViewById(R.id.apiKeyInput)
        aiModelStatus = findViewById(R.id.aiModelStatus)

        aiServiceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, services)
        val currentService = Prefs.getAiService(this)
        aiServiceSpinner.setSelection(services.indexOf(currentService).coerceAtLeast(0))
        refreshApiKeyFieldsFor(currentService)

        aiServiceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                saveCurrentApiKey() // don't lose what was typed for the previous service
                val svc = services[position]
                Prefs.setAiService(this@SettingsActivity, svc)
                refreshApiKeyFieldsFor(svc)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        findViewById<Button>(R.id.btnSelectModel).setOnClickListener { fetchAndPickModel() }
    }

    private fun refreshApiKeyFieldsFor(service: String) {
        when (service) {
            "OpenRouter" -> {
                apiKeyLabel.text = "OpenRouter API Key"
                apiKeyInput.setText(Prefs.getOpenRouterKey(this).orEmpty())
                aiModelStatus.text = "Model: ${Prefs.getOpenRouterModel(this)}"
            }
            "Groq" -> {
                apiKeyLabel.text = "Groq API Key"
                apiKeyInput.setText(Prefs.getGroqKey(this).orEmpty())
                aiModelStatus.text = "Model: ${Prefs.getGroqModel(this).ifBlank { "not set" }}"
            }
            else -> {
                apiKeyLabel.text = "Google Gemini API Key"
                apiKeyInput.setText(Prefs.getApiKey(this).orEmpty())
                aiModelStatus.text = "Model: ${Prefs.getGeminiModel(this)}"
            }
        }
    }

    private fun saveCurrentApiKey() {
        val key = apiKeyInput.text.toString().trim()
        when (Prefs.getAiService(this)) {
            "OpenRouter" -> Prefs.setOpenRouterKey(this, key)
            "Groq" -> Prefs.setGroqKey(this, key)
            else -> Prefs.setApiKey(this, key)
        }
    }

    private fun fetchAndPickModel() {
        saveCurrentApiKey() // need the key saved before fetching
        val service = Prefs.getAiService(this)
        Toast.makeText(this, "Fetching models...", Toast.LENGTH_SHORT).show()
        Thread {
            val models = AIClient.fetchModels(this, service)
            runOnUiThread {
                if (models.isEmpty()) {
                    Toast.makeText(this, "Could not fetch models - check the API key and your internet connection", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                AlertDialog.Builder(this)
                    .setTitle("Select Model (${models.size})")
                    .setItems(models.toTypedArray()) { _, which ->
                        val chosen = models[which]
                        when (service) {
                            "OpenRouter" -> Prefs.setOpenRouterModel(this, chosen)
                            "Groq" -> Prefs.setGroqModel(this, chosen)
                            else -> Prefs.setGeminiModel(this, chosen)
                        }
                        aiModelStatus.text = "Model: $chosen"
                        Toast.makeText(this, "Model set to $chosen", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }.start()
    }

    // ---------------- Walking Assistant model download ----------------
    private fun setupWalkingModelDownload() {
        val statusText = findViewById<TextView>(R.id.walkingModelStatus)
        val btn = findViewById<Button>(R.id.btnDownloadWalkingModel)

        fun refreshStatus() {
            val done = ObjectDetectorHelper.isModelDownloaded(this)
            statusText.text = if (done) "Downloaded - Walking Assistant works offline" else "Not downloaded yet - needs internet once"
            btn.isEnabled = !done
            btn.text = if (done) "Already Downloaded" else "Download Walking Assistant Model"
        }
        refreshStatus()

        btn.setOnClickListener {
            btn.isEnabled = false
            btn.text = "Downloading..."
            Thread {
                val ok = ObjectDetectorHelper.downloadModel(this)
                runOnUiThread {
                    refreshStatus()
                    Toast.makeText(this, if (ok) "Downloaded" else "Download failed - check internet and try again", Toast.LENGTH_SHORT).show()
                }
            }.start()
        }
    }

    // ---------------- TTS engine list ----------------
    private fun populateEngines() {
        engines = tts.engines ?: emptyList()
        val names = mutableListOf("System Default")
        names += engines.map { it.label }
        engineSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        val saved = Prefs.getTtsEnginePackage(this)
        val idx = engines.indexOfFirst { it.name == saved }
        engineSpinner.setSelection(if (idx >= 0) idx + 1 else 0)
    }

    private fun progressToValue(progress: Int): Float = 0.5f + progress * 0.1f
    private fun updateSpeedLabel(v: Float) { speedLabel.text = "Speech Speed: ${String.format("%.1f", v)}x" }
    private fun updatePitchLabel(v: Float) { pitchLabel.text = "Speech Pitch: ${String.format("%.1f", v)}x" }

    override fun onDestroy() {
        tts.shutdown()
        super.onDestroy()
    }
}
