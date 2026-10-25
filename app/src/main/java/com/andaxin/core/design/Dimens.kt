package com.andaxin.core.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 圆角四档：小容器 / 输入框与中卡 / 主卡与大按钮 / 胶囊。 */
object Radius {
    val Tag = 8.dp
    val Field = 12.dp
    val Card = 16.dp
    val Pill = 20.dp

    val tag = RoundedCornerShape(Tag)
    val field = RoundedCornerShape(Field)
    val card = RoundedCornerShape(Card)
    val pill = RoundedCornerShape(Pill)
}

/** 间距按 4 的倍数走，屏边距单独一档，别到处写 16.dp。 */
object Gap {
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp

    /** 左右边距：整屏统一，改这一处 */
    val screen: Dp = 18.dp

    /**
     * 悬浮底栏让出来的那条尾巴（再加系统导航条的高度，见 shell 的 ShelfTail）。
     *
     * 64 栏体 + 12 栏底间距 + 收尾留白，凑成 96。
     */
    val shelfTail: Dp = 96.dp
}

/**
 * 字号只有这六档。
 *
 * 为什么专门立一份：改到这版才发现，同一个屏里"副标题"同时存在 11/12/13 三种字号、
 * 行主标题有 14/15/16/17 四种，数字有 17/20/24/30 四种 —— 每一处单看都说得通，
 * 放在一起看就是"没对齐"。他要的"一目了然"，八成靠的就是这一档收干净。
 *
 * 用法：屏标题 [display]、格子里的大数字 [num]、一行主文字 [title]、
 * 正文段落 [body]、次要信息/右栏值 [meta]、说明与免责 [micro]。
 */
object Type {
    val display = 22.sp
    val num = 26.sp
    val title = 16.sp
    val body = 14.sp
    val meta = 12.sp
    val micro = 11.sp

    /**
     * 三档"窄容器专用"，不是给普通文字用的：
     * [tiny] 只有课程表格子（一列约 86dp）和几枚角标；[tinier] 是格子下面那行节次时间；
     * [hero] 只有校园卡那张卡面上的余额。别的地方要字号，从上面六档里挑。
     */
    val tiny = 10.sp
    /** 只有 [tiny] 那一档要用：格子里两行字，行高再按 body 的 21 就撑不开了。 */
    val tinyLine = 12.sp
    val tinier = 8.5.sp
    /** [tinier] 换行时用这一档行高：块里那行教室要占两三行，按默认行高会把块的下半截撑过头。 */
    val tinierLine = 10.sp
    val hero = 30.sp

    /**
     * 数字走等宽字形。
     *
     * 为什么专门立这一条：MiSans 默认的数字是**比例宽度** —— 现拆这个字体本体量过，
     * upem 1000 下「1」是 403、「0」是 634，差一半多。所以余额从 11.11 跳到 88.88、
     * 跑步时长每一分钟一跳，整行字都会左右挪。挂上 OpenType 的 tnum 之后十个数字同宽。
     *
     * 只给"会变、要看数"的位置用（数字格、余额、配速、时长、倒计时、节次时间）；
     * 正文里偶尔出现的数字不用管，等宽反而显得松。
     */
    val tabular = TextStyle(fontFeatureSettings = "tnum")

    /** 行高跟着字号走，别默认挤在一起。 */
    val bodyLine = 21.sp
    val metaLine = 16.sp
    val microLine = 15.sp
}

/** 节奏：卡片内边距、行高、卡与卡之间、区块与区块之间。 */
object Rhythm {
    /** 卡里左右上下的留白。原来跟着 Gap.lg=16，一行五个字时显得空。 */
    val cardPad = 14.dp

    /** 一行的上下内边距（列表行、键值行都用它）。 */
    val rowV = 11.dp

    /** 卡与卡之间：以前各处 12/16/24 混着来，看着像没排过。 */
    val cardGap = 8.dp

    /** 一个区块和下一个区块之间。 */
    val sectionGap = 18.dp

    /** 右栏（值、状态）最多占的宽度比例：超过它就把左边主文字挤成竖排了。 */
    const val rightMax = 0.42f
}
