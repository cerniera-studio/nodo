package com.cerniera.nodo

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * Nodo's words: reading a time out of what you type ("Friday 9 call the bank") and saying when a
 * knot is due ("In 40 min", "Tomorrow 09:00"). All on the phone, no model and no network.
 */
object Words {
    private val DN = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private val MN = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    class Parsed(val text: String, val due: Long?)

    private fun zdt(t: Long, z: ZoneId) = ZonedDateTime.ofInstant(Instant.ofEpochMilli(t), z)
    fun dayStart(t: Long, z: ZoneId = ZoneId.systemDefault()) = zdt(t, z).toLocalDate().atStartOfDay(z).toInstant().toEpochMilli()
    fun hm(t: Long, z: ZoneId = ZoneId.systemDefault()) = zdt(t, z).let { "%02d:%02d".format(it.hour, it.minute) }
    fun dayName(t: Long, z: ZoneId = ZoneId.systemDefault()) = DN[zdt(t, z).dayOfWeek.value - 1]
    fun dateLine(t: Long, z: ZoneId = ZoneId.systemDefault()) = zdt(t, z).let { "${DN[it.dayOfWeek.value - 1]} ${it.dayOfMonth} ${MN[it.monthValue - 1]}" }

    /** When a knot is due (or was done), as a short line under its words. */
    fun whenLine(k: Knot, now: Long, z: ZoneId = ZoneId.systemDefault()): String {
        k.done?.let { d -> return "Done " + (if (dayStart(d, z) == dayStart(now, z)) hm(d, z) else dayName(d, z) + " " + hm(d, z)) }
        val d = k.due - now
        if (d < 0) {
            val a = -d
            return when {
                a < HOUR -> "${maxOf(1, Math.round(a / MIN.toDouble()).toInt())} min overdue"
                a < DAY -> "${Math.round(a / HOUR.toDouble())} h overdue"
                else -> "Overdue since ${dayName(k.due, z)}"
            }
        }
        if (d < HOUR) return "In ${maxOf(1, Math.round(d / MIN.toDouble()).toInt())} min"
        val days = ChronoUnit.DAYS.between(zdt(now, z).toLocalDate(), zdt(k.due, z).toLocalDate())
        return when {
            days == 0L -> "Today ${hm(k.due, z)}"
            days == 1L -> "Tomorrow ${hm(k.due, z)}"
            days < 7 -> "${dayName(k.due, z)} ${hm(k.due, z)}"
            else -> "${dateLine(k.due, z)} · ${hm(k.due, z)}"
        }
    }

    private val I = RegexOption.IGNORE_CASE

