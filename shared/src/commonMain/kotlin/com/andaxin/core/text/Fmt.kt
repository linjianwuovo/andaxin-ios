package com.andaxin.core.text

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round

/**
 * 定点小数串：顶替 `String.format(Locale.US, "%.nf", x)`（logicMain 里没有 java.util.Formatter）。
 *
 * 为什么得写这么绕：跑步上传那十几个键里的 `mile`/`speed` 和轨迹的经纬度是要交给学校判
 * 「有效」的数，线上口径是 java 的 `%.2f`/`%.6f`，而 **java 是在 `Double.toString()` 的
 * 最短十进制表示上四舍五入的**（所以 `1.005 → "1.01"`）。
 * 图省事写成 `round(x * 100) / 100` 会得 "1.00" —— 那一发的 `1.005km` 正好压在学校
 * 「单次不少于 1 公里」的线上，差一分就是一个判罚。所以这里先拿最短十进制串，再手工 half-up。
 *
 * 指数形式的最短表示（|x| ≥ 1e7 或极小，这两个字标量达不到的量级）退回长整算术，结果同样是定点。
 */
fun fixed(value: Double, decimals: Int): String {
    if (value.isNaN() || value.isInfinite()) return value.toString()
    val negative = value < 0.0 || (value == 0.0 && 1.0 / value < 0.0)
    val magnitude = abs(value)
    val text = magnitude.toString()
    val out = if ('E' in text || 'e' in text) {
        val factor = 10.0.pow(decimals).toLong()
        val scaled = round(magnitude * factor).toLong()
        val wholePart = (scaled / factor).toString()
        if (decimals <= 0) wholePart else {
            val fracPart = (scaled % factor).toString().padStart(decimals, '0')
            "$wholePart.$fracPart"
        }
    } else {
        halfUpOnShortest(text, decimals)
    }
    // java 给 -0.001 打的是 "-0.00"：进了零也留着那个负号
    return if (negative) "-$out" else out
}

/** 最短十进制串 `text`（非负、无指数）按 half-up 定到 decimals 位，位数不足补零。 */
private fun halfUpOnShortest(text: String, decimals: Int): String {
    val dot = text.indexOf('.')
    val intPart = if (dot < 0) text else text.substring(0, dot)
    val frac = if (dot < 0) "" else text.substring(dot + 1)
    if (decimals <= 0) {
        return if (frac.isNotEmpty() && frac[0] >= '5') addOne(intPart) else intPart
    }
    if (frac.length <= decimals) return "$intPart.${frac.padEnd(decimals, '0')}"
    var digits = intPart + frac.substring(0, decimals)
    if (frac[decimals] >= '5') digits = addOne(digits)
    val cut = digits.length - decimals
    return "${digits.substring(0, cut)}.${digits.substring(cut)}"
}

/** 十进制整数数字串 +1（只进位，不带符号："999" → "1000"）。 */
private fun addOne(digits: String): String {
    val out = StringBuilder(digits)
    var i = out.length - 1
    while (i >= 0) {
        if (out[i] == '9') {
            out[i] = '0'
            i--
        } else {
            out[i] = (out[i] + 1)
            return out.toString()
        }
    }
    return "1$out"
}
