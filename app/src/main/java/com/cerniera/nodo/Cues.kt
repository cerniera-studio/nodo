package com.cerniera.nodo

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager

/**
 * Fluido in Nodo (18 Nov 2026): knots that untie themselves by moment rather than time.
 *
 *  - **Home.** Nodo learns home as a Wi-Fi network, once, when you say "I'm home now" in its
 *    settings. After that the phone tells Nodo whenever Wi-Fi connects (a network request with a
 *    pending intent, so nothing runs in between), and if it is home's, the home knots come due.
 *    Android shows a Wi-Fi network's name only with location permission, and to an app that's
 *    closed only with location "Allow all the time" (19 Nov 2026: chosen for usability; nothing
 *    is tracked, Nodo only reads the network's name when Wi-Fi connects).
 *  - **The backup.** Opening Nodo on home's Wi-Fi unties the home knots that were tied away from
 *    home, in case the phone didn't wake Nodo. Knots tied at home wait for the next arrival.
 *  - **An app.** The family's apps that can see the app in front (Fold Taskbar) ask Nodo which
 *    apps knots are waiting for ([CueProvider]) and tell it when one opens ([CueReceiver]).
 *
 * When a moment comes, each knot waiting for it arrives as a notification and moves to now on the
 * thread, an ordinary knot again.
 */
object Cues {
    const val ACTION = "com.cerniera.nodo.CUE"
    private const val WIFI = "com.cerniera.nodo.WIFI"
    private fun prefs(c: Context) = Store.store(c)

    /** An installed app whose name matches what was typed: exact first, then the start of a name. */
    fun findApp(c: Context, name: String): Pair<String, String>? = runCatching {
        val pm = c.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }.distinctBy { it.first }
        val n = name.trim().lowercase()
        apps.firstOrNull { it.second.lowercase() == n }
            ?: apps.filter { it.second.lowercase().startsWith(n) }.minByOrNull { it.second.length }
            // "Spotify Music" typed, "Spotify" installed
            ?: apps.firstOrNull { n.startsWith(it.second.lowercase() + " ") }
    }.getOrNull()

    /**
     * A moment has come: [what] is "home" or "app:<package>". Every open knot waiting for it is
     * notified and moved to now. Returns how many.
     */
    fun fire(c: Context, what: String, only: Set<Int>? = null): Int {
        val knots = Store.load(c)
        val now = System.currentTimeMillis()
        val due = knots.filter { k ->
            val cue = k.cue
            k.done == null && cue != null && (only == null || k.id in only) &&
                (cue == what || (what.startsWith("app:") && Knot.appOf(cue) == what.removePrefix("app:")))
        }
        if (due.isEmpty()) return 0
        due.forEach { k ->
            val why = if (k.cue == Knot.HOME) "You're home" else "Opened " + k.cue!!.substringAfter('|')
            k.cue = null; k.due = now
            Reminders.notify(c, k, why)
        }
        Store.save(c, knots)
        android.util.Log.i("Nodo", "moment $what untied ${due.size} knot(s)")
        return due.size
    }

    /** Packages that knots are waiting for, for the apps that can see what's in front. */
    fun waitingApps(c: Context): Set<String> = Store.load(c).mapNotNull { if (it.done == null) Knot.appOf(it.cue) else null }.toSet()

    // ---- Home, by its Wi-Fi ------------------------------------------------------------------

    fun homeNetwork(c: Context): String? = prefs(c).getString("home_wifi", null)
    fun lastHome(c: Context): Long = prefs(c).getLong("home_seen", 0L)
    fun lastWifi(c: Context): String = prefs(c).getString("wifi_note", "").orEmpty()

    fun canReadWifi(c: Context) = c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Location "Allow all the time": what lets Nodo read the Wi-Fi's name while it is closed. */
    fun canReadInBackground(c: Context) = canReadWifi(c) && c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun locationOn(c: Context) = runCatching { c.getSystemService(android.location.LocationManager::class.java)?.isLocationEnabled ?: true }.getOrDefault(true)
    @Suppress("DEPRECATION")
    fun wifiOn(c: Context) = runCatching { c.applicationContext.getSystemService(WifiManager::class.java)?.isWifiEnabled ?: true }.getOrDefault(true)

    /** What stops home being noticed right now, in a few words, or null when all is well. */
    enum class Problem { NO_PERMISSION, NOT_ALWAYS, LOCATION_OFF, WIFI_OFF }

    fun problem(c: Context): Problem? = when {
        homeNetwork(c) == null -> null
        !canReadWifi(c) -> Problem.NO_PERMISSION
        !canReadInBackground(c) -> Problem.NOT_ALWAYS
        !locationOn(c) -> Problem.LOCATION_OFF
        !wifiOn(c) -> Problem.WIFI_OFF
        else -> null
    }

    // ---- The backup: knots tied away from home, untied when Nodo opens at home -----------------

    private fun ids(c: Context, key: String) = prefs(c).getStringSet(key, emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet()
    private fun putIds(c: Context, key: String, v: Set<Int>) = prefs(c).edit().putStringSet(key, v.map { it.toString() }.toSet()).apply()

    /** Notes, for each new home knot, whether it was tied away from home. Called on every save. */
    fun classify(c: Context, knots: List<Knot>) {
        val home = knots.filter { it.done == null && it.cue == Knot.HOME }.map { it.id }.toSet()
        val known = ids(c, "home_known"); val away = ids(c, "home_away")
        val fresh = home - known
        if (fresh.isEmpty() && known.all { it in home } && away.all { it in home }) return
        val atHome = homeNetwork(c)?.let { it == currentWifi(c) } ?: false
        putIds(c, "home_known", known.intersect(home) + fresh)
        putIds(c, "home_away", away.intersect(home) + if (atHome) emptySet() else fresh)
    }

    /** Nodo opened: if this is home's Wi-Fi, the knots tied away from home come due. Returns how many. */
    fun checkHere(c: Context): Int {
        val home = homeNetwork(c) ?: return 0
        if (!canReadWifi(c) || currentWifi(c) != home) return 0
        val away = ids(c, "home_away")
        if (away.isEmpty()) return 0
        return fire(c, Knot.HOME, only = away)
    }

    /** The Wi-Fi network the phone is on now, by name, or null (not on Wi-Fi, or its name is hidden from Nodo). */
    @Suppress("DEPRECATION")
    fun currentWifi(c: Context): String? = runCatching {
        val info = c.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo ?: return null
        info.ssid?.trim('"')?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" && it != WifiManager.UNKNOWN_SSID.trim('"') }
    }.getOrNull()

    /** Remembers the Wi-Fi the phone is on as home. Returns its name, or null if it couldn't be read. */
    fun setHome(c: Context): String? {
        val name = currentWifi(c) ?: return null
        prefs(c).edit().putString("home_wifi", name).putLong("home_seen", System.currentTimeMillis()).apply()
        watch(c)
        return name
    }

    fun forgetHome(c: Context) { prefs(c).edit().remove("home_wifi").apply(); watch(c) }

    /**
     * Asks the phone to wake Nodo whenever Wi-Fi connects, while a home is set and a knot waits for
     * it. A pending intent, so Nodo needn't be running. Set again on boot and on every save.
     */
    fun watch(c: Context) {
        val cm = c.getSystemService(ConnectivityManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(c, 77, Intent(c, CueReceiver::class.java).setAction(WIFI), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        runCatching { cm.unregisterNetworkCallback(pi) }
        val waiting = Store.load(c).any { it.done == null && it.cue == Knot.HOME }
        if (homeNetwork(c) == null || !waiting) return
        runCatching {
            cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), pi)
        }.onFailure { android.util.Log.w("Nodo", "could not watch Wi-Fi", it) }
    }

    /** Wi-Fi connected: if it's home's (and home wasn't just seen), the home knots come due. */
    fun wifiChanged(c: Context) {
        val home = homeNetwork(c) ?: return
        val now = System.currentTimeMillis()
        val name = currentWifi(c)
        prefs(c).edit().putString("wifi_note", (name ?: "a network whose name Nodo can't read") + " at " + Words.hm(now)).apply()
        if (name != home) return
        // A brief drop and reconnect at home isn't arriving home
        val seen = lastHome(c)
        prefs(c).edit().putLong("home_seen", now).apply()
        if (now - seen < 20 * MIN) return
        fire(c, Knot.HOME)
    }
}

