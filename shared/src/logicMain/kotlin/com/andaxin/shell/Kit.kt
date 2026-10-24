@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.andaxin.shell

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andaxin.core.design.Gap
import com.andaxin.core.design.LocalInk
import com.andaxin.core.design.Motion
import com.andaxin.core.design.Palette
import com.andaxin.core.design.Radius
import com.andaxin.core.design.Rhythm
import com.andaxin.core.design.Type
import com.andaxin.core.ui.clickHaptic
import com.andaxin.data.brief.Flag
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text

/*
 * 平移注：这一族是从 app 的 `shell/Bits.kt` 里摘出来的 —— 登录那一批共享层界面（ScaffoldPage
 * 一族）先摘走了 pressClick/Refreshable，底栏五格（消息/公告/课表/我的/首页）那一批再摘走
 * 排版零件这一族。Bits.kt 里剩下的（LoadWhy/StatBlock/EmptyState/FillHeight）还牵着 app 的
 * LocalAppGraph 或者暂无共享层调用者，留在原地；包名没变，全仓调用点一行不用改。
 * 触感那一下走 `com.andaxin.core.ui.PlatformHooks`，android 端行为与原来一字不差。
 */

/**
 * 能点的东西按下去得有个交代。
 *
 * 两种交代，分开用：
 * ① [shrink] = true —— 独立的胶囊、图标格、按钮、整张卡：按他 10-21 那份规范，
 *    缩到 **0.98**（原来 0.97，2% 就够，缩多了像抖），同时叠一层很淡的白光（"微亮高光"）；
 * ② [shrink] = false —— 卡在一张卡里的行：不能缩，一缩就把同卡别的行也带歪，
 *    所以改成压暗到 0.55，这是 HyperOS 设置列表那一下（这种行也不叠高光，白一下反而像闪）。
 *
 * 触觉：只在**真的点中**那一下响（`onClick` 里），不在手指刚落下时响 ——
 * 卡片铺满整屏，按在卡上起手一划也是"按下"，按下去就震会变成一路滑一路嗡嗡。
 * 震感走 `PlatformHooks.clickHaptic()`（android = 系统的 `CONTEXT_CLICK`，
 * 用户关了系统触感反馈就自动没有）。
 *
 * 涟漪（ripple）在这儿一律关掉：这一层已经给足了反馈，再叠一圈水波纹就是两种风格打架。
 * 松手比按下稍慢（[Motion.press] / [Motion.pressRelease]），手指抬起来的那一下能看见"回弹"，
 * 而且动画是可打断的 —— 没按满也会立刻往回走。
 */
@Composable
fun Modifier.pressClick(enabled: Boolean = true, shrink: Boolean = true, onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val haptic = clickHaptic()
    val ink = LocalInk.current
    val amount = animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        // 按下去是一段贴手的 tween；松手走 spring 收尾。阻尼给满（1.0）——
        // 他 10-21 发的那张参考图的 MOTION 页明确写了"不做弹性过冲"：
        // 要的是自然的减速，不是弹一下再回来。
        animationSpec = if (pressed) {
            tween(Motion.press, easing = Motion.EaseOut)
        } else {
            spring(dampingRatio = 1f, stiffness = 900f)
        },
        label = "press",
    )
    /*
     * 高光的圆角按高度猜：胶囊/按钮的高都在 40dp 内，取 height/2 就是一颗药丸；
     * 卡片更高，封顶 16dp —— 正好是 `Radius.Card`。不另加参数，62 个调用点不用逐个改。
     */
    val glow = if (ink.dark) 0.10f else 0.16f
    return this
        .graphicsLayer {
            val t = amount.value
            if (shrink) {
                scaleX = 1f - t * 0.02f
                scaleY = 1f - t * 0.02f
            } else {
                alpha = 1f - t * 0.45f
            }
        }
        .then(
            if (shrink) Modifier.drawWithContent {
                drawContent()
                val t = amount.value
                if (t > 0.001f) {
                    drawRoundRect(
                        color = Color.White.copy(alpha = glow * t),
                        cornerRadius = CornerRadius(minOf(size.height / 2f, 16.dp.toPx())),
                    )
                }
            } else Modifier
        )
        .clickable(interactionSource = src, indication = null, enabled = enabled) {
            haptic()
            onClick()
        }
}

