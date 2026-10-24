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
