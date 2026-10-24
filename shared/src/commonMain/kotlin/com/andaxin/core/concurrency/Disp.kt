package com.andaxin.core.concurrency

import kotlinx.coroutines.CoroutineDispatcher
import kotlin.time.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/**
 * commonMain 里没有 Dispatchers.IO，也没有墙钟。两个 expect 各平台给：
 * Android/JVM 是 IO 与 System.currentTimeMillis()；
 * iOS 用 Default（Darwin 没有专门的 IO 池）与 posix time。
 */
expect val ioDispatcher: CoroutineDispatcher

expect fun nowMillis(): Long

/** 本机时区今天的日期。toString() 就是 ISO 串（"2026-10-24"），口径对齐 java.time.LocalDate.now()。 */
fun todayLocalDate(): LocalDate =
    Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

/** java.time LocalDate.minusMonths 的同口径帮手（kotlinx-datetime 里叫 DatePeriod）。 */
fun LocalDate.minusMonthsCompat(months: Int): LocalDate = minus(DatePeriod(months = months))

/** 今天偏移 days 天的 ISO 日期串（"yyyy-MM-dd"），对齐 Calendar+SimpleDateFormat 那套老写法。 */
fun localDateOffset(days: Int): String =
    todayLocalDate().plus(days, DateTimeUnit.DAY).toString()

/**
 * 毫秒时间戳 → 本机时区的 "yyyy-MM-dd HH:mm:ss"，对齐
 * `SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))`。
 * 跑步上传的 `startDate`/`endDate` 就是这个格式，学校的 `endDate` 只认它。
 */
fun clockText(millis: Long): String {
    val t = kotlinx.datetime.Instant.fromEpochMilliseconds(millis)
        .toLocalDateTime(TimeZone.currentSystemDefault())
    return "${t.year}-${two(t.monthNumber)}-${two(t.dayOfMonth)} " +
        "${two(t.hour)}:${two(t.minute)}:${two(t.second)}"
}

private fun two(n: Int): String = if (n in 0..9) "0$n" else "$n"

/** 本机时区 "yyyy-MM"（年-月），对齐 SimpleDateFormat("yyyy-MM") 那发。 */
fun yearMonthIso(): String {
    val t = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    return t.year.toString() + "-" + (if (t.monthNumber < 10) "0" else "") + t.monthNumber
}

/** 上传报文里要带的设备型号（android=Build.MODEL；iOS=utsname.machine；JVM=系统名）。 */
expect fun deviceModel(): String

/** 今天星期几，1..7（周一起算）。口径对齐 Periods.today()。 */
fun todayDayOfWeek(): Int = todayLocalDate().dayOfWeek.ordinal + 1

/** 现在几点（本机时区，0..23）。对齐 java.time.LocalTime.now().hour。 */
fun currentHour(): Int =
    Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).hour

/** 现在几点几分（0..1439），对齐 Periods.minuteOfDay()。 */
fun minuteOfDay(): Int {
    val t = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    return t.hour * 60 + t.minute
}

/**
 * "yyyy-MM-dd HH:mm" 或 "yyyy-MM-dd" → LocalDateTime。空间隔自动转 T（ISO 8601）。
 * 只有日期的写法按 00:00 起算。读不出来回 null。
 */
fun parsePatternTime(text: String): kotlinx.datetime.LocalDateTime? {
    val t = text.trim().replace(' ', 'T')
    return runCatching { kotlinx.datetime.LocalDateTime.parse(t) }.getOrNull()
        ?: runCatching { kotlinx.datetime.LocalDateTime.parse(t.take(10) + "T00:00") }.getOrNull()
}

/** LocalDateTime 按它自己的格式回写一遍（去掉人随手打入的多余空格）。 */
fun formatPatternTime(time: kotlinx.datetime.LocalDateTime, withTime: Boolean): String =
    if (withTime) {
        "${time.year}-${two(time.monthNumber)}-${two(time.dayOfMonth)} ${two(time.hour)}:${two(time.minute)}"
    } else {
        "${time.year}-${two(time.monthNumber)}-${two(time.dayOfMonth)}"
    }

/** 两个 LocalDateTime 相差几小时（同区）。对齐 ChronoUnit.HOURS.between。 */
fun hoursBetweenLDT(a: kotlinx.datetime.LocalDateTime, b: kotlinx.datetime.LocalDateTime): Long {
    // 不用 toInstant（那要 TimeZone 扩展，common 里引不了）：手算两场的"本地秒数"差，
    // 同时区跨日也准，夏令时切换的那一小时这一族没遇到过。
    val aSec = (a.dayOfYear.toLong() * 86400) + a.hour * 3600L + a.minute * 60L + a.second
    val bSec = (b.dayOfYear.toLong() * 86400) + b.hour * 3600L + b.minute * 60L + b.second
    return (bSec - aSec) / 3600
}

// 定点小数（%.Nf 的同口径）在 core/text/Fmt.kt 的 fixed() —— 那份连 java 按最短表示
// 四舍五入的口径都复刻了（1.005→"1.01"），别在这边再写一份二舍三入的。
