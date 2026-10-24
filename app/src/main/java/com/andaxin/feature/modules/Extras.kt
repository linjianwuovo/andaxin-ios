package com.andaxin.feature.modules

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.andaxin.shell.Chip
import com.andaxin.shell.LoadWhy
import com.andaxin.shell.pressClick
import com.andaxin.app.LocalAppGraph
import com.andaxin.core.design.Gap
import com.andaxin.core.design.LocalInk
import com.andaxin.core.design.Palette
import com.andaxin.core.design.Radius
import com.andaxin.core.design.Type
import com.andaxin.feature.brief.ScaffoldPage
import com.andaxin.shell.Hairline
import com.andaxin.shell.Labeled
import com.andaxin.shell.RowLine
import com.andaxin.shell.Sheet

/**
 * 更多功能那一格：只是入口，不取任何数据。
 * 顺序按"哪块证据厚"排，接报文时从上往下推。
 */
private val MORE = listOf(
    "报修" to "repair",
    "待办事务" to "affair",
    "AI 课堂" to "aiclass",
    "公开课" to "opencourse",
    "查寝签到" to "dorm",
    "考试成绩" to "exam",
    "素质学分" to "credit",
    "劳动教育" to "labor",
    "节假日离返校" to "holiday",
    "校园卡" to "card",
    "班级群" to "group",
    // 凡是"要不要响、响在哪、系统让不让响"都收在那一屏里（上课提醒以前挂在「我的」最底下）
    "通知设置" to "notify",
)

@Composable
fun MoreScreen(open: (String) -> Unit, onBack: () -> Unit) {
    val ink = LocalInk.current
    ScaffoldPage(title = "更多功能", onBack = onBack) {
        Sheet {
            MORE.forEachIndexed { i, (label, route) ->
                RowLine(title = label, right = "›", onClick = { open(route) })
                if (i != MORE.lastIndex) Hairline()
            }
        }
    }
}

/**
 * 校园卡。17wanxiao 是第三方，要先用校方会话换它的会话，所以这一块接起来是三跳
 * （取证与坑见 `data/capture/CaptureCampusCard.kt` 顶上那段）。
 */
@Composable
fun CampusCardScreen(open: (String) -> Unit, onBack: () -> Unit) {
    val ink = LocalInk.current
    val (card, why, page, reload) = rememberCard()
    ScaffoldPage(title = "校园卡", onBack = onBack, onRefresh = { reload() }) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .background(if (ink.dark) Palette.NightCardBlue else Palette.Blue, Radius.card)
                .padding(Gap.lg),
        ) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Text("安徽信息工程学院 · 校园卡", fontSize = Type.body, color = Palette.Cream)
                Column {
                    Text(
                        card?.let { "¥ %.2f".format(it.balance) } ?: "¥ —",
                        style = Type.tabular,
                        fontSize = Type.hero, fontWeight = FontWeight.SemiBold, color = Palette.Cream,
                    )
                    Text(
                        text = when {
                            card != null -> "补助钱包 ¥ %.2f · ${card.statusText}".format(card.subsidy)
                            why != null -> "余额暂时没取到"
                            else -> "正在取余额…"
                        },
                        fontSize = Type.micro, color = Palette.Cream.copy(alpha = 0.78f),
                    )
                }
            }
        }
        // 为什么没取到要写在卡面下面：卡面上只有一句"没取到"，人没法判断是会话、网络还是第三方。
        // 这一层走 LoadWhy：没登录是灰字 + 去登录，真失败才是红色那句。
        if (why != null) {
            Spacer(Modifier.height(Gap.sm))
            LoadWhy(why, wide = false)
            // 校方 10-21 把「完美校园」换成了自己的 H5，原生这条换不出会话时，
            // 至少给一条官方页面能点（页面地址和应用里其它服务同源，身份走 query）。
            if (!page.isNullOrBlank()) {
                Spacer(Modifier.height(Gap.sm))
                Chip("打开校园卡交易页", picked = false) {
                    open("web/" + com.andaxin.feature.web.encodeUrl(page))
                }
            }
        }
        Spacer(Modifier.height(Gap.lg))
        Sheet {
            Labeled(
                label = "最近一笔",
                value = when {
                    card == null -> "—"
                    card.flowsPending -> "流水还在拉…"
                    else -> card.flows.firstOrNull()?.let {
                        "%s · %s".format(it.at.take(16), money(it))
                    } ?: "这 30 天没有消费"
                },
            )
            Spacer(Modifier.height(Gap.sm)); Hairline(); Spacer(Modifier.height(Gap.sm))
            Labeled(
                label = "今天支出",
                // 各看各的那一发：今天那一发回来就能填这格，不用等 200 条那发
                value = card?.let { if (!it.todayKnown) "…" else "¥ %.2f".format(it.todayOut) } ?: "—",
            )
            Spacer(Modifier.height(Gap.sm)); Hairline(); Spacer(Modifier.height(Gap.sm))
            Labeled(
                label = "近 30 天支出",
                value = card?.let { if (it.windowPending) "…" else "¥ %.2f".format(it.windowOut) } ?: "—",
            )
            if (card != null && card.validUntil.isNotBlank()) {
                Spacer(Modifier.height(Gap.sm)); Hairline(); Spacer(Modifier.height(Gap.sm))
                Labeled(label = "卡有效期", value = card.validUntil)
            }
        }
        Spacer(Modifier.height(Gap.lg))
        /*
         * 原来这一行是「流水 | 挂失」两颗并排，右边那颗画成按不动的样子（灰底、ghost 字色、没有回调）。
         * 10-21 凌晨深色扫屏时把它摘了：**一颗永远按不动的按钮仍然是一颗按钮** ——
         * 它有按钮的形状、占按钮的位置，人会去按它，按完发现没反应。
         * 而它想说的全部信息，下面那行小字已经说得更清楚了。
         * 「点了没用的主按钮比少一颗按钮坏得多」这条规矩，对哑按钮同样成立。
         */
        Box(
            Modifier.fillMaxWidth().height(46.dp).background(ink.offFill, Radius.field)
                .pressClick { open("card/flows") }.padding(Gap.md),
            contentAlignment = Alignment.Center,
        ) { Text("流水", fontSize = Type.body, color = ink.body) }
        Spacer(Modifier.height(Gap.sm))
        Text(
            text = "挂失要去学生活动中心，App 里办不了。",
            fontSize = Type.micro,
            color = ink.faint,
        )
        Spacer(Modifier.height(Gap.md))
    }
}

