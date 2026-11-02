package it.marco.musica

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Guarda un video di YouTube con il lettore ufficiale (IFrame Player). */
class VideoActivity : Activity() {

    private var web: WebView? = null
    private var fullView: View? = null
    private var fullCallback: WebChromeClient.CustomViewCallback? = null
    private lateinit var root: FrameLayout

    private fun dp(x: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, x.toFloat(), resources.displayMetrics).toInt()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("id") ?: return finish()
        val title = intent.getStringExtra("title") ?: ""
        val channel = intent.getStringExtra("channel") ?: ""
        val s = Skins.byId(intent.getStringExtra("skin"))
        val display = Skins.typeface(this, s)
        window.statusBarColor = s.ground
        window.navigationBarColor = s.ground

        root = FrameLayout(this).apply { setBackgroundColor(s.ground); fitsSystemWindows = true }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(24))
        }

        // barra in alto
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56))
        }
        top.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_down); setColorFilter(s.text)
            background = null; contentDescription = getString(R.string.close)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            setOnClickListener { finish() }
        })
        top.addView(TextView(this).apply {
            text = "Da YouTube"; textSize = 13f; setTextColor(s.muted); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        top.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)) })
        col.addView(top)

        // lettore 16:9
        val w = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            setBackgroundColor(0xFF000000.toInt())
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                    // i link verso YouTube si aprono nell'app di YouTube
                    openExternal(req.url)
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    fullView = view; fullCallback = callback
                    root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                }
                override fun onHideCustomView() {
                    fullView?.let { root.removeView(it) }
                    fullView = null
                    fullCallback?.onCustomViewHidden()
                }
            }
        }
        web = w
        val width = resources.displayMetrics.widthPixels - dp(32)
        col.addView(FrameLayout(this).apply {
            background = GradientDrawable().apply { setColor(0xFF000000.toInt()); cornerRadius = dp(12).toFloat() }
            clipToOutline = true
            addView(w, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, width * 9 / 16)
        })

        col.addView(TextView(this).apply {
            text = title; textSize = 20f; setTextColor(s.text); typeface = display
            setPadding(0, dp(16), 0, dp(2))
        })
        col.addView(TextView(this).apply { text = channel; textSize = 14f; setTextColor(s.muted) })
        col.addView(TextView(this).apply {
            text = "Il video va tenuto aperto: a schermo spento si ferma, è una regola di YouTube. Per ascoltare a schermo spento usa YouTube Music."
            textSize = 13f; setTextColor(s.faint); setLineSpacing(0f, 1.3f)
            setPadding(0, dp(16), 0, 0)
        })
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(16), 0, 0)
        }
        fun pill(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label; textSize = 14f; setTextColor(s.text); gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply { setColor(s.surface); cornerRadius = dp(20).toFloat() }
            setPadding(dp(16), 0, dp(16), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginEnd = dp(8) }
            setOnClickListener { onClick() }
        }
        buttons.addView(pill("Apri in YouTube Music") {
            openExternal(Uri.parse("https://music.youtube.com/watch?v=$id"), "com.google.android.apps.youtube.music")
        })
        buttons.addView(pill("Apri in YouTube") {
            openExternal(Uri.parse("https://www.youtube.com/watch?v=$id"), "com.google.android.youtube")
        })
        col.addView(buttons)

        root.addView(ScrollView(this).apply { addView(col) })
        setContentView(root)

        val html = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>html,body{margin:0;height:100%;background:#000}#p{position:absolute;inset:0;width:100%;height:100%}</style></head>
            <body><div id="p"></div>
            <script src="https://www.youtube.com/iframe_api"></script>
            <script>
              function onYouTubeIframeAPIReady(){
                new YT.Player('p',{videoId:'$id',width:'100%',height:'100%',
                  playerVars:{autoplay:1,playsinline:1,rel:0,modestbranding:1,origin:'https://musicvibes.app'},
                  events:{onReady:function(e){e.target.playVideo();}}});
              }
            </script></body></html>
        """.trimIndent()
        w.loadDataWithBaseURL("https://musicvibes.app/", html, "text/html", "utf-8", null)
    }

    private fun openExternal(uri: Uri, pkg: String? = null) {
        val tries = listOfNotNull(
            pkg?.let { Intent(Intent.ACTION_VIEW, uri).setPackage(it) },
            Intent(Intent.ACTION_VIEW, uri)
        )
        for (i in tries) {
            try {
                startActivity(i)
                web?.onPause()
                return
            } catch (e: ActivityNotFoundException) { }
        }
    }

    override fun onPause() {
        super.onPause()
        web?.onPause()
    }

    override fun onResume() {
        super.onResume()
        web?.onResume()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (fullView != null) web?.webChromeClient?.onHideCustomView() else super.onBackPressed()
    }

    override fun onDestroy() {
        web?.apply { loadUrl("about:blank"); destroy() }
        web = null
        super.onDestroy()
    }
}
