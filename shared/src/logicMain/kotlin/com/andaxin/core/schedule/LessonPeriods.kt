package com.andaxin.core.schedule

import com.andaxin.core.concurrency.minuteOfDay
import com.andaxin.core.concurrency.todayDayOfWeek
import com.andaxin.core.concurrency.todayLocalDate
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/*
 * 平移注：这一份是 app 侧 `core/schedule/Periods.kt` 的 **common 影子**（底栏五格那批，
 * 课表网格和首页"下一节"要用）。时间表逐字照抄 app 那张官方十节表（08:30 起、
 * 结束时刻是抄的不是算的）；Calendar 换成 kotlinx-datetime 的纯算术。
 *
 * 为什么不直接共用一个：app 侧那份还长着闹钟能力（slotStart 给 ClassReminder 用），
 * 牵着 android 的 AlarmManager，搬不动；data 层的 `SchoolPeriods` 是另一张表
 * （08:00 起的那份），别混用。等哪天 app 侧 Periods 也瘦身了再合。
 *
 * 名字起成 LessonPeriods 就是为了不和 app 侧同包的 `Periods` 撞名 —— 同一个 FQCN
 * 在 app 和 shared 各出一份，R8 会按重复类处理。
 */

/**
 * 作息表。周网格那一屏、首页"下一节"都用这一份。
 *
 * **10 节，时间照他 10-21 早上发的那张官方课表逐行抄的。**
 * 以前这里是一张我自己拍的 12 节表（08:00 起、每节按 45 分钟推）—— 官方课表里根本没有
 * 第 11、12 节，那两行是凭空多出来的，还带着编出来的时间。他一句"哪来的 11 节 12 节"
 * 把它钉住了。现在这里只放官方那十对时间，**结束时刻是抄的，不是算的**：
 * 第 7 节官方给的是 15:55–16:35（40 分钟），所以"每节都 45 分钟"这个假设也一并删了。
 */
object LessonPeriods {

    /** 一节一对 (开始, 结束)，"HH:mm"。顺序就是第 1..10 节。 */
    val SLOTS = listOf(
        "08:30" to "09:15",
        "09:20" to "10:05",
        "10:25" to "11:10",
        "11:15" to "12:00",
        "14:00" to "14:45",
        "14:50" to "15:35",
        "15:55" to "16:35",
        "16:45" to "17:30",
        "19:00" to "19:45",
        "19:55" to "20:40",
    )

    val START_AT = SLOTS.map { it.first }
    val END_AT = SLOTS.map { it.second }

    private fun minutes(hhmm: String): Int =
        hhmm.substringBefore(":").toInt() * 60 + hhmm.substringAfter(":").toInt()

    private val START_MINUTES = START_AT.map(::minutes)
    private val END_MINUTES = END_AT.map(::minutes)

    /** 第 [period] 节（1 起）的上课时刻，"HH:mm"。越界回 null，调用方自己决定怎么兜。 */
    fun startAt(period: Int): String? = START_AT.getOrNull(period - 1)

    /** 第 [period] 节的下课时刻。越界回 null。 */
    fun endAt(period: Int): String? = END_AT.getOrNull(period - 1)

    /** 第 [period] 节的开始分钟数；越界 null。 */
    fun startMinute(period: Int): Int? = START_MINUTES.getOrNull(period - 1)

    /** 第 [period] 节的结束分钟数；越界 null。 */
    fun endMinute(period: Int): Int? = END_MINUTES.getOrNull(period - 1)

    /**
     * 现在**是不是真的在一节课里**。是就给节次号（1 起），课间、午休、还没上课都回 null。
     *
     * 结束时刻用的是官方那张表里抄来的那一个（不是"下一节的开始"，也不是"开始 + 45"）：
     * 课间那十分钟要是按下一节算，会被一直高亮成"正在上这一节"。
     */
    fun periodNow(minute: Int = minuteOfDay()): Int? {
        START_MINUTES.forEachIndexed { i, start ->
            if (minute in start until END_MINUTES[i]) return i + 1
        }
        return null
    }

    /**
     * 从第 [startPeriod] 节起连上 [periodCount] 节的整段时间，"08:30–10:05"。
     * 「只看今天」每一行用它 —— 只给节次不给钟点，人还是得自己换一遍。
     */
    fun rangeText(startPeriod: Int, periodCount: Int): String? {
        val from = startAt(startPeriod) ?: return null
        val to = endAt(startPeriod + periodCount.coerceAtLeast(1) - 1) ?: return null
        return "$from–$to"
    }

    /**
     * 本周（`offset=0`）或第 offset 周后的那一周的周一。
     * todayDayOfWeek() 是"周一起 1..7"，减掉它就是这一周的第 0 天 —— 口径对齐
     * app 侧 `Periods.weekMonday`（那个按 Calendar.DAY_OF_WEEK 算会跟着 locale 变，不能学它）。
     */
    fun weekMonday(offset: Int = 0): LocalDate =
        todayLocalDate().plus(DatePeriod(days = -(todayDayOfWeek() - 1) + offset * 7))
}