/** 卡片数据只给校园卡两屏用，就地取，不再多开一个 Model。 */
@Composable
private fun rememberCard(): CardHolder {
    val graph = LocalAppGraph.current
    var card by remember { mutableStateOf<com.andaxin.data.capture.CampusCardClient.Card?>(null) }
    var why by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        // 三刀分三次上屏：余额 → 今天支出（顺带今天的最近一笔）→ 近 30 天那 200 条。
        // 以前是余额和流水一起等最慢那一发（他 10-23 那句「校园卡数据加载慢」）。
        val r = graph.card.card(onStage = { card = it })
        card = r.getOrNull() ?: card
        why = r.exceptionOrNull()?.message
        // 没拿到才去问"官方页面在哪"：拿到了原生数据就不必多这一条
        page = if (r.isSuccess) null else graph.card.pageUrl()
    }
    LaunchedEffect(Unit) { reload() }
    return CardHolder(card, why, page) { reload() }
}

/** [rememberCard] 的四样东西。Triple 装不下了，单独起一个名字，读的地方也清楚。 */
private data class CardHolder(
    val card: com.andaxin.data.capture.CampusCardClient.Card?,
    val why: String?,
    val page: String?,
    val reload: suspend () -> Unit,
)

private fun money(f: com.andaxin.data.capture.CampusCardClient.Flow): String =
    (if (f.income) "+" else "-") + "%.2f".format(f.yuan) + (if (f.what.isBlank()) "" else " ${f.what}")

/**
 * 流水。服务端**不给合计、不给余额快照**，所以这里只列它给的行。
 * 分页键是 `count`（不是 pageSize）—— 少这一个键它只回总数不回行，见契约文件那条坑。
 */
@Composable
fun CardFlowsScreen(onBack: () -> Unit) {
    val ink = LocalInk.current
    val holder = rememberCard()
    val card = holder.card
    val why = holder.why
    ScaffoldPage(title = "校园卡流水", onBack = onBack, onRefresh = { holder.reload() }) {
        if (card == null) {
            Text(
                text = why ?: "正在取…",
                fontSize = Type.body,
                color = if (why == null) ink.faint else MaterialTheme.colorScheme.error,
            )
        }
        card?.flows?.forEach { f ->
            Sheet {
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(f.at.take(16), fontSize = Type.body, color = ink.muted)
                        Text(
                            money(f), fontSize = Type.body,
                            color = if (f.income) (if (ink.dark) Palette.NightTeal else Palette.TealInk) else ink.body,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Spacer(Modifier.height(Gap.xs))
                    Text(
                        text = listOf(f.what, f.term).filter { it.isNotBlank() }.joinToString(" · ")
                            .ifBlank { "（无说明）" },
                        fontSize = Type.micro, color = ink.faint,
                    )
                }
            }
            Spacer(Modifier.height(Gap.sm))
        }
    }
}

// 平移注：登录（LoginScreen）和免责声明（OnboardingScreen）这一批进共享层了，
// 在 `shared/src/logicMain/kotlin/com/andaxin/feature/modules/Login.kt`，包名没变。