    /**
     * Reads a time out of [raw] and returns the words left over. A date with no time is 09:00; a time
     * already past today is tomorrow's; 1 to 7 without am or pm reads as the afternoon. No time at
     * all leaves [Parsed.due] null, so the knot stays where it was tied.
     */
    fun parse(raw: String, now: Long, z: ZoneId = ZoneId.systemDefault()): Parsed {
        var s = " " + raw.trim() + " "
        val n = zdt(now, z)
        var date: LocalDate? = null
        var h: Int? = null
        var m = 0
        var rel: Long? = null
        fun cut(re: Regex): MatchResult? = re.find(s)?.also { s = s.replaceRange(it.range, " ") }
        fun ampm(hour: Int, mer: String?): Int = if (mer == null) hour else hour % 12 + (if (mer.lowercase() == "pm") 12 else 0)

        cut(Regex("""\sin\s+(\d+|an?|half an?)\s*(min(?:ute)?s?|m|hours?|hrs?|h|days?|d|weeks?|w)\b""", I))?.let { r ->
            val qs = r.groupValues[1].lowercase()
            val q = if (qs.startsWith("half")) 0.5 else if (qs == "a" || qs == "an") 1.0 else qs.toDouble()
            val unit = when (r.groupValues[2].lowercase()[0]) { 'm' -> MIN; 'h' -> HOUR; 'd' -> DAY; else -> 7 * DAY }
            rel = (q * unit).toLong()
        }
        val wd = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
        var r: MatchResult?
        r = cut(Regex("""\s(today|tonight|tomorrow|tmrw)\b""", I))
        if (r != null) {
            val w = r.groupValues[1].lowercase()
            date = n.toLocalDate().let { if (w.startsWith("tom") || w == "tmrw") it.plusDays(1) else it }
            if (w == "tonight") h = 19
        } else {
            r = cut(Regex("""\snext\s+week\b""", I))
            if (r != null) date = n.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY))
            else {
                r = cut(Regex("""\s(?:on\s+)?(next\s+)?(sun|mon|tue|tues|wed|thu|thur|thurs|fri|sat)(?:day|nesday|rsday|urday|sday)?\b""", I))
                if (r != null) {
                    val want = DayOfWeek.of(wd.indexOf(r.groupValues[2].lowercase().take(3)) + 1)
                    var d = n.toLocalDate().with(TemporalAdjusters.next(want))
                    if (r.groupValues[1].isNotEmpty()) d = d.plusWeeks(1)
                    date = d
                    // "Friday 9": a bare hour straight after the day
                    val t = Regex("""^\s*(\d{1,2})(?:[:.](\d{2}))?\s*(am|pm)?\b""", I).find(s)
                    if (t != null && !Regex("""^\s*\d{1,2}\s*(min|h|d|w)""", I).containsMatchIn(s)) {
                        s = s.replaceRange(t.range, " ")
                        var hh = t.groupValues[1].toInt()
                        m = t.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
                        val mer = t.groupValues[3].takeIf { it.isNotEmpty() }
                        hh = if (mer != null) ampm(hh, mer) else if (hh <= 7) hh + 12 else hh
                        h = hh
                    }
                } else {
                    r = cut(Regex("""\s(?:on\s+)?(\d{1,2})(?:st|nd|rd|th)?\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\b""", I))
                    if (r != null) {
                        val month = MN.indexOfFirst { it.equals(r.groupValues[2], true) } + 1
                        var d = runCatching { LocalDate.of(n.year, month, r.groupValues[1].toInt()) }.getOrNull()
                        if (d != null && d.isBefore(n.toLocalDate())) d = d.plusYears(1)
                        date = d
                    }
                }
            }
        }
        if (h == null) {
            r = cut(Regex("""\s(?:at\s+)?(\d{1,2})[:.](\d{2})\s*(am|pm)?\b""", I))
            if (r != null) { h = ampm(r.groupValues[1].toInt(), r.groupValues[3].takeIf { it.isNotEmpty() }); m = r.groupValues[2].toInt() }
            else {
                r = cut(Regex("""\s(?:at\s+)?(\d{1,2})\s*(am|pm)\b""", I))
                if (r != null) h = ampm(r.groupValues[1].toInt(), r.groupValues[2])
                else {
                    r = cut(Regex("""\sat\s+(\d{1,2})\b""", I))
                    if (r != null) { val hh = r.groupValues[1].toInt(); h = if (hh <= 7) hh + 12 else hh }
                    else if (cut(Regex("""\s(noon|midday)\b""", I)) != null) h = 12
                    else {
                        r = cut(Regex("""\s(this\s+)?(morning|afternoon|evening)\b""", I))
                        if (r != null) h = mapOf("morning" to 9, "afternoon" to 14, "evening" to 18)[r.groupValues[2].lowercase()]
                    }
                }
            }
        }
        val hour = h?.coerceIn(0, 23)
        val due: Long? = when {
            rel != null -> now + rel!!
            date != null -> date!!.atTime(LocalTime.of(hour ?: 9, if (hour != null) m.coerceIn(0, 59) else 0)).atZone(z).toInstant().toEpochMilli()
            hour != null -> {
                var t = n.toLocalDate().atTime(LocalTime.of(hour, m.coerceIn(0, 59))).atZone(z)
                if (!t.isAfter(n)) t = t.plusDays(1)
                t.toInstant().toEpochMilli()
            }
            else -> null
        }
        val text = s.replace(Regex("""\s+(at|on|by)\s*$""", I), " ").replace(Regex("""^\s*(at|on|by)\s+""", I), " ")
            .replace(Regex("""\s{2,}"""), " ").trim()
        return Parsed(if (text.isEmpty()) "" else text[0].uppercase() + text.substring(1), due)
    }
}