/**
 * Moments from outside: Wi-Fi connecting (from [Cues.watch]), and the family's apps saying an app
 * has opened: `am broadcast -a com.cerniera.nodo.CUE -p com.cerniera.nodo --es app <package>`.
 * Any app may send it; the worst it can do is bring a knot forward early.
 */
class CueReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val pending = goAsync()
        Thread {
            try {
                when {
                    i.action == "com.cerniera.nodo.WIFI" -> Cues.wifiChanged(c)
                    i.getStringExtra("app") != null -> Cues.fire(c, "app:" + i.getStringExtra("app"))
                    i.getStringExtra("place") == Knot.HOME -> Cues.fire(c, Knot.HOME)
                }
            } finally { pending.finish() }
        }.start()
    }
}

/**
 * `content://com.cerniera.nodo.cues/apps`: one row (`package`) per app a knot is waiting for. Read
 * by Fold Taskbar, so it tells Nodo only about apps that matter. Nothing else is shared: no words.
 */
class CueProvider : ContentProvider() {
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        if (uri.lastPathSegment == "next") return next()
        val out = MatrixCursor(arrayOf("package"))
        context?.let { c -> Cues.waitingApps(c).forEach { out.addRow(arrayOf(it)) }; out.setNotificationUri(c.contentResolver, URI) }
        return out
    }
    override fun getType(uri: Uri) = "vnd.android.cursor.dir/vnd.cerniera.cue"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

    /**
     * The knot that matters most now, for Momento's notes in Fold Widgets (22 Nov 2026): the
     * earliest late knot, else the next one due within two hours, else none. Its words leave Nodo
     * only here, and only to apps holding com.cerniera.nodo.permission.NEXT (the manifest's
     * path-permission), which only the family's apps ask for.
     */
    private fun next(): Cursor {
        val out = MatrixCursor(arrayOf("id", "text", "due", "late", "weight", "late_count"))
        val c = context ?: return out
        val now = System.currentTimeMillis()
        val open = Store.load(c).filter { it.open && it.cue == null && !it.pending }
        val late = open.filter { it.due < now }.sortedBy { it.due }
        val k = late.firstOrNull() ?: open.filter { it.due - now in 0..2 * HOUR }.minByOrNull { it.due }
        k?.let { out.addRow(arrayOf<Any>(it.id, it.text, it.due, if (it.due < now) 1 else 0, it.weight, late.size)) }
        out.setNotificationUri(c.contentResolver, NEXT)
        return out
    }

    companion object {
        val URI: Uri = Uri.parse("content://com.cerniera.nodo.cues/apps")
        val NEXT: Uri = Uri.parse("content://com.cerniera.nodo.cues/next")
    }
}
