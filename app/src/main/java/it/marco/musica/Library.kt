package it.marco.musica

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.text.Normalizer

data class Track(
    val uri: String,
    val name: String,
    val folder: String,
    var title: String,
    var artist: String,
    var album: String = "",
    var lyrics: String = "",
    var scanned: Boolean = false,
    var modified: Long = 0L,
    var duration: Long = 0L
)

object Library {

    private val AUDIO_EXT = Regex("\\.(mp3|m4a|aac|flac|ogg|oga|opus|wav)$", RegexOption.IGNORE_CASE)
    private val ARTIST_TITLE = Regex("^(?:\\d+[\\s.\\-]+)?(.+?)\\s+-\\s+(.+)$")
    private val LEADING_NUM = Regex("^\\d+[\\s.\\-]+")
    private val MARKS = Regex("\\p{Mn}+")

    fun norm(s: String): String =
        MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase()

    /** Elenca i file audio nella cartella scelta e nelle sottocartelle. */
    fun listAudio(ctx: Context, treeUri: Uri): List<Track> {
        val out = mutableListOf<Track>()
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        walk(ctx, treeUri, rootId, "", out, 0)
        out.sortWith(compareBy({ it.folder.lowercase() }, { it.name.lowercase() }))
        return out
    }

    private fun walk(ctx: Context, tree: Uri, docId: String, path: String, out: MutableList<Track>, depth: Int) {
        if (depth > 12) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        try {
            ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        walk(ctx, tree, id, if (path.isEmpty()) name else "$path/$name", out, depth + 1)
                    } else if (mime.startsWith("audio/") || AUDIO_EXT.containsMatchIn(name)) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        val base = name.substringBeforeLast('.').replace('_', ' ').trim()
                        val m = ARTIST_TITLE.find(base)
                        val title = m?.groupValues?.get(2) ?: LEADING_NUM.replace(base, "")
                        val artist = m?.groupValues?.get(1) ?: ""
                        out.add(Track(uri.toString(), name, path, title, artist, modified = if (c.isNull(3)) 0L else c.getLong(3)))
                    }
                }
            }
        } catch (e: Exception) {
            // cartella non leggibile: la saltiamo
        }
    }

    /** Legge titolo, artista, album e testo (tag ID3) dal file. */
    fun readMeta(ctx: Context, t: Track) {
        val uri = Uri.parse(t.uri)
        try {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(ctx, uri)
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }?.let { t.title = it.trim() }
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }?.let { t.artist = it.trim() }
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { t.duration = it }
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() }?.let { t.album = it.trim() }
            } finally {
                r.release()
            }
        } catch (e: Exception) { }
        try {
            ctx.contentResolver.openInputStream(uri)?.use { t.lyrics = Id3Lyrics.read(it) ?: "" }
        } catch (e: Exception) { }
        t.scanned = true
    }

    /** Copertina dell'album salvata dentro il file (ridotta per risparmiare memoria). */
    fun artwork(ctx: Context, uri: String, maxSize: Int = 800): Bitmap? {
        return try {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(ctx, Uri.parse(uri))
                val bytes = r.embeddedPicture ?: return null
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                var sample = 1
                while (o.outWidth / (sample * 2) >= maxSize && o.outHeight / (sample * 2) >= maxSize) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            } finally {
                r.release()
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---- archivio locale, così la libreria non va riletta a ogni apertura ----

    private fun file(ctx: Context) = File(ctx.filesDir, "libreria.json")

    fun save(ctx: Context, list: List<Track>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("u", it.uri); put("n", it.name); put("f", it.folder)
                put("t", it.title); put("a", it.artist); put("al", it.album)
                put("l", it.lyrics); put("s", it.scanned); put("m", it.modified); put("d", it.duration)
            })
        }
        try { file(ctx).writeText(arr.toString()) } catch (e: Exception) { }
    }

    fun load(ctx: Context): List<Track> {
        return try {
            val f = file(ctx)
            if (!f.exists()) return emptyList()
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Track(
                    o.getString("u"), o.optString("n"), o.optString("f"),
                    o.optString("t"), o.optString("a"), o.optString("al"),
                    o.optString("l"), o.optBoolean("s"), o.optLong("m"), o.optLong("d")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

/** Lettore minimo dei testi salvati nei tag ID3 (frame USLT). */
object Id3Lyrics {

    fun read(input: InputStream): String? {
        val h = ByteArray(10)
        if (readFully(input, h) < 10) return null
        if (h[0] != 'I'.code.toByte() || h[1] != 'D'.code.toByte() || h[2] != '3'.code.toByte()) return null
        val major = h[3].toInt()
        val flags = h[5].toInt() and 0xff
        val size = synch(h, 6)
        if (size <= 0 || size > 32 * 1024 * 1024) return null
        var data = ByteArray(size)
        val n0 = readFully(input, data)
        if (flags and 0x80 != 0 && major < 4) data = unsync(data, n0) else if (n0 < size) data = data.copyOf(n0)
        val n = data.size

        var pos = 0
        if (flags and 0x40 != 0 && n >= 4) {
            pos = if (major == 3) 4 + be32(data, 0) else synch(data, 0)
        }
        val idLen = if (major == 2) 3 else 4
        val hdrLen = if (major == 2) 6 else 10

        while (pos >= 0 && pos + hdrLen <= n) {
            if (data[pos].toInt() == 0) break
            val id = String(data, pos, idLen, Charsets.ISO_8859_1)
            val fsize = when (major) {
                2 -> be24(data, pos + 3)
                3 -> be32(data, pos + 4)
                else -> synch(data, pos + 4)
            }
            val start = pos + hdrLen
            if (fsize <= 0 || start + fsize > n) break
            if (id == "USLT" || id == "ULT") return decode(data, start, fsize)
            pos = start + fsize
        }
        return null
    }

    private fun decode(d: ByteArray, start: Int, len: Int): String? {
        val end = start + len
        val enc = d[start].toInt()
        var p = start + 4 // codifica + lingua (3 byte)
        if (enc == 1 || enc == 2) {
            while (p + 1 < end && !(d[p].toInt() == 0 && d[p + 1].toInt() == 0)) p += 2
            p += 2
        } else {
            while (p < end && d[p].toInt() != 0) p++
            p++
        }
        if (p >= end) return null
        val cs = when (enc) {
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        val text = String(d, p, end - p, cs).replace("\r\n", "\n").replace('\r', '\n').trim('\u0000', ' ', '\n')
        return text.ifBlank { null }
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val r = input.read(buf, off, buf.size - off)
            if (r < 0) break
            off += r
        }
        return off
    }

    private fun unsync(d: ByteArray, n: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(n)
        var i = 0
        while (i < n) {
            val b = d[i]
            out.write(b.toInt())
            if ((b.toInt() and 0xff) == 0xff && i + 1 < n && d[i + 1].toInt() == 0) i++
            i++
        }
        return out.toByteArray()
    }

    private fun synch(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0x7f) shl 21) or ((b[o + 1].toInt() and 0x7f) shl 14) or
            ((b[o + 2].toInt() and 0x7f) shl 7) or (b[o + 3].toInt() and 0x7f)

    private fun be32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xff) shl 24) or ((b[o + 1].toInt() and 0xff) shl 16) or
            ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff)

    private fun be24(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xff) shl 16) or ((b[o + 1].toInt() and 0xff) shl 8) or (b[o + 2].toInt() and 0xff)
}
