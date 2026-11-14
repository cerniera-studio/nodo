package com.cerniera.nodo

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.util.SizeF
import android.util.TypedValue
import android.widget.RemoteViews
import com.cerniera.kit.Kit
import java.time.ZoneId
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The thread on the home screen (14 Nov 2026). Mechack's three questions, answered before it was
 * built: it adds value only where the app can't (an overdue knot seen without opening anything,
 * and the next knot done from the home screen); a second time-ordered list would overlap the
 * Agenda widget; so it is a short stretch of the thread instead, and it complements Agenda.
 * Agenda holds commitments to others at fixed times, which pass and go; Nodo holds promises to
 * yourself, which stay overdue until dealt with. Agenda never shows knots; this never shows events.
 *
 * Drawn to Fold Widgets' recipe so the two sit together as one set: one picture per size and
 * orientation, drawn to its exact size at the screen's density; Shapes.squircle corners at
 * radius 24; Jost in Fold Widgets' roles (label 11/600 tracked, primary 14.5/600, meta 12.5/500,
 * one Italiana figure); the solid finishes with Fold Widgets' own values. Its glass finishes
 * (Vetro, Cristallo) read the wallpaper, which another app can't share, so they aren't offered.
 *
 * Widgets can't animate, be dragged or take long presses: the knot's Done is a tap, everything
 * else opens Nodo at that knot.
 */
class NodoWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) { ids.forEach { draw(c, m, it) } }
    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, o: Bundle) = draw(c, m, id)

    companion object {
        const val DONE = "com.cerniera.nodo.WIDGET_DONE"

        /** Redraws every Nodo widget: after any change to the thread, and when a knot comes due. */
        fun refresh(c: Context) {
            val m = AppWidgetManager.getInstance(c) ?: return
            runCatching { m.getAppWidgetIds(ComponentName(c, NodoWidget::class.java)).forEach { draw(c, m, it) } }
        }

        /** The sizes the launcher shows this widget at (one per orientation), in dp. */
        private fun sizes(m: AppWidgetManager, id: Int): List<SizeF> {
            val o = m.getAppWidgetOptions(id)
            @Suppress("DEPRECATION")
            val listed = o.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)?.filter { it.width > 0 && it.height > 0 }.orEmpty()
            if (listed.isNotEmpty()) return listed.distinctBy { "${it.width.toInt()}x${it.height.toInt()}" }.take(4)
            val pw = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH); val ph = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            val lw = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH); val lh = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            return listOf(SizeF(pw.coerceAtLeast(110).toFloat(), ph.coerceAtLeast(110).toFloat()), SizeF(lw.coerceAtLeast(110).toFloat(), lh.coerceAtLeast(110).toFloat())).distinct()
        }

        private fun draw(c: Context, m: AppWidgetManager, id: Int) {
            val d = c.resources.displayMetrics.density
            val knots = Store.load(c)
            val now = System.currentTimeMillis()
            val finish = Store.widgetFinish(c)
            val map = LinkedHashMap<SizeF, RemoteViews>()
            for (s in sizes(m, id)) {
                val pic = Painter.draw(c, s.width, s.height, d, finish, knots, now)
                val rv = RemoteViews(c.packageName, R.layout.widget)
                rv.setImageViewBitmap(R.id.face, pic.bitmap)
                rv.setContentDescription(R.id.face, pic.spoken)
                rv.setOnClickPendingIntent(R.id.root, open(c, pic.focus?.id))
                val hit = pic.done
                if (hit != null && pic.focus != null) {
                    rv.setViewVisibility(R.id.done, android.view.View.VISIBLE)
                    rv.setViewLayoutMargin(R.id.done, RemoteViews.MARGIN_LEFT, hit.left - 6f, TypedValue.COMPLEX_UNIT_DIP)
                    rv.setViewLayoutMargin(R.id.done, RemoteViews.MARGIN_TOP, hit.top - 6f, TypedValue.COMPLEX_UNIT_DIP)
                    rv.setViewLayoutWidth(R.id.done, hit.width() + 12f, TypedValue.COMPLEX_UNIT_DIP)
                    rv.setViewLayoutHeight(R.id.done, hit.height() + 12f, TypedValue.COMPLEX_UNIT_DIP)
                    rv.setContentDescription(R.id.done, "Mark ${pic.focus.text} done")
                    rv.setOnClickPendingIntent(R.id.done, PendingIntent.getBroadcast(c, 500_000 + pic.focus.id,
                        Intent(c, ActionReceiver::class.java).setAction(ActionReceiver.DONE).putExtra("id", pic.focus.id),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                } else rv.setViewVisibility(R.id.done, android.view.View.GONE)
                map[s] = rv
            }
            if (map.isEmpty()) return
            runCatching { m.updateAppWidget(id, if (map.size == 1) map.values.first() else RemoteViews(map)) }
                .onFailure { android.util.Log.w("Nodo", "widget $id not drawn", it) }
        }

        private fun open(c: Context, knot: Int?): PendingIntent = PendingIntent.getActivity(c, 600_000 + (knot ?: 0),
            Intent(c, MainActivity::class.java).apply { knot?.let { putExtra("knot", it) } }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}

/** The finishes, with Fold Widgets' values (Finish.kt there), so a Nodo widget and theirs match. */
enum class WidgetFinish(val label: String, val fill: Int, val ink: Int, val edge: Int, val glow: Int, val late: Int) {
    CARBONIO("Carbonio", 0xF216181A.toInt(), 0xFFE9E6DF.toInt(), 0x14FFFFFF, 0xFFD3B27C.toInt(), 0xFFFF6A6F.toInt()),
    SMOKE("Smoked", 0xDB121614.toInt(), 0xFFE6E8E2.toInt(), 0x14FFFFFF, 0xFFD3B27C.toInt(), 0xFFFF6A6F.toInt()),
    VISCONTI("Visconti", 0xF01A3F31.toInt(), 0xFFEFE6D3.toInt(), 0x73D3B27C, 0xFFD3B27C.toInt(), 0xFFFF7A7E.toInt()),
    ROSSO("Rosso", 0xF0861018.toInt(), 0xFFF6F1E6.toInt(), 0x40D9BB86, 0xFFD9BB86.toInt(), 0xFFFFD08A.toInt()),
    AVORIO("Avorio", 0xF0F3ECDD.toInt(), 0xFF2A211A.toInt(), 0x22000000, 0xFF9E1B22.toInt(), 0xFF9E1B22.toInt());
}

/** Paints one picture of the widget at a size in dp. */
object Painter {
    class Picture(val bitmap: Bitmap, val done: RectF?, val focus: Knot?, val spoken: String)

    private fun squircle(r: RectF, radius: Float): Path {
        val s = (radius * 1.3f).coerceAtMost(min(r.width(), r.height()) / 2f); val k = 0.26f * s
        return Path().apply {
            moveTo(r.left + s, r.top); lineTo(r.right - s, r.top); cubicTo(r.right - k, r.top, r.right, r.top + k, r.right, r.top + s)
            lineTo(r.right, r.bottom - s); cubicTo(r.right, r.bottom - k, r.right - k, r.bottom, r.right - s, r.bottom)
            lineTo(r.left + s, r.bottom); cubicTo(r.left + k, r.bottom, r.left, r.bottom - k, r.left, r.bottom - s)
            lineTo(r.left, r.top + s); cubicTo(r.left, r.top + k, r.left + k, r.top, r.left + s, r.top); close()
        }
    }

    private fun a(col: Int, alpha: Float) = (col and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * ((col ushr 24) and 255)).toInt() shl 24)

    /** When, for a picture that may sit unchanged for a while: clock times rather than "in 40 min". */
    fun whenLine(k: Knot, now: Long, z: ZoneId = ZoneId.systemDefault()): String {
        if (k.late(now)) return "Overdue · " + (if (Words.dayStart(k.due, z) == Words.dayStart(now, z)) "was " + Words.hm(k.due, z) else "since " + Words.dayName(k.due, z))
        return if (Words.dayStart(k.due, z) == Words.dayStart(now, z)) "Today " + Words.hm(k.due, z) else Words.whenLine(k, now, z)
    }

    fun draw(c: Context, w: Float, h: Float, density: Float, f: WidgetFinish, knots: List<Knot>, now: Long): Picture {
        // Kept within what a launcher accepts for a widget's picture
        val d = min(density, sqrt(1_600_000f / (w * h)))
        val bmp = Bitmap.createBitmap(max(1, (w * d).toInt()), max(1, (h * d).toInt()), Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp); cv.scale(d, d)
        val p = Paint(Paint.ANTI_ALIAS_FLAG); val st = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        val sans6 = Kit.sans(c, 600); val sans5 = Kit.sans(c, 500); val sans4 = Kit.sans(c, 400); val display = Kit.display(c)
        val ink = f.ink; val muted = a(ink, 0.62f); val faint = a(ink, 0.3f)

        val shape = squircle(RectF(0f, 0f, w, h), min(24f, min(w, h) / 2))
        cv.save(); cv.clipPath(shape)
        p.color = f.fill; cv.drawRect(0f, 0f, w, h, p)
        cv.restore()
        st.color = f.edge; st.strokeWidth = 1.1f / d; cv.drawPath(shape, st)

        fun text(s: String, x: Float, y: Float, size: Float, col: Int, face: android.graphics.Typeface, align: Paint.Align = Paint.Align.LEFT, track: Float = 0f) {
            p.shader = null; p.typeface = face; p.textSize = size; p.color = col; p.textAlign = align; p.letterSpacing = track; cv.drawText(s, x, y, p); p.letterSpacing = 0f
        }
        fun fit(s: String, size: Float, face: android.graphics.Typeface, maxW: Float, lines: Int): List<String> {
            p.typeface = face; p.textSize = size
            val out = ArrayList<String>(); var cur = ""
            for (word in s.split(" ")) { val t = if (cur.isEmpty()) word else "$cur $word"; if (p.measureText(t) > maxW && cur.isNotEmpty()) { out += cur; cur = word } else cur = t }
            if (cur.isNotEmpty()) out += cur
            if (out.size > lines) { while (out.size > lines) out.removeAt(out.size - 1); var l = out.last(); while (p.measureText("$l…") > maxW && l.isNotEmpty()) l = l.dropLast(1); out[lines - 1] = "$l…" }
            return out.map { l -> var t = l; while (p.measureText(t) > maxW && t.length > 1) t = t.dropLast(2) + "…"; t }
        }
        fun knot(x: Float, y: Float, r: Float, body: Int, glow: Boolean, double: Boolean, vertical: Boolean) {
            cv.save(); cv.translate(x, y); if (vertical) cv.rotate(90f)
            if (glow) { p.shader = RadialGradient(0f, 0f, r * 4f, a(body, 0.4f), 0, Shader.TileMode.CLAMP); cv.drawCircle(0f, 0f, r * 4f, p); p.shader = null }
            fun bump(cx: Float) {
                val path = Path(); path.moveTo(cx - r * 2.1f, 0f)
                path.cubicTo(cx - r * 1.1f, -r * 0.25f, cx - r * 0.9f, -r, cx, -r); path.cubicTo(cx + r * 0.9f, -r, cx + r * 1.1f, -r * 0.25f, cx + r * 2.1f, 0f)
                path.cubicTo(cx + r * 1.1f, r * 0.25f, cx + r * 0.9f, r, cx, r); path.cubicTo(cx - r * 0.9f, r, cx - r * 1.1f, r * 0.25f, cx - r * 2.1f, 0f); path.close()
                p.color = body; cv.drawPath(path, p)
                val cross = Path(); cross.moveTo(cx - r * 0.55f, r * 0.72f); cross.quadTo(cx, -r * 0.1f, cx + r * 0.55f, -r * 0.72f)
                st.strokeWidth = 1f; st.color = a(f.fill or 0xFF000000.toInt(), 0.55f); cv.drawPath(cross, st)
            }
            if (double) { bump(-r * 0.95f); bump(r * 0.95f) } else bump(0f)
            cv.restore()
        }

        val open = knots.filter { it.done == null && !it.pending }
        val late = open.filter { it.late(now) }.sortedBy { it.due }
        val ahead = open.filter { !it.late(now) }.sortedBy { it.due }
        val focus = late.firstOrNull() ?: ahead.firstOrNull()
        val todayCount = ahead.count { Words.dayStart(it.due) == Words.dayStart(now) }
        val tall = h >= 1.3f * w && w < 260f
        val small = w < 170f || h < 120f
        val pad = if (small) 12f else 16f
        val k = if (small) 0.88f else 1f

        // Heading: the name as a label, and what's waiting
        val headY = pad + 11f * k
        text("NODO", pad, headY, 11f * k, muted, sans6, track = 0.14f)
        val summary = when {
            late.isNotEmpty() && !small -> "${late.size} overdue"
            todayCount > 0 && !small -> "$todayCount today"
            else -> ""
        }
        if (summary.isNotEmpty()) text(summary, w - pad, headY, 12.5f * k, if (late.isNotEmpty()) f.late else muted, sans5, Paint.Align.RIGHT)

        var doneRect: RectF? = null
        fun donePill(right: Float, centreY: Float): RectF {
            p.typeface = sans6; p.textSize = 13f * k
            val pw = p.measureText("Done") + 26f * k; val ph = 30f * k
            val r = RectF(right - pw, centreY - ph / 2, right, centreY + ph / 2)
            p.color = f.glow; cv.drawRoundRect(r, ph / 2, ph / 2, p)
            text("Done", r.centerX(), r.centerY() + 4.6f * k, 13f * k, f.fill or 0xFF000000.toInt(), sans6, Paint.Align.CENTER)
            return r
        }

        if (focus == null) {
            // An empty thread: just the loose end
            val y = h * 0.5f
            st.strokeWidth = 1.4f; st.color = a(ink, 0.55f); st.pathEffect = null; cv.drawLine(pad, y, w - pad - 14f, y, st)
            for (s in -1..1) { val path = Path(); path.moveTo(w - pad - 14f, y); path.quadTo(w - pad - 7f, y + s * 2f, w - pad - 1f, y + s * 6f + 3f); st.strokeWidth = 1f; cv.drawPath(path, st) }
            text("Nothing on the thread", pad, y + 28f * k, 14.5f * k, ink, sans6)
            if (h > 120f) text("Tap to tie a knot", pad, y + 46f * k, 12.5f * k, muted, sans5)
            return Picture(bmp, null, null, "Nodo. Nothing on the thread. Tap to tie a knot.")
        }

        if (!tall) {
            // The thread across: now near the left, done today behind it, what's to come ahead
            // The thread and the knot in focus sit as one block, centred in the height
            val ty = if (small) headY + 26f else max(headY + 30f, h * 0.5f - 34f)
            val nowX = pad + (w - 2 * pad) * (if (small) 0.12f else 0.16f)
            val endX = w - pad - 12f
            val s = (endX - 10f - nowX) / ln(1f + 168f)
            fun xFor(t: Long): Float { val hrs = (t - now) / HOUR.toFloat(); return if (hrs >= 0) nowX + s * ln(1f + hrs) else nowX - s * ln(1f - hrs) }
            st.color = a(ink, 0.5f); st.strokeWidth = 1.3f; cv.drawLine(pad - 2f, ty, endX, ty, st)
            st.pathEffect = DashPathEffect(floatArrayOf(2f, 6f), 0f); st.color = a(f.glow, 0.45f); cv.drawLine(pad - 2f, ty, endX, ty, st); st.pathEffect = null
            st.color = a(ink, 0.45f); st.strokeWidth = 1f
            for (sgn in -1..1) { val path = Path(); path.moveTo(endX, ty); path.quadTo(endX + 6f, ty + sgn * 2f, endX + 11f, ty + sgn * 5f + 3f); cv.drawPath(path, st) }
            p.color = a(f.glow, 0.6f); cv.drawRect(nowX - 0.5f, ty - 13f, nowX + 0.5f, ty + 13f, p)
            if (!small) text("NOW", nowX, ty - 17f, 9.5f, a(f.glow, 0.85f), sans6, Paint.Align.CENTER, 0.14f)
            // Done today, faint, behind now
            knots.filter { it.done != null && Words.dayStart(it.done!!) == Words.dayStart(now) }.forEach { kk ->
                val x = xFor(kk.done!!).coerceIn(pad + 4f, nowX - 3f * (3.2f + kk.weight)); knot(x, ty, 3.2f + kk.weight, faint, false, kk.weight == 3, false)
            }
            // Overdue gathered against now; then what's to come, a gap apart, the rest coiled at the end
            // Each knot keeps clear of the one before by its own length: a knot never sits on another
            fun r(kk: Knot) = 3.6f + kk.weight * 1.3f
            fun half(kk: Knot) = r(kk) * (if (kk.weight == 3) 3.05f else 2.1f)
            var last = nowX; var lastHalf = 0f
            late.forEachIndexed { i, kk -> val x = if (i == 0) nowX + half(kk) else last + lastHalf + half(kk) - 4f; last = x; lastHalf = half(kk); knot(x, ty, r(kk), f.late, i == 0, kk.weight == 3, false) }
            var coiled = 0
            for (kk in ahead) {
                val x = max(xFor(kk.due), last + lastHalf + half(kk) + 5f)
                if (x + half(kk) > endX + 2f) { coiled++; continue }
                last = x; lastHalf = half(kk)
                knot(x, ty, 3.6f + kk.weight * 1.3f, if (kk === focus) f.glow else ink, kk === focus, kk.weight == 3, false)
            }
            if (coiled > 0 && !small) text("+$coiled", endX - 4f, ty - 10f, 10.5f, muted, sans5, Paint.Align.CENTER)

            // The knot in focus: its words, when, and Done
            val pill = donePill(w - pad, ty + (if (small) 30f else 40f) * k)
            doneRect = pill
            val textW = pill.left - pad - 10f
            val lines = fit(focus.text, 14.5f * k, sans6, textW, if (h - ty > 100f) 2 else 1)
            var y = pill.top + 4f + 11f * k
            lines.forEach { l -> text(l, pad, y, 14.5f * k, ink, sans6); y += 17.5f * k }
            text(whenLine(focus, now), pad, y + 2f, 12.5f * k, if (focus.late(now)) f.late else f.glow, sans5)
            y += 18f * k
            // Then, if there's room: what follows, in one quiet line
            val then = (late.drop(1) + ahead).filter { it !== focus }.firstOrNull()
            if (then != null && y + 26f < h - pad) {
                val line = fit("Then " + then.text + " · " + whenLine(then, now), 12.5f * k, sans5, w - 2 * pad, 1).first()
                text(line, pad, y + 14f, 12.5f * k, muted, sans5)
            }
        } else {
            // Tall and narrow: the thread down the left edge, as on the cover screen
            val tx = pad + 6f
            val nowY = headY + 26f
            val endY = h - pad - 12f
            st.color = a(ink, 0.5f); st.strokeWidth = 1.3f; cv.drawLine(tx, nowY - 18f, tx, endY, st)
            st.color = a(ink, 0.45f); st.strokeWidth = 1f
            for (sgn in -1..1) { val path = Path(); path.moveTo(tx, endY); path.quadTo(tx + sgn * 2f, endY + 6f, tx + sgn * 5f, endY + 11f); cv.drawPath(path, st) }
            p.color = a(f.glow, 0.6f); cv.drawRect(tx - 10f, nowY - 0.5f, tx + 10f, nowY + 0.5f, p)
            val lx = tx + 18f; val textW = w - lx - pad
            var y = nowY + 18f
            val list = late + ahead
            for ((i, kk) in list.withIndex()) {
                val isFocus = kk === focus
                val lines = fit(kk.text, (if (isFocus) 14.5f else 13f) * k, if (isFocus) sans6 else sans5, textW, if (isFocus) 3 else 1)
                val block = lines.size * 17f * k + 16f * k + (if (isFocus) 40f * k else 0f)
                if (y + block > endY - 6f && i > 0) { text("+${list.size - i} later", lx, min(y + 10f, endY), 11.5f * k, muted, sans5); break }
                knot(tx, y - 4f, 3.6f + kk.weight * 1.3f, when { kk.late(now) -> f.late; isFocus -> f.glow; else -> ink }, isFocus, kk.weight == 3, true)
                var ly = y
                lines.forEach { l -> text(l, lx, ly, (if (isFocus) 14.5f else 13f) * k, ink, if (isFocus) sans6 else sans5); ly += 17f * k }
                text(whenLine(kk, now), lx, ly, 11.5f * k, if (kk.late(now)) f.late else if (isFocus) f.glow else muted, sans5)
                ly += 8f * k
                if (isFocus) { p.typeface = sans6; p.textSize = 13f * k; val pw = p.measureText("Done") + 26f * k; doneRect = donePill(lx + pw, ly + 19f * k); ly += 40f * k }
                y = ly + 16f * k
            }
        }
        val spoken = "Nodo. " + (if (late.isNotEmpty()) "${late.size} overdue. " else "") + "${focus.text}, ${whenLine(focus, now)}."
        return Picture(bmp, doneRect, focus, spoken)
    }
}
