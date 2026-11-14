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
import com.cerniera.kit.Backdrop
import com.cerniera.kit.Finish
import com.cerniera.kit.Ground
import com.cerniera.kit.Kit
import com.cerniera.kit.Surface
import com.cerniera.kit.lum
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
 * one Italiana figure). The finishes are Fold Widgets' own, from the Kit (14 Nov 2026): all ten,
 * adapting to the wallpaper behind the widget the same way, the glass ones seeing through it when
 * Nodo may read the wallpaper image. Like Fold Widgets', the widget learns where it sits from taps.
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
            // The wallpaper may have changed since it was last read
            Backdrop.invalidate()
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
            // What lies behind this widget: the slice of wallpaper at its seat, or the wallpaper's colours
            val ground = Backdrop.ground(c, Store.store(c), id)
            val map = LinkedHashMap<SizeF, RemoteViews>()
            for ((index, s) in sizes(m, id).withIndex()) {
                val pic = Painter.draw(c, s.width, s.height, d, finish, ground, knots, now)
                val rv = RemoteViews(c.packageName, R.layout.widget)
                rv.setImageViewBitmap(R.id.face, pic.bitmap)
                rv.setContentDescription(R.id.face, pic.spoken)
                rv.setOnClickPendingIntent(R.id.root, open(c, pic.focus?.id, id, index, s))
                val hit = pic.done
                if (hit != null && pic.focus != null) {
                    rv.setViewVisibility(R.id.done, android.view.View.VISIBLE)
                    rv.setViewLayoutMargin(R.id.done, RemoteViews.MARGIN_LEFT, hit.left - 6f, TypedValue.COMPLEX_UNIT_DIP)
                    rv.setViewLayoutMargin(R.id.done, RemoteViews.MARGIN_TOP, hit.top - 6f, TypedValue.COMPLEX_UNIT_DIP)
                    rv.setViewLayoutWidth(R.id.done, hit.width() + 12f, TypedValue.COMPLEX_UNIT_DIP)
                    rv.setViewLayoutHeight(R.id.done, hit.height() + 12f, TypedValue.COMPLEX_UNIT_DIP)
                    rv.setContentDescription(R.id.done, "Mark ${pic.focus.text} done")
                    // The tap carries where Done sits inside the widget, so the widget learns its seat from it
                    rv.setOnClickPendingIntent(R.id.done, PendingIntent.getBroadcast(c, 500_000 + id * 8 + index,
                        Intent(c, ActionReceiver::class.java).setAction(ActionReceiver.DONE).putExtra("id", pic.focus.id)
                            .putExtra(SEAT_WIDGET, id).putExtra(SEAT_HIT, floatArrayOf(hit.left - 6f, hit.top - 6f, hit.right + 6f, hit.bottom + 6f))
                            .putExtra(SEAT_SIZE, floatArrayOf(s.width, s.height)),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                } else rv.setViewVisibility(R.id.done, android.view.View.GONE)
                map[s] = rv
            }
            if (map.isEmpty()) return
            runCatching { m.updateAppWidget(id, if (map.size == 1) map.values.first() else RemoteViews(map)) }
                .onFailure { android.util.Log.w("Nodo", "widget $id not drawn", it) }
        }

        /** Opens Nodo at the knot. A tap on the whole widget gives its exact bounds: its seat. */
        private fun open(c: Context, knot: Int?, widget: Int, index: Int, size: SizeF): PendingIntent = PendingIntent.getActivity(c, 600_000 + widget * 8 + index,
            Intent(c, MainActivity::class.java).apply { knot?.let { putExtra("knot", it) } }
                .putExtra(SEAT_WIDGET, widget).putExtra(SEAT_ROOT, true).putExtra(SEAT_SIZE, floatArrayOf(size.width, size.height))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        const val SEAT_WIDGET = "nodo.widget"
        const val SEAT_ROOT = "nodo.seat.root"
        const val SEAT_HIT = "nodo.seat.hit"
        const val SEAT_SIZE = "nodo.seat.size"

        /**
         * Learns where a widget sits from a tap's bounds (as Fold Widgets' do), and redraws it
         * against what's really behind it when that changed.
         */
        fun seatFrom(c: Context, i: Intent?) {
            i ?: return
            val id = i.getIntExtra(SEAT_WIDGET, -1); if (id < 0) return
            val size = i.getFloatArrayExtra(SEAT_SIZE)?.takeIf { it.size == 2 }?.let { SizeF(it[0], it[1]) } ?: return
            val root = i.getBooleanExtra(SEAT_ROOT, false)
            val hit = i.getFloatArrayExtra(SEAT_HIT)?.takeIf { it.size == 4 }?.let { RectF(it[0], it[1], it[2], it[3]) }
            val moved = runCatching { Backdrop.record(c, Store.store(c), i, id, hit, size, root, size) }.getOrDefault(false)
            if (moved) refresh(c)
        }
    }
}

