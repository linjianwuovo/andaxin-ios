package com.andaxin.core.schedule

/*
 * 平移注：从 app 的 `core/schedule/RoomShort.kt` 原样搬进共享层（底栏五格那批，
 * 课表网格和首页那一行都要用）。包名没变，全仓调用点一行不用改。
 */

/**
 * 教室名压短。校方给的是「博雅楼（A1）S103」这种一整串，
 * 网格里一格六七十 dp 宽放不下，首页那一行也被它拖得很长。
 *
 * 规则：去掉"楼/栋"和括号，只留 楼栋短名 + 房号 ——「博雅A1 S103」。
 * 认不出这个格式的原样返回，不硬切（切错了比长得更难看）。
 */
fun shortRoom(room: String): String {
    val text = room.trim()
    val open = text.indexOf('（')
    val close = text.indexOf('）')
    if (open <= 0 || close <= open) return text
    val name = text.substring(0, open).removeSuffix("楼").removeSuffix("栋")
    val zone = text.substring(open + 1, close)
    val code = text.substring(close + 1).trim()
    return if (code.isBlank()) "$name$zone" else "$name$zone $code"
}
