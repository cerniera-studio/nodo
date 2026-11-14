package com.cerniera.nodo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
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
 * same choices: the ten finishes, the four corners and an image for each screen's glass.
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

/** Fold Widgets says its look, or the wallpaper, changed: redraw the widget to match. */
class LookReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { if (i.action == Look.CHANGED) NodoWidget.refresh(c) }
}
