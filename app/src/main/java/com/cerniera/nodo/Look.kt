package com.cerniera.nodo

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cerniera.kit.Backdrop
import com.cerniera.kit.Family
import com.cerniera.kit.Finish

/**
 * The widget's look: its finish, its corners and the images behind its glass (14 Nov 2026).
 *
 * With Fold Widgets installed, Nodo's widget wears Fold Widgets' look, read through the Kit's Family
 * (26 Nov 2026), the same code every Cerniera widget uses, so one choice there dresses every widget
 * on the home screen. There is no longer a switch to stop following it: Mechack asked on 26 Nov that
 * every widget in the family be aligned, and a Nodo widget left on its own finish was one of the
 * mismatches he saw. If Fold Widgets can't answer for a moment, the last look it gave is kept.
 *
 * Without Fold Widgets Nodo keeps a look of its own, with the same choices: the finishes, the four
 * corners and an image for each screen's glass.
 */
object Look {
    const val AUTHORITY = Family.AUTHORITY
    const val CHANGED = Family.CHANGED
    /** Fold Widgets' corners, by the same names. */
    val CORNERS = Family.CORNERS

    class Now(val finish: Finish, val radius: Int, val fromFoldWidgets: Boolean)

    private fun p(c: Context) = Store.store(c)

    fun foldWidgets(c: Context) = Family.hostInstalled(c)
    fun follows(c: Context) = foldWidgets(c)

    fun radius(c: Context) = p(c).getInt("radius", 24)
    fun setRadius(c: Context, dp: Int) { p(c).edit().putInt("radius", dp).apply(); NodoWidget.refresh(c) }

    /** Nodo's own image for a screen's glass ("cover" or "main"), used only without Fold Widgets. */
    fun ownImage(c: Context, screen: String): String? = p(c).getString("own_image_$screen", null)
    fun setOwnImage(c: Context, screen: String, uri: String?) {
        p(c).edit().apply { if (uri == null) remove("own_image_$screen") else putString("own_image_$screen", uri) }.apply()
        NodoWidget.refresh(c)
    }

    /** The look in force now; also points the Kit's Backdrop at the right pictures. */
    fun read(c: Context): Now {
        val l = Family.look(c, p(c), Store.widgetFinish(c), radius(c), mapOf("cover" to ownImage(c, "cover"), "main" to ownImage(c, "main")))
        return Now(l.finish, l.radius, l.fromHost)
    }
}

/**
 * Where Nodo's widgets sit, as Fold Widgets' live mode sees them (15 Nov 2026), now taken up through
 * the Kit's Family (26 Nov 2026), as every Cerniera widget does.
 */
object Seats {
    fun live(c: Context, id: Int): Backdrop.LiveView? = Family.live(Store.store(c), id)
    fun take(c: Context, m: AppWidgetManager, ids: IntArray, sizesDp: (Int) -> List<android.util.SizeF>) = Family.seats(c, Store.store(c), ids, sizesDp)
}

/** Fold Widgets says its look, the wallpaper, or where Nodo's widgets sit changed: redraw to match. */
class LookReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { if (i.action == Look.CHANGED) NodoWidget.refresh(c) }
}
