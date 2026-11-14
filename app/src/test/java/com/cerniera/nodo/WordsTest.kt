package com.cerniera.nodo

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** The date reader, against the phrases the preview was checked with (14 Nov 2026, a Saturday). */
class WordsTest {
    private val z = ZoneId.of("Europe/London")
    private val now = LocalDateTime.of(2026, 11, 14, 10, 15).atZone(z).toInstant().toEpochMilli()
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(z).toInstant().toEpochMilli()

    private fun check(input: String, text: String, due: Long?) {
        val p = Words.parse(input, now, z)
        assertEquals(input, text, p.text)
        assertEquals(input, due, p.due)
    }

    @Test fun weekdayAndBareHour() = check("Friday 9 call Delta", "Call Delta", at(2026, 11, 20, 9, 0))
    @Test fun tomorrowAt3pm() = check("call the bank tomorrow at 3pm", "Call the bank", at(2026, 11, 15, 15, 0))
    @Test fun inMinutes() = check("in 20 min check oven", "Check oven", now + 20 * MIN)
    @Test fun nextWeek() = check("Chase filing next week", "Chase filing", at(2026, 11, 16, 9, 0))
    @Test fun dateAndTime() = check("Dentist 17 Nov 10:30", "Dentist", at(2026, 11, 17, 10, 30))
    @Test fun tonight() = check("ring mum tonight", "Ring mum", at(2026, 11, 14, 19, 0))
    @Test fun noTime() = check("Pay rent", "Pay rent", null)
    @Test fun atFiveIsAfternoon() = check("gym at 5", "Gym", at(2026, 11, 14, 17, 0))
    @Test fun pastTimeIsTomorrow() = check("vitamins 8am", "Vitamins", at(2026, 11, 15, 8, 0))
    @Test fun nextFriday() = check("next friday renew insurance", "Renew insurance", at(2026, 11, 27, 9, 0))

    @Test fun weekdaysSkipTheWeekend() {
        val fri = at(2026, 11, 20, 9, 0)
        assertEquals(at(2026, 11, 23, 9, 0), Knot.nextTurn(fri, Knot.WEEKDAYS, fri, z))
    }

    @Test fun whenLines() {
        assertEquals("In 40 min", Words.whenLine(Knot(1, "x", now + 40 * MIN), now, z))
        assertEquals("Tomorrow 09:00", Words.whenLine(Knot(1, "x", at(2026, 11, 15, 9, 0)), now, z))
        assertEquals("2 h overdue", Words.whenLine(Knot(1, "x", now - 2 * HOUR - 10 * MIN), now, z))
    }
}