/**
 * 一屏内容的下拉刷新壳。[onRefresh] 给 null 就等于没套（详情页这种没什么可刷的）。
 *
 * 那颗回调是**挂起**的：拉完才收指示器。不等它就收，是个假动作 —— 用户看到转圈停了
 * 但数还没换，只会再拉一次。
 */
@Composable
fun Refreshable(
    onRefresh: (suspend () -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (onRefresh == null) {
        content()
        return
    }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            scope.launch {
                onRefresh()
                refreshing = false
            }
        },
        modifier = modifier,
        content = { content() },
    )
}

/* ───────────── 以下是从 app 的 Bits.kt 整族平移过来的排版零件（原文照搬，包名没变） ───────────── */

/**
 * 数据块第一次拿到时淡一下（[Motion.enter]，ease-out）。
 *
 * 只淡入、不位移、更不做"一块接一块排队出现"那种 stagger —— 多条来源要一次出齐，
 * 一路加载一路蹦是最招人烦的。[key] 变了（比如换票重拉）会再播一次，
 * 因为那一屏内容确实是新的一份。
 */
@Composable
fun Modifier.enterFade(key: Any? = Unit): Modifier {
    /*
     * 用 rememberSaveable 而不是 remember：从二级页（比如「关于」）退回来时，这一屏是
     * **重新进组合**的，用 remember 的话"已经淡入过"这个事实跟着丢了，于是每退一次
     * 就再播一遍 0→1 —— 反复进出「关于」看到的那一下闪就是这个。
     * Navigation 会把每个返回栈条目的 saveable 状态存住，回来时是 true，就直接跳过动画。
     */
    var shown by rememberSaveable(key) { mutableStateOf(false) }
    LaunchedEffect(key) { shown = true }
    val a = animateFloatAsState(if (shown) 1f else 0f, tween(Motion.enter, easing = Motion.EaseOut), label = "enter")
    // 读 state 而不是读裸值：这样动画每帧只重画这一层，不会把整块内容 recompose 一遍
    return graphicsLayer { alpha = a.value }
}

/**
 * 一张卡。底子和圆角交给 Miuix 的 `Card`（HyperOS 的设置卡就是那个样子：
 * 16 圆角、`surfaceContainer` 底色、无投影），内边距走 [Rhythm.cardPad]。
 */
@Composable
fun Sheet(
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    val ink = LocalInk.current
    Card(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = Radius.Card,
        insideMargin = PaddingValues(Rhythm.cardPad),
        colors = CardColors(color = tint, contentColor = ink.strong),
        content = content,
    )
}

/** 分组小标题：Miuix 的 `SmallTitle`，上下留白按区块节奏给。 */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    SmallTitle(
        text = text,
        modifier = modifier.padding(top = Rhythm.sectionGap, bottom = Gap.xs),
        textColor = LocalInk.current.muted,
    )
}

/**
 * 一行列表条目 —— 全仓所有"左边一句主话、右边一个状态"的形状都走它。
 *
 * 三条硬规矩，都是这次改出来的：
 * ① 右栏最多占 [Rhythm.rightMax]：以前右栏不限宽，长状态（"记录可看 · 登记没接"）
 *    会把左边挤成两行竖排，整行看着就散了；
 * ② 主文字最多两行、副文字一行，超了就省略号 —— 不许把行高撑开；
 * ③ 副文字和主文字之间只留 2dp，行内上下 padding 走 [Rhythm.rowV]，
 *    这样一屏能多塞三四条，滚动距离少一半。
 */
