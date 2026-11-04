package ai.mira.guide.net

import ai.mira.guide.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Turn(val role: String, val text: String)

object MiraApi {
    /** Fire-and-forget analytics event. Never blocks the conversation. */
    fun logEvent(type: String, artifactId: String, question: String, dwellS: Double) {
        try {
            val body = JSONObject()
                .put("type", type)
                .put("artifact_id", artifactId)
                .put("question_text", question)
                .put("dwell_s", dwellS)
            val conn = URL("${BuildConfig.BASE_URL}/events").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            conn.responseCode
            conn.disconnect()
        } catch (e: Exception) {
            // Analytics must never break the guide experience.
        }
    }

    /**
     * Streams /ask sentence by sentence. [onSentence] fires per spoken sentence
     * (call it on a background thread; hop to UI inside). Returns full answer.
     */
    fun askStreaming(
        artifactId: String,
        question: String,
        history: List<Turn>,
        intent: String,
        visited: List<String>,
        interests: List<String>,
        proactiveIndex: Int,
        lang: String,
        onSentence: (String) -> Unit
    ): String {
        val body = JSONObject()
            .put("artifact_id", artifactId)
            .put("question_text", question)
            .put("intent", intent)
            .put("visited", JSONArray(visited))
            .put("interests", JSONArray(interests))
            .put("proactive_index", proactiveIndex)
            .put("lang", lang)
            .put(
                "history",
                JSONArray(history.map { JSONObject().put("role", it.role).put("text", it.text) })
            )
        val conn = URL("${BuildConfig.BASE_URL}/ask").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "text/event-stream")
        conn.connectTimeout = 10000
        conn.readTimeout = 70000
        conn.doOutput = true
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) throw RuntimeException("HTTP ${conn.responseCode}")
            var full = ""
            conn.inputStream.bufferedReader().forEachLine { line ->
                if (!line.startsWith("data:")) return@forEachLine
                val evt = try {
                    JSONObject(line.substring(5).trim())
                } catch (e: Exception) {
                    return@forEachLine
                }
                when (evt.optString("type")) {
                    "sentence" -> onSentence(evt.optString("text"))
                    "done" -> full = evt.optString("answer_text")
                }
            }
            return full
        } finally {
            conn.disconnect()
        }
    }
}
