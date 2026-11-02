package it.marco.musica

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.Html
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
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
import java.util.concurrent.Executors

sealed class Row {
    object Back : Row()
    data class Artist(val name: String, val count: Int, val albums: Int, val coverUri: String?) : Row()
    data class Song(val index: Int) : Row()
    /** Una riga di testo: titolo di sezione o messaggio. */
    data class Info(val text: String, val head: Boolean = false, val action: String? = null) : Row()
    data class Yt(val v: YtVideo) : Row()
}

class MainActivity : Activity() {

    companion object {
        private const val REQ_TREE = 1
        private const val REQ_VOICE = 2
        private const val REQ_NOTIF = 3
        private const val PREFS = "musica"
        private const val KEY_TREE = "tree"
        private const val KEY_SORT = "sort2"
        private const val KEY_SKIN = "skin"
        private const val KEY_MODE = "mode"
        private const val KEY_REPEAT = "repeat"
        private val PLAY_CMD = Regex("^(riproduci|suona|metti|ascolta|fammi sentire|play)\\s+(.+)$", RegexOption.IGNORE_CASE)
        private val SEARCH_CMD = Regex("^(cerca|trova)\\s+", RegexOption.IGNORE_CASE)
        private val YT_CMD = Regex("^(?:cerca|trova|riproduci|metti|ascolta)?\\s*(.+?)\\s+su\\s+youtube(\\s+music)?$", RegexOption.IGNORE_CASE)
        private const val PKG_YT_MUSIC = "com.google.android.apps.youtube.music"
        private const val PKG_YT = "com.google.android.youtube"

        // ordinamento della lista
        private val SORTS = listOf(
            "folder" to "Cartella", "title" to "Titolo A-Z", "titleZ" to "Titolo Z-A", "artist" to "Artista A-Z",
            "album" to "Album A-Z", "new" to "Più recenti", "old" to "Meno recenti"
        )
        // ordine di riproduzione
        private val MODES = listOf(
            Triple("list", "Come la lista", "L'ordine che vedi sullo schermo"),
            Triple("shuffle", "Casuale", "Ogni volta un ordine diverso"),
            Triple("title", "Titolo A-Z", "In ordine alfabetico"),
            Triple("artist", "Per artista", "Artista A-Z, poi titolo"),
            Triple("album", "Per album", "Album A-Z, poi titolo"),
            Triple("new", "Più recenti prima", "Prima gli ultimi brani aggiunti")
        )
        private val REPEATS = listOf(
            Triple("off", "Nessuna", "Si ferma a fine coda"),
            Triple("all", "Ripeti tutto", "Ricomincia dall'inizio"),
            Triple("one", "Ripeti un brano", "Lo stesso brano all'infinito")
        )
    }

    private val tracks = mutableListOf<Track>()
    private val byUri = HashMap<String, Int>()
    private var view: List<Int> = emptyList()
    private var rows: List<Row> = emptyList()
    private var currentUri: String? = null
    private var scanning = false
    private var tab = 0
    private var artist: String? = null
    private var sortMode = "folder"
    private var playMode = "list"
    private var lastOrder = "list"
    private var repeatMode = "off"
    private var newSinceLast = 0
    private var skin: Skin = Skins.all[0]
    private var ready = false
    private val collator = java.text.Collator.getInstance(java.util.Locale.ITALIAN).apply { strength = java.text.Collator.PRIMARY }

    private val thumbs = LruCache<String, Bitmap>(160)
    private val noArt = HashSet<String>()
    private val loading = HashSet<String>()
    private val pool = Executors.newFixedThreadPool(2)

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val handler = Handler(Looper.getMainLooper())
    private var userSeeking = false
    private var artUri: String? = null

