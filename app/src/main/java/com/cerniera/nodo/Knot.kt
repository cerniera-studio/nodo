package com.cerniera.nodo

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * A knot on the thread: one thing to remember. [due] and [done] are epoch milliseconds.
 * [weight] is 1 (Light), 2 (Firm) or 3 (Tight, a double knot). [repeat] is none, daily, weekdays
 * or weekly; marking a repeating knot done ties a fresh one at its next turn.
 */
class Knot(
    var id: Int,
    var text: String,
    var due: Long,
    var weight: Int = 1,
    var done: Long? = null,
    var repeat: String = NONE,
    /** Tied but not yet named: a new knot waiting for its words. Never stored. */
    var pending: Boolean = false,
) {
    val open get() = done == null
    fun late(now: Long) = done == null && due < now

    companion object {
        const val NONE = "none"
        const val DAILY = "daily"
        const val WEEKDAYS = "weekdays"
        const val WEEKLY = "weekly"
        val REPEATS = listOf(NONE, DAILY, WEEKDAYS, WEEKLY)

        /** The next turn of a repeating knot after [due], later than [now]. */
        fun nextTurn(due: Long, repeat: String, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
            if (repeat == NONE) return null
            var d = ZonedDateTime.ofInstant(Instant.ofEpochMilli(due), zone)
            do {
                d = when (repeat) {
                    DAILY -> d.plusDays(1)
                    WEEKLY -> d.plusWeeks(1)
                    else -> { var n = d.plusDays(1); while (n.dayOfWeek == DayOfWeek.SATURDAY || n.dayOfWeek == DayOfWeek.SUNDAY) n = n.plusDays(1); n }
                }
            } while (d.toInstant().toEpochMilli() <= now)
            return d.toInstant().toEpochMilli()
        }
    }
}

/** Snaps a time to a sensible step: 5 minutes within two hours, 15 within a day, 30 beyond. */
fun snap(t: Long, now: Long): Long {
    val d = t - now
    val step = if (d < 2 * HOUR) 5 * MIN else if (d < DAY) 15 * MIN else 30 * MIN
    return Math.round(t.toDouble() / step).toLong() * step
}

const val MIN = 60_000L
const val HOUR = 60 * MIN
const val DAY = 24 * HOUR
