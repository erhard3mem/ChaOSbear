package com.example.voiceapp

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.os.PowerManager
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var btnMic: Button
    private lateinit var btnHistory: Button
    private lateinit var tvStatus: TextView

    private val serverUrl = "http://127.0.0.1:5555/api/data"

    // Datei liegt im internen App-Speicher: /data/data/com.example.voiceapp/files/history.json
    private val historyFile by lazy { File(filesDir, "history.json") }
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        private const val REQUEST_RECORD_AUDIO = 100
        private const val TAG = "VoiceApp"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnMic     = findViewById(R.id.btnMic)
        btnHistory = findViewById(R.id.btnHistory)
        tvStatus   = findViewById(R.id.tvStatus)

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            tvStatus.text = "Spracherkennung nicht verfügbar auf diesem Gerät."
            btnMic.isEnabled = false
            return
        }

        setupSpeechRecognizer()
        btnMic.setOnClickListener     { checkPermissionAndListen() }
        btnHistory.setOnClickListener { showHistory() }
    }

    // ── Speech Recognizer ─────────────────────────────────────────────────────
    private fun setupSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(object : RecognitionListener {

            override fun onReadyForSpeech(params: Bundle?) {
                tvStatus.text    = "🎤 Ich höre zu..."
                btnMic.text      = "Aufnahme läuft..."
                btnMic.isEnabled = false
            }

            override fun onBeginningOfSpeech() { tvStatus.text = "🎙️ Sprechen erkannt..." }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { tvStatus.text = "⏳ Verarbeite..." }

            override fun onError(error: Int) {
                val errorMsg = when (error) {
                    SpeechRecognizer.ERROR_AUDIO                    -> "Audio-Aufnahmefehler"
                    SpeechRecognizer.ERROR_CLIENT                   -> "Client-Fehler"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Keine Berechtigung"
                    SpeechRecognizer.ERROR_NETWORK                  -> "Netzwerkfehler"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT          -> "Netzwerk-Timeout"
                    SpeechRecognizer.ERROR_NO_MATCH                 -> "Kein Ergebnis erkannt"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY          -> "Erkennung beschäftigt"
                    SpeechRecognizer.ERROR_SERVER                   -> "Server-Fehler"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT           -> "Timeout – keine Sprache"
                    else                                            -> "Unbekannter Fehler ($error)"
                }
                resetMicButton()
                showResultDialog("Fehler", "❌ $errorMsg")
            }

            override fun onResults(results: Bundle?) {
                resetMicButton()
                val matches       = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val recognizedText = matches?.firstOrNull() ?: "Kein Text erkannt"
                showResultDialog("Erkannter Text", recognizedText)
                postSpeechText(recognizedText)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!partial.isNullOrEmpty()) tvStatus.text = "$partial"
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    // ── HTTP POST ─────────────────────────────────────────────────────────────
    private fun postSpeechText(text: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = (URL(serverUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    doOutput       = true
                    connectTimeout = 600_000
                    readTimeout    = 600_000
                }

                val body = JSONObject().apply { put("message", text) }.toString()
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }

                val code     = conn.responseCode
                val stream   = if (code in 200..299) conn.inputStream else conn.errorStream
                val response = stream.bufferedReader(Charsets.UTF_8).readText()
                conn.disconnect()

                Log.d(TAG, "Server Antwort ($code): $response")

                val result = JSONObject(response).getString("browser-result")

                // Eintrag in Historien-Datei speichern
                saveToHistory(text, result)

                withContext(Dispatchers.Main) {
                    tvStatus.text = if (code in 200..299) "✅ Gesendet" else "⚠️ HTTP $code"
                    showResultDialog("Bot-Antwort", result)
                }

            } catch (e: Exception) {
                Log.e(TAG, "POST fehlgeschlagen: ${e.message}")
                withContext(Dispatchers.Main) {
                    tvStatus.text = "❌ Senden fehlgeschlagen: ${e.message}"
                }
            }
        }
    }

    // ── Historie: Speichern ───────────────────────────────────────────────────
    private fun saveToHistory(spokenText: String, botResponse: String) {
        val timestamp = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMANY).format(Date())

        val entry = JSONObject().apply {
            put("timestamp",  timestamp)
            put("spoken",     spokenText)
            put("response",   botResponse)
        }

        // Bestehende Historie laden oder leeres Array anlegen
        val array = if (historyFile.exists()) {
            try { JSONArray(historyFile.readText()) } catch (e: Exception) { JSONArray() }
        } else {
            JSONArray()
        }

        array.put(entry)
        historyFile.writeText(array.toString(2))   // pretty-printed JSON
        Log.d(TAG, "Historie gespeichert: ${historyFile.absolutePath}")
    }

    // ── Historie: Anzeigen ────────────────────────────────────────────────────
    private fun showHistory() {
        if (!historyFile.exists()) {
            showResultDialog("Historie", "Noch keine Einträge vorhanden.")
            return
        }

        val array = try {
            JSONArray(historyFile.readText())
        } catch (e: Exception) {
            showResultDialog("Fehler", "Historie konnte nicht geladen werden.")
            return
        }

        if (array.length() == 0) {
            showResultDialog("Historie", "Noch keine Einträge vorhanden.")
            return
        }

        // Einträge von neu nach alt anzeigen
        val sb = StringBuilder()
        for (i in array.length() - 1 downTo 0) {
            val entry = array.getJSONObject(i)
            sb.appendLine("🕐 ${entry.getString("timestamp")}")
            sb.appendLine("🎤 ${entry.getString("spoken")}")
            sb.appendLine("🤖 ${entry.getString("response")}")
            if (i > 0) sb.appendLine("─────────────────────")
        }

        // Dialog mit Scroll-Funktion
        val tv = TextView(this).apply {
            text    = sb.toString()
            setPadding(48, 32, 48, 32)
            textSize = 13f
        }
        val scroll = android.widget.ScrollView(this).apply { addView(tv) }

        AlertDialog.Builder(this)
            .setTitle("📋 Verlauf (${array.length()} Einträge)")
            .setView(scroll)
            .setPositiveButton("Schließen") { d, _ -> d.dismiss() }
            .setNegativeButton("Löschen") { d, _ ->
                historyFile.delete()
                tvStatus.text = "🗑️ Historie gelöscht"
                d.dismiss()
            }
            .show()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun resetMicButton() {
        btnMic.text      = "🎤 Sprechen"
        btnMic.isEnabled = true
        tvStatus.text    = "Bereit – Mikrofon drücken"
    }

    private fun checkPermissionAndListen() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
        } else {
            startListening()
        }
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        speechRecognizer.startListening(intent)
    }

    private fun showResultDialog(title: String, message: String) {
        // Bildschirm aufwecken falls Display aus ist
        acquireWakeLock()

        // Fenster über Lockscreen erzwingen (API 27+ kompatibel)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
                releaseWakeLock()
            }
            .setCancelable(true)
            .show()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
            PowerManager.ACQUIRE_CAUSES_WAKEUP or
            PowerManager.ON_AFTER_RELEASE,
            "VoiceApp::ResultWakeLock"
        )
        wakeLock?.acquire(60_000L) // max 60 Sekunden, dann auto-release
        Log.d(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            Log.d(TAG, "WakeLock released")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int,
        permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) startListening()
        else showResultDialog("Berechtigung verweigert",
            "Mikrofon-Zugriff wurde nicht erlaubt. Bitte in den App-Einstellungen aktivieren.")
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer.destroy()
        releaseWakeLock()
    }
}