    private lateinit var adapter: TrackAdapter
    private lateinit var list: ListView
    private lateinit var searchBox: EditText
    private lateinit var countText: TextView
    private lateinit var statusText: TextView
    private lateinit var sortText: TextView
    private lateinit var modeText: TextView
    private lateinit var tabSongs: TextView
    private lateinit var tabArtists: TextView
    private lateinit var np: View
    private lateinit var seek: SeekBar

    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    private fun dp(x: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, x, resources.displayMetrics)
    private fun dpi(x: Int) = dp(x.toFloat()).toInt()
    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)
    private fun <T : View> v(id: Int): T = findViewById(id)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        list = v(R.id.list)
        searchBox = v(R.id.search)
        countText = v(R.id.count)
        statusText = v(R.id.status)
        sortText = v(R.id.sort)
        modeText = v(R.id.modeText)
        tabSongs = v(R.id.tabSongs)
        tabArtists = v(R.id.tabArtists)
        np = v(R.id.np)
        seek = v(R.id.seek)

        val p = prefs()
        sortMode = p.getString(KEY_SORT, "folder").takeIf { s -> SORTS.any { it.first == s } } ?: "folder"
        playMode = p.getString(KEY_MODE, "list").takeIf { s -> MODES.any { it.first == s } } ?: "list"
        repeatMode = p.getString(KEY_REPEAT, "off").takeIf { s -> REPEATS.any { it.first == s } } ?: "off"
        if (playMode != "shuffle") lastOrder = playMode
        skin = Skins.byId(p.getString(KEY_SKIN, null))

        adapter = TrackAdapter()
        list.adapter = adapter
        list.emptyView = v(R.id.empty)
        list.setOnItemClickListener { _, _, pos, _ -> handleRow(pos) }

        tabSongs.setOnClickListener { tab = 0; artist = null; refresh(); list.setSelection(0) }
        tabArtists.setOnClickListener { tab = 1; artist = null; refresh(); list.setSelection(0) }
        v<View>(R.id.themeBtn).setOnClickListener { chooseSkin() }
        v<View>(R.id.folderBtn).setOnClickListener { folderMenu() }
        v<View>(R.id.mic).setOnClickListener { startVoice() }
        sortText.setOnClickListener { chooseSort() }
        v<View>(R.id.modeBtn).setOnClickListener { chooseMode() }
        v<View>(R.id.playAll).setOnClickListener { playAll() }
        v<View>(R.id.ytMusic).setOnClickListener { openYouTube(searchBox.text.toString(), music = true) }
        v<View>(R.id.ytVideo).setOnClickListener { openYouTube(searchBox.text.toString(), music = false) }

        v<View>(R.id.mini).setOnClickListener { if (controller?.currentMediaItem != null) openNp() else toast(getString(R.string.tap_song)) }
        v<View>(R.id.miniPlay).setOnClickListener { togglePlay() }
        v<View>(R.id.miniNext).setOnClickListener { controller?.seekToNext() }
        v<View>(R.id.play).setOnClickListener { togglePlay() }
        v<View>(R.id.next).setOnClickListener { controller?.seekToNext() }
        v<View>(R.id.prev).setOnClickListener { controller?.seekToPrevious() }
        v<View>(R.id.shuffle).setOnClickListener { setMode(if (playMode == "shuffle") lastOrder else "shuffle") }
        v<View>(R.id.repeat).setOnClickListener { setRepeat(when (repeatMode) { "off" -> "all"; "all" -> "one"; else -> "off" }) }
        v<View>(R.id.npClose).setOnClickListener { closeNp() }
        v<View>(R.id.npLyricsBtn).setOnClickListener {
            v<ScrollView>(R.id.npScroll).smoothScrollTo(0, v<View>(R.id.lyricsCard).top)
        }

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                refresh()
                handler.removeCallbacks(ytDebounce)
                if ((s?.trim()?.length ?: 0) >= 3) handler.postDelayed(ytDebounce, 1500)
            }
        })

        searchBox.setOnEditorActionListener { tv, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                handler.removeCallbacks(ytDebounce)
                searchYouTube(tv.text.toString())
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(tv.windowToken, 0)
                true
            } else false
        }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, pr: Int, fromUser: Boolean) {
                if (fromUser) {
                    val d = controller?.duration ?: return
                    if (d > 0) v<TextView>(R.id.cur).text = fmt(d * pr / 1000)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { userSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                userSeeking = false
                val c = controller ?: return
                if (c.duration > 0) c.seekTo(c.duration * seek.progress / 1000)
            }
        })

        // la copertina grande è quadrata
        val npCover = v<View>(R.id.npCover)
        npCover.addOnLayoutChangeListener { cv, l, _, r, _, _, _, _, _ ->
            val want = minOf(r - l, dpi(420))
            if (want > 0 && cv.layoutParams.height != want) cv.post { cv.layoutParams = cv.layoutParams.apply { height = want } }
        }
        npCover.clipToOutline = true
        v<View>(R.id.miniCover).clipToOutline = true

        ready = true
        applySkin(skin, save = false)
        updateModeLabel()

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
            !canRead(tree) -> statusText.text = getString(R.string.lost_folder)
            else -> scan(tree)
        }
    }

    override fun onStart() {
        super.onStart()
        reconnect()
    }

    private fun reconnect() {
        if (controller != null || controllerFuture != null) return
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            if (isDestroyed) return@addListener
            val c = try { future.get() } catch (e: Exception) {
                controllerFuture = null
                val msg = "Il lettore non si avvia: ${e.cause?.message ?: e.message ?: e.javaClass.simpleName}"
                statusText.text = msg
                toast(msg)
                null
            } ?: return@addListener
            controller = c
            c.addListener(playerListener)
            c.repeatMode = repeatConst()
            syncFromPlayer()
            pending?.let { pending = null; it(c) }
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

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            np.visibility == View.VISIBLE -> closeNp()
            artist != null -> { artist = null; refresh() }
            else -> super.onBackPressed()
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = updatePlayIcons()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = syncFromPlayer()
        override fun onRepeatModeChanged(repeatMode: Int) = updateUpNext()
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            val name = currentTrack()?.title ?: "il brano"
            val why = error.cause?.message ?: error.message ?: ""
            val msg = "Non riesco a riprodurre «$name» (${error.errorCodeName}) $why".trim()
            statusText.text = msg
            toast(msg)
        }
    }

    /** Se il lettore non è ancora collegato, il comando parte appena lo è. */
    private var pending: ((MediaController) -> Unit)? = null

    private fun ripple(): android.graphics.drawable.Drawable? {
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        return getDrawable(tv.resourceId)
    }

    private fun handleRow(pos: Int) {
        when (val r = rows.getOrNull(pos)) {
            is Row.Back -> { artist = null; refresh() }
            is Row.Artist -> {
                artist = r.name
                if (searchBox.text.isNotEmpty()) searchBox.setText("") else refresh()
                list.setSelection(0)
            }
            is Row.Song -> {
                val c = controller
                if (tracks[r.index].uri == currentUri && c != null && c.isPlaying) openNp() else playFrom(r.index)
            }
            is Row.Yt -> openVideo(r.v)
            is Row.Info -> when (r.action) {
                "key" -> askKey()
                "retry" -> { ytQuery = ""; ytError = null; searchYouTube(searchBox.text.toString()) }
            }
            null -> {}
        }
    }

    // ---------- YouTube dentro l'app ----------

    private var ytQuery = ""
    private var ytResults: List<YtVideo> = emptyList()
    private var ytLoading = false
    private var ytError: String? = null
    private val ytCache = HashMap<String, List<YtVideo>>()
    private val ytThumbs = LruCache<String, Bitmap>(60)
    private val ytDebounce = Runnable { searchYouTube(searchBox.text.toString()) }

    private fun ytKey(): String? = prefs().getString("ytkey", null)?.trim()?.ifEmpty { null }

    private fun searchYouTube(query: String) {
        val q = query.trim()
        if (q.length < 2) return
        val key = ytKey() ?: run { refresh(); return }
        ytCache[q.lowercase()]?.let { ytQuery = q; ytResults = it; ytError = null; ytLoading = false; refresh(); return }
        if (q == ytQuery && (ytLoading || ytResults.isNotEmpty())) return
        ytQuery = q; ytLoading = true; ytError = null; ytResults = emptyList()
        refresh()
        pool.execute {
            val res = try { Result.success(YouTube.search(key, q)) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                if (ytQuery != q) return@runOnUiThread
                ytLoading = false
                res.onSuccess { ytResults = it; ytCache[q.lowercase()] = it }
                res.onFailure { ytError = it.message ?: "YouTube non risponde." }
                refresh()
            }
        }
    }

    private fun ytRows(w: List<String>): List<Row> {
        if (w.isEmpty() || artist != null) return emptyList()
        val q = searchBox.text.toString().trim()
        val out = ArrayList<Row>()
        out.add(Row.Info("Da YouTube", head = true))
        when {
            ytKey() == null -> out.add(Row.Info("Tocca qui per inserire la chiave YouTube e vedere i video in questa lista.", action = "key"))
            ytLoading -> out.add(Row.Info("Cerco «$q» su YouTube…"))
            ytError != null -> out.add(Row.Info(ytError!!, action = if (ytError!!.contains("chiave")) "key" else "retry"))
            ytQuery.equals(q, true) && ytResults.isEmpty() -> out.add(Row.Info("Nessun video trovato."))
            ytQuery.equals(q, true) -> ytResults.forEach { out.add(Row.Yt(it)) }
            q.length >= 3 -> out.add(Row.Info("Cerco «$q» su YouTube…"))
            else -> out.add(Row.Info("Tocca qui per cercare «$q» su YouTube.", action = "retry"))
        }
        return out
    }

    private fun askKey() {
        sheet("Chiave YouTube") { box, d ->
            box.addView(TextView(this).apply {
                text = "Incolla qui la chiave della YouTube Data API v3 (inizia con «AIza»). Resta salvata solo su questo telefono."
                textSize = 13f; setTextColor(skin.muted); setPadding(dpi(22), 0, dpi(22), dpi(10))
            })
            val field = EditText(this).apply {
                setText(ytKey() ?: ""); hint = "AIza…"; textSize = 15f; setSingleLine()
                setTextColor(skin.text); setHintTextColor(skin.faint)
                background = rounded(skin.raised, 12f); setPadding(dpi(14), dpi(10), dpi(14), dpi(10))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dpi(22), 0, dpi(22), 0)
                }
            }
            box.addView(field)
            box.addView(TextView(this).apply {
                text = "Salva"; textSize = 15f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
                setTextColor(skin.primaryInk); background = rounded(skin.primary, 23f)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(46)).apply { setMargins(dpi(22), dpi(14), dpi(22), 0) }
                setOnClickListener {
                    prefs().edit().putString("ytkey", field.text.toString().trim()).apply()
                    ytCache.clear(); ytQuery = ""; ytError = null
                    d.dismiss()
                    toast(if (field.text.isBlank()) "Chiave YouTube rimossa." else "Chiave YouTube salvata.")
                    searchYouTube(searchBox.text.toString())
                }
            })
        }
    }

    private fun openVideo(v: YtVideo) {
        controller?.pause()
        startActivity(Intent(this, VideoActivity::class.java)
            .putExtra("id", v.id).putExtra("title", v.title).putExtra("channel", v.channel).putExtra("skin", skin.id))
    }

    private fun bindYtThumb(iv: ImageView, url: String) {
        iv.tag = url
        iv.clipToOutline = true
        val cached = ytThumbs.get(url)
        if (cached != null) { iv.setImageBitmap(cached); return }
        iv.setImageDrawable(null)
        if (url.isEmpty() || !loading.add(url)) return
        pool.execute {
            val bmp = YouTube.image(url)
            runOnUiThread {
                loading.remove(url)
                if (bmp != null) { ytThumbs.put(url, bmp); if (iv.tag == url) iv.setImageBitmap(bmp) }
            }
        }
    }

    // ---------- Temi ----------

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp)
    }

    private fun bottomLine(color: Int, heightDp: Int): LayerDrawable {
        val ld = LayerDrawable(arrayOf(ColorDrawable(color)))
        ld.setLayerGravity(0, Gravity.BOTTOM or Gravity.FILL_HORIZONTAL)
        ld.setLayerHeight(0, dpi(heightDp))
        return ld
    }

    private fun applySkin(s: Skin, save: Boolean = true) {
        skin = s
        if (save) prefs().edit().putString(KEY_SKIN, s.id).apply()
        val display = Skins.typeface(this, s)

        window.statusBarColor = s.ground
        window.navigationBarColor = s.surface
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (s.dark) 0 else
            (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)

        v<View>(R.id.root).setBackgroundColor(s.ground)
        val bg = v<ImageView>(R.id.bgPhoto)
        Thread {
            val bmp = Skins.photo(this, s)
            runOnUiThread { if (skin == s) bg.setImageBitmap(bmp) }
        }.start()
        val top = Skins.withAlpha(s.ground, s.veilTop)
        val low = Skins.withAlpha(s.ground, s.veil)
        v<View>(R.id.veil).background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, top, low, low, low))

        v<TextView>(R.id.brandName).apply { setTextColor(s.text); typeface = display }
        v<View>(R.id.themeBtn).background = rounded(s.surface, 18f)
        v<View>(R.id.themeDot).background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(s.accent) }
        v<TextView>(R.id.themeName).apply { text = s.name; setTextColor(s.text) }
        v<ImageButton>(R.id.folderBtn).setColorFilter(s.muted)
        v<View>(R.id.tabRule).setBackgroundColor(Skins.withAlpha(s.text, 0.12f))

        v<View>(R.id.searchField).background = rounded(s.surface, 23f)
        v<ImageView>(R.id.searchIcon).setColorFilter(s.faint)
        searchBox.setTextColor(s.text)
        searchBox.setHintTextColor(s.faint)
        v<View>(R.id.mic).background = rounded(s.primary, 23f)
        v<ImageView>(R.id.micIcon).setColorFilter(s.primaryInk)
        v<TextView>(R.id.micText).setTextColor(s.primaryInk)

        countText.setTextColor(s.muted)
        sortText.setTextColor(s.text)
        sortText.compoundDrawablesRelative[2]?.setTint(s.faint)
        v<View>(R.id.playAll).background = rounded(s.surface, 20f)
        v<ImageView>(R.id.playAllIcon).setColorFilter(s.accent)
        v<TextView>(R.id.playAllText).setTextColor(s.text)
        v<View>(R.id.modeBtn).background = rounded(s.surface, 20f)
        v<ImageView>(R.id.modeIcon).setColorFilter(s.muted)
        v<ImageView>(R.id.modeDown).setColorFilter(s.faint)
        modeText.setTextColor(s.text)
        statusText.setTextColor(s.faint)
        v<TextView>(R.id.ytLabel).setTextColor(s.muted)
        listOf(R.id.ytMusic, R.id.ytVideo).forEach { id ->
            v<TextView>(id).apply { background = rounded(s.surface, 17f); setTextColor(s.text) }
        }
        v<TextView>(R.id.empty).setTextColor(s.muted)

        v<View>(R.id.mini).setBackgroundColor(s.surface)
        v<View>(R.id.miniProgress).setBackgroundColor(s.accent)
        v<TextView>(R.id.miniTitle).setTextColor(s.text)
        v<TextView>(R.id.miniArtist).setTextColor(s.muted)
        v<ImageButton>(R.id.miniPlay).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(s.primary) }
            setColorFilter(s.primaryInk)
        }
        v<ImageButton>(R.id.miniNext).setColorFilter(s.text)

        // In riproduzione
        np.setBackgroundColor(s.ground)
        v<View>(R.id.npShade).setBackgroundColor(Skins.withAlpha(s.ground, 0.55f))
        v<ImageButton>(R.id.npClose).setColorFilter(s.text)
        v<ImageButton>(R.id.npLyricsBtn).setColorFilter(s.text)
        v<TextView>(R.id.npLabel).setTextColor(s.muted)
        v<TextView>(R.id.npTitle).apply { setTextColor(s.text); typeface = display }
        v<TextView>(R.id.npArtist).setTextColor(s.muted)
        v<TextView>(R.id.npNext).setTextColor(s.faint)
        seek.progressTintList = ColorStateList.valueOf(s.accent)
        seek.progressBackgroundTintList = ColorStateList.valueOf(Skins.withAlpha(s.text, 0.35f))
        seek.thumbTintList = ColorStateList.valueOf(s.primary)
        v<TextView>(R.id.cur).setTextColor(s.muted)
        v<TextView>(R.id.dur).setTextColor(s.muted)
        v<ImageButton>(R.id.play).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(s.primary) }
            setColorFilter(s.primaryInk)
        }
        v<ImageButton>(R.id.prev).setColorFilter(s.text)
        v<ImageButton>(R.id.next).setColorFilter(s.text)
        v<TextView>(R.id.repeatOne).setTextColor(s.text)
        v<View>(R.id.lyricsCard).background = rounded(Skins.withAlpha(s.surface, 0.92f), 20f)
        v<TextView>(R.id.lyricsHead).apply { setTextColor(s.text); typeface = display }
        v<TextView>(R.id.lyricsText).setTextColor(s.text)

        updateToggles()
        refresh()
    }

    private fun sheet(title: String, build: (LinearLayout, Dialog) -> Unit) {
        val d = Dialog(this, R.style.SheetDialog)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(skin.surface)
                cornerRadii = floatArrayOf(dp(20f), dp(20f), dp(20f), dp(20f), 0f, 0f, 0f, 0f)
            }
            setPadding(0, dpi(14), 0, dpi(18))
        }
        box.addView(TextView(this).apply {
            text = title; textSize = 17f; setTextColor(skin.text); typeface = Skins.typeface(this@MainActivity, skin)
            setPadding(dpi(22), dpi(6), dpi(22), dpi(8))
        })
        build(box, d)
        val scroll = ScrollView(this).apply { addView(box) }
        d.setContentView(scroll)
        d.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.5f)
        }
        d.show()
    }

    private fun groupLabel(box: LinearLayout, label: String) {
        box.addView(TextView(this).apply {
            text = label; textSize = 13f; setTextColor(skin.muted)
            setPadding(dpi(22), dpi(12), dpi(22), dpi(2))
        })
    }

    /** Una riga con titolo, sottotitolo e pallino di scelta. */
    private fun option(box: LinearLayout, title: String, sub: String?, checked: Boolean, thumb: Bitmap? = null, onPick: () -> Unit): RadioButton {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dpi(56)
            setPadding(dpi(22), dpi(6), dpi(14), dpi(6))
            isClickable = true
        }
        if (thumb != null) {
            row.addView(ImageView(this).apply {
                setImageBitmap(thumb); scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(skin.raised, 12f); clipToOutline = true
                layoutParams = LinearLayout.LayoutParams(dpi(52), dpi(52)).apply { marginEnd = dpi(14) }
            })
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply { text = title; textSize = 15f; setTextColor(skin.text) })
        if (sub != null) texts.addView(TextView(this).apply { text = sub; textSize = 12f; setTextColor(skin.faint) })
        row.addView(texts)
        val rb = RadioButton(this).apply {
            isChecked = checked
            buttonTintList = ColorStateList.valueOf(skin.accent)
            isClickable = false
            isFocusable = false
        }
        row.addView(rb)
        row.setOnClickListener { onPick() }
        box.addView(row)
        return rb
    }

    private fun chooseSkin() {
        sheet("Scegli il tema") { box, d ->
            Skins.all.forEach { s ->
                option(box, s.name, s.desc, s.id == skin.id, Skins.photo(this, s, mini = true)) {
                    d.dismiss(); applySkin(s)
                }
            }
            box.addView(TextView(this).apply {
                text = Skins.CREDITS; textSize = 12f; setTextColor(skin.faint)
                setPadding(dpi(22), dpi(10), dpi(22), 0)
            })
        }
    }

    private fun chooseSort() {
        sheet("Ordina la lista per") { box, d ->
            SORTS.forEach { (id, name) ->
                option(box, name, null, id == sortMode) {
                    sortMode = id
                    prefs().edit().putString(KEY_SORT, id).apply()
                    d.dismiss(); refresh(); list.setSelection(0)
                }
            }
        }
    }

    private fun chooseMode() {
        sheet("Riproduzione") { box, d ->
            groupLabel(box, "Ordine dei brani")
            val modeButtons = ArrayList<Pair<String, RadioButton>>()
            MODES.forEach { (id, name, sub) ->
                val rb = option(box, name, sub, id == playMode) {
                    modeButtons.forEach { (mid, b) -> b.isChecked = mid == id }
                    setMode(id)
                }
                modeButtons.add(id to rb)
            }
            groupLabel(box, "Ripetizione")
            val repButtons = ArrayList<Pair<String, RadioButton>>()
            REPEATS.forEach { (id, name, sub) ->
                val rb = option(box, name, sub, id == repeatMode) {
                    repButtons.forEach { (rid, b) -> b.isChecked = rid == id }
                    setRepeat(id)
                }
                repButtons.add(id to rb)
            }
            box.addView(TextView(this).apply {
                text = "Fatto"; textSize = 15f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
                setTextColor(skin.primaryInk); background = rounded(skin.primary, 23f)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(46)).apply {
                    setMargins(dpi(22), dpi(14), dpi(22), 0)
                }
                setOnClickListener { d.dismiss() }
            })
        }
    }

    private fun folderMenu() {
        sheet("Cartella della musica") { box, d ->
            option(box, "Aggiorna la libreria", "Cerca brani nuovi o cancellati", false) { d.dismiss(); rescan() }
            option(box, "Scegli un'altra cartella", "La nuova cartella resterà memorizzata", false) { d.dismiss(); pickFolder() }
            option(box, "Chiave YouTube", if (ytKey() == null) "Non inserita: serve per vedere i video nella ricerca" else "Inserita · tocca per cambiarla", false) { d.dismiss(); askKey() }
        }
    }

    // ---------- Cartella e libreria ----------

    private fun savedTree(): Uri? = prefs().getString(KEY_TREE, null)?.let { Uri.parse(it) }

    private fun canRead(tree: Uri): Boolean =
        contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }

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
            if (found.isEmpty() && old.isNotEmpty()) {
                scanning = false
                runOnUiThread { statusText.text = getString(R.string.lost_folder) }
                return@Thread
            }
            val added = if (old.isEmpty()) 0 else found.count { it.uri !in old }
            val merged = found.map { t ->
                old[t.uri]?.takeIf { it.scanned && it.duration > 0 }?.also { it.modified = t.modified } ?: t
            }
            runOnUiThread { newSinceLast = added; setTracks(merged) }
            readAllMeta(merged)
        }.start()
    }

    private fun readAllMeta(items: List<Track>) {
        val todo = items.filter { !it.scanned || it.duration == 0L }
        todo.forEachIndexed { i, t ->
            Library.readMeta(this, t)
            if (i % 20 == 19) runOnUiThread {
                statusText.text = getString(R.string.reading, i + 1, todo.size)
                refresh()
            }
        }
        Library.save(this, items)
        scanning = false
        runOnUiThread { refresh(); updateStatus() }
    }

    private fun updateStatus() {
        statusText.text = if (newSinceLast > 0) {
            val n = if (newSinceLast == 1) "1 brano nuovo" else "$newSinceLast brani nuovi"
            Html.fromHtml("<b>$n</b> trovati nella cartella", Html.FROM_HTML_MODE_LEGACY)
        } else getString(R.string.hint_ready, tracks.size, tracks.count { it.lyrics.isNotEmpty() })
    }

    private fun setTracks(items: List<Track>) {
        tracks.clear()
        tracks.addAll(items)
        byUri.clear()
        tracks.forEachIndexed { i, t -> byUri[t.uri] = i }
        refresh()
    }

    // ---------- Lista e ricerca ----------

    private fun words(): List<String> =
        Library.norm(searchBox.text.toString()).split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun artistOf(t: Track) = t.artist.ifEmpty { getString(R.string.unknown_artist) }

    private fun styleTabs() {
        listOf(tabSongs to (tab == 0), tabArtists to (tab == 1)).forEach { (tv, on) ->
            tv.setTextColor(if (on) skin.text else skin.muted)
            tv.background = if (on) bottomLine(skin.text, 2) else null
        }
    }

    private fun refresh() {
        if (!ready) return
        val w = words()
        val match = { i: Int ->
            if (w.isEmpty()) true else {
                val t = tracks[i]
                val hay = Library.norm("${t.title} ${t.artist} ${t.album} ${t.name} ${t.folder}")
                val lyr = Library.norm(t.lyrics)
                w.all { hay.contains(it) || lyr.contains(it) }
            }
        }
        v<View>(R.id.ytRow).visibility = if (searchBox.text.isNotBlank()) View.VISIBLE else View.GONE
        styleTabs()
        sortText.text = SORTS.first { it.first == sortMode }.second
        sortText.visibility = if (tab == 1 && artist == null) View.INVISIBLE else View.VISIBLE

        if (tab == 1 && artist == null) {
            val unknown = getString(R.string.unknown_artist)
            val groups = tracks.indices.groupBy { artistOf(tracks[it]) }
            val names = groups.keys.filter { n ->
                w.isEmpty() || w.all { Library.norm(n).contains(it) } || groups.getValue(n).any(match)
            }.sortedWith(Comparator { a, b ->
                val ua = a == unknown
                val ub = b == unknown
                if (ua != ub) (if (ua) 1 else -1) else collator.compare(a, b)
            })
            view = emptyList()
            rows = names.map { n ->
                val ix = groups.getValue(n)
                val coverIdx = ix.firstOrNull { tracks[it].album.isNotEmpty() } ?: ix.first()
                Row.Artist(n, ix.size, ix.map { tracks[it].album }.filter { it.isNotEmpty() }.toSet().size, tracks[coverIdx].uri)
            }
            countText.text = "${names.size} artisti"
        } else {
            var ix = tracks.indices.filter(match)
            artist?.let { a -> ix = ix.filter { artistOf(tracks[it]) == a } }
            view = sortBy(ix, sortMode)
            val songs: List<Row> = view.map { Row.Song(it) }
            val local = if (w.isNotEmpty() && songs.isEmpty()) listOf<Row>(Row.Info("Nessuno dei tuoi brani corrisponde alla ricerca.")) else songs
            rows = (if (artist != null) listOf<Row>(Row.Back) else emptyList()) + local + ytRows(w)
            countText.text = when {
                artist != null -> "${view.size} brani"
                w.isEmpty() -> "${tracks.size} brani"
                else -> "${view.size} di ${tracks.size} brani"
            }
        }
        adapter.notifyDataSetChanged()
    }

    private fun sortBy(idx: List<Int>, key: String): List<Int> {
        fun byText(k: (Track) -> String) = Comparator<Int> { a, b -> collator.compare(k(tracks[a]), k(tracks[b])) }
        val c: Comparator<Int> = when (key) {
            "title" -> byText { it.title }
            "titleZ" -> byText { it.title }.reversed()
            "artist" -> byText { it.artist.ifEmpty { "￿" } }.then(byText { it.title })
            "album" -> byText { it.album.ifEmpty { "￿" } }.then(byText { it.title })
            "new" -> compareByDescending<Int> { tracks[it].modified }
            "old" -> compareBy<Int> { tracks[it].modified }
            else -> return idx
        }
        return idx.sortedWith(c)
    }

    private fun snippet(t: Track, w: List<String>): CharSequence? {
        if (w.isEmpty() || t.lyrics.isEmpty()) return null
        val hay = Library.norm("${t.title} ${t.artist} ${t.album} ${t.name}")
        if (w.all { hay.contains(it) }) return null
        val flat = t.lyrics.replace(Regex("\\s+"), " ")
        val n = Library.norm(flat)
        val word = w.firstOrNull { n.contains(it) } ?: return null
        if (n.length != flat.length) return null
        val i = n.indexOf(word)
        val s = maxOf(0, i - 30)
        val e = minOf(flat.length, i + word.length + 40)
        val sp = SpannableString("…" + flat.substring(s, e) + "…")
        val hs = 1 + (i - s)
        sp.setSpan(ForegroundColorSpan(skin.text), hs, hs + word.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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
        YT_CMD.find(q)?.let { m ->
            val query = SEARCH_CMD.replace(m.groupValues[1], "")
            searchBox.setText(query)
            openYouTube(query, music = true)
            return
        }
        var autoplay = false
        PLAY_CMD.find(q)?.let { q = it.groupValues[2]; autoplay = true }
        if (autoplay) { tab = 0; artist = null }
        q = SEARCH_CMD.replace(q, "")
        searchBox.setText(q)
        searchBox.setSelection(q.length)
        if (autoplay && view.isNotEmpty()) playFrom(view[0])
        handler.removeCallbacks(ytDebounce)
        searchYouTube(q)
        statusText.text = if (view.isEmpty()) "«$spoken»: non c'è tra i tuoi brani. Puoi cercarlo su YouTube qui sotto." else "«$spoken»: ${view.size} risultati"
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_TREE -> {
                val uri = data.data ?: return
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: SecurityException) { }
                prefs().edit().putString(KEY_TREE, uri.toString()).apply()
                setTracks(emptyList())
                scan(uri)
            }
            REQ_VOICE -> {
                val text = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
                handleSpoken(text)
            }
        }
    }

    // ---------- YouTube ----------

    /** Apre la ricerca nell'app di YouTube Music o di YouTube; se manca, nel browser. */
    private fun openYouTube(query: String, music: Boolean) {
        val q = query.trim()
        if (q.isEmpty()) { toast("Scrivi o di' cosa cercare."); return }
        controller?.pause()
        val enc = Uri.encode(q)
        val attempts = if (music) listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=$enc")).setPackage(PKG_YT_MUSIC),
            Intent(Intent.ACTION_SEARCH).setPackage(PKG_YT).putExtra("query", q),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=$enc"))
        ) else listOf(
            Intent(Intent.ACTION_SEARCH).setPackage(PKG_YT).putExtra("query", q),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$enc")).setPackage(PKG_YT),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$enc"))
        )
        for (i in attempts) {
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: ActivityNotFoundException) {
            } catch (e: SecurityException) {
            }
        }
        toast("Non trovo né YouTube né un browser su questo telefono.")
    }

    // ---------- Riproduzione ----------

    private fun repeatConst() = when (repeatMode) {
        "all" -> Player.REPEAT_MODE_ALL
        "one" -> Player.REPEAT_MODE_ONE
        else -> Player.REPEAT_MODE_OFF
    }

    /** I brani da suonare, nell'ordine della modalità scelta. */
    private fun buildQueue(base: List<Int>, first: Int?): List<Int> {
        var q = when (playMode) {
            "list" -> base
            "shuffle" -> base.shuffled()
            else -> sortBy(base, playMode)
        }
        if (playMode == "shuffle" && first != null) q = listOf(first) + q.filter { it != first }
        return q
    }

    private fun baseList(): List<Int> = view.ifEmpty { sortBy(tracks.indices.toList(), sortMode) }

    private var queueGen = 0

    /**
     * Avvia subito il brano scelto da solo, poi aggiunge il resto della coda a piccoli gruppi.
     * Passare tutta la coda in un colpo solo rallentava l'avvio.
     */
    private fun startQueue(queueIn: List<Int>, startIndexIn: Int, positionMs: Long = 0L, play: Boolean = true) {
        val c = controller
        if (queueIn.isEmpty()) return
        if (c == null) {
            statusText.text = "Avvio il lettore…"
            pending = { startQueue(queueIn, startIndexIn, positionMs, play) }
            reconnect()
            return
        }
        val gen = ++queueGen
        val start = startIndexIn.coerceIn(0, queueIn.size - 1)
        c.shuffleModeEnabled = false
        c.repeatMode = repeatConst()
        c.setMediaItem(toItem(tracks[queueIn[start]]), positionMs)
        c.prepare()
        if (play) c.play()

        val after = queueIn.subList(start + 1, queueIn.size).chunked(40)
        val before = queueIn.subList(0, start).chunked(40)
        var step = 0
        var insertAt = 0
        val addNext = object : Runnable {
            override fun run() {
                val cc = controller ?: return
                if (gen != queueGen) return
                when {
                    step < after.size -> cc.addMediaItems(after[step].map { toItem(tracks[it]) })
                    step < after.size + before.size -> {
                        val chunk = before[step - after.size]
                        cc.addMediaItems(insertAt, chunk.map { toItem(tracks[it]) })
                        insertAt += chunk.size
                    }
                    else -> { updateUpNext(); return }
                }
                step++
                handler.postDelayed(this, 120)
            }
        }
        handler.postDelayed(addNext, 600)
    }

    private fun playFrom(index: Int) {
        val base = baseList().let { if (index in it) it else tracks.indices.toList() }
        val q = buildQueue(base, index)
        startQueue(q, q.indexOf(index))
    }

    private fun playAll() {
        startQueue(buildQueue(baseList(), null), 0)
    }

    /** I brani attualmente in coda nel lettore. */
    private fun currentQueue(): List<Int> {
        val c = controller ?: return emptyList()
        return (0 until c.mediaItemCount).mapNotNull { byUri[c.getMediaItemAt(it).mediaId] }
    }

    private fun setMode(m: String) {
        if (m != "shuffle") lastOrder = m
        playMode = m
        prefs().edit().putString(KEY_MODE, m).apply()
        val c = controller
        val curIdx = currentUri?.let { byUri[it] }
        if (c != null && curIdx != null && c.mediaItemCount > 0) {
            val q = buildQueue(currentQueue(), curIdx)
            startQueue(q, q.indexOf(curIdx), c.currentPosition, c.isPlaying)
        }
        updateModeLabel()
    }

    private fun setRepeat(r: String) {
        repeatMode = r
        prefs().edit().putString(KEY_REPEAT, r).apply()
        controller?.repeatMode = repeatConst()
        updateModeLabel()
    }

    private fun updateModeLabel() {
        val m = MODES.first { it.first == playMode }.second
        val r = REPEATS.first { it.first == repeatMode }.second
        modeText.text = if (repeatMode == "off") m else "$m · ${r.removePrefix("Ripeti ")}"
        updateToggles()
        updateUpNext()
    }

    private fun updateToggles() {
        if (!ready) return
        v<ImageButton>(R.id.shuffle).setColorFilter(if (playMode == "shuffle") skin.text else Skins.withAlpha(skin.text, 0.4f))
        v<ImageButton>(R.id.repeat).setColorFilter(if (repeatMode != "off") skin.text else Skins.withAlpha(skin.text, 0.4f))
        v<View>(R.id.repeatOne).visibility = if (repeatMode == "one") View.VISIBLE else View.GONE
    }

    private fun updateUpNext() {
        val c = controller
        val tv = v<TextView>(R.id.npNext)
        if (c == null || c.currentMediaItem == null) { tv.text = ""; return }
        if (repeatMode == "one") { tv.text = "Poi: di nuovo questo brano"; return }
        val n = c.nextMediaItemIndex
        tv.text = if (n == C.INDEX_UNSET) "Ultimo brano della coda" else {
            val md = c.getMediaItemAt(n).mediaMetadata
            "Poi: ${md.title ?: ""}" + (md.artist?.let { " · $it" } ?: "")
        }
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
            c.mediaItemCount == 0 -> playAll()
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
            val title = t?.title ?: md?.title?.toString() ?: ""
            val artistName = t?.let { artistOf(it) } ?: md?.artist?.toString() ?: ""
            val who = listOf(artistName, t?.album ?: "").filter { it.isNotEmpty() }.joinToString(" · ")
            v<TextView>(R.id.miniTitle).text = title
            v<TextView>(R.id.miniArtist).text = artistName
            v<TextView>(R.id.npTitle).text = title
            v<TextView>(R.id.npArtist).text = who
            val hasLyrics = t?.lyrics?.isNotEmpty() == true
            v<TextView>(R.id.lyricsText).apply {
                text = if (hasLyrics) t!!.lyrics else getString(R.string.lyrics_none)
                setTextColor(if (hasLyrics) skin.text else skin.muted)
            }
        }
        if (currentUri != artUri) loadArt(currentUri)
        updatePlayIcons()
        updateUpNext()
    }

    private fun loadArt(uri: String?) {
        artUri = uri
        if (uri == null) { showArt(null); return }
        pool.execute {
            val bmp = Library.artwork(this, uri)
            runOnUiThread { if (artUri == uri && !isDestroyed) showArt(bmp) }
        }
    }

    private fun showArt(bmp: Bitmap?) {
        val cover = v<ImageView>(R.id.npCover)
        val mini = v<ImageView>(R.id.miniCover)
        val bg = v<ImageView>(R.id.npBg)
        if (bmp == null) {
            cover.setImageResource(R.mipmap.ic_launcher)
            mini.setImageResource(R.mipmap.ic_launcher)
            bg.setImageDrawable(null)
            return
        }
        cover.setImageBitmap(bmp)
        mini.setImageBitmap(bmp)
        // sfondo sfumato dietro la copertina (la copertina resta nitida)
        if (Build.VERSION.SDK_INT >= 31) {
            val w = 240
            val h = (bmp.height * w / bmp.width.coerceAtLeast(1)).coerceAtLeast(1)
            bg.setImageBitmap(Bitmap.createScaledBitmap(bmp, w, h, true))
            bg.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(dp(30f), dp(30f), android.graphics.Shader.TileMode.CLAMP))
        } else {
            val w = 40
            val h = (bmp.height * w / bmp.width.coerceAtLeast(1)).coerceAtLeast(1)
            bg.setImageBitmap(Bitmap.createScaledBitmap(bmp, w, h, true))
        }
    }

    private fun openNp() {
        if (np.visibility == View.VISIBLE) return
        np.visibility = View.VISIBLE
        np.translationY = if (np.height > 0) np.height.toFloat() else dp(800f)
        np.animate().translationY(0f).setDuration(260).start()
    }

    private fun closeNp() {
        np.animate().translationY(np.height.toFloat()).setDuration(220).withEndAction { np.visibility = View.GONE }.start()
    }

    private fun updatePlayIcons() {
        val playing = controller?.isPlaying == true
        val icon = if (playing) R.drawable.ic_pause else R.drawable.ic_play
        val desc = getString(if (playing) R.string.pause else R.string.play)
        listOf(R.id.play, R.id.miniPlay).forEach { id ->
            v<ImageButton>(id).apply { setImageResource(icon); contentDescription = desc }
        }
        adapter.notifyDataSetChanged()
    }

    private fun updateProgress() {
        val c = controller ?: return
        val dur = c.duration
        val pos = c.currentPosition
        val mini = v<View>(R.id.miniProgress)
        val parentW = (mini.parent as View).width
        if (dur == C.TIME_UNSET || dur <= 0) {
            v<TextView>(R.id.dur).text = "0:00"
            if (!userSeeking) { seek.progress = 0; v<TextView>(R.id.cur).text = fmt(pos) }
            if (mini.layoutParams.width != 0) mini.layoutParams = mini.layoutParams.apply { width = 0 }
            return
        }
        v<TextView>(R.id.dur).text = fmt(dur)
        val frac = (pos.toFloat() / dur).coerceIn(0f, 1f)
        if (!userSeeking) {
            seek.progress = (frac * 1000).toInt()
            v<TextView>(R.id.cur).text = fmt(pos)
        }
        val wpx = (parentW * frac).toInt()
        if (mini.layoutParams.width != wpx) mini.layoutParams = mini.layoutParams.apply { width = wpx }
    }

    private fun fmt(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ---------- Copertine nella lista ----------

    private fun bindThumb(iv: ImageView, uri: String?) {
        iv.tag = uri
        iv.clipToOutline = true
        val cached = uri?.let { thumbs.get(it) }
        if (cached != null) { iv.setImageBitmap(cached); return }
        iv.setImageResource(R.mipmap.ic_launcher)
        if (uri == null || uri in noArt || !loading.add(uri)) return
        pool.execute {
            val bmp = Library.artwork(this, uri, 200)
            runOnUiThread {
                loading.remove(uri)
                if (bmp == null) noArt.add(uri) else {
                    thumbs.put(uri, bmp)
                    if (iv.tag == uri) iv.setImageBitmap(bmp)
                }
            }
        }
    }

    // ---------- Elenco ----------

    inner class TrackAdapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 5
        override fun getItemViewType(position: Int) = when (rows[position]) {
            is Row.Back -> 0
            is Row.Artist -> 1
            is Row.Song -> 2
            is Row.Info -> 3
            is Row.Yt -> 4
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            when (val r = rows[position]) {
                is Row.Back -> {
                    val row = convertView ?: layoutInflater.inflate(R.layout.item_back, parent, false)
                    row.setOnClickListener { handleRow(position) }
                    row.findViewById<TextView>(R.id.backLabel).setTextColor(skin.muted)
                    row.findViewById<TextView>(R.id.who).apply {
                        text = artist ?: ""; setTextColor(skin.text); typeface = Skins.typeface(this@MainActivity, skin)
                    }
                    row
                }
                is Row.Artist -> {
                    val row = convertView ?: layoutInflater.inflate(R.layout.item_artist, parent, false)
                    row.setOnClickListener { handleRow(position) }
                    row.background = bottomLine(Skins.withAlpha(skin.text, 0.08f), 1)
                    row.findViewById<TextView>(R.id.name).apply { text = r.name; setTextColor(skin.text) }
                    row.findViewById<TextView>(R.id.info).apply {
                        text = (if (r.count == 1) "1 brano" else "${r.count} brani") + (if (r.albums > 0) " · ${r.albums} album" else "")
                        setTextColor(skin.muted)
                    }
                    row.findViewById<TextView>(R.id.chev).setTextColor(skin.faint)
                    bindThumb(row.findViewById(R.id.thumb), r.coverUri)
                    row
                }
                is Row.Info -> {
                    val tv = (convertView as? TextView) ?: TextView(this@MainActivity)
                    tv.text = r.text
                    tv.setOnClickListener { handleRow(position) }
                    tv.isClickable = r.action != null || r.head
                    if (r.head) {
                        tv.textSize = 17f; tv.setTextColor(skin.text); tv.typeface = Skins.typeface(this@MainActivity, skin)
                        tv.setPadding(0, dpi(22), 0, dpi(8))
                        tv.background = bottomLine(Skins.withAlpha(skin.text, 0.08f), 1)
                    } else {
                        tv.textSize = 14f; tv.typeface = Typeface.DEFAULT
                        tv.setTextColor(if (r.action != null) skin.accent else skin.muted)
                        tv.setPadding(0, dpi(14), 0, dpi(14))
                        tv.background = null
                    }
                    tv
                }
                is Row.Yt -> {
                    val row = convertView ?: layoutInflater.inflate(R.layout.item_yt, parent, false)
                    row.setOnClickListener { handleRow(position) }
                    if (row.foreground == null) row.foreground = ripple()
                    row.background = bottomLine(Skins.withAlpha(skin.text, 0.08f), 1)
                    row.findViewById<TextView>(R.id.ytTitle).apply { text = r.v.title; setTextColor(skin.text) }
                    row.findViewById<TextView>(R.id.ytSub).apply {
                        text = listOf(r.v.channel, r.v.views).filter { it.isNotEmpty() }.joinToString(" · ")
                        setTextColor(skin.muted)
                    }
                    row.findViewById<TextView>(R.id.ytDur).apply {
                        text = r.v.duration; visibility = if (r.v.duration.isEmpty()) View.GONE else View.VISIBLE
                    }
                    bindYtThumb(row.findViewById(R.id.ytThumb), r.v.thumb)
                    row
                }
                is Row.Song -> songView(r, convertView, parent).also { row ->
                    row.setOnClickListener { handleRow(position) }
                    if (row.foreground == null) row.foreground = ripple()
                }
            }

        private fun songView(r: Row.Song, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: layoutInflater.inflate(R.layout.item_track, parent, false)
            row.background = bottomLine(Skins.withAlpha(skin.text, 0.08f), 1)
            val t = tracks[r.index]
            val on = t.uri == currentUri
            row.findViewById<TextView>(R.id.title).apply { text = t.title; setTextColor(if (on) skin.accent else skin.text) }
            val byDate = sortMode == "new" || sortMode == "old"
            val sub = if (artist != null) t.album.ifEmpty { t.folder } else
                artistOf(t) + (if (t.album.isNotEmpty()) " · ${t.album}" else "")
            row.findViewById<TextView>(R.id.artist).apply {
                text = if (byDate && t.modified > 0) sub + " · " + android.text.format.DateFormat.format("d MMM yyyy", t.modified) else sub
                setTextColor(skin.muted)
            }
            val sn = snippet(t, words())
            row.findViewById<TextView>(R.id.snippet).apply {
                visibility = if (sn == null) View.GONE else View.VISIBLE
                text = sn ?: ""
                setTextColor(skin.faint)
            }
            row.findViewById<TextView>(R.id.duration).apply {
                text = if (t.duration > 0) fmt(t.duration) else ""
                setTextColor(skin.faint)
            }
            row.findViewById<TextView>(R.id.tag).apply {
                visibility = if (t.lyrics.isEmpty()) View.GONE else View.VISIBLE
                background = rounded(skin.raised, 10f)
                setTextColor(skin.text)
            }
            bindThumb(row.findViewById(R.id.cover), t.uri)
            row.findViewById<ImageView>(R.id.nowMark).apply {
                visibility = if (on) View.VISIBLE else View.GONE
                background = rounded(Skins.withAlpha(Color.BLACK, 0.5f), 8f)
                setImageResource(if (controller?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play)
                setColorFilter(skin.accent)
            }
            return row
        }
    }
}
