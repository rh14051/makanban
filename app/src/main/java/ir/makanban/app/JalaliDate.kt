package ir.makanban.app

import java.util.Calendar
import java.util.Locale

/**
 * تبدیل تاریخ میلادی گوشی به تاریخ شمسی (جلالی)
 * الگوریتم استاندارد و پایدار؛ بدون وابستگی خارجی.
 */
object JalaliDate {

    private val G_DAYS = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)

    /** خروجی: [سال شمسی، ماه شمسی، روز شمسی] */
    fun toJalali(gy: Int, gm: Int, gd: Int): IntArray {
        val gy2 = if (gm > 2) gy + 1 else gy
        var days = 355666 + (365 * gy) +
            ((gy2 + 3) / 4) - ((gy2 + 99) / 100) + ((gy2 + 399) / 400) +
            gd + sumDaysBefore(gm)
        var jy = -1595 + (33 * (days / 12053))
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            jy += (days - 1) / 365
            days = (days - 1) % 365
        }
        val jm: Int
        val jd: Int
        if (days < 186) {
            jm = 1 + days / 31
            jd = 1 + days % 31
        } else {
            jm = 7 + (days - 186) / 30
            jd = 1 + (days - 186) % 30
        }
        return intArrayOf(jy, jm, jd)
    }

    private fun sumDaysBefore(month: Int): Int {
        var s = 0
        for (i in 0 until month - 1) s += G_DAYS[i]
        return s
    }

    /** تاریخ شمسی امروز به قالب 1405/08/10 */
    fun todayJalali(calendar: Calendar = Calendar.getInstance()): String {
        val j = toJalali(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH)
        )
        return String.format(Locale.US, "%04d/%02d/%02d", j[0], j[1], j[2])
    }

    /** ساعت فعلی گوشی به قالب 14:30:25 */
    fun nowTime(calendar: Calendar = Calendar.getInstance()): String =
        String.format(
            Locale.US, "%02d:%02d:%02d",
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            calendar.get(Calendar.SECOND)
        )
}
