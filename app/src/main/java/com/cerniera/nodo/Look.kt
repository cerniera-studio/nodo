package com.cerniera.nodo

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.cerniera.kit.Backdrop
import com.cerniera.kit.Finish

/**
 * The widget's look: its finish, its corners and the images behind its glass (14 Nov 2026).
 *
 * With Fold Widgets installed, Nodo's widget follows Fold Widgets' look by default, read from its
 * LookProvider, so one choice there dresses every widget on the home screen. On a live wallpaper
 * this matters most: Android gives no picture of a live wallpaper, so the glass shows the image
 * chosen in Fold Widgets for that screen, the same one Fold Widgets' own glass shows.
 *
 * Without Fold Widgets (or with following switched off) Nodo keeps a look of its own, with the
 * same choices: the eleven finishes, the four corners and an image for each screen's glass.
 */
object Look {
    const val AUTHORITY = "com.fergusonconsultancy.foldwidgets.look"
    const val CHANGED = "com.cerniera.LOOK_CHANGED"
    /** Fold Widgets' corners, by the same names. */
    val CORNERS = listOf("Square" to 0, "Soft" to 14, "Round" to 24, "Full" to 32)

    class Now(val finish: Finish, val radius: Int, val fromFoldWidgets: Boolean)

    private fun p(c: Context) = Store.store(c)

    fun foldWidgets(c: Context) = runCatching { c.packageManager.resolveContentProvider(AUTHORITY, 0) != null }.getOrDefault(false)
    fun follows(c: Context) = p(c).getBoolean("followFoldWidgets", true) && foldWidgets(c)
    fun setFollows(c: Context, v: Boolean) { p(c).edit().putBoolean("followFoldWidgets", v).apply(); NodoWidget.refresh(c) }

    fun radius(c: Context) = p(c).getInt("radius", 24)
    fun setRadius(c: Context, dp: Int) { p(c).edit().putInt("radius", dp).apply(); NodoWidget.refresh(c) }

    /** Nodo's own image for a screen's glass ("cover" or "main"), when not following Fold Widgets. */
    fun ownImage(c: Context, screen: String): String? = p(c).getString("own_image_$screen", null)
    fun setOwnImage(c: Context, screen: String, uri: String?) {
        p(c).edit().apply { if (uri == null) remove("own_image_$screen") else putString("own_image_$screen", uri) }.apply()
        NodoWidget.refresh(c)
    }

    /**
     * The look in force now. Also points the Kit's Backdrop (which reads "image_cover" and
     * "image_main" from Nodo's store) at the right pictures: Fold Widgets' or Nodo's own.
     */
    fun read(c: Context): Now {
        var now: Now? = null
        var images = mapOf("cover" to ownImage(c, "cover"), "main" to ownImage(c, "main"))
        if (follows(c)) runCatching {
            c.contentResolver.query(Uri.parse("content://$AUTHORITY/look"), null, null, null, null)?.use { cur ->
                if (cur.moveToFirst()) {
                    val finish = runCatching { Finish.valueOf(cur.getString(cur.getColumnIndexOrThrow("finish"))) }.getOrDefault(Finish.VETRO)
                    now = Now(finish, cur.getInt(cur.getColumnIndexOrThrow("radius")), true)
                    images = mapOf(
                        "cover" to if (cur.getInt(cur.getColumnIndexOrThrow("image_cover")) == 1) "content://$AUTHORITY/image/cover" else null,
                        "main" to if (cur.getInt(cur.getColumnIndexOrThrow("image_main")) == 1) "content://$AUTHORITY/image/main" else null)
                }
            }
        }
        p(c).edit().apply {
            images.forEach { (screen, uri) -> if (uri == null) remove("image_$screen") else putString("image_$screen", uri) }
        }.apply()
        val look = now ?: Now(Store.widgetFinish(c), radius(c), false)
        android.util.Log.i("Nodo", "look: ${look.finish.name} radius ${look.radius} " +
            (if (look.fromFoldWidgets) "from Fold Widgets" else if (foldWidgets(c)) "own (following off)" else "own (no Fold Widgets)") +
            ", glass images: " + images.filterValues { it != null }.keys.ifEmpty { listOf("none") }.joinToString())
        return look
    }
}

