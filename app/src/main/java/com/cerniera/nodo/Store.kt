package com.cerniera.nodo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/**
 * The thread, kept on the phone in the app's preferences as one JSON list. Every save re-arms the
 * reminders, so the alarms always match what the thread shows.
 */
object Store {
    private fun prefs(c: Context) = c.getSharedPreferences("nodo", Context.MODE_PRIVATE)
    /** Nodo's preferences, where the Kit's Backdrop also keeps each widget's seat. */
    fun store(c: Context) = prefs(c)

    fun load(c: Context): MutableList<Knot> = runCatching {
        val a = JSONArray(prefs(c).getString("knots", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Knot(o.getInt("id"), o.getString("text"), o.getLong("due"), o.optInt("weight", 1),
                if (o.has("done")) o.getLong("done") else null, o.optString("repeat", Knot.NONE),
                cue = o.optString("cue").ifEmpty { null })
        }.toMutableList()
    }.getOrDefault(mutableListOf())

    fun save(c: Context, knots: List<Knot>) {
        val a = JSONArray()
        // Done knots older than a fortnight have slipped off the far end of the thread
        val keepFrom = System.currentTimeMillis() - 14 * DAY
        knots.filter { !it.pending && (it.done == null || it.done!! > keepFrom) }.forEach { k ->
            a.put(JSONObject().put("id", k.id).put("text", k.text).put("due", k.due).put("weight", k.weight)
                .put("repeat", k.repeat).apply { k.done?.let { put("done", it) }; k.cue?.let { put("cue", it) } })
        }
        prefs(c).edit().putString("knots", a.toString()).apply()
        Reminders.arm(c, knots)
        NodoWidget.refresh(c)
        // Knots waiting for a moment: watch Wi-Fi for home, and tell Fold Taskbar which apps matter
        Cues.watch(c)
        Cues.classify(c, knots)
        runCatching { c.contentResolver.notifyChange(CueProvider.URI, null) }
        // Momento (Fold Widgets) shows the knot that matters most: tell it to look again
        runCatching { c.contentResolver.notifyChange(CueProvider.NEXT, null) }
        runCatching { c.contentResolver.notifyChange(CueProvider.WEEK, null) }
        runCatching { c.sendBroadcast(android.content.Intent("com.cerniera.MOMENTO_REFRESH").setPackage("com.fergusonconsultancy.foldwidgets")) }
    }

    fun newId(knots: List<Knot>) = (knots.maxOfOrNull { it.id } ?: 0) + 1

    /** Marks [k] done; a repeating knot passes its repeat on to a fresh knot at the next turn. */
    fun done(knots: MutableList<Knot>, k: Knot, now: Long = System.currentTimeMillis()): Knot? {
        k.done = now
        val next = Knot.nextTurn(k.due, k.repeat, now) ?: return null
        val fresh = Knot(newId(knots), k.text, next, k.weight, null, k.repeat)
        k.repeat = Knot.NONE
        knots += fresh
        return fresh
    }

    // ---- Settings ---------------------------------------------------------------------------

    fun sound(c: Context) = prefs(c).getBoolean("sound", true)
    fun haptics(c: Context) = prefs(c).getBoolean("haptics", true)
    fun setSound(c: Context, v: Boolean) = prefs(c).edit().putBoolean("sound", v).apply()
    fun setHaptics(c: Context, v: Boolean) = prefs(c).edit().putBoolean("haptics", v).apply()
    /** The widget's finish: Fold Widgets' ten, from the Kit. Vetro, as in Fold Widgets, until one is chosen. */
    fun widgetFinish(c: Context): com.cerniera.kit.Finish = runCatching { com.cerniera.kit.Finish.valueOf(prefs(c).getString("widgetFinish", null)!!) }.getOrDefault(com.cerniera.kit.Finish.VETRO)
    fun setWidgetFinish(c: Context, f: com.cerniera.kit.Finish) { prefs(c).edit().putString("widgetFinish", f.name).apply(); NodoWidget.refresh(c) }
    fun seenHint(c: Context) = prefs(c).getBoolean("hint", false)
    fun setSeenHint(c: Context) = prefs(c).edit().putBoolean("hint", true).apply()

    /** Example knots, for the emulator's screenshots only (opened with --es seed examples). */
    fun examples(now: Long = System.currentTimeMillis(), z: ZoneId = ZoneId.systemDefault()): MutableList<Knot> {
        fun at(d: Long, h: Int, m: Int) = LocalDate.now(z).plusDays(d).atTime(h, m).atZone(z).toInstant().toEpochMilli()
        var id = 1
        fun k(text: String, due: Long, w: Int = 1, done: Long? = null, rep: String = Knot.NONE) = Knot(id++, text, due, w, done, rep)
        return mutableListOf(
            k("Send S166 status note", now - 2 * HOUR - 10 * MIN, 3),
            k("DataCamp: window functions", now - 5 * HOUR, 1, now - 4 * HOUR),
            k("Reply to the recruiter", now - 26 * HOUR, 2, now - 25 * HOUR),
            k("Call back about the KYC role", now + 40 * MIN, 2),
            k("Gym: legs", now + 3 * HOUR, 1),
            k("Chase the PSC filing", at(1, 9, 0), 2),
            k("Book the Giulietta's service", at(2, 12, 30), 1),
            k("Ring Mum", at(3, 18, 0), 2, rep = Knot.WEEKLY),
            k("Renew the car insurance", at(6, 9, 0), 3),
            k("Dentist", at(11, 10, 15), 1),
        )
    }
}
