package com.andaxin.core.concurrency

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.datetime.Clock
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

// 定点小数（%.Nf 的同口径）在 core/text/Fmt.kt 的 fixed() —— 那份连 java 按最短表示
// 四舍五入的口径都复刻了（1.005→"1.01"），别在这边再写一份二舍三入的。
