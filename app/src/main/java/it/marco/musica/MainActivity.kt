package it.marco.musica

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

class MainActivity : Activity() {

    companion object {
        private const val REQ_TREE = 1
        private const val REQ_VOICE = 2
        private const val REQ_NOTIF = 3
        private const val PREFS = "musica"
        private const val KEY_TREE = "tree"
        private const val KEY_SORT = "sort"
        private val SORT_NAMES = arrayOf(
            "Cartella", "Titolo (A-Z)", "Titolo (Z-A)", "Artista (A-Z)", "Album (A-Z)",
            "Ultima modifica (più recenti)", "Ultima modifica (meno recenti)"
        )
        private val PLAY_CMD = Regex("^(riproduci|suona|metti|ascolta|fammi sentire|play)\\s+(.+)$", RegexOption.IGNORE_CASE)
        private val SEARCH_CMD = Regex("^(cerca|trova)\\s+", RegexOption.IGNORE_CASE)
    }

    private val tracks = mutableListOf<Track>()
    private val byUri = HashMap<String, Int>()
    private var view: List<Int> = emptyList()
    private var currentUri: String? = null
    private var scanning = false
    private var sortMode = 0
    private var artUri: String? = null
    private val collator = java.text.Collator.getInstance(java.util.Locale.ITALIAN).apply { strength = java.text.Collator.PRIMARY }

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val handler = Handler(Looper.getMainLooper())
    private var userSeeking = false

    private lateinit var countText: TextView
    private lateinit var statusText: TextView
    private lateinit var searchBox: EditText
    private lateinit var list: ListView
    private lateinit var nowTitle: TextView
    private lateinit var nowArtist: TextView
    private lateinit var lyricsBtn: Button
    private lateinit var seek: SeekBar
    private lateinit var curText: TextView
    private lateinit var durText: TextView
    private lateinit var playBtn: ImageButton
    private lateinit var shuffleBtn: ImageButton
    private lateinit var repeatBtn: ImageButton
    private lateinit var adapter: TrackAdapter
    private lateinit var sortBtn: Button
    private lateinit var bgArt: ImageView
    private lateinit var cover: ImageView

    private var accent = 0
    private var ink = 0
    private var muted = 0

    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        accent = getColor(R.color.accent)
        ink = getColor(R.color.ink)
        muted = getColor(R.color.muted)

        countText = findViewById(R.id.count)
        statusText = findViewById(R.id.status)
        searchBox = findViewById(R.id.search)
        list = findViewById(R.id.list)
        nowTitle = findViewById(R.id.nowTitle)
        nowArtist = findViewById(R.id.nowArtist)
        lyricsBtn = findViewById(R.id.lyricsBtn)
        seek = findViewById(R.id.seek)
        curText = findViewById(R.id.cur)
        durText = findViewById(R.id.dur)
        playBtn = findViewById(R.id.play)
        shuffleBtn = findViewById(R.id.shuffle)
        repeatBtn = findViewById(R.id.repeat)
        sortBtn = findViewById(R.id.sort)
        bgArt = findViewById(R.id.bgArt)
        cover = findViewById(R.id.cover)
        cover.clipToOutline = true

