package ai.mira.guide.data

import ai.mira.guide.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class Artifact(
    val id: String,
    val title: String,
    val shortIntro: String,
    val description: String,
    val maker: String,
    val era: String,
    val materials: String,
    val minor: Int
) {
    companion object {
        fun fromJson(o: JSONObject): Artifact = Artifact(
            id = o.getString("id"),
            title = o.optString("title"),
            shortIntro = o.optString("short_intro"),
            description = o.optString("description"),
            maker = o.optString("maker"),
            era = o.optString("era"),
            materials = o.optString("materials"),
            minor = o.optJSONObject("beacon")?.optInt("minor", 0) ?: 0
        )
    }
}

/** Fetches venue artifacts, caches to disk, serves cache offline. */
class ArtifactRepository(private val cacheDir: File) {
    fun load(): List<Artifact> {
        val cache = File(cacheDir, "artifacts.json")
        try {
            val body = httpGet("${BuildConfig.BASE_URL}/venues/${BuildConfig.VENUE_ID}/artifacts")
            cache.writeText(body)
            return parse(body)
        } catch (e: Exception) {
            if (cache.exists()) return parse(cache.readText())
            throw e
        }
    }

    private fun parse(body: String): List<Artifact> {
        val arr = JSONObject(body).getJSONArray("artifacts")
        return (0 until arr.length()).map { Artifact.fromJson(arr.getJSONObject(it)) }
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        try {
            if (conn.responseCode != 200) throw RuntimeException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }
}
