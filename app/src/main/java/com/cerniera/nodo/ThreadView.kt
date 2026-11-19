package com.cerniera.nodo

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import com.cerniera.kit.Kit
import com.cerniera.kit.withAlpha
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.tanh

/**
 * The thread (14 Nov 2026), ported from the browser preview. Every reminder is a knot on one thread
 * and the crease is now: what's done lies on one side, what's to come on the other. An overdue knot
 * can't slip into the past: it gathers against the crease in rosso until it is done or moved.
 *
 * Distance from now follows the log of the time away, so the next hour is wide and next week
 * narrow. Knots keep a gap apart, and their neighbours slide on springs to make room.
 *
 * Membrane: drag a knot and the thread follows the finger; stretch the thread to spread today out
 * or squeeze next week in (it eases back 3.2 s after the hand leaves). Concentrato: moving fast,
 * the day marks and time lines drop first, then the words, leaving knots sized by weight.
 * Giulietta: no add button. Pull the loose end and a knot forms under the finger; drag a knot
 * across the crease to mark it done; its detail comes forward only when it is tapped.
 *
 * Drawn in dp: the canvas is scaled by the density, and touches are divided by it.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class ThreadView(ctx: Context, private val feel: Feel, private val listener: Listener) : View(ctx) {

    interface Listener {
        fun tapped(k: Knot)
        fun tie(k: Knot)
        fun changed()
        fun settings()
        /** True while the line for a new knot is open: a touch on the thread closes it first. */
        fun tying(): Boolean
        fun endTie()
    }

    enum class Shape { COVER_P, COVER_L, MAIN_P, MAIN_L }
    private enum class O { H, V }

    private class G(
        val o: O, val cross: Float, val now: Float, val end: Float, val gap: Float, val tiers: Int,
        val right: Boolean, val crease: Char, val headX: Float, val headY: Float, val marksAt: Float,
    ) { val start = -30f; var s = 1f }

    private class V(var a: Float, var va: Float = 0f, var p: Float = 0f, var vp: Float = 0f, var t: Float = a, var coil: Boolean = false)

    var knots: MutableList<Knot> = mutableListOf()
        set(v) { field = v; vis.clear(); kick() }
    /** The knot shown in the lower half on the main screen turned. */
    var selected: Int? = null
        set(v) { field = v; invalidate() }

    var shape = Shape.COVER_P
        private set
    private val d = resources.displayMetrics.density
    private var w = 0f
    private var h = 0f
    private var ins = RectF()
    private lateinit var g: G
    private val vis = HashMap<Int, V>()

    // The thread's stretch (membrane), Concentrato's speed, and the gestures in hand
    private var z = 1f; private var zV = 0f; private var zTarget = 1f; private var lastTouch = 0L
    private var velo = 0f
    private class Drag(val k: Knot, var a: Float, var p: Float = 0f, var across: Boolean = false, var due: Long, val fresh: Boolean = false)
    private var drag: Drag? = null
    private var stretching = false
    private var pullOff = 0f; private var pullP = 0f; private var pulling = false
    private var coilCount = 0
    private var running = false
    private var lastFrame = 0L

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val path = Path()
    private val sans3 = Kit.sans(ctx, 300); private val sans4 = Kit.sans(ctx, 400); private val sans5 = Kit.sans(ctx, 500)
    private val display = Kit.display(ctx)

    private val ivory = Kit.Fixed.IVORY; private val champ = Kit.Fixed.CHAMPAGNE; private val rosso = Kit.Fixed.ROSSO
    private val rossoHi = 0xFFFF6A6F.toInt(); private val muted = 0xFFA9A496.toInt(); private val faint = 0xFF6E716A.toInt()
    private val ground = Kit.Fixed.VISCONTI_GROUND

    init {
        setOnApplyWindowInsetsListener { _, i ->
            val b = i.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            // The loose end is pulled with a swipe: keep it clear of the swipe that goes home
            val gest = i.getInsets(WindowInsets.Type.systemGestures())
            ins.set(b.left / d, b.top / d, b.right / d, max(b.bottom, gest.bottom) / d); relayout(); i
        }
        isFocusable = true
    }

    // The clock moves knots only once in a while: a redraw every half minute, never between
    private val clock = object : Runnable { override fun run() { invalidate(); postDelayed(this, 30_000L) } }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); postDelayed(clock, 30_000L) }
    override fun onDetachedFromWindow() { removeCallbacks(clock); super.onDetachedFromWindow() }

    // ---- Layout ---------------------------------------------------------------------------

    override fun onSizeChanged(nw: Int, nh: Int, ow: Int, oh: Int) { relayout() }

    private fun relayout() {
        w = width / d; h = height / d
        if (w <= 0f || h <= 0f) return
        // Laid out from the window, not the fold (adaptive quality, 19 Nov 2026): a window at least
        // 600dp both ways gets the main screen's shapes; anything smaller (the cover, half of the
        // main screen in split screen, a pop-up window) gets the cover's. The main screen turned
        // has its crease across the middle (as Sveglia reads it).
        val big = minOf(w, h) >= 600f
        shape = if (!big) (if (h > w) Shape.COVER_P else Shape.COVER_L) else (if (w > h) Shape.MAIN_L else Shape.MAIN_P)
        g = when (shape) {
            Shape.COVER_P -> G(O.V, ins.left + w * 0.2f, max(h * 0.2f, ins.top + 150f), h - ins.bottom - 50f, 66f, 1, true, ' ', ins.left + 22f, ins.top + 44f, 0f)
            Shape.COVER_L -> G(O.H, ins.top + (h - ins.top - ins.bottom) * 0.5f, ins.left + (w - ins.left - ins.right) * 0.22f, w - ins.right - 40f, 100f, 2, false, ' ', ins.left + 24f, ins.top + 40f, 118f)
            Shape.MAIN_P -> G(O.H, h * 0.47f, w / 2f, w - ins.right - 34f, 58f, 4, false, 'v', ins.left + 28f, ins.top + 52f, 236f)
            Shape.MAIN_L -> G(O.H, ins.top + (h / 2f - ins.top) * 0.56f, ins.left + w * 0.3f, w - ins.right - 34f, 104f, 2, false, 'h', ins.left + 28f, ins.top + 44f, 96f)
        }
        g.s = (g.end - 34f - g.now) / ln(1f + 168f)
        kick()
    }

    /** The lower half of the main screen turned belongs to the selected knot's detail. */
    fun lowerHalfFree() = shape == Shape.MAIN_L

    // ---- Time on the thread ---------------------------------------------------------------

    private fun now() = System.currentTimeMillis()
    private fun off(dt: Long): Float { val hrs = abs(dt) / HOUR.toFloat(); return sign(dt.toFloat()) * g.s * z * ln(1f + hrs) }
    private fun inv(o: Float): Long = (sign(o) * (exp(abs(o) / (g.s * z)) - 1f) * HOUR).toLong()
    private fun pt(a: Float, p: Float) = if (g.o == O.H) PointF(a, g.cross + p) else PointF(g.cross + p, a)
    private fun axisOf(x: Float, y: Float) = if (g.o == O.H) x else y
    private fun perpOf(x: Float, y: Float) = if (g.o == O.H) y - g.cross else x - g.cross

    private fun axisFor(k: Knot, n: Long): Float = when {
        k.done != null -> g.now + off(k.done!! - n)
        k.late(n) -> g.now + g.gap * 0.42f
        // Waiting for a moment: just ahead of now, where it will be untied
        k.cue != null -> g.now + g.gap * 1.1f
        else -> g.now + off(k.due - n)
    }

    private fun v(k: Knot): V = vis.getOrPut(k.id) { V(axisFor(k, now())) }

    /** Lays the knots along each side of the crease, at least a gap apart. A held knot stays under the finger. */
    private fun solve() {
        val n = now()
        val fut = ArrayList<Pair<Knot, Float>>(); val past = ArrayList<Pair<Knot, Float>>()
        for (k in knots) {
            val dk = drag?.takeIf { it.k === k }
            val t = dk?.a ?: axisFor(k, n)
            if (t >= g.now && dk?.across != true) fut += k to t else past += k to t
        }
        val gap = g.gap * min(1f, 0.55f + 0.45f * z)
        val floor = g.gap * 0.42f
        class Slot(val k: Knot, var o: Float)
        fun relax(arr: List<Pair<Knot, Float>>, dir: Float): List<Slot> {
            val q = arr.map { Slot(it.first, dir * (it.second - g.now)) }.sortedBy { it.o }
            val pin = q.indexOfFirst { drag?.k === it.k }
            for (i in q.indices) { if (i == pin) continue; q[i].o = max(q[i].o, if (i > 0) q[i - 1].o + gap else floor) }
            if (pin >= 0) for (i in pin - 1 downTo 0) q[i].o = max(min(q[i].o, q[i + 1].o - gap), min(floor, q[i + 1].o - 6f))
            q.forEach { it.o = g.now + dir * it.o }
            return q
        }
        var coiled = 0
        for (sl in relax(fut, 1f)) {
            val k = sl.k; val t = sl.o
            val vv = v(k); vv.coil = false; vv.t = t
            if (t > g.end - 14f && drag?.k !== k) { vv.t = g.end - 14f + min(coiled * 3f, 12f); vv.coil = true; coiled++ }
        }
        for (sl in relax(past, -1f)) { val vv = v(sl.k); vv.t = sl.o; vv.coil = false }
        coilCount = coiled
    }

    private fun step(dtMs: Float): Boolean {
        val f = min(dtMs / 16.7f, 3f)
        var moving = false
        solve()
        for (k in knots) {
            val vv = v(k)
            vv.va += ((vv.t - vv.a) * 0.14f - vv.va * 0.36f) * f; vv.a += vv.va * f
            val want = if (drag?.k === k) drag!!.p else 0f
            vv.vp += ((want - vv.p) * 0.16f - vv.vp * 0.32f) * f; vv.p += vv.vp * f
            if (abs(vv.t - vv.a) > 0.3f || abs(vv.va) > 0.05f || abs(vv.p - want) > 0.3f || abs(vv.vp) > 0.05f) moving = true
        }
        // The stretch eases back once the hand has left, after Giulietta's 3.2 s
        if (!stretching && z != 1f && SystemClock.uptimeMillis() - lastTouch > Kit.RECEDE_MS) zTarget = 1f
        if (!stretching) {
            zV += ((zTarget - z) * 0.08f - zV * 0.4f) * f; z += zV * f
            if (abs(z - zTarget) > 0.002f || abs(zV) > 0.001f) moving = true else z = zTarget
            if (z != 1f) moving = true
        }
        velo *= Math.pow(0.86, f.toDouble()).toFloat()
        if (velo > 0.02f) moving = true
        return moving || drag != null || stretching || pulling
    }

    /** Nothing redraws at rest: frames run only while something moves. */
    fun kick() { if (!running) { running = true; lastFrame = SystemClock.uptimeMillis(); postInvalidateOnAnimation() } }

    // ---- Drawing --------------------------------------------------------------------------

    private fun smooth(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

    private fun wrap(text: String, maxW: Float, lines: Int): List<String> {
        val out = ArrayList<String>(); var cur = ""
        for (word in text.split(" ")) {
            val t = if (cur.isEmpty()) word else "$cur $word"
            if (paint.measureText(t) > maxW && cur.isNotEmpty()) { out += cur; cur = word } else cur = t
        }
        if (cur.isNotEmpty()) out += cur
        if (out.size > lines) {
            while (out.size > lines) out.removeAt(out.size - 1)
            var l = out[lines - 1]
            while (paint.measureText("$l…") > maxW && l.isNotEmpty()) l = l.dropLast(1)
            out[lines - 1] = "$l…"
        }
        return out
    }

    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, face: android.graphics.Typeface = sans3, align: Paint.Align = Paint.Align.LEFT, alpha: Float = 1f) {
        paint.shader = null; paint.typeface = face; paint.textSize = size; paint.textAlign = align
        paint.color = withAlpha(color, alpha.coerceIn(0f, 1f)); c.drawText(s, x, y, paint)
    }

    override fun onDraw(c: Canvas) {
        if (w <= 0f) return
        if (running) {
            val t = SystemClock.uptimeMillis(); var dt = min((t - lastFrame).toFloat(), 100f); lastFrame = t
            var go = false
            while (dt > 0f) { go = step(min(dt, 16.7f)) || go; dt -= 16.7f }
            if (go) postInvalidateOnAnimation() else running = false
        } else { solve(); for (k in knots) v(k).let { it.a = it.t; it.va = 0f } }

        c.save(); c.scale(d, d)
        c.drawColor(ground)
        val n = now()
        val conc = smooth(0.25f, 1.3f, velo)
        val secA = (1f - smooth(0.08f, 0.6f, velo) - (if (drag != null) 0.6f else 0f)).coerceIn(0f, 1f)
        val labelA = 1f - conc

        // The crease
        if (g.crease == 'v') {
            paint.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(withAlpha(champ, 0f), withAlpha(champ, 0.55f), withAlpha(champ, 0f)), null, Shader.TileMode.CLAMP)
            c.drawRect(w / 2 - 0.5f, 0f, w / 2 + 0.5f, h, paint); paint.shader = null
        } else if (g.crease == 'h') {
            paint.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(withAlpha(champ, 0f), withAlpha(champ, 0.4f), withAlpha(champ, 0f)), null, Shader.TileMode.CLAMP)
            c.drawRect(0f, h / 2 - 0.5f, w, h / 2 + 0.5f, paint); paint.shader = null
        }

        // Heading: the name, today, and what's waiting
        val late = knots.count { it.late(n) }
        val today = knots.count { it.done == null && it.cue == null && it.due >= n && Words.dayStart(it.due) == Words.dayStart(n) }
        text(c, "Nodo", g.headX, g.headY, if (shape == Shape.COVER_L) 26f else 30f, ivory, display, alpha = secA)
        text(c, Words.dateLine(n) + " · " + Words.hm(n), g.headX, g.headY + 20f, 12.5f, muted, alpha = secA)
        var sx = g.headX
        fun piece(s: String, col: Int) { text(c, s, sx, g.headY + 39f, 12.5f, col, sans4, alpha = secA); sx += paint.measureText(s) + 12f }
        if (late > 0) piece("$late overdue", rossoHi)
        piece(if (today == 0 && late == 0) "Nothing more today" else "$today more today", champ)

        // Now
        val np = pt(g.now, 0f)
        when {
            g.crease == 'v' -> text(c, "NOW", np.x, g.cross - 150f, 10.5f, champ, sans4, Paint.Align.CENTER, 0.85f * secA)
            g.o == O.H -> { paint.color = withAlpha(champ, 0.5f); c.drawRect(np.x - 0.5f, np.y - 46f, np.x + 0.5f, np.y + 46f, paint); text(c, "NOW", np.x, np.y - 54f, 10.5f, champ, sans4, Paint.Align.CENTER, 0.85f * secA) }
            else -> { paint.color = withAlpha(champ, 0.5f); c.drawRect(np.x - 30f, np.y - 0.5f, np.x + 30f, np.y + 0.5f, paint); text(c, "NOW", np.x - 36f, np.y + 4f, 10.5f, champ, sans4, Paint.Align.RIGHT, 0.85f * secA) }
        }

        // Day marks along the thread
        var lastMark = -1e9f
        for (i in 1 until 60) {
            val zone = java.time.ZoneId.systemDefault()
            val day = java.time.LocalDate.now(zone).plusDays(i.toLong())
            val tt = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val a = g.now + off(tt - n)
            if (a > g.end - 24f || a < 0f) continue
            if (a - lastMark < (if (g.o == O.H) 52f else 24f)) continue
            lastMark = a
            val label = if (i == 1) "Tomorrow" else Words.dayName(tt) + " " + day.dayOfMonth
            val p = pt(a, 0f)
            paint.color = withAlpha(faint, 0.75f * secA)
            if (g.o == O.H) { c.drawRect(p.x - 0.5f, p.y + 14f, p.x + 0.5f, p.y + 20f, paint); text(c, label, p.x, p.y + g.marksAt, 10.5f, faint, align = Paint.Align.CENTER, alpha = 0.75f * secA) }
            else { c.drawRect(p.x - 20f, p.y - 0.5f, p.x - 14f, p.y + 0.5f, paint); text(c, label, p.x - 24f, p.y + 4f, 10.5f, faint, align = Paint.Align.RIGHT, alpha = 0.75f * secA) }
        }

        // The thread: hung through every knot, sagging under its own weight between them
        val pts = knots.map { k -> val vv = v(k); Triple(vv.a, vv.p, k) }.sortedBy { it.first }
        val endA = g.end + (if (pulling) pullOff else 0f); val endP = if (pulling) pullP else 0f
        val chain = ArrayList<PointF>().apply { add(PointF(g.start, 0f)); pts.forEach { add(PointF(it.first, it.second)) }; add(PointF(endA, endP)) }
        path.reset()
        var p0 = pt(chain[0].x, chain[0].y); path.moveTo(p0.x, p0.y)
        for (i in 1 until chain.size) {
            val p1 = pt(chain[i].x, chain[i].y)
            val sag = if (g.o == O.H) min(16f, abs(chain[i].x - chain[i - 1].x) * 0.05f) / max(0.6f, z) else 0f
            path.quadTo((p0.x + p1.x) / 2, (p0.y + p1.y) / 2 + sag, p1.x, p1.y); p0 = p1
        }
        stroke.pathEffect = null; stroke.strokeWidth = 1.5f; stroke.color = withAlpha(ivory, 0.62f); c.drawPath(path, stroke)
        stroke.pathEffect = android.graphics.DashPathEffect(floatArrayOf(2f, 6f), 0f); stroke.color = withAlpha(champ, 0.45f); c.drawPath(path, stroke)
        stroke.pathEffect = null

        // The loose end, frayed
        val e = pt(endA, endP)
        stroke.strokeWidth = 1.1f; stroke.color = withAlpha(ivory, 0.55f)
        for (s in -1..1) {
            path.reset(); path.moveTo(e.x, e.y)
            if (g.o == O.H) path.quadTo(e.x + 7f, e.y + s * 2f, e.x + 13f, e.y + s * 6f + 3f) else path.quadTo(e.x + s * 2f, e.y + 7f, e.x + s * 6f, e.y + 13f)
            c.drawPath(path, stroke)
        }
        if (coilCount > 0) {
            val cp = pt(g.end - 10f, if (g.o == O.H) -20f else 0f)
            if (g.o == O.H) text(c, "+$coilCount", cp.x, cp.y, 11f, muted, sans4, Paint.Align.CENTER) else text(c, "+$coilCount later", cp.x + 18f, cp.y + 4f, 11f, muted, sans4)
        }
        if (!Store.seenHint(context) || knots.none { it.done == null }) {
            val msg = if (knots.isEmpty()) "Pull the loose end to tie your first knot" else "Pull the loose end to tie a knot"
            if (g.o == O.H) text(c, msg, w - ins.right - 24f, min(e.y + (if (g.tiers == 4) 262f else 150f), (if (g.crease == 'h') h / 2 else h - ins.bottom) - 34f), 12f, faint, align = Paint.Align.RIGHT, alpha = secA)
            else text(c, msg, e.x + 18f, e.y - 26f, 12f, faint, alpha = secA)
        }

        // Knots, then their words
        var fi = 0; var pi = 0
        val idx = HashMap<Int, Int>()
        for (q in pts) { val k = q.third; if (k.done != null || (drag?.k === k && drag!!.across)) idx[k.id] = pi++ else idx[k.id] = fi++ }
        val nextK = knots.filter { it.done == null && !it.late(n) }.minByOrNull { it.due }
        for ((a, pp, k) in pts) {
            val vv = v(k); val p = pt(a, pp)
            val isLate = k.late(n); val held = drag?.k === k
            val done = k.done != null || (held && drag!!.across); val isNext = k === nextK
            val r = (4.2f + k.weight * 1.6f) * (1f + (k.weight - 1) * 0.45f * conc) * (if (vv.coil) 0.6f else 1f) * (if (held) 1.25f else 1f)
            val body = when { done -> faint; isLate -> rosso; isNext -> champ; else -> ivory }
            c.save(); c.translate(p.x, p.y); if (g.o == O.V) c.rotate(90f)
            if ((isLate || isNext || k.id == selected) && !done) {
                paint.shader = RadialGradient(0f, 0f, r * 4.2f, withAlpha(if (isLate) rosso else champ, if (isLate) 0.42f else 0.28f), 0, Shader.TileMode.CLAMP)
                c.drawCircle(0f, 0f, r * 4.2f, paint); paint.shader = null
            }
            fun bump(cx: Float) {
                path.reset(); path.moveTo(cx - r * 2.1f, 0f)
                path.cubicTo(cx - r * 1.1f, -r * 0.25f, cx - r * 0.9f, -r, cx, -r)
                path.cubicTo(cx + r * 0.9f, -r, cx + r * 1.1f, -r * 0.25f, cx + r * 2.1f, 0f)
                path.cubicTo(cx + r * 1.1f, r * 0.25f, cx + r * 0.9f, r, cx, r)
                path.cubicTo(cx - r * 0.9f, r, cx - r * 1.1f, r * 0.25f, cx - r * 2.1f, 0f); path.close()
                paint.color = body; c.drawPath(path, paint)
                path.reset(); path.moveTo(cx - r * 0.55f, r * 0.72f); path.quadTo(cx, -r * 0.1f, cx + r * 0.55f, -r * 0.72f)
                stroke.strokeWidth = 1.2f; stroke.color = withAlpha(ground, 0.55f); c.drawPath(path, stroke)
            }
            if (k.weight == 3) { bump(-r * 0.95f); bump(r * 0.95f) } else bump(0f)
            if (k.repeat != Knot.NONE && !done) {
                stroke.strokeWidth = 1f; stroke.color = withAlpha(champ, 0.6f)
                c.drawArc(RectF(-r * 1.9f, -r * 1.9f, r * 1.9f, r * 1.9f), 207f, 126f, false, stroke)
            }
            c.restore()

            if (vv.coil || labelA < 0.02f || k.pending) continue
            val i = idx[k.id] ?: 0
            val big = isNext && shape == Shape.COVER_P
            val a0 = labelA * (if (done) 0.5f else 1f)
            val tsz = if (big) 17f else if (shape == Shape.COVER_P || shape == Shape.COVER_L) 13.5f else 15.5f
            paint.typeface = if (big) sans4 else sans3; paint.textSize = tsz
            val maxW = if (g.right) w - g.cross - 46f - ins.right else if (g.tiers == 4) 178f else min(190f, g.gap * 2 - 16f)
            val lines = wrap(k.text, maxW, if (big) 3 else 2)
            val sub = Words.whenLine(k, n)
            val subCol = when { isLate -> rossoHi; done -> faint; else -> champ }
            val lh = tsz + 4f
            if (g.right) {
                val lx = p.x + 22f; val top = p.y - (lines.size * lh + 15f) / 2 + tsz * 0.8f
                lines.forEachIndexed { j, l -> text(c, l, lx, top + j * lh, tsz, if (done) muted else ivory, if (big) sans4 else sans3, alpha = a0) }
                text(c, sub, lx, top + lines.size * lh + 1f, 11.5f, subCol, sans4, alpha = a0 * secA)
            } else {
                val tier = i % g.tiers; val above = tier % 2 == 0; val far = if (tier >= 2) 84f else 0f
                paint.typeface = if (big) sans4 else sans3; paint.textSize = tsz
                val half = max(lines.maxOf { paint.measureText(it) }, 70f) / 2
                val x = p.x.coerceIn(half + 14f + ins.left, w - half - 14f - ins.right)
                val block = lines.size * lh + 15f
                val top = if (above) p.y - 22f - far - block + tsz else p.y + 30f + far + tsz * 0.8f
                lines.forEachIndexed { j, l -> text(c, l, x, top + j * lh, tsz, if (done) muted else ivory, sans3, Paint.Align.CENTER, a0) }
                text(c, sub, x, top + lines.size * lh + 1f, 11.5f, subCol, sans4, Paint.Align.CENTER, a0 * secA)
                if (secA > 0.1f) {
                    val len = 8f + far
                    paint.color = withAlpha(champ, (if (far > 0) 0.35f else 0.25f) * labelA * secA * (if (done) 0.6f else 1f))
                    if (above) c.drawRect(p.x - 0.5f, p.y - 12f - len, p.x + 0.5f, p.y - 12f, paint) else c.drawRect(p.x - 0.5f, p.y + 12f, p.x + 0.5f, p.y + 12f + len, paint)
                }
            }
        }

        // While dragging: where the knot will land
        drag?.let { dr ->
            val vv = v(dr.k); val p = pt(vv.a, vv.p)
            val label = if (dr.across) (if (dr.k.done == null) "Done" else "Still done") else Words.whenLine(Knot(0, "", dr.due), n)
            paint.typeface = sans5; paint.textSize = 12.5f
            val bw = paint.measureText(label) + 22f
            val bx = if (g.o == O.H) p.x - bw / 2 else p.x + 28f; val by = if (g.o == O.H) p.y - 58f else p.y - 14f
            paint.color = if (dr.across) faint else champ
            c.drawRoundRect(RectF(bx, by, bx + bw, by + 26f), 13f, 13f, paint)
            text(c, label, bx + bw / 2, by + 17.5f, 12.5f, ground, sans5, Paint.Align.CENTER)
        }
        c.restore()
        contentDescription = "Nodo. $late overdue, $today more today."
    }

    // ---- Touch ----------------------------------------------------------------------------

    private enum class Down { NONE, PULL, KNOT, STRETCH, HEAD }
    private var down = Down.NONE
    private var downKnot: Knot? = null
    private var downX = 0f; private var downY = 0f; private var downA = 0f; private var downZ = 1f; private var downSide = 1f
    private var onThread = false; private var moved = false
    private var lastA = 0f; private var lastT = 0L

    private fun hit(x: Float, y: Float): Knot? {
        var best: Knot? = null; var bd = 30f * 30f
        for (k in knots) { val vv = v(k); if (vv.coil) continue; val p = pt(vv.a, vv.p); val dd = (p.x - x) * (p.x - x) + (p.y - y) * (p.y - y); if (dd < bd) { bd = dd; best = k } }
        return best
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val x = ev.x / d; val y = ev.y / d
        val a = axisOf(x, y); val p = perpOf(x, y)
        lastTouch = SystemClock.uptimeMillis()
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (listener.tying()) { listener.endTie(); down = Down.NONE; return true }
                downX = x; downY = y; downA = a; lastA = a; lastT = lastTouch; moved = false
                val e = pt(g.end, 0f)
                down = when {
                    hypot(x - e.x, y - e.y) < 38f -> { pulling = true; pullOff = 0f; pullP = 0f; kick(); Down.PULL }
                    hit(x, y)?.also { downKnot = it } != null -> Down.KNOT
                    x < g.headX + 160f && y < g.headY + 48f && y > g.headY - 40f -> Down.HEAD
                    abs(p) < 120f -> { downZ = z; downSide = if (a - g.now >= 0) 1f else -1f; onThread = abs(p) < 26f; Down.STRETCH }
                    else -> Down.NONE
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val t = SystemClock.uptimeMillis(); val inst = abs(a - lastA) / max(1L, t - lastT); lastA = a; lastT = t
                when (down) {
                    Down.PULL -> {
                        pullOff = min(30f, a - g.end); pullP = p.coerceIn(-40f, 40f)
                        if (g.end - a > 46f) {
                            // Pulled far enough: a knot forms under the finger
                            val n = now()
                            val k = Knot(Store.newId(knots), "", snap(n + max(5 * MIN, inv(a - g.now)), n), pending = true)
                            knots.add(k); vis[k.id] = V(a)
                            pulling = false
                            drag = Drag(k, a, due = k.due, fresh = true); downKnot = k; down = Down.KNOT; moved = true
                            feel.cinch()
                        }
                        kick()
                    }
                    Down.KNOT -> {
                        val k = downKnot ?: return true
                        if (drag == null && hypot(x - downX, y - downY) > 8f) { drag = Drag(k, a, due = k.due); moved = true }
                        drag?.let { dr ->
                            val was = dr.across; val wasDay = Words.dayStart(dr.due)
                            dr.a = a; dr.p = p.coerceIn(-46f, 46f) * 0.6f
                            dr.across = if (k.done == null) a < g.now - 12f else a < g.now + 12f
                            if (dr.across != was) feel.crease()
                            val n = now()
                            dr.due = snap(n + max(5 * MIN, inv(a - g.now)), n)
                            if (!dr.across && Words.dayStart(dr.due) != wasDay) feel.tick()
                            velo = max(velo * 0.6f, inst * 0.6f); kick()
                        }
                    }
                    Down.STRETCH -> {
                        val dd = (a - downA) * downSide
                        if (!stretching && abs(dd) > 6f) stretching = true
                        if (stretching) { z = (downZ * exp(dd / 220f)).coerceIn(0.32f, 3.2f); zTarget = z; velo = max(velo, inst); kick() }
                    }
                    else -> {}
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val cancel = ev.actionMasked == MotionEvent.ACTION_CANCEL
                when (down) {
                    Down.PULL -> { pulling = false; kick() }
                    Down.KNOT -> {
                        val k = downKnot
                        val dr = drag
                        if (k != null && dr != null) {
                            drag = null
                            if (!cancel) {
                                when {
                                    dr.across && k.done == null -> { Store.done(knots, k); feel.cinch() }
                                    k.done != null && !dr.across && dr.a > g.now + 12f -> { k.done = null; k.due = dr.due; feel.slip() }
                                    // Moved in time: a knot waiting for a moment becomes a knot in time
                                    k.done == null -> { k.due = dr.due; k.cue = null; if (!dr.fresh) feel.tick(0.45f) }
                                }
                            }
                            if (dr.fresh || k.pending) listener.tie(k) else listener.changed()
                            kick()
                        } else if (k != null && !cancel) { feel.tick(0.3f); listener.tapped(k) }
                    }
                    Down.STRETCH -> { if (!cancel && (stretching || onThread)) feel.pluck(z); stretching = false; kick() }
                    Down.HEAD -> if (!cancel) listener.settings()
                    Down.NONE -> {}
                }
                down = Down.NONE; downKnot = null
                return true
            }
        }
        return true
    }

    // ---- For the activity and the shapes test ---------------------------------------------

    /** Where a knot sits, in px within this view. */
    fun at(k: Knot): PointF { if (w <= 0f) return PointF(0f, 0f); val vv = v(k); val p = pt(vv.t, vv.p); return PointF(p.x * d, p.y * d) }

    fun orientationHorizontal() = g.o == O.H

    /** Boxes, in px within this view, of what must be in view at rest. */
    fun parts(): Map<String, RectF> {
        if (w <= 0f) return emptyMap()
        solve()
        val m = HashMap<String, RectF>()
        val n = now()
        knots.filter { it.done == null && !it.late(n) }.minByOrNull { it.due }?.let { k -> val p = at(k); m["next"] = RectF(p.x - 12 * d, p.y - 12 * d, p.x + 12 * d, p.y + 12 * d) }
        val e = pt(g.end, 0f); m["end"] = RectF((e.x - 6) * d, (e.y - 6) * d, (e.x + 14) * d, (e.y + 14) * d)
        m["now"] = pt(g.now, 0f).let { RectF((it.x - 4) * d, (it.y - 4) * d, (it.x + 4) * d, (it.y + 4) * d) }
        m["head"] = RectF(g.headX * d, (g.headY - 26) * d, (g.headX + 60) * d, (g.headY + 42) * d)
        return m
    }
}