@Composable
fun RowLine(
    title: String,
    sub: String = "",
    right: String = "",
    flag: Flag = Flag.None,
    onClick: (() -> Unit)? = null,
    leading: (@Composable RowScope.() -> Unit)? = null,
) {
    val ink = LocalInk.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.pressClick(onClick = onClick, shrink = false) else Modifier)
            .padding(vertical = Rhythm.rowV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(Gap.md))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = Type.title,
                lineHeight = Type.bodyLine,
                fontWeight = FontWeight.Medium,
                color = ink.strong,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (sub.isNotBlank()) {
                Spacer(Modifier.height(Gap.xxs))
                Text(
                    text = sub,
                    fontSize = Type.meta,
                    lineHeight = Type.metaLine,
                    color = ink.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (right.isNotBlank()) {
            Spacer(Modifier.width(Gap.sm))
            Text(
                text = right,
                fontSize = Type.meta,
                lineHeight = Type.metaLine,
                color = when (flag) {
                    // 状态字是 12sp，吃"写字那一档"的状态色（见 `Palette` 里 TealInk/AmberInk 那段）；
                    // 夜间本来就浅，直接吃亮档。
                    Flag.Ok -> if (ink.dark) Palette.NightTeal else Palette.TealInk
                    Flag.Bad -> MaterialTheme.colorScheme.error
                    Flag.Warn -> if (ink.dark) Palette.NightAmber else Palette.AmberInk
                    Flag.Unread -> MaterialTheme.colorScheme.primary
                    Flag.None -> ink.faint
                },
                textAlign = TextAlign.End,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 150.dp),
            )
        }
    }
}

/**
 * 一格数字。首页那种"一眼要看数"的位置用它：数字 [Type.num]，单位跟在右下、
 * 只给 [Type.meta]，标签在最下面 [Type.micro]。
 */
@Composable
fun MetricCell(
    label: String,
    value: String,
    unit: String = "",
    accent: Color? = null,
    /** 数字那一档的字号。时间串这种"不是数"的值要降一档，不然 26sp 会折行。 */
    size: androidx.compose.ui.unit.TextUnit = Type.num,
    modifier: Modifier = Modifier,
) {
    val ink = LocalInk.current
    Column(modifier, horizontalAlignment = Alignment.Start) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                fontSize = size,
                fontWeight = FontWeight.SemiBold,
                style = Type.tabular,
                color = accent ?: ink.strong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // 数字先占位、单位才不会被挤成竖排（极端数据那一趟量出来的：
                // 九位数跑量把"公里"顶成了两行「公/里」）
                modifier = Modifier.weight(1f, fill = false),
            )
            // 单位是给数字用的：值本身不是数（"—"、"没拉到"）就别跟着挂一个"公里" ——
            // 26sp 那一横杠后面顶个"公里"，看着像把数字划掉了（10-21 深色扫首页看到的）。
            if (unit.isNotBlank() && value.any { it.isDigit() }) {
                Spacer(Modifier.width(Gap.xxs))
                Text(
                    text = unit,
                    fontSize = Type.meta,
                    color = ink.faint,
                    maxLines = 1,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(Gap.xxs))
        Text(label, fontSize = Type.micro, color = ink.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * 一排可选的小胶囊（周次、视图切换、提前量、首页快捷入口都用它）。
 *
 * 10-20 深夜他定的一条：**没选中的那颗要给灰底**，选中的那颗换成主色淡底。
 * 原来没选中用的是 `ink.hairline`（6% 黑），在浅色主题下几乎看不出来，
 * 结果"没选中"看着像没画完、"选中"（surfaceVariant）反而像灰的 —— 状态正好是反的。
 */
@Composable
fun Chip(text: String, picked: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val ink = LocalInk.current
    /*
     * 选中那颗的底要比没选中的那颗**更显眼**，两套主题都得成立。
     * 夜里原来那层 primary@13% 压在近黑底上比未选中的灰底还暗，两颗是反的
     * （10-21 凌晨深色扫课程表「本周/下周」撞见的），所以夜里把选中底提到 0.30。
     */
    val pickedAlpha = if (ink.dark) 0.30f else 0.13f
    Text(
        text = text,
        fontSize = Type.meta,
        fontWeight = if (picked) FontWeight.Medium else FontWeight.Normal,
        color = if (picked) MaterialTheme.colorScheme.primary else ink.muted,
        modifier = modifier
            .pressClick(onClick = onClick)
            .background(
                if (picked) MaterialTheme.colorScheme.primary.copy(alpha = pickedAlpha) else ink.offFill,
                Radius.pill,
            )
            .padding(horizontal = Gap.md, vertical = Gap.sm),
    )
}

/**
 * 一行两列的键值对，明细类界面到处要用。
 *
 * 值那一栏限宽（210dp 上限）并且右对齐 ——
 * 不限的话长值会把左边标签挤到换行，一屏明细看着像两栏没对齐。
 */
@Composable
fun Labeled(label: String, value: String, modifier: Modifier = Modifier) {
    val ink = LocalInk.current
    BasicComponent(
        modifier = modifier.fillMaxWidth(),
        title = label,
        titleColor = BasicComponentDefaults.titleColor(
            color = ink.muted,
            disabledColor = ink.faint,
        ),
        insideMargin = PaddingValues(horizontal = 0.dp, vertical = Gap.xs),
        endActions = {
            Text(
                text = value,
                fontSize = Type.body,
                lineHeight = Type.bodyLine,
                color = ink.strong,
                textAlign = TextAlign.End,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = 210.dp)
                    .fillMaxHeight()
                    .wrapContentHeight(),
            )
        },
    )
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier, color = LocalInk.current.hairline)
}