/**
 * Where Nodo's widgets sit, as Fold Widgets' live mode sees them (15 Nov 2026).
 *
 * The adaptive finishes read what is behind each widget, zone by zone; a launcher never tells a
 * widget where it sits, so on its own Nodo's widget learns it from a tap and until then wears the
 * wallpaper's overall colour. That left it reading a different ground from the Fold widgets beside
 * it. Fold Widgets' live mode sees the whole home screen, Nodo's widget included, so it passes on
 * where it sits, to the pixel, and where the launcher has the wallpaper and how it tints it. With
 * those, Nodo cuts its ground from exactly the slice of wallpaper the Fold widgets would.
 */
object Seats {
    private fun p(c: Context) = Store.store(c)

    private class Seen(val left: Float, val top: Float, val width: Float, val height: Float, val screenW: Int, val screenH: Int, val live: Backdrop.LiveView?)

    /** The launcher's placing and tint for widget [id], when live mode saw them. */
    fun live(c: Context, id: Int): Backdrop.LiveView? = runCatching {
        val v = p(c).getString("live_$id", null)!!.split(",")
        Backdrop.LiveView(v[0].toFloat(), v[1].toFloat(), floatArrayOf(v[2].toFloat(), v[3].toFloat(), v[4].toFloat()), v[5].toInt(), v[6].toInt())
    }.getOrNull()

    /**
     * Takes up what Fold Widgets last saw of Nodo's widgets, if it is new: each widget seen is
     * matched to the Nodo widget nearest its last known place, or else closest in size.
     */
    fun take(c: Context, m: AppWidgetManager, ids: IntArray, sizesDp: (Int) -> List<android.util.SizeF>) {
        if (ids.isEmpty() || !Look.foldWidgets(c)) return
        val seen = ArrayList<Seen>(); var at = 0L
        runCatching {
            c.contentResolver.query(android.net.Uri.parse("content://${Look.AUTHORITY}/seats"), null, null, null, null)?.use { cur ->
                fun f(n: String) = cur.getFloat(cur.getColumnIndexOrThrow(n))
                fun i(n: String) = cur.getInt(cur.getColumnIndexOrThrow(n))
                while (cur.moveToNext()) {
                    val sw = i("screen_w"); val sh = i("screen_h")
                    val live = if (i("live") == 1) Backdrop.LiveView(f("ox"), f("oy"), floatArrayOf(f("gain_r"), f("gain_g"), f("gain_b")), sw, sh) else null
                    seen += Seen(f("left"), f("top"), f("width"), f("height"), sw, sh, live)
                    at = cur.getLong(cur.getColumnIndexOrThrow("at"))
                }
            }
        }.onFailure { android.util.Log.w("Nodo", "seats: Fold Widgets not read", it) }
        if (seen.isEmpty() || p(c).getLong("seatsAt", 0L) == at) return
        val screen = Backdrop.screenSize(c)
        val here = seen.filter { it.screenW == screen.x && it.screenH == screen.y }
        // Seen on the other screen: kept for when the phone is folded or opened again
        if (here.isEmpty()) return
        val store = p(c); val d = Backdrop.density(c)
        val claimed = HashSet<Int>(); val e = store.edit(); var placed = 0
        for (s in here) {
            fun distance(id: Int): Float {
                val old = Backdrop.seat(store, id)
                if (old != null && old.screenW == s.screenW && old.screenH == s.screenH)
                    return Math.abs(old.left - s.left) + Math.abs(old.top - s.top) + 3f * (Math.abs(old.width - s.width) + Math.abs(old.height - s.height))
                return 400f + 3f * (sizesDp(id).minOfOrNull { Math.abs(it.width * d - s.width) + Math.abs(it.height * d - s.height) } ?: 400f)
            }
            val id = ids.filter { it !in claimed }.minByOrNull { distance(it) } ?: break
            claimed += id
            e.putString("seat_$id", Backdrop.Seat(s.left, s.top, s.width, s.height, s.screenW, s.screenH, true).encode())
            s.live?.let { v -> val g = v.gain ?: floatArrayOf(1f, 1f, 1f); e.putString("live_$id", listOf(v.ox, v.oy, g[0], g[1], g[2], v.screenW, v.screenH).joinToString(",")) } ?: e.remove("live_$id")
            placed++
        }
        e.putLong("seatsAt", at).apply()
        android.util.Log.i("Nodo", "seats: $placed of ${seen.size} placed by Fold Widgets' live mode" +
            if (here.any { it.live != null }) ", wallpaper placed and tinted" else ", wallpaper as centred")
    }
}

/** Fold Widgets says its look, the wallpaper, or where Nodo's widgets sit changed: redraw to match. */
class LookReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { if (i.action == Look.CHANGED) NodoWidget.refresh(c) }
}
