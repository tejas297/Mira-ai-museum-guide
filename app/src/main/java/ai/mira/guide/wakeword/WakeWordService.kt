package ai.mira.guide.wakeword

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Always-listening "Hey Mira" detector. Vosk runs fully on-device with a
 * constrained grammar; nothing leaves the phone until the phrase fires.
 * The model (~40MB) downloads once on first start, then unzips locally.
 */
class WakeWordService : Service() {
    companion object {
        @Volatile var onWakeWord: (() -> Unit)? = null
        @Volatile var onStatus: ((String) -> Unit)? = null
        @Volatile var instance: WakeWordService? = null
        private const val MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        private const val MODEL_DIR = "vosk-model-small-en-us-0.15"
    }

    private var speechService: SpeechService? = null
    private var model: Model? = null
    @Volatile private var paused = false
    @Volatile private var lastWakeMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForeground(7, buildNotification())
        onStatus?.invoke("Wake word: loading model...")
        Thread { prepareModel() }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        speechService?.stop()
        speechService?.shutdown()
        model?.close()
        instance = null
        super.onDestroy()
    }

    fun setPaused(value: Boolean) {
        paused = value
        val svc = speechService ?: return
        if (value) svc.stop() else svc.startListening(listener)
    }

    private fun buildNotification(): Notification {
        val channelId = "mira-wake"
        val mgr = getSystemService(NotificationManager::class.java)
        mgr?.createNotificationChannel(
            NotificationChannel(channelId, "Mira listening", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, channelId)
            .setContentTitle("Mira is listening")
            .setContentText("Say Hey Mira, or tap Ask Mira")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }

    private fun prepareModel() {
        try {
            val dir = File(filesDir, MODEL_DIR)
            if (!File(dir, "am").exists()) {
                onStatus?.invoke("Wake word: downloading model (one time, ~40MB)...")
                downloadAndUnzip(dir)
            }
            model = Model(dir.absolutePath)
            val grammar = "[\"hey mira\", \"hello mira\", \"mira\", \"hey mirror\", \"hey mera\"]"
            val recognizer = Recognizer(model, 16000.0f, grammar)
            speechService = SpeechService(recognizer, 16000.0f)
            if (!paused) speechService?.startListening(listener)
            onStatus?.invoke("Listening for Hey Mira...")
        } catch (e: Exception) {
            onStatus?.invoke("Wake word unavailable: ${e.message}. Mic button still works.")
        }
    }

    private fun downloadAndUnzip(target: File) {
        val zip = File(cacheDir, "vosk-model.zip")
        URL(MODEL_URL).openStream().use { input ->
            FileOutputStream(zip).use { out -> input.copyTo(out) }
        }
        ZipInputStream(zip.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                val rel = name.substringAfter("$MODEL_DIR/", "")
                if (rel.isNotEmpty()) {
                    val out = File(target, rel)
                    if (entry.isDirectory) out.mkdirs()
                    else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { o -> zis.copyTo(o) }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        zip.delete()
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            checkWake(hypothesis, "partial")
        }

        override fun onResult(hypothesis: String?) {
            checkWake(hypothesis, "text")
        }

        override fun onFinalResult(hypothesis: String?) {}

        override fun onError(e: Exception?) {
            onStatus?.invoke("Wake word hiccup: ${e?.message ?: "unknown"}")
        }

        override fun onTimeout() {}
    }

    private fun checkWake(hypothesis: String?, key: String) {
        if (paused || hypothesis.isNullOrBlank()) return
        val text = try {
            JSONObject(hypothesis).optString(key).lowercase()
        } catch (e: Exception) {
            return
        }
        if (text.contains("mira") || text.contains("mirror") || text.contains("mera")) {
            val now = System.currentTimeMillis()
            if (now - lastWakeMs > 3000) {
                lastWakeMs = now
                onStatus?.invoke("Heard you. Listening...")
                onWakeWord?.invoke()
            }
        }
    }
}
