package me.parham1995.notes.markdown

import java.time.LocalDate
import java.util.Locale

/**
 * A day of the Solar Hijri (Jalali) calendar, Iran's own: a day of it from a
 * Gregorian day and back, its month and weekday names, and Persian digits.
 *
 * The arithmetic is jalaali-js's (Borkowski's leap years, as Iran's calendar
 * sets them), good from 1 to 3177 of the Jalali years. A port of Daftar for
 * Linux's `jalali.rs`, so the apps agree day for day; ICU's `PersianCalendar`
 * is not used, because its leap years can differ from Iran's
 * (`docs/specs/jalali-recurrence.md` in notes-linux).
 */
data class Jalali(
    val year: Int,
    val month: Int,
    val day: Int,
) {
    /** The Gregorian day, or null for a day the calendar does not have. */
    fun toDate(): LocalDate? {
        val info = yearOf(year.toLong()) ?: return null
        val length = monthLength(year, month) ?: return null
        if (day < 1 || day > length) return null
        val m = month.toLong()
        val first = julian(info.gregorian, MARCH, info.march)
        val jdn = first + (m - 1) * LONG_MONTH - m / FIRST_SHORT * (m - FIRST_SHORT) + day - 1
        return LocalDate.ofEpochDay(jdn - julian(EPOCH_YEAR, 1, 1))
    }

    val monthName: String get() = MONTHS[month.coerceIn(1, MONTHS.size) - 1]

    /** `1405-08-05`. */
    fun toIso(): String = "%04d-%02d-%02d".format(Locale.ROOT, year, month, day)

    companion object {
        val MONTHS =
            listOf(
                "فروردین",
                "اردیبهشت",
                "خرداد",
                "تیر",
                "مرداد",
                "شهریور",
                "مهر",
                "آبان",
                "آذر",
                "دی",
                "بهمن",
                "اسفند",
            )

        /** From Monday, as `DayOfWeek` counts. */
        val WEEKDAYS =
            listOf(
                "دوشنبه",
                "سه‌شنبه",
                "چهارشنبه",
                "پنجشنبه",
                "جمعه",
                "شنبه",
                "یکشنبه",
            )

        /** The Jalali day [date] is. */
        fun of(date: LocalDate): Jalali {
            val jdn = julian(date.year.toLong(), date.monthValue.toLong(), date.dayOfMonth.toLong())
            val gregorian = gregorianYear(jdn)
            var year = gregorian - GREGORIAN_OFFSET
            val march = yearOf(year)?.march ?: DEFAULT_MARCH
            var k = jdn - julian(gregorian, MARCH, march)
            if (k >= 0) {
                if (k <= FIRST_HALF - 1) {
                    return Jalali(year.toInt(), (1 + k / LONG_MONTH).toInt(), (k % LONG_MONTH + 1).toInt())
                }
                k -= FIRST_HALF
            } else {
                year -= 1
                k += SECOND_HALF
                if (yearOf(year)?.leap == true) k += 1
            }
            return Jalali(year.toInt(), (FIRST_SHORT + k / SHORT_MONTH).toInt(), (k % SHORT_MONTH + 1).toInt())
        }

        /**
         * The days of [month] in [year]: 31 in the first six months, 30 in the
         * next five, and Esfand's 30 in a leap year or 29. Null for a month or
         * a year the calendar does not have.
         */
        fun monthLength(
            year: Int,
            month: Int,
        ): Int? {
            val info = yearOf(year.toLong()) ?: return null
            return when (month) {
                in 1..6 -> 31
                in 7..11 -> 30
                12 -> if (info.leap) 30 else 29
                else -> null
            }
        }

        /**
         * [months] Jalali months after [date], on the same day of the month or
         * the last day of a shorter one (Shahrivar 31 and a month is Mehr 30):
         * the step of `🔁 every jalali month`. Null past the calendar's range.
         */
        fun addingMonths(
            date: LocalDate,
            months: Long,
        ): LocalDate? {
            if (months !in -MAX_MONTHS..MAX_MONTHS) return null
            val from = of(date)
            val index = from.year.toLong() * 12 + from.month - 1 + months
            val year = Math.floorDiv(index, 12L)
            if (year !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) return null
            val month = Math.floorMod(index, 12L).toInt() + 1
            val length = monthLength(year.toInt(), month) ?: return null
            return Jalali(year.toInt(), month, minOf(from.day, length)).toDate()
        }

        /** `سه‌شنبه ۵ آبان ۱۴۰۵`: the day as it is said in Persian. */
        fun long(date: LocalDate): String {
            val jalali = of(date)
            val weekday = WEEKDAYS[date.dayOfWeek.value - 1]
            return persianDigits("$weekday ${jalali.day} ${jalali.monthName} ${jalali.year}")
        }

        /**
         * A day typed as `1405-08-05`, in Latin or Persian digits, as the
         * Gregorian day it is; a year from 1700 on is not a Jalali year.
         */
        fun parse(text: String): LocalDate? {
            val parts = latinDigits(text.trim()).split('-', '/')
            if (parts.size != 3) return null
            val year = parts[0].toIntOrNull() ?: return null
            val month = parts[1].toIntOrNull() ?: return null
            val day = parts[2].toIntOrNull() ?: return null
            if (year >= GREGORIAN_ONLY) return null
            return Jalali(year, month, day).toDate()
        }

        /** The digits 0-9 as Persian ones. */
        fun persianDigits(text: String): String =
            buildString(text.length) {
                for (c in text) append(if (c in '0'..'9') '۰' + (c - '0') else c)
            }

        /** Persian and Arabic digits as ASCII ones. */
        fun latinDigits(text: String): String =
            buildString(text.length) {
                for (c in text) append(latinDigit(c))
            }

        /** A Persian or Arabic digit as its ASCII one; anything else as it is. */
        fun latinDigit(c: Char): Char =
            when (c) {
                in '۰'..'۹' -> '0' + (c - '۰')
                in '٠'..'٩' -> '0' + (c - '٠')
                else -> c
            }

        /** Whether a year is leap, the Gregorian year it starts in, and the day of March its first day falls on. */
        private data class YearInfo(
            val leap: Boolean,
            val gregorian: Long,
            val march: Long,
        )

        private val BREAKS =
            longArrayOf(
                -61,
                9,
                38,
                199,
                426,
                686,
                756,
                818,
                1111,
                1181,
                1210,
                1635,
                2060,
                2097,
                2192,
                2262,
                2324,
                2394,
                2456,
                3178,
            )

        private fun yearOf(year: Long): YearInfo? {
            if (year < BREAKS.first() || year >= BREAKS.last()) return null
            val gregorian = year + GREGORIAN_OFFSET
            var leapJ = -14L
            var previous = BREAKS.first()
            var jump = 0L
            for (i in 1 until BREAKS.size) {
                val next = BREAKS[i]
                jump = next - previous
                if (year < next) break
                leapJ += jump / CYCLE * 8 + jump % CYCLE / 4
                previous = next
            }
            var n = year - previous
            leapJ += n / CYCLE * 8 + (n % CYCLE + 3) / 4
            if (jump % CYCLE == 4L && jump - n == 4L) leapJ += 1
            val leapG = gregorian / 4 - (gregorian / 100 + 1) * 3 / 4 - 150
            val march = 20 + leapJ - leapG
            if (jump - n < 6) n = n - jump + (jump + 4) / CYCLE * CYCLE
            var leap = ((n + 1) % CYCLE - 1) % 4
            if (leap == -1L) leap = 4
            return YearInfo(leap == 0L, gregorian, march)
        }

        /** The Julian day number of a Gregorian day. */
        private fun julian(
            year: Long,
            month: Long,
            day: Long,
        ): Long {
            val d = (year + (month - 8) / 6 + 100100) * 1461 / 4 + (153 * ((month + 9) % 12) + 2) / 5 + day - 34840408
            return d - (year + 100100 + (month - 8) / 6) / 100 * 3 / 4 + 752
        }

        private fun gregorianYear(jdn: Long): Long {
            var j = 4 * jdn + 139361631
            j += (4 * jdn + 183187720) / 146097 * 3 / 4 * 4 - 3908
            val i = j % 1461 / 4 * 5 + 308
            val month = i / 153 % 12 + 1
            return j / 1461 - 100100 + (8 - month) / 6
        }

        private const val GREGORIAN_OFFSET = 621L
        private const val CYCLE = 33L
        private const val MARCH = 3L
        private const val DEFAULT_MARCH = 20L
        private const val EPOCH_YEAR = 1970L
        private const val LONG_MONTH = 31L
        private const val SHORT_MONTH = 30L
        private const val FIRST_SHORT = 7L

        /** The days of the six 31-day months. */
        private const val FIRST_HALF = 186L

        /** The days from the 1st of Mehr to the end of a common year's Esfand. */
        private const val SECOND_HALF = 179L
        private const val GREGORIAN_ONLY = 1700

        /** Further than the whole calendar spans, in months. */
        private const val MAX_MONTHS = 12L * 4000
    }
}
