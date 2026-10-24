package com.andaxin.core.design

import androidx.compose.ui.graphics.Color

/**
 * 调色板。数值来自我们已经定稿并装机验收过的那套外观（十六进制是事实，不是表达式），
 * 名字和分组重新起：亮色按"纸/卡/沙"分三层底，暗色按"夜"分三层底，墨是文字。
 */
object Palette {

    // 亮色：三层底 + 墨
    val Paper = Color(0xFFF4F6F9)
    val Cream = Color(0xFFFAFBFD)
    val Card = Color(0xFFFFFFFF)
    val Sand = Color(0xFFEDF0F5)
    val SandDeep = Color(0xFFDCE1E9)
    val Hairline = Color(0x0F000000)

    /*
     * 墨色五档是**按 WCAG AA 现算过一遍再定的**（10-21 凌晨，脚本在 `lxtmp/contrast.py`）。
     * 以前下面三档是凭手感往浅里走的：`InkFaint` 在纸色上只有 **2.41:1**、`InkGhost` **1.51:1**，
     * 而 `faint` 是拿来写时间戳和每一行副标题的 —— 那不是装饰，是正文。
     * 现在这五档在纸 #F4F6F9 上的对比度是 15.8 / 9.5 / 6.3 / 4.6 / 3.1：
     * 写小字的四档全部 ≥4.5（AA），只有最浅那档留给占位符、禁用态这类"看不清也没关系"的东西。
     * 层级没有变平：步长从"浅到糊"换成"深到能读"，五档还是一档比一档轻。
     */
    val Ink = Color(0xFF191C22)
    val InkSoft = Color(0xFF3B4149)
    val InkMuted = Color(0xFF535B69)
    val InkFaint = Color(0xFF69717E)
    val InkGhost = Color(0xFF878D97)

    // 暗色
    val NightPaper = Color(0xFF0E1116)
    val NightCream = Color(0xFF11151B)
    val NightCard = Color(0xFF161A21)
    val NightSand = Color(0xFF21262F)
    val NightSandDeep = Color(0xFF2C323C)
    /** 夜间那张校园卡：白天用 #2563EB 整块饱和蓝，在黑底上是一块刺眼的灯板，压深一档。 */
    val NightCardBlue = Color(0xFF1E2F5C)
    val NightHairline = Color(0x14FFFFFF)

    val NightInk = Color(0xFFE7EAF0)
    val NightInkSoft = Color(0xFFC3C9D4)
    val NightInkMuted = Color(0xFF98A1AF)
    // 同上一趟浅色阶梯一起量的：这两档以前是 3.90 / 2.15，写时间戳和副标题读不动。
    // 现在夜纸 #0E1116 上是 15.7 / 11.4 / 7.3 / 5.0 / 3.1；faint 单独再按夜卡 #161A21 上
    // 也够（4.63）复核过一次 —— 大部分副标题是写在卡里的。
    val NightInkFaint = Color(0xFF7C8492)
    val NightInkGhost = Color(0xFF5C636F)

    // 语义色：亮/暗各一档，soft 是同一色的低透明版，用来做底色而不是描边
    val Blue = Color(0xFF2563EB)
    val BlueSoft = Color(0x1F2563EB)
    val BlueGlow = Color(0x142563EB)
    val Teal = Color(0xFF0D9488)
    val TealSoft = Color(0x1A0D9488)
    val Amber = Color(0xFFF59E0B)
    val AmberSoft = Color(0x1AF59E0B)
    /*
     * 状态色里"拿去写小字"的那一档。上面 `Teal`/`Amber` 是给大数字、圆点、淡底用的，
     * 直接写 12sp 的状态字（通过 / 审批中）在纸色上只有 3.46 和 1.98:1 —— 琥珀那颗几乎读不出。
     * 所以同一族压深一档只给文字：`TealInk` 4.71、`AmberInk` 4.72（AA 门槛 4.5）。
     * 夜间不用另压：`NightTeal` 10.1、`NightAmber` 11.3，本来就在浅的那一头。
     */
    val TealInk = Color(0xFF007C70)
    val AmberInk = Color(0xFFAD5600)
    val Violet = Color(0xFF8B5CF6)
    val VioletSoft = Color(0x1A8B5CF6)
    val Slate = Color(0xFF64748B)
    val SlateSoft = Color(0x1A64748B)
    /** 报错文字、失败状态都吃这颗。原来是 #E5484D，纸色上 3.61:1，写小字不合格 → 压到 4.73。 */
    val Rose = Color(0xFFCC333A)

    val NightBlue = Color(0xFF7AA2FF)
    val NightBlueSoft = Color(0x337AA2FF)
    val NightTeal = Color(0xFF4FD1C5)
    val NightTealSoft = Color(0x334FD1C5)
    val NightAmber = Color(0xFFFBBF24)
    val NightAmberSoft = Color(0x33FBBF24)
    val NightViolet = Color(0xFFB79CFF)
    val NightVioletSoft = Color(0x33B79CFF)
    val NightSlate = Color(0xFF94A3B8)
    val NightSlateSoft = Color(0x3394A3B8)
    val NightRose = Color(0xFFFF8080)
}
