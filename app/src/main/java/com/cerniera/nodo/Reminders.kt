package com.cerniera.nodo

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cerniera.kit.Kit

/**
 * One system alarm per knot still to come. Exact when the phone allows it (Alarms & reminders,
 * SCHEDULE_EXACT_ALARM, granted once from Nodo's settings), otherwise the nearest the system will
 * give. When a knot comes due it arrives as a notification with Done and Later.
 */
object Reminders {
    const val CHANNEL = "knots"
    private const val FIRE = "com.cerniera.nodo.FIRE"

    fun canExact(c: Context) = c.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

    fun channel(c: Context) {
        c.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Knots coming due", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A knot on the thread has reached now"
                enableVibration(true)
            })
    }

    private fun pending(c: Context, id: Int, flags: Int = PendingIntent.FLAG_UPDATE_CURRENT): PendingIntent? =
        PendingIntent.getBroadcast(c, id, Intent(c, ReminderReceiver::class.java).setAction(FIRE).putExtra("id", id), PendingIntent.FLAG_IMMUTABLE or flags)

    /** Sets an alarm for every knot still to come and cancels the alarms of knots that are gone or done. */
    fun arm(c: Context, knots: List<Knot>) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val prefs = c.getSharedPreferences("nodo", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        // A knot waiting for a moment has no alarm: the moment unties it (Cues.kt)
        val want = knots.filter { !it.pending && it.done == null && it.cue == null && it.due > now }
        val was = prefs.getStringSet("armed", emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet()
        (was - want.map { it.id }.toSet()).forEach { id -> pending(c, id, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it); it.cancel() } }
        val exact = canExact(c)
        android.util.Log.i("Nodo", "arming ${want.size} knot(s), exact=$exact")
        for (k in want) {
            val pi = pending(c, k.id) ?: continue
            runCatching {
                if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, k.due, pi)
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, k.due, pi)
            }.onFailure { android.util.Log.w("Nodo", "could not arm knot ${k.id}", it) }
        }
        prefs.edit().putStringSet("armed", want.map { it.id.toString() }.toSet()).apply()
    }

    fun arm(c: Context) = arm(c, Store.load(c))

    /** The notification for a knot that has reached now. */
    fun notify(c: Context, k: Knot, cueWhy: String? = null) {
        channel(c)
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        fun action(label: String, what: String, mins: Int = 0): Notification.Action {
            val pi = PendingIntent.getBroadcast(c, k.id * 8 + what.length + mins,
                Intent(c, ActionReceiver::class.java).setAction(what).putExtra("id", k.id).putExtra("mins", mins),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return Notification.Action.Builder(null, label, pi).build()
        }
        val open = PendingIntent.getActivity(c, k.id, Intent(c, MainActivity::class.java).putExtra("knot", k.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(Kit.Fixed.CHAMPAGNE)
            .setContentTitle(k.text)
            .setContentText(when { cueWhy != null -> cueWhy; k.weight == 3 -> "Tight knot · now"; else -> "Now · " + Words.hm(k.due) })
            .setCategory(Notification.CATEGORY_REMINDER)
            .setWhen(k.due).setShowWhen(true)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(action("Done", ActionReceiver.DONE))
            .addAction(action("15 min", ActionReceiver.LATER, 15))
            .addAction(action("1 hour", ActionReceiver.LATER, 60))
            .build()
        runCatching { nm.notify(k.id, n) }.onFailure { android.util.Log.w("Nodo", "notify failed", it) }
    }
}

/** A knot's alarm has gone off: say so, unless it was done in the meantime. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val id = i.getIntExtra("id", -1)
        val k = Store.load(c).firstOrNull { it.id == id }
        android.util.Log.i("Nodo", "knot $id came due: ${if (k == null) "gone" else if (k.done != null) "already done" else "notifying"}")
        if (k != null && k.done == null) Reminders.notify(c, k)
        // The knot is now overdue: the widget shows it against now
        NodoWidget.refresh(c)
    }
}

/** Done and Later, from the notification, without opening Nodo. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        // A tap on the widget's Done also tells the widget where it sits
        NodoWidget.seatFrom(c, i)
        val id = i.getIntExtra("id", -1)
        val knots = Store.load(c)
        val k = knots.firstOrNull { it.id == id } ?: return
        when (i.action) {
            DONE -> Store.done(knots, k)
            LATER -> { k.due = snap(System.currentTimeMillis() + i.getIntExtra("mins", 15) * MIN, System.currentTimeMillis()); k.done = null }
        }
        Store.save(c, knots)
        c.getSystemService(NotificationManager::class.java)?.cancel(id)
    }

    companion object {
        const val DONE = "com.cerniera.nodo.DONE"
        const val LATER = "com.cerniera.nodo.LATER"
    }
}

/** Alarms don't survive a restart, a clock change or an update: set them again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { Reminders.arm(c); NodoWidget.refresh(c); Cues.watch(c) }
}
