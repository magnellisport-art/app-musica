package it.marco.musica

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.Html
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class YtVideo(
    val id: String,
    val title: String,
    val channel: String,
    val thumb: String,
    var duration: String = "",
    var views: String = ""
)

/** Ricerca con l'API ufficiale di YouTube (YouTube Data API v3). */
object YouTube {

    class KeyProblem(msg: String) : Exception(msg)

    private fun get(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 15000
        try {
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val reason = try {
                    JSONObject(body).getJSONObject("error").getJSONArray("errors").getJSONObject(0).optString("reason")
                } catch (e: Exception) { "" }
                throw when {
                    reason.contains("quota", true) -> KeyProblem("Hai finito le ricerche YouTube di oggi: riprova domani.")
                    code == 400 || code == 403 -> KeyProblem("La chiave YouTube non è valida o non è abilitata per YouTube Data API v3.")
                    else -> Exception("YouTube non risponde (errore $code).")
                }
            }
            return JSONObject(body)
        } finally {
            c.disconnect()
        }
    }

    private fun plain(s: String) = Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString()

    /** "PT3M58S" -> "3:58" */
    fun duration(iso: String): String {
        val m = Regex("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?").find(iso) ?: return ""
        val h = m.groupValues[1].toIntOrNull() ?: 0
        val min = m.groupValues[2].toIntOrNull() ?: 0
        val s = m.groupValues[3].toIntOrNull() ?: 0
        return if (h > 0) "$h:${min.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
        else "$min:${s.toString().padStart(2, '0')}"
    }

    fun views(n: Long): String = when {
        n >= 1_000_000_000 -> String.format(java.util.Locale.ITALIAN, "%.1f Mrd visualizzazioni", n / 1e9)
        n >= 1_000_000 -> String.format(java.util.Locale.ITALIAN, "%.1f Mln visualizzazioni", n / 1e6)
        else -> String.format(java.util.Locale.ITALIAN, "%,d visualizzazioni", n)
    }

    fun search(key: String, query: String): List<YtVideo> {
        val q = Uri.encode(query)
        val k = Uri.encode(key)
        val res = get("https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&videoEmbeddable=true&maxResults=8&regionCode=IT&relevanceLanguage=it&q=$q&key=$k")
        val items = res.optJSONArray("items") ?: return emptyList()
        val list = (0 until items.length()).mapNotNull { i ->
            val it = items.getJSONObject(i)
            val id = it.optJSONObject("id")?.optString("videoId").orEmpty()
            if (id.isEmpty()) return@mapNotNull null
            val sn = it.getJSONObject("snippet")
            val th = sn.optJSONObject("thumbnails")
            val thumb = th?.optJSONObject("medium")?.optString("url") ?: th?.optJSONObject("default")?.optString("url") ?: ""
            YtVideo(id, plain(sn.optString("title")), plain(sn.optString("channelTitle")), thumb)
        }
        if (list.isEmpty()) return list
        // durata e visualizzazioni
        try {
            val det = get("https://www.googleapis.com/youtube/v3/videos?part=contentDetails,statistics&id=${list.joinToString(",") { it.id }}&key=$k")
            val arr = det.optJSONArray("items")
            if (arr != null) for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val v = list.firstOrNull { it.id == o.optString("id") } ?: continue
                v.duration = duration(o.optJSONObject("contentDetails")?.optString("duration").orEmpty())
                o.optJSONObject("statistics")?.optString("viewCount")?.toLongOrNull()?.let { v.views = views(it) }
            }
        } catch (e: Exception) { }
        return list
    }

    fun image(url: String): Bitmap? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 10000
        c.inputStream.use { BitmapFactory.decodeStream(it) }.also { c.disconnect() }
    } catch (e: Exception) {
        null
    }
}
