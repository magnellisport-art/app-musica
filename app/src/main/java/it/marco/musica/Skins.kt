package it.marco.musica

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface

/** Un tema: colori, foto di sfondo e carattere dei titoli. */
data class Skin(
    val id: String,
    val name: String,
    val desc: String,
    val dark: Boolean,
    val ground: Int,
    val surface: Int,
    val raised: Int,
    val rule: Int,
    val text: Int,
    val muted: Int,
    val faint: Int,
    val accent: Int,
    val primary: Int,
    val primaryInk: Int,
    val veilTop: Float,
    val veil: Float,
    val font: String,
    val weight: Int
)

object Skins {
    private fun c(hex: String) = Color.parseColor(hex)

    val all = listOf(
        Skin("universo", "Universo", "La Via Lattea sopra un cielo pieno di stelle", true,
            c("#05060F"), c("#10132A"), c("#1C2142"), c("#141833"),
            c("#EEF0FF"), c("#A9AED0"), c("#8D93B8"), c("#8AB4FF"), c("#EEF0FF"), c("#05060F"),
            0.15f, 0.78f, "unbounded.ttf", 600),
        Skin("sport", "Sport", "Campi da tennis visti dall'alto", true,
            c("#0A1D14"), c("#11291D"), c("#1A3A29"), c("#13291B"),
            c("#FFFFFF"), c("#B7CDBF"), c("#94AD9D"), c("#E9F04A"), c("#E9F04A"), c("#0A1D14"),
            0.35f, 0.82f, "barlow_bold_italic.ttf", 700),
        Skin("natura", "Natura", "Gocce su una foglia, tema chiaro", false,
            c("#EEF2E6"), c("#FFFFFF"), c("#E2E9D6"), c("#E3E9D8"),
            c("#1D2A1B"), c("#536150"), c("#5F6D5B"), c("#3F7D3A"), c("#2C5A29"), c("#FFFFFF"),
            0.25f, 0.80f, "fraunces.ttf", 600),
        Skin("trekking", "Trekking", "Escursionista sopra una valle di montagna", true,
            c("#1C1915"), c("#28231D"), c("#3A3229"), c("#29241E"),
            c("#F3EDE3"), c("#C3B8A6"), c("#A89D8C"), c("#EF8A33"), c("#EF8A33"), c("#1C1915"),
            0.50f, 0.82f, "bitter.ttf", 700),
        Skin("mare", "Mare", "Isola su un mare calmo, tema chiaro", false,
            c("#E7F3F8"), c("#FFFFFF"), c("#D5EAF3"), c("#DCECF2"),
            c("#0C2A3A"), c("#46657A"), c("#557286"), c("#0A84B8"), c("#0A6D99"), c("#FFFFFF"),
            0.10f, 0.60f, "nunito.ttf", 800)
    )

    const val CREDITS = "Foto da Unsplash: Greg Rakozy, Noah Kuhn, Buzo Jesús, Danka & Peter, Ivan Slade."

    fun byId(id: String?) = all.firstOrNull { it.id == id } ?: all[0]

    private val fonts = HashMap<String, Typeface>()

    fun typeface(ctx: Context, s: Skin): Typeface = fonts.getOrPut(s.id) {
        try {
            Typeface.Builder(ctx.assets, "fonts/${s.font}")
                .setFontVariationSettings("'wght' ${s.weight}")
                .build() ?: Typeface.DEFAULT_BOLD
        } catch (e: Exception) {
            Typeface.DEFAULT_BOLD
        }
    }

    /** Foto del tema, ridotta alla misura dello schermo. */
    fun photo(ctx: Context, s: Skin, mini: Boolean = false): Bitmap? = try {
        val path = if (mini) "bg/${s.id}_mini.jpg" else "bg/${s.id}.jpg"
        val h = ctx.resources.displayMetrics.heightPixels
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.assets.open(path).use { BitmapFactory.decodeStream(it, null, o) }
        var sample = 1
        while (!mini && o.outHeight / (sample * 2) >= h) sample *= 2
        ctx.assets.open(path).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    } catch (e: Exception) {
        null
    }

    fun withAlpha(color: Int, a: Float) = Color.argb((a * 255).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
}