        sortMode = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_SORT, 0).coerceIn(0, SORT_NAMES.size - 1)
        updateSortLabel()
        sortBtn.setOnClickListener { chooseSort() }

        adapter = TrackAdapter()
        list.adapter = adapter
        list.emptyView = findViewById(R.id.empty)
        list.setOnItemClickListener { _, _, pos, _ -> playFrom(view[pos]) }

        findViewById<Button>(R.id.pickFolder).setOnClickListener { pickFolder() }
        findViewById<Button>(R.id.rescan).setOnClickListener { rescan() }
        findViewById<ImageButton>(R.id.mic).setOnClickListener { startVoice() }
        findViewById<ImageButton>(R.id.next).setOnClickListener { controller?.seekToNext() }
        findViewById<ImageButton>(R.id.prev).setOnClickListener { controller?.seekToPrevious() }
        playBtn.setOnClickListener { togglePlay() }
        shuffleBtn.setOnClickListener { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
        repeatBtn.setOnClickListener {
            controller?.let { it.repeatMode = if (it.repeatMode == Player.REPEAT_MODE_ALL) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ALL }
        }
        lyricsBtn.setOnClickListener { showLyrics() }

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = refresh()
        })

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    val d = controller?.duration ?: return
                    if (d > 0) curText.text = fmt(d * p / 1000)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { userSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                userSeeking = false
                val c = controller ?: return
                if (c.duration > 0) c.seekTo(c.duration * seek.progress / 1000)
            }
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }

        val saved = Library.load(this)
        setTracks(saved)
        val tree = savedTree()
        when {
            tree == null -> statusText.text = getString(R.string.hint_first)
            saved.isEmpty() || saved.any { it.modified == 0L } -> scan(tree)
            else -> {
                statusText.text = getString(R.string.hint_ready, saved.size, saved.count { it.lyrics.isNotEmpty() })
                if (saved.any { !it.scanned }) readAllMeta(saved)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            if (isDestroyed) return@addListener
            val c = try { future.get() } catch (e: Exception) { null } ?: return@addListener
            controller = c
            c.addListener(playerListener)
            syncFromPlayer()
            handler.post(ticker)
        }, MoreExecutors.directExecutor())
    }

    override fun onStop() {
        handler.removeCallbacks(ticker)
        controller?.removeListener(playerListener)
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = updatePlayIcon()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = syncFromPlayer()
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = updateToggles()
        override fun onRepeatModeChanged(repeatMode: Int) = updateToggles()
    }

    // ---------- Cartella e libreria ----------

    private fun savedTree(): Uri? =
        getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_TREE, null)?.let { Uri.parse(it) }

    private fun pickFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        try {
            startActivityForResult(i, REQ_TREE)
        } catch (e: ActivityNotFoundException) {
            toast("Su questo telefono non trovo la schermata per scegliere le cartelle.")
        }
    }

    private fun rescan() {
        val tree = savedTree()
        if (tree == null) pickFolder() else scan(tree)
    }

    private fun scan(tree: Uri) {
        if (scanning) return
        scanning = true
        statusText.text = getString(R.string.scanning)
        Thread {
            val found = Library.listAudio(this, tree)
            val old = tracks.associateBy { it.uri }
            // conserva i dati già letti per i brani che c'erano già
            val merged = found.map { t -> old[t.uri]?.takeIf { it.scanned }?.also { it.modified = t.modified } ?: t }
            runOnUiThread {
                setTracks(merged)
                if (merged.isEmpty()) statusText.text = getString(R.string.no_audio)
            }
            readAllMeta(merged, alreadyOnThread = true)
        }.start()
    }

    private fun readAllMeta(items: List<Track>, alreadyOnThread: Boolean = false) {
        val work = Runnable {
            scanning = true
            val todo = items.filter { !it.scanned }
            todo.forEachIndexed { i, t ->
                Library.readMeta(this, t)
                if (i % 20 == 19) runOnUiThread {
                    statusText.text = getString(R.string.reading, i + 1, todo.size)
                    refresh()
                }
            }
            Library.save(this, items)
            scanning = false
            runOnUiThread {
                refresh()
                statusText.text = getString(R.string.hint_ready, items.size, items.count { it.lyrics.isNotEmpty() })
            }
        }
        if (alreadyOnThread) work.run() else Thread(work).start()
    }

    private fun setTracks(list: List<Track>) {
        tracks.clear()
        tracks.addAll(list)
        byUri.clear()
        tracks.forEachIndexed { i, t -> byUri[t.uri] = i }
        refresh()
    }

    // ---------- Ricerca ----------

    private fun words(): List<String> =
        Library.norm(searchBox.text.toString()).split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun refresh() {
        val w = words()
        view = tracks.indices.filter { i ->
            if (w.isEmpty()) true else {
                val t = tracks[i]
                val hay = Library.norm("${t.title} ${t.artist} ${t.album} ${t.name} ${t.folder}")
                val lyr = Library.norm(t.lyrics)
                w.all { hay.contains(it) || lyr.contains(it) }
            }
        }
        view = sorted(view)
        countText.text = if (w.isEmpty()) "${tracks.size} brani" else "${view.size} di ${tracks.size}"
        adapter.notifyDataSetChanged()
    }

    private fun sorted(idx: List<Int>): List<Int> {
        fun byText(key: (Track) -> String) = Comparator<Int> { a, b -> collator.compare(key(tracks[a]), key(tracks[b])) }
        val c: Comparator<Int> = when (sortMode) {
            1 -> byText { it.title }
            2 -> byText { it.title }.reversed()
            3 -> byText { it.artist.ifEmpty { "\uFFFF" } }.then(byText { it.title })
            4 -> byText { it.album.ifEmpty { "\uFFFF" } }.then(byText { it.title })
            5 -> compareByDescending<Int> { tracks[it].modified }
            6 -> compareBy<Int> { tracks[it].modified }
            else -> return idx // ordine della cartella
        }
        return idx.sortedWith(c)
    }

    private fun updateSortLabel() {
        sortBtn.text = "Ordina: " + SORT_NAMES[sortMode].substringBefore(" (")
    }

    private fun chooseSort() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Ordina i brani per")
            .setSingleChoiceItems(SORT_NAMES, sortMode) { d, which ->
                sortMode = which
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_SORT, which).apply()
                updateSortLabel()
                refresh()
                list.setSelection(0)
                d.dismiss()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun snippet(t: Track, w: List<String>): CharSequence? {
        if (w.isEmpty() || t.lyrics.isEmpty()) return null
        val hay = Library.norm("${t.title} ${t.artist} ${t.album} ${t.name}")
        if (w.all { hay.contains(it) }) return null
        val flat = t.lyrics.replace(Regex("\\s+"), " ")
        val n = Library.norm(flat)
        val word = w.firstOrNull { n.contains(it) } ?: return null
        val i = n.indexOf(word)
        if (n.length != flat.length) return null
        val s = maxOf(0, i - 30)
        val e = minOf(flat.length, i + word.length + 40)
        val text = "…" + flat.substring(s, e) + "…"
        val sp = SpannableString(text)
        val hs = 1 + (i - s)
        sp.setSpan(ForegroundColorSpan(accent), hs, hs + word.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sp.setSpan(StyleSpan(Typeface.BOLD), hs, hs + word.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sp
    }

    // ---------- Voce ----------

    private fun startVoice() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_prompt))
        try {
            startActivityForResult(i, REQ_VOICE)
        } catch (e: ActivityNotFoundException) {
            toast("Riconoscimento vocale non disponibile: installa o aggiorna l'app Google.")
        }
    }

    private fun handleSpoken(spoken: String) {
        var q = spoken.trim()
        var autoplay = false
        PLAY_CMD.find(q)?.let { q = it.groupValues[2]; autoplay = true }
        q = SEARCH_CMD.replace(q, "")
        searchBox.setText(q)
        searchBox.setSelection(q.length)
        if (autoplay && view.isNotEmpty()) playFrom(view[0])
        statusText.text = if (view.isEmpty()) "«$spoken»: nessun brano trovato" else "«$spoken»: ${view.size} risultati"
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_TREE -> {
                val uri = data.data ?: return
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: SecurityException) { }
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_TREE, uri.toString()).apply()
                scan(uri)
            }
            REQ_VOICE -> {
                val text = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
                handleSpoken(text)
            }
        }
    }

    // ---------- Riproduzione ----------

    private fun playFrom(index: Int) {
        val c = controller ?: return toast("Il lettore si sta avviando, riprova tra un attimo.")
        val queue = if (view.contains(index)) view else tracks.indices.toList()
        val items = queue.map { toItem(tracks[it]) }
        c.setMediaItems(items, queue.indexOf(index), 0L)
        c.prepare()
        c.play()
    }

    private fun toItem(t: Track): MediaItem =
        MediaItem.Builder()
            .setUri(t.uri)
            .setMediaId(t.uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(t.title)
                    .setArtist(t.artist.ifEmpty { null })
                    .setAlbumTitle(t.album.ifEmpty { null })
                    .build()
            )
            .build()

    private fun togglePlay() {
        val c = controller ?: return
        when {
            c.mediaItemCount == 0 -> (view.firstOrNull() ?: tracks.indices.firstOrNull())?.let { playFrom(it) }
            c.isPlaying -> c.pause()
            else -> {
                if (c.playbackState == Player.STATE_ENDED) c.seekTo(0, 0L)
                c.play()
            }
        }
    }

    private fun currentTrack(): Track? = currentUri?.let { byUri[it] }?.let { tracks[it] }

    private fun syncFromPlayer() {
        val c = controller ?: return
        currentUri = c.currentMediaItem?.mediaId
        val t = currentTrack()
        val md = c.currentMediaItem?.mediaMetadata
        if (t != null || md != null) {
            nowTitle.text = t?.title ?: md?.title ?: ""
            val sub = listOf(t?.artist ?: md?.artist?.toString() ?: "", t?.album ?: "").filter { it.isNotEmpty() }
            nowArtist.text = if (sub.isEmpty()) getString(R.string.unknown_artist) else sub.joinToString(" · ")
        }
        if (currentUri != artUri) loadArt(currentUri)
        lyricsBtn.isEnabled = t?.lyrics?.isNotEmpty() == true
        lyricsBtn.alpha = if (lyricsBtn.isEnabled) 1f else 0.4f
        updatePlayIcon()
        updateToggles()
        adapter.notifyDataSetChanged()
    }

    private fun loadArt(uri: String?) {
        artUri = uri
        if (uri == null) { showArt(null); return }
        Thread {
            val bmp = Library.artwork(this, uri)
            runOnUiThread { if (artUri == uri && !isDestroyed) showArt(bmp) }
        }.start()
    }

    private fun showArt(bmp: Bitmap?) {
        if (bmp == null) {
            bgArt.animate().alpha(0f).setDuration(400).withEndAction { bgArt.setImageDrawable(null) }.start()
            cover.setImageResource(R.drawable.ic_launcher_fg)
            return
        }
        cover.setImageBitmap(bmp)
        // sfondo sfocato: immagine rimpicciolita e poi ingrandita
        val w = 48
        val h = (bmp.height * w / bmp.width.coerceAtLeast(1)).coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(bmp, w, h, true)
        bgArt.setImageBitmap(small)
        bgArt.alpha = 0f
        bgArt.animate().alpha(1f).setDuration(500).start()
    }

    private fun updatePlayIcon() {
        val playing = controller?.isPlaying == true
        playBtn.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        playBtn.contentDescription = getString(if (playing) R.string.pause else R.string.play)
    }

    private fun updateToggles() {
        val c = controller ?: return
        shuffleBtn.setColorFilter(if (c.shuffleModeEnabled) accent else ink)
        repeatBtn.setColorFilter(if (c.repeatMode == Player.REPEAT_MODE_ALL) accent else ink)
    }

    private fun updateProgress() {
        val c = controller ?: return
        val dur = c.duration
        val pos = c.currentPosition
        if (dur == C.TIME_UNSET || dur <= 0) {
            durText.text = "0:00"
            if (!userSeeking) { seek.progress = 0; curText.text = fmt(pos) }
            return
        }
        durText.text = fmt(dur)
        if (!userSeeking) {
            seek.progress = (pos * 1000 / dur).toInt()
            curText.text = fmt(pos)
        }
    }

    private fun showLyrics() {
        val t = currentTrack() ?: return
        if (t.lyrics.isEmpty()) return
        val pad = (20 * resources.displayMetrics.density).toInt()
        val tv = TextView(this).apply {
            text = t.lyrics
            textSize = 16f
            setTextColor(ink)
            setLineSpacing(0f, 1.25f)
            setTextIsSelectable(true)
            setPadding(pad, pad / 2, pad, pad)
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(t.title)
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Chiudi", null)
            .show()
    }

    private fun fmt(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ---------- Elenco ----------

    inner class TrackAdapter : BaseAdapter() {
        override fun getCount() = view.size
        override fun getItem(position: Int) = tracks[view[position]]
        override fun getItemId(position: Int) = view[position].toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_track, parent, false)
            val t = getItem(position)
            val playing = t.uri == currentUri
            v.findViewById<TextView>(R.id.num).apply {
                text = if (playing) "♪" else (position + 1).toString()
                setTextColor(if (playing) accent else muted)
            }
            v.findViewById<TextView>(R.id.title).apply {
                text = t.title
                setTextColor(if (playing) accent else ink)
            }
            v.findViewById<TextView>(R.id.artist).text =
                t.artist.ifEmpty { t.folder.ifEmpty { getString(R.string.unknown_artist) } }
            val sn = snippet(t, words())
            v.findViewById<TextView>(R.id.snippet).apply {
                visibility = if (sn == null) View.GONE else View.VISIBLE
                text = sn ?: ""
            }
            v.findViewById<TextView>(R.id.tag).visibility = if (t.lyrics.isEmpty()) View.INVISIBLE else View.VISIBLE
            return v
        }
    }
}