/** Paints one picture of the widget at a size in dp. */
object Painter {
    /** The tones a picture's marks take, read from its finish over its ground. */
    private class Tones(val ink: Int, val glow: Int, val late: Int)

    class Picture(val bitmap: Bitmap, val done: RectF?, val focus: Knot?, val spoken: String)

    private fun a(col: Int, alpha: Float) = (col and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * ((col ushr 24) and 255)).toInt() shl 24)

    /** When, for a picture that may sit unchanged for a while: clock times rather than "in 40 min". */
    fun whenLine(k: Knot, now: Long, z: ZoneId = ZoneId.systemDefault()): String {
        if (k.late(now)) return "Overdue · " + (if (Words.dayStart(k.due, z) == Words.dayStart(now, z)) "was " + Words.hm(k.due, z) else "since " + Words.dayName(k.due, z))
        return if (Words.dayStart(k.due, z) == Words.dayStart(now, z)) "Today " + Words.hm(k.due, z) else Words.whenLine(k, now, z)
    }

    fun draw(c: Context, w: Float, h: Float, density: Float, finish: Finish, ground: Ground, knots: List<Knot>, now: Long): Picture {
        // Kept within what a launcher accepts for a widget's picture
        val d = min(density, sqrt(1_600_000f / (w * h)))
        val bmp = Bitmap.createBitmap(max(1, (w * d).toInt()), max(1, (h * d).toInt()), Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp); cv.scale(d, d)
        val p = Paint(Paint.ANTI_ALIAS_FLAG); val st = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        val sans6 = Kit.sans(c, 600); val sans5 = Kit.sans(c, 500); val sans4 = Kit.sans(c, 400); val display = Kit.display(c)
        // The finish, painted by the Kit exactly as Fold Widgets paints its own: one ink for the whole
        // widget, proven legible over what lies behind it
        val surface = Surface(finish, ground, w, h, min(24f, min(w, h) / 2), 1.1f / d)
        surface.paint(cv)
        val ink = surface.ink(w / 2, h / 2); val muted = surface.muted(w / 2, h / 2); val faint = a(ink, 0.3f)
        val glowCol = surface.accent(w / 2, h / 2)
        val lightGlass = surface.darkInk(w / 2, h / 2)
        // Overdue: rosso, kept legible on every finish (pale on Rosso lacquer, deep on light glass)
        val lateCol = when { finish == Finish.ROSSO -> 0xFFFFD08A.toInt(); lightGlass -> 0xFF9E1B22.toInt(); else -> 0xFFFF6A6F.toInt() }
        val onGlow = if (lum(glowCol) > 0.5f) 0xFF15191A.toInt() else 0xFFF6F1E6.toInt()
        val f = Tones(ink, glowCol, lateCol)

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
                st.strokeWidth = 1f; st.color = a(if (lum(body) > 0.5f) 0xFF0E1311.toInt() else 0xFFF6F1E6.toInt(), 0.55f); cv.drawPath(cross, st)
            }
            if (double) { bump(-r * 0.95f); bump(r * 0.95f) } else bump(0f)
            cv.restore()
        }

        val open = knots.filter { it.done == null && !it.pending }
        val late = open.filter { it.late(now) }.sortedBy { it.due }
        val ahead = open.filter { !it.late(now) }.sortedBy { it.due }
        val focus = late.firstOrNull() ?: ahead.firstOrNull()
        val todayCount = ahead.count { Words.dayStart(it.due) == Words.dayStart(now) }
        val tall = h >= 1.3f * w
        val small = w < 170f || h < 120f
        // The whole composition grows with the widget, as Fold Widgets' do from 300 dp: a larger
        // widget is the same design at a larger scale, never a small one floating in space
        val k = if (small) 0.88f else (min(w, h) / (if (tall) 180f else 200f)).coerceIn(1f, 1.6f)
        val pad = (if (small) 12f else 16f) * k

        // Heading: the name as a label, and what's waiting
        val headY = pad + 11f * k
        text("NODO", pad, headY, 11f * k, muted, sans6, track = 0.14f)
        val summary = when {
            late.isNotEmpty() && !small -> "${late.size} overdue"
            todayCount > 0 && !small -> "$todayCount today"
            else -> ""
        }
        // Narrow and not tall: Done takes the top corner, so the knot's words get the whole width
        val compact = !tall && w < 200f
        if (summary.isNotEmpty() && !compact) text(summary, w - pad, headY, 12.5f * k, if (late.isNotEmpty()) f.late else muted, sans5, Paint.Align.RIGHT)

        var doneRect: RectF? = null
        fun pillWidth(): Float { p.typeface = sans6; p.textSize = 13f * k; return p.measureText("Done") + 26f * k }
        fun donePill(right: Float, centreY: Float): RectF {
            val pw = pillWidth(); val ph = 30f * k
            val r = RectF(right - pw, centreY - ph / 2, right, centreY + ph / 2)
            p.color = f.glow; cv.drawRoundRect(r, ph / 2, ph / 2, p)
            text("Done", r.centerX(), r.centerY() + 4.6f * k, 13f * k, onGlow, sans6, Paint.Align.CENTER)
            return r
        }
        fun fray(x: Float, y: Float, vertical: Boolean) {
            st.color = a(ink, 0.45f); st.strokeWidth = 1f * k; st.pathEffect = null
            for (sgn in -1..1) { val path = Path(); path.moveTo(x, y)
                if (vertical) path.quadTo(x + sgn * 2f * k, y + 6f * k, x + sgn * 5f * k, y + 11f * k) else path.quadTo(x + 6f * k, y + sgn * 2f * k, x + 11f * k, y + sgn * 5f * k + 3f * k)
                cv.drawPath(path, st) }
        }
        fun r(kk: Knot) = (3.6f + kk.weight * 1.3f) * k
        fun half(kk: Knot) = r(kk) * (if (kk.weight == 3) 3.05f else 2.1f)
        fun bodyOf(kk: Knot) = when { kk.late(now) -> f.late; kk === focus -> f.glow; else -> ink }

        if (focus == null) {
            // An empty thread: just the loose end, centred
            val y = h * 0.5f - 12f * k
            st.strokeWidth = 1.4f * k; st.color = a(ink, 0.55f); st.pathEffect = null; cv.drawLine(pad, y, w - pad - 14f * k, y, st)
            fray(w - pad - 14f * k, y, false)
            text("Nothing on the thread", pad, y + 30f * k, 14.5f * k, ink, sans6)
            if (h > 120f) text("Tap to tie a knot", pad, y + 49f * k, 12.5f * k, muted, sans5)
            return Picture(bmp, null, null, "Nodo. Nothing on the thread. Tap to tie a knot.")
        }

        // Day marks: a small tick at each midnight, the thread's own rhythm, never words that could collide
        val zone = ZoneId.systemDefault()
        fun midnights(n: Int) = (1..n).map { java.time.LocalDate.now(zone).plusDays(it.toLong()).atStartOfDay(zone).toInstant().toEpochMilli() }

        // ---- Large: a hero layout. The knot in focus as the one large figure (its time, in Italiana,
        // as Agenda leads with its date), its words and Done beside it, and the thread running the
        // rest of the widget with the week ahead named along it. Even one knot fills the space with
        // meaning: the week it sits in. ----
        val hero = !small && ((!tall && h >= 290f && w >= 300f) || (tall && h >= 540f && w >= 220f))
        if (hero) {
            val K = if (tall) (w / 300f).coerceIn(1f, 2f) else (min(w, h * 1.3f) / 380f).coerceIn(1f, 2.2f)
            val lateFocus = focus.late(now)
            val figure = Words.hm(focus.due)
            val figSize = if (tall) min(w * 0.3f, 104f * K) else min(h * 0.22f, 104f * K)
            val figY = headY + 14f * K + figSize * 0.78f
            text(figure, pad, figY, figSize, if (lateFocus) f.late else ink, display)
            p.typeface = display; p.textSize = figSize
            val figW = p.measureText(figure)
            val pillScale = (K * 1.1f).coerceAtMost(1.8f)
            p.typeface = sans6; p.textSize = 13f * pillScale
            val pillW = p.measureText("Done") + 26f * pillScale; val pillH = 30f * pillScale
            fun pill(right: Float, cy: Float): RectF {
                val rr = RectF(right - pillW, cy - pillH / 2, right, cy + pillH / 2)
                p.color = f.glow; cv.drawRoundRect(rr, pillH / 2, pillH / 2, p)
                text("Done", rr.centerX(), rr.centerY() + 4.6f * pillScale, 13f * pillScale, onGlow, sans6, Paint.Align.CENTER)
                return rr
            }
            // Words: beside the figure when there's width, otherwise under it
            val titleSize = 19f * K
            val beside = !tall && w - 2 * pad - figW - pillW - 40f * K > 180f * K
            val tx0 = if (beside) pad + figW + 24f * K else pad
            val titleW = (if (beside) w - pad - pillW - 20f * K - tx0 else w - 2 * pad - (if (tall) 0f else pillW + 16f * K))
            val tLines = fit(focus.text, titleSize, sans6, titleW, 2)
            var ty0 = if (beside) figY - figSize * 0.62f + titleSize else figY + 14f * K + titleSize
            tLines.forEach { l -> text(l, tx0, ty0, titleSize, ink, sans6); ty0 += titleSize * 1.22f }
            // The figure already says the time: the line under the words says only the day
            val dayLine = when {
                lateFocus -> if (Words.dayStart(focus.due) == Words.dayStart(now)) "Overdue" else "Overdue since " + Words.dayName(focus.due)
                Words.dayStart(focus.due) == Words.dayStart(now) -> "Today"
                Words.dayStart(focus.due) == Words.dayStart(now + DAY) -> "Tomorrow"
                else -> Words.dateLine(focus.due)
            }
            text(dayLine, tx0, ty0 + 2f * K, 13.5f * K, if (lateFocus) f.late else f.glow, sans5)
            var heroBottom = ty0 + 10f * K
            doneRect = if (tall) pill(pad + pillW, heroBottom + 8f * K + pillH / 2).also { heroBottom += 18f * K + pillH }
                else pill(w - pad, if (beside) figY - figSize * 0.35f else figY + 14f * K + titleSize * 0.7f)
            if (late.size > 1) { text("+${late.size - 1} more overdue", tx0, heroBottom + 14f * K, 12.5f * K, f.late, sans5); heroBottom += 22f * K }

            val others = (late.drop(1) + ahead).filter { it !== focus }
            val dayFmt = { i: Int, t: Long -> if (i == 1) "Tomorrow" else Words.dayName(t) + " " + java.time.Instant.ofEpochMilli(t).atZone(zone).dayOfMonth }
            if (!tall) {
                // The thread across the lower part, the week named under it, the other knots named below
                val nowX = pad + (w - 2 * pad) * 0.1f
                val endX = w - pad - 12f * K
                val s = (endX - 10f * K - nowX) / ln(1f + 168f)
                fun xFor(t: Long): Float { val hrs = (t - now) / HOUR.toFloat(); return if (hrs >= 0) nowX + s * ln(1f + hrs) else nowX - s * ln(1f - hrs) }
                val tierH = 38f * K
                val room = h - pad - heroBottom
                val tiers = ((room - 110f * K) / tierH).toInt().coerceIn(0, 2)
                val ty = (heroBottom + (room - 50f * K - tiers * tierH) * 0.45f + 30f * K).coerceIn(heroBottom + 34f * K, h - pad - 44f * K - tiers * tierH)
                st.color = a(ink, 0.5f); st.strokeWidth = 1.4f * K; st.pathEffect = null; cv.drawLine(pad - 2f, ty, endX, ty, st)
                st.pathEffect = DashPathEffect(floatArrayOf(2f * K, 6f * K), 0f); st.color = a(f.glow, 0.45f); cv.drawLine(pad - 2f, ty, endX, ty, st); st.pathEffect = null
                fray(endX, ty, false)
                p.color = a(f.glow, 0.6f); cv.drawRect(nowX - 0.5f, ty - 14f * K, nowX + 0.5f, ty + 14f * K, p)
                text("NOW", nowX, ty - 19f * K, 9.5f * K, a(f.glow, 0.85f), sans6, Paint.Align.CENTER, 0.14f)
                var lastLabel = -1e9f
                midnights(14).forEachIndexed { i, m ->
                    val x = xFor(m); if (x > endX - 20f * K) return@forEachIndexed
                    p.color = a(ink, 0.25f); cv.drawRect(x - 0.5f, ty + 7f * K, x + 0.5f, ty + 13f * K, p)
                    if (x - lastLabel > 58f * K) { text(dayFmt(i + 1, m), x, ty + 27f * K, 11f * K, a(ink, 0.45f), sans5, Paint.Align.CENTER); lastLabel = x }
                }
                knots.filter { it.done != null && Words.dayStart(it.done!!) == Words.dayStart(now) }.forEach { kk ->
                    knot(min(max(xFor(kk.done!!), pad + 4f), nowX - 3f * (3.2f + kk.weight) * K), ty, (3.2f + kk.weight) * K, faint, false, kk.weight == 3, false)
                }
                val rr = { kk: Knot -> (3.6f + kk.weight * 1.3f) * K }
                val hh = { kk: Knot -> rr(kk) * (if (kk.weight == 3) 3.05f else 2.1f) }
                var last = nowX; var lastHalf = 0f; var coiled = 0
                class HeroSpot(val kk: Knot, val x: Float)
                val spots = ArrayList<HeroSpot>()
                for ((i, kk) in late.withIndex()) { val x = if (i == 0) nowX + hh(kk) else last + lastHalf + hh(kk) - 4f * K; last = x; lastHalf = hh(kk); spots += HeroSpot(kk, x) }
                for (kk in ahead) { val x = max(xFor(kk.due), last + lastHalf + hh(kk) + 5f * K); if (x + hh(kk) > endX + 2f) { coiled++; continue }; last = x; lastHalf = hh(kk); spots += HeroSpot(kk, x) }
                spots.forEachIndexed { i, sp -> knot(sp.x, ty, rr(sp.kk), bodyOf(sp.kk), sp.kk === focus || (i == 0 && sp.kk.late(now)), sp.kk.weight == 3, false) }
                if (coiled > 0) text("+$coiled", endX - 4f * K, ty - 11f * K, 11f * K, muted, sans5, Paint.Align.CENTER)
                if (tiers > 0) {
                    val labelW = min(170f * K, (w - 2 * pad) / 3.2f)
                    val rightEdge = FloatArray(tiers) { -1e9f }; var t = 0
                    for (sp in spots) {
                        if (sp.kk === focus) continue
                        val cx = sp.x.coerceIn(pad + labelW / 2, w - pad - labelW / 2)
                        val tier = (0 until tiers).map { (t + it) % tiers }.firstOrNull { cx - labelW / 2 > rightEdge[it] + 10f * K } ?: continue
                        t = tier + 1; rightEdge[tier] = cx + labelW / 2
                        val base = ty + 56f * K + tier * tierH
                        text(fit(sp.kk.text, 13.5f * K, sans5, labelW, 1).first(), cx, base, 13.5f * K, ink, sans5, Paint.Align.CENTER)
                        text(whenLine(sp.kk, now), cx, base + 15f * K, 11.5f * K, if (sp.kk.late(now)) f.late else muted, sans5, Paint.Align.CENTER)
                        p.color = a(f.glow, 0.3f); cv.drawRect(sp.x - 0.5f, ty + half(sp.kk) / 2 + 16f * K, sp.x + 0.5f, base - 14f * K, p)
                    }
                }
                if (others.isEmpty()) text("Nothing else on the thread this week", w - pad, h - pad, 12.5f * K, muted, sans5, Paint.Align.RIGHT)
            } else {
                // The thread down the rest, the days named to its left, the other knots to its right
                val tx = pad + 54f * K
                val top = heroBottom + 34f * K
                val endY = h - pad - 12f * K
                val s = (endY - 10f * K - top) / ln(1f + 168f)
                fun yFor(t: Long): Float = top + s * ln(1f + max(0f, (t - now) / HOUR.toFloat()))
                st.color = a(ink, 0.5f); st.strokeWidth = 1.4f * K; st.pathEffect = null; cv.drawLine(tx, top - 18f * K, tx, endY, st)
                fray(tx, endY, true)
                p.color = a(f.glow, 0.6f); cv.drawRect(tx - 12f * K, top - 0.5f, tx + 12f * K, top + 0.5f, p)
                text("NOW", tx - 16f * K, top + 3.5f * K, 9.5f * K, a(f.glow, 0.85f), sans6, Paint.Align.RIGHT, 0.14f)
                var lastLabel = -1e9f
                midnights(14).forEachIndexed { i, m ->
                    val y = yFor(m); if (y > endY - 16f * K) return@forEachIndexed
                    p.color = a(ink, 0.25f); cv.drawRect(tx - 9f * K, y - 0.5f, tx - 4f * K, y + 0.5f, p)
                    if (y - lastLabel > 20f * K) { text(dayFmt(i + 1, m), tx - 14f * K, y + 4f * K, 11f * K, a(ink, 0.45f), sans5, Paint.Align.RIGHT); lastLabel = y }
                }
                val lx = tx + 18f * K; val textW = w - lx - pad
                var cursor = top + 4f * K
                val all = late + ahead
                for ((i, kk) in all.withIndex()) {
                    val isFocus = kk === focus
                    val want = if (kk.late(now)) cursor + 8f * K else max(yFor(kk.due), cursor + 8f * K)
                    val blockH = if (isFocus) 18f * K else 13.5f * K + 30f * K
                    if (want + blockH > endY + 4f * K) { text("+${all.size - i} later", lx, min(cursor + 14f * K, endY), 11.5f * K, muted, sans5); break }
                    knot(tx, want, (3.6f + kk.weight * 1.3f) * K, bodyOf(kk), isFocus || kk.late(now), kk.weight == 3, true)
                    if (isFocus) { cursor = want + 18f * K; continue }   // its words are the hero's
                    text(fit(kk.text, 13.5f * K, sans5, textW, 1).first(), lx, want + 4.5f * K, 13.5f * K, ink, sans5)
                    text(whenLine(kk, now), lx, want + 20f * K, 11.5f * K, if (kk.late(now)) f.late else muted, sans5)
                    cursor = want + blockH
                }
                if (others.isEmpty()) text("Nothing else this week", lx, (top + endY) / 2, 12.5f * K, muted, sans5)
            }
            val spoken = "Nodo. " + (if (late.isNotEmpty()) "${late.size} overdue. " else "") + "${focus.text}, ${whenLine(focus, now)}."
            return Picture(bmp, doneRect, focus, spoken)
        }

        if (!tall) {
            // ---- Wide: the thread across ------------------------------------------------------
            val nowX = pad + (w - 2 * pad) * (if (small) 0.12f else 0.16f)
            val endX = w - pad - 12f * k
            val s = (endX - 10f * k - nowX) / ln(1f + 168f)
            fun xFor(t: Long): Float { val hrs = (t - now) / HOUR.toFloat(); return if (hrs >= 0) nowX + s * ln(1f + hrs) else nowX - s * ln(1f - hrs) }

            // Place every knot first: overdue against now, then what's to come, each clear of the last
            class Spot(val kk: Knot, val x: Float)
            val spots = ArrayList<Spot>()
            var last = nowX; var lastHalf = 0f
            late.forEachIndexed { i, kk -> val x = if (i == 0) nowX + half(kk) else last + lastHalf + half(kk) - 4f * k; last = x; lastHalf = half(kk); spots += Spot(kk, x) }
            var coiled = 0
            for (kk in ahead) {
                val x = max(xFor(kk.due), last + lastHalf + half(kk) + 5f * k)
                if (x + half(kk) > endX + 2f) { coiled++; continue }
                last = x; lastHalf = half(kk); spots += Spot(kk, x)
            }

            // How much room the words have: labels above the thread in tiers when the widget is tall enough
            val tierH = 40f * k
            val tiers = when { small -> 0; h >= 330f * k -> 2; h >= 230f * k -> 1; else -> 0 }
            val textW = if (compact) w - 2 * pad else w - 2 * pad - pillWidth() - 12f * k
            val focusLines = fit(focus.text, 14.5f * k, sans6, textW, if (h >= 200f * k || compact) 2 else 1)
            val focusH = 26f * k + focusLines.size * 17.5f * k + 18f * k
            val thenRoom = tiers == 0 && !small
            val blockTop = (if (tiers > 0) tiers * tierH + 6f * k else 0f) + 22f * k   // above the thread: labels, then NOW
            val blockBottom = focusH + (if (thenRoom) 20f * k else 0f)
            val top = headY + 14f * k; val bottom = h - pad
            val ty = if (small) headY + 26f else (top + (bottom - top - blockTop - blockBottom) / 2 + blockTop).coerceAtLeast(top + blockTop)

            st.color = a(ink, 0.5f); st.strokeWidth = 1.3f * k; cv.drawLine(pad - 2f, ty, endX, ty, st)
            st.pathEffect = DashPathEffect(floatArrayOf(2f * k, 6f * k), 0f); st.color = a(f.glow, 0.45f); cv.drawLine(pad - 2f, ty, endX, ty, st); st.pathEffect = null
            fray(endX, ty, false)
            p.color = a(ink, 0.22f)
            for (m in midnights(14)) { val x = xFor(m); if (x < endX - 6f * k) cv.drawRect(x - 0.5f, ty + 7f * k, x + 0.5f, ty + 13f * k, p) }
            p.color = a(f.glow, 0.6f); cv.drawRect(nowX - 0.5f, ty - 13f * k, nowX + 0.5f, ty + 13f * k, p)
            if (!small) text("NOW", nowX, ty - 17f * k, 9.5f * k, a(f.glow, 0.85f), sans6, Paint.Align.CENTER, 0.14f)
            knots.filter { it.done != null && Words.dayStart(it.done!!) == Words.dayStart(now) }.forEach { kk ->
                val x = min(max(xFor(kk.done!!), pad + 4f), nowX - 3f * (3.2f + kk.weight) * k); knot(x, ty, (3.2f + kk.weight) * k, faint, false, kk.weight == 3, false)
            }
            spots.forEachIndexed { i, sp -> knot(sp.x, ty, r(sp.kk), bodyOf(sp.kk), sp.kk === focus || (i == 0 && sp.kk.late(now)), sp.kk.weight == 3, false) }
            if (coiled > 0 && !small) text("+$coiled", endX - 4f * k, ty - 10f * k, 10.5f * k, muted, sans5, Paint.Align.CENTER)

            // The other knots named above the thread, alternating tiers, each kept clear of its neighbour
            if (tiers > 0) {
                val labelW = min(170f * k, (w - 2 * pad) / 2.6f)
                val rightEdge = FloatArray(tiers) { -1e9f }
                var t = 0
                for (sp in spots) {
                    if (sp.kk === focus) continue
                    val cx = sp.x.coerceIn(pad + labelW / 2, w - pad - labelW / 2)
                    val tier = (0 until tiers).map { (t + it) % tiers }.firstOrNull { cx - labelW / 2 > rightEdge[it] + 10f * k } ?: continue
                    t = tier + 1
                    rightEdge[tier] = cx + labelW / 2
                    val base = ty - 30f * k - tier * tierH
                    val line = fit(sp.kk.text, 13f * k, sans5, labelW, 1).first()
                    text(line, cx, base - 14f * k, 13f * k, ink, sans5, Paint.Align.CENTER)
                    text(whenLine(sp.kk, now), cx, base, 11.5f * k, if (sp.kk.late(now)) f.late else muted, sans5, Paint.Align.CENTER)
                    p.color = a(f.glow, 0.3f); cv.drawRect(sp.x - 0.5f, base + 5f * k, sp.x + 0.5f, ty - half(sp.kk) / 2 - 3f * k, p)
                }
            }

            // The knot in focus: its words, when, and Done
            val pill = if (compact) donePill(w - pad, headY - 4f * k) else donePill(w - pad, ty + (if (small) 30f else 40f) * k)
            doneRect = pill
            var y = if (compact) ty + 30f * k else pill.top + 4f * k + 11f * k
            focusLines.forEach { l -> text(l, pad, y, 14.5f * k, ink, sans6); y += 17.5f * k }
            // Kept clear of Done: on a small widget the line is shortened rather than run under it
            text(fit(whenLine(focus, now), 12.5f * k, sans5, textW, 1).first(), pad, y + 2f * k, 12.5f * k, if (focus.late(now)) f.late else f.glow, sans5)
            y += 18f * k
            if (thenRoom) {
                val then = (late.drop(1) + ahead).firstOrNull { it !== focus }
                if (then != null && y + 16f * k < h - pad) {
                    val line = fit("Then " + then.text + " · " + whenLine(then, now), 12.5f * k, sans5, w - 2 * pad, 1).first()
                    text(line, pad, y + 14f * k, 12.5f * k, muted, sans5)
                }
            }
        } else {
            // ---- Tall: the thread down the left edge, knots placed by time as on the cover screen ----
            val tx = pad + 6f * k
            val nowY = headY + 30f * k
            val endY = h - pad - 12f * k
            val lx = tx + 18f * k; val textW = w - lx - pad
            val s = (endY - 10f * k - nowY) / ln(1f + 168f)
            fun yFor(t: Long): Float = nowY + s * ln(1f + max(0f, (t - now) / HOUR.toFloat()))
            st.color = a(ink, 0.5f); st.strokeWidth = 1.3f * k; st.pathEffect = null; cv.drawLine(tx, nowY - 20f * k, tx, endY, st)
            fray(tx, endY, true)
            p.color = a(ink, 0.22f)
            for (m in midnights(14)) { val y = yFor(m); if (y < endY - 6f * k) cv.drawRect(tx - 13f * k, y - 0.5f, tx - 7f * k, y + 0.5f, p) }
            p.color = a(f.glow, 0.6f); cv.drawRect(tx - 10f * k, nowY - 0.5f, tx + 10f * k, nowY + 0.5f, p)

            // Each label needs its height; a knot sits at its time unless the one before still needs room
            var cursor = nowY + 4f * k
            val list = late + ahead
            for ((i, kk) in list.withIndex()) {
                val isFocus = kk === focus
                val size = (if (isFocus) 14.5f else 13f) * k
                val lines = fit(kk.text, size, if (isFocus) sans6 else sans5, textW, if (isFocus) 3 else 1)
                val blockH = lines.size * (size + 3f * k) + 16f * k + (if (isFocus) 40f * k else 0f)
                val want = if (kk.late(now)) cursor + 8f * k else max(yFor(kk.due), cursor + 8f * k)
                if (want + blockH > endY + 4f * k) { text("+${list.size - i} later", lx, min(cursor + 14f * k, endY), 11.5f * k, muted, sans5); break }
                knot(tx, want, r(kk), bodyOf(kk), isFocus || kk.late(now), kk.weight == 3, true)
                var ly = want + size * 0.35f
                lines.forEach { l -> text(l, lx, ly, size, ink, if (isFocus) sans6 else sans5); ly += size + 3f * k }
                text(whenLine(kk, now), lx, ly, 11.5f * k, if (kk.late(now)) f.late else if (isFocus) f.glow else muted, sans5)
                ly += 8f * k
                if (isFocus) { doneRect = donePill(lx + pillWidth(), ly + 19f * k); ly += 40f * k }
                cursor = ly + 6f * k
            }
        }
        val spoken = "Nodo. " + (if (late.isNotEmpty()) "${late.size} overdue. " else "") + "${focus.text}, ${whenLine(focus, now)}."
        return Picture(bmp, doneRect, focus, spoken)
    }
}
