package com.cerniera.nodo

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.widget.LinearLayout
import com.cerniera.kit.Finish
import com.cerniera.kit.Ground
import com.cerniera.kit.Kit
import com.cerniera.kit.Surface
import com.cerniera.kit.px
import com.cerniera.kit.text

/**
 * Choosing the widget's finish, shown as Fold Widgets shows its own (Ui.kt there, FinishTile): each
 * finish as a small widget over a ground that is bright on one side and dark on the other, crossed by
 * fine bands, so an adaptive finish shows both its faces and the glass shows how it treats detail.
 */
object Finishes {
    /** The tiles' ground: warm stone fading to a dark green, crossed by fine diagonal bands. */
    fun stripes(): Bitmap = Bitmap.createBitmap(192, 160, Bitmap.Config.ARGB_8888).also {
        val g = Canvas(it)
        g.drawRect(0f, 0f, 192f, 160f, Paint().apply {
            shader = LinearGradient(0f, 0f, 192f, 160f, intArrayOf(0xFFEDE6D8.toInt(), 0xFFC9B79A.toInt(), 0xFF2B3A2F.toInt(), 0xFF101613.toInt()),
                floatArrayOf(0f, 0.42f, 0.58f, 1f), Shader.TileMode.CLAMP)
        })
        val band = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 3f; color = 0x4D000000 }
        val light = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f; color = 0x40FFFFFF }
        var x = -160f
        while (x < 192f) { g.drawLine(x, 160f, x + 160f, 0f, band); g.drawLine(x + 7f, 160f, x + 167f, 0f, light); x += 16f }
    }

    /** Three tiles to a row, the chosen one ringed in champagne. */
    fun grid(c: Context, chosen: Finish, picked: (Finish) -> Unit): LinearLayout {
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val tiles = ArrayList<Tile>()
        Finish.values().toList().chunked(3).forEach { row ->
            val r = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEach { f ->
                val t = Tile(c, f, f == chosen) { pick -> tiles.forEach { it.chosen = it.finish == pick }; picked(pick) }
                tiles += t
                r.addView(t, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = c.px(10) })
            }
            repeat(3 - row.size) { r.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f).apply { marginEnd = c.px(10) }) }
            col.addView(r, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = c.px(14) })
        }
        return col
    }

    @SuppressLint("ViewConstructor")
    class Tile(c: Context, val finish: Finish, chosen: Boolean, onPick: (Finish) -> Unit) : LinearLayout(c) {
        var chosen = chosen
            set(v) { field = v; picture.invalidate(); name.setTextColor(if (v) Kit.CHAMPAGNE else Kit.MUTED) }
        private val picture = object : View(c) {
            override fun onDraw(canvas: Canvas) {
                val w = width.toFloat(); val h = height.toFloat(); if (w <= 0f) return
                val ground = stripes()
                canvas.drawBitmap(ground, null, RectF(0f, 0f, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
                val d = resources.displayMetrics.density
                val inset = 12f * d
                canvas.save(); canvas.translate(inset, inset); canvas.scale(d, d)
                val sw = (w - 2 * inset) / d; val sh = (h - 2 * inset) / d
                val s = Surface(finish, Ground.of(ground, 0, true), sw, sh, 14f, 1f / d)
                s.paint(canvas)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Kit.sans(context, 500); textSize = 15f }
                p.color = s.ink(sw * 0.25f, sh * 0.5f); canvas.drawText("Aa", 10f, sh / 2 + 5f, p)
                p.color = s.ink(sw * 0.75f, sh * 0.5f); canvas.drawText("Aa", sw / 2 + 10f, sh / 2 + 5f, p)
                canvas.restore()
                if (this@Tile.chosen) canvas.drawRoundRect(RectF(1.5f * d, 1.5f * d, w - 1.5f * d, h - 1.5f * d), 16f * d, 16f * d,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d; color = Kit.CHAMPAGNE })
            }
        }.apply {
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, 16f * v.resources.displayMetrics.density)
            }
        }
        private val name = c.text(finish.short, 12.5f, if (chosen) Kit.CHAMPAGNE else Kit.MUTED, 500).apply { setPadding(c.px(2), c.px(8), 0, 0) }

        init {
            orientation = VERTICAL
            addView(picture, LayoutParams(-1, c.px(76)))
            addView(name)
            isClickable = true
            setOnClickListener { onPick(finish) }
            contentDescription = "${finish.short} finish"
        }
    }
}