/**
 * 五个 tab 那一屏的收尾留白。
 *
 * 为什么要有它：底栏是悬浮的玻璃，页面现在从它底下整屏铺过去（这样玻璃才有东西可折射），
 * 那么滚到底时最后一条内容必须停在栏子上沿之上，不然它永远压在玻璃底下读不出来。
 *
 * 两个修饰符的顺序别反：`navigationBarsPadding` 要写在 `height` **前面**，
 * 那样它是加在 96 之外；写在后面就变成把 96 里面挖掉一块，三键导航的机器上照样被压住。
 */
@Composable
fun ShelfTail() {
    Spacer(Modifier.navigationBarsPadding().height(Gap.shelfTail))
}

/** 未登录时那几格假数据顶上的小标记。一句"示例"够了，不用一整条横幅。 */
@Composable
fun SampleTag(modifier: Modifier = Modifier) {
    val ink = LocalInk.current
    Text(
        text = "示例",
        fontSize = Type.tiny,
        color = ink.faint,
        modifier = modifier
            .background(ink.hairline, Radius.tag)
            .padding(horizontal = Gap.xs, vertical = 2.dp),
    )
}

/**
 * 失败态：一行原因 + 一颗「重试」。
 *
 * 原因用服务端/网络给的那句短的，超过一行就截断 —— 用户要的是"现在没拿到、能不能再来一次"，
 * 不是一篇说明文。
 */
@Composable
fun ErrorLine(text: String, modifier: Modifier = Modifier, retry: (() -> Unit)? = null) {
    val ink = LocalInk.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = text,
            fontSize = Type.meta,
            lineHeight = Type.metaLine,
            color = MaterialTheme.colorScheme.error,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (retry != null) {
            Spacer(Modifier.width(Gap.sm))
            Text(
                text = "重试",
                fontSize = Type.meta,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.pressClick(onClick = retry).padding(horizontal = Gap.xs, vertical = Gap.xs),
            )
        }
    }
}

/* ───────────── 二次确认：举着的那一下要自己放下 ───────────── */

/**
 * 二次确认的开关。
 *
 * 为什么单独立一个：以前「再点一次确认结束」和「再点一次就退出」都是点第一下就永久 armed，
 * 界面一直停在"再按一下就没了"那个样子。跑步时手是抖的、口袋里也会误触，
 * 误触一下就得自己记得回来把它取消，不然下一次按就是真按下去了（他 10-22 那句
 * 「点一次就不会复原了如果误触就不好了」）。所以给一个窗口：[seconds] 秒内按第二下才算确认，
 * 过了自己回到没 armed。
 *
 * 倒计时写进按钮文字里，让人看得见它会在几秒后自己放下 —— 不写的话突然变回原样更像坏了。
 */
@Composable
fun rememberArmConfirm(seconds: Int = 4): ArmConfirm {
    var armed by remember { mutableStateOf(false) }
    var countdown by remember { mutableStateOf(0) }
    LaunchedEffect(armed) {
        if (!armed) {
            countdown = 0
            return@LaunchedEffect
        }
        for (s in seconds downTo 1) {
            countdown = s
            delay(1000)
        }
        armed = false
    }
    return ArmConfirm(armed, countdown, { armed = true }, { armed = false })
}

/** [armed]=false 时按第一下走 [arm]；已经 armed 就按 [disarm] 或按确认那一路。 */
@Immutable
class ArmConfirm(
    val armed: Boolean,
    val countdown: Int,
    val arm: () -> Unit,
    val disarm: () -> Unit,
)
