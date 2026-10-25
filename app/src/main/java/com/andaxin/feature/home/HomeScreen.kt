package com.andaxin.feature.home

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andaxin.R
import com.andaxin.app.LocalAppGraph
import com.andaxin.core.design.Gap
import com.andaxin.core.design.LocalInk
import com.andaxin.core.design.Palette
import com.andaxin.core.design.Radius
import com.andaxin.core.design.Rhythm
import com.andaxin.core.design.Type
import com.andaxin.data.capture.HomeExtras
import com.andaxin.core.net.RemoteImage
import com.andaxin.core.schedule.Periods
import com.andaxin.core.settings.Appearance
import com.andaxin.core.settings.HeroStyle
import com.andaxin.core.schedule.shortRoom
import com.andaxin.data.TimetableSlot
import com.andaxin.data.capture.SchoolService
import com.andaxin.shell.Chip
import com.andaxin.shell.Hairline
import com.andaxin.shell.LoadWhy
import com.andaxin.shell.MetricCell
import com.andaxin.shell.RowLine
import com.andaxin.shell.SampleTag
import com.andaxin.shell.Refreshable
import com.andaxin.shell.Sheet
import com.andaxin.shell.ShelfTail
import com.andaxin.shell.enterFade
import com.andaxin.shell.pressClick
import com.andaxin.shell.adxViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.andaxin.shell.ErrorLine
import com.andaxin.state.HomeModel

/*
 * 首页。10-20 晚重排过一次，方向是"少一点、密一点"：
 *
 * ① 原来六行「标签 — 值」的流水账（今天/下一节/未读/查寝/跑量/晨跑）换成**两行三格的数字**，
 *    数字给 [Type.num]、单位缩在右下 —— 要看的是数，不是"本学期跑步"这五个字；
 * ② 今日课程原来一门课一张卡，三节课就是三张卡叠着，中间全是缝 ——
 *    改成**一张卡里三行 + 分隔线**，这也是这一屏最占地方的那一段；
 * ③ 服务清单原来是"一行一个、右边一个域名"，七行拉得老长 ——
 *    改成四列图标网格，名字下面不留副标题；
 * ④ 底部那几段取证说明全部拿掉了 —— 界面只留一句状态，那些话在 docs/接入清单.md。
 */

@Composable
fun HomeScreen(open: (String) -> Unit = {}, modifier: Modifier = Modifier) {
    val graph = LocalAppGraph.current
    val model = adxViewModel { HomeModel(it.dashboard, it.timetable) }
    val state by model.state.collectAsStateWithLifecycle()
    val ink = LocalInk.current
    /*
     * 首页下半屏那些东西（服务清单、轮播图、联系方式、校历）是另外四条接口，
     * 和上面那一叠数字不是一个来源，所以各存各的状态；但它们**作为一个整块一次出现**。
     *
     * key 用的是 `session.generation`，不是 `Unit`：冷启动时这五发和"被顶号之后自动重登"
     * 撞在一起，三条拿着旧票先发了出去，屏幕上就写着「轮播图没拉到：登录已在别处被顶掉」，
     * 而同一屏的校历是真数据 —— 看着像只接好了一半。换票之后重拉一次。
     */
    val session by graph.session.state.collectAsStateWithLifecycle(com.andaxin.core.store.Session())
    // 首页壁纸跟着「设置 → 主题」里选的那一档走：自动/晨雾/金树/月夜
    val look by graph.appearance.state.collectAsStateWithLifecycle(initialValue = Appearance())
    /*
     * 首页下半屏那一栏的数据。以前这里挂的是 `graph.home.load()` 那一发（服务清单 / 轮播图 /
     * 联系方式 / 校历），屏上最后只剩「求助电话」一组号码 —— 10-23 深夜他说"删了换成待办"，
     * 所以这一栏改成数今天该办的事；那条接口还在数据层里，只是不再上屏。
     *
     * key 用 `session.generation`：换票之前发出去的那几发拿着旧票，会写出一句
     * 「登录已在别处被顶掉」而同一屏别处是真数据，看着像只接好了一半。
     */
    // 只剩校历那一行还在用这一发（「第 X 周」那句话是服务端给的，跟着问候语走）；
    // 同一发里的联系方式（求助电话）10-23 深夜起不再上屏了。
    var extras by remember { mutableStateOf<HomeExtras?>(null) }
    LaunchedEffect(session.generation) {
        runCatching { graph.home.load() }.getOrNull()?.onSuccess { extras = it }
    }

    /*
     * 首页那一栏「待办事务」的四个数。排版照官方那一屏（23:1x 读屏读到的）：
     * 一句小标题 + 一格一个数、数下面挂名字，**不是列表**。
     *
     * 数从哪来：**四栏各自的列表口自己数**。官方那一屏四个数全 0，而它用的是
     * `flowRuTask/getYwdtCount.do` 那四个 redDot —— 那条口 10-20 现打定死不能用
     * （零业务参数、`approvedType` 收了完全忽略、六次响应逐字节相同，而同一把会话下
     * 列表口回 21 条时它四个数全 0）。所以我们不拿它当权威，谁那一栏就用谁那条列表口的条数。
     *
     * 四发**并着发、一起出现**（他定过一条：多数据源那一屏不许一路加载一路蹦），
     * 哪一发挂了那一格就摆「—」而不是 0 —— 没拿到和没有是两件事。
     */
    val todoTabs = listOf("待办", "待阅", "我的申请", "已办")
    var todoCounts by remember { mutableStateOf<List<String>?>(null) }
    var todoWhy by remember { mutableStateOf<String?>(null) }
    var todoTry by remember { mutableStateOf(0) }
    LaunchedEffect(session.generation, todoTry) {
        todoCounts = null
        todoWhy = null
        val feeds = listOf(graph.affairList, graph.toReadList, graph.myAffairList, graph.handledList)
        val got: List<Result<List<com.andaxin.data.brief.BriefRow>>> = coroutineScope {
            feeds.map { f -> async { runCatching { f.rows() }.getOrElse { Result.failure(it) } } }
                .map { it.await() }
        }
        todoCounts = got.map { r -> r.getOrNull()?.size?.toString() ?: "—" }
        todoWhy = got.firstNotNullOfOrNull { it.exceptionOrNull()?.message }
    }

    val d = state.data
    /*
     * 顶卡只报"今天、还没结束"的那一节。
     *
     * 10-21 13:3x 他打开首页，顶卡写着"下一节 高等数学 10:25–11:10"，而那节课 12:00 就上完了 ——
     * 两个毛病叠在一起：① 数据层兜底取了"今天最后一节"，不管过没过；② 垫屏快照没带日期，
     * 昨天存下的"下一节"今天照样上屏。现在数据层两处都堵了，这里再按真实时钟自己算一遍：
     * 一节课都没有（今天没课 / 都上完了）就换成壁纸，不再挂着一条过去时的"下一节"。
     *
     * 30 秒一跳：跨过下课那一刻，卡会自己换成壁纸，不用杀进程重进。
     */
    var clock by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            clock++
        }
    }
    val nowMin = remember(clock) { Periods.minuteOfDay() }
    val upcoming = remember(state.today, nowMin) {
        state.today
            .filter { slot ->
                val end = Periods.endMinute(slot.startPeriod + slot.periodCount - 1) ?: return@filter false
                end > nowMin
            }
            .minByOrNull { Periods.startMinute(it.startPeriod) ?: Int.MAX_VALUE }
    }

    // 下拉这一把手：数据只在进屏时拉一次，签完查寝、批完假之后不想杀进程重开。
    // 刷新时旧内容留在原位（不清空、不闪），新的到了再换 —— 一路加载一路蹦是他最烦的。
    // 两趟一起等：上面那一叠数和下半屏那几发不是一个来源，只等一边就是假动作。
    Refreshable({
        model.refresh()
        todoTry++
        runCatching { graph.home.load() }.getOrNull()?.onSuccess { extras = it }
    }, modifier.fillMaxSize()) {
        val scroll = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = Gap.screen),
        ) {
            Spacer(Modifier.height(Gap.md))
            /*
             * 问候语是服务端给的（`notReadCount.do` 的 `date`，现打对过），我们只在后面接一个名字。
             * 名字太长（少数民族姓名、英文名）就不接：那一行会折成两行，问候语就不像问候语了。
             */
            val name = session.userName.trim()
            /*
             * 折叠大标题（照他发的那份规范）：静止 28sp，往上滚最多 60dp 收到 18sp；
             * 周次那行跟着淡出。没有做"缩到中间顶栏"那一半 —— 我们这屏没有独立顶栏，
             * 硬做会变成一颗悬空的字。
             */
            val collapse by remember { derivedStateOf { (scroll.value / 60f).coerceIn(0f, 1f) } }
            val titleSize = Type.display * (1f - collapse * 0.36f)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = (d?.greeting ?: "…") + if (session.loggedIn && name.length in 2..4) "，$name" else "",
                    fontSize = titleSize,
                    fontWeight = FontWeight.SemiBold,
                    color = ink.strong,
                    modifier = Modifier.weight(1f),
                )
                // 屏幕上这批数字是上一份快照时，明写"更新中" —— 不把旧数字冒充成刚拉的
                if (state.refreshing && state.data != null) {
                    Text("更新中…", fontSize = Type.micro, color = ink.faint)
                }
            }
            /*
             * 没登录时这一屏那几个数是本机编的示例（`LocalDashboard`）。
             * 「只要还在用 Local*，界面上那条说明就不许摘」是这条线的规矩，而首页是原来
             * 唯一漏掉的一屏：「我的」写着"未登录，看的是占位数据"，首页却把"今天有课 1 节"
             * 当真的报给人 —— 那一句还是屏幕上的第一行字。
             */
            if (!session.loggedIn) {
                Spacer(Modifier.height(Gap.sm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SampleTag()
                    Spacer(Modifier.width(Gap.sm))
                    Text(
                        text = "还没登录，下面这几个数是示例。",
                        fontSize = Type.meta,
                        color = ink.faint,
                    )
                }
            }
            // 校历那一行跟着问候语走：它就是"今天算第几周"这句话，不该单独占一个区块
            if (!extras?.calendar.isNullOrBlank()) {
                Spacer(Modifier.height(Gap.xxs))
                Text(extras!!.calendar, fontSize = Type.meta, color = ink.faint)
            }

            /*
             * 首屏那一块：今天还有课就是主色"下一节"卡（课名、教室、还有几分钟，一眼看完）；
             * 今天没课或者都上完了，换一张壁纸，不摆一条过去时的课。
             * 课表还没拉到（`todayKnown=false`）时两块都不摆 —— 说"今天没课"是假话。
             */
            when {
                upcoming != null -> {
                    Spacer(Modifier.height(Gap.md))
                    NextClassCard(upcoming, nowMin, ink.dark, Modifier.enterFade(session.generation))
                }
                else -> {
                    // 这一格以前要等课表回来（todayKnown）才摆 —— 每次进应用，第一张卡片
                    // 都空着等一会儿再"闪"出来（他 10-23 报的就是这个）。现在卡片**第一帧就在**，
                    // 课表还没到只是把上面那两行换成时段问候 + 一句"还在拉"，
                    // 不替学校宣布你今天有没有课。
                    Spacer(Modifier.height(Gap.md))
                    HeroWallpaper(
                        style = look.hero,
                        hadClassToday = state.today.isNotEmpty(),
                        known = state.todayKnown && !state.firstLoad,
                        modifier = Modifier.enterFade(session.generation),
                    )
                }
            }

            Spacer(Modifier.height(Gap.md))
            Sheet(modifier = Modifier.enterFade(session.generation)) {
                Row(Modifier.fillMaxWidth()) {
                    MetricCell("今天有课", d?.todayLessons?.let { "$it" } ?: "—", "节", modifier = Modifier.weight(1f))
                    MetricCell("未读消息", d?.let { "${it.unreadMessages}" } ?: "—", "条", modifier = Modifier.weight(1f))
                    /*
                     * 查寝那一格：服务端给的是 "10-21 21:00" 这种带日期的串。
                     * 首页本来就是"今天/今晚"，日期在这儿是重复的 —— 今天那一场只留 "21:00"，
                     * 26sp 就放得下了；不是今天的（比如已过期的上一场）才整串照搬，不替它裁。
                     */
                    val dormRaw = d?.dormCheckState?.trimStart('—')?.trim().orEmpty()
                    val todayPrefix = java.text.SimpleDateFormat("MM-dd", java.util.Locale.US).format(java.util.Date())
                    val dormToday = dormRaw.startsWith(todayPrefix)
                    MetricCell(
                        label = if (dormToday) "今晚查寝" else "查寝",
                        value = dormRaw.removePrefix(todayPrefix).trim().ifBlank { "—" },
                        unit = "",
                        size = if (dormToday) Type.num else Type.title,
                        modifier = Modifier.weight(1.4f),
                    )
                }
                Spacer(Modifier.height(Gap.md))
                Hairline()
                Spacer(Modifier.height(Gap.md))
                Row(Modifier.fillMaxWidth()) {
                    // 两格各占一半。以前后面还挂了一个 `Spacer(weight(1f))`，
                    // 为的是跟上面那行三格对齐 —— 代价是这两格各只有三分之一宽，
                    // "51.34 公里" 放不下，数字被掐成 "51 …"。那个省略号不是"没数"，
                    // 学校给的数一直在，是这里把它截掉了。对齐没有数重要。
                    MetricCell(
                        "学期跑量",
                        d?.runSemesterKm?.let { "%.2f".format(it) } ?: "—",
                        "公里",
                        // 三位数跑量（"123.45"）再降一档字号，别再走截断那条路
                        size = if ((d?.runSemesterKm ?: 0.0) >= 100.0) Type.title else Type.num,
                        modifier = Modifier.weight(1f),
                    )
                    MetricCell(
                        "其中晨跑",
                        d?.runMorningKm?.let { "%.2f".format(it) } ?: "—",
                        "公里",
                        size = if ((d?.runMorningKm ?: 0.0) >= 100.0) Type.title else Type.num,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // 跑量那一格挂不上时，原因写在下面这句里，不塞进格子里当值。
            state.error?.let { ErrorLine(it) }

            if (state.today.isNotEmpty()) {
                // 一门课一张卡是上一版最"肿"的地方：三张卡之间的缝比内容还显眼
                SectionBlock("今日课程")
                Sheet(modifier = Modifier.enterFade()) {
                    state.today.forEachIndexed { i, slot ->
                        RowLine(
                            title = slot.course,
                            // 跟课程表同一张作息表：几点到几点写在副标题最前面，教室跟在后面
                            sub = listOf(
                                com.andaxin.core.schedule.Periods.rangeText(slot.startPeriod, slot.periodCount).orEmpty(),
                                shortRoom(slot.room),
                            ).filter { it.isNotBlank() }.joinToString(" · "),
                            right = if (slot.periodCount > 1)
                            // 连堂的要写全"第 1-2 节"，只写起始节次会让人以为 9:40 就下课
                                "第 ${slot.startPeriod}-${slot.startPeriod + slot.periodCount - 1} 节"
                            else "第 ${slot.startPeriod} 节",
                        )
                        if (i != state.today.lastIndex) Hairline()
                    }
                }
            }

            // 四枚快捷格只负责跳，不在这儿摆数据：数据在各自那一屏里。
            Spacer(Modifier.height(Rhythm.sectionGap))
            Row(
                horizontalArrangement = Arrangement.spacedBy(Gap.sm),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                listOf("运动" to com.andaxin.shell.Route.SPORT, "查寝" to com.andaxin.shell.Route.DORM,
                    "校园卡" to com.andaxin.shell.Route.CARD, "报修" to com.andaxin.shell.Route.REPAIR,
                    "AI 课堂" to com.andaxin.shell.Route.AICLASS, "待办" to com.andaxin.shell.Route.AFFAIR,
                    "第二课堂" to com.andaxin.shell.Route.DEKT)
                    .forEach { (name, at) -> Chip(name, picked = false) { open(at) } }
            }

            /*
             * 首页下半屏这一栏。以前是「求助电话」（三个报警号 + 一条校内电话），
             * 10-23 深夜他一句"底下求助电话那一栏删了换成待办"换掉了 —— 号码那条接口照旧在数据层里，
             * 只是不再上屏。排版按官方那一屏的待办事务模块：小标题 + 四个数各挂一个名字，点哪格进哪一栏。
             */
            SectionBlock("待办事务")
            Sheet(modifier = Modifier.enterFade(if (todoCounts == null) 0 else 1)) {
                Row(Modifier.fillMaxWidth()) {
                    todoTabs.forEachIndexed { i, name ->
                        val v = todoCounts?.getOrNull(i) ?: "—"
                        MetricCell(
                            label = name,
                            value = v,
                            unit = "",
                            accent = if (v != "—" && v != "0") MaterialTheme.colorScheme.primary else null,
                            modifier = Modifier
                                .weight(1f)
                                .pressClick(shrink = false) { open(com.andaxin.shell.affairTab(i)) },
                        )
                        if (i != todoTabs.lastIndex) Spacer(Modifier.width(Gap.sm))
                    }
                }
            }
            todoWhy?.let {
                Spacer(Modifier.height(Gap.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(Gap.sm), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = it,
                        fontSize = Type.meta,
                        color = ink.faint,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Chip("重试", picked = false) { todoTry++ }
                }
            }
            ShelfTail()
        }
    }
}

/**
 * 「下一节 / 正在上课」主色卡（照他发的那份规范里的 Now-Focus Card）。
 *
 * 左边课名 + 教室，右边一颗小药丸写"还有 N 分钟"或这一节的起止时间。
 * 钟点全部从 `Periods` 那张官方作息表查，和课程表同一份；连堂按 `periodCount` 算到最后一节，
 * 不再把 10:25–12:00 的课写成 10:25–11:10。
 */
@Composable
private fun NextClassCard(slot: TimetableSlot, nowMinute: Int, dark: Boolean, modifier: Modifier = Modifier) {
    val lastPeriod = slot.startPeriod + slot.periodCount - 1
    val startMin = Periods.startMinute(slot.startPeriod)
    val endMin = Periods.endMinute(lastPeriod)
        ?: startMin?.plus(45)
        ?: 0
    val inClass = startMin != null && nowMinute in startMin until endMin
    val toStart = (startMin ?: 0) - nowMinute

    val pill = when {
        inClass -> "还有 ${(endMin - nowMinute).coerceAtLeast(1)} 分钟下课"
        toStart in 0..90 -> "还有 $toStart 分钟"
        else -> Periods.rangeText(slot.startPeriod, slot.periodCount).orEmpty()
    }

    val shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
    val bg = if (dark) {
        Brush.linearGradient(listOf(Palette.NightCardBlue, Color(0xFF16213F)))
    } else {
        Brush.linearGradient(listOf(Color(0xFF2F6BFF), Color(0xFF1E44C9)))
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg, shape)
            .padding(horizontal = Gap.lg, vertical = Gap.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = if (inClass) "正在上课" else "下一节",
                fontSize = Type.micro,
                color = Palette.Cream.copy(alpha = 0.72f),
            )
            Spacer(Modifier.height(Gap.xs))
            Text(
                text = slot.course,
                fontSize = (Type.title.value + 2).sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 2,
                lineHeight = Type.bodyLine,
            )
            if (slot.room.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = shortRoom(slot.room),
                    fontSize = Type.meta,
                    color = Palette.Cream.copy(alpha = 0.82f),
                    maxLines = 1,
                )
            }
        }
        if (pill.isNotBlank()) {
            Spacer(Modifier.width(Gap.md))
            Box(
                Modifier
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = if (dark) 0.14f else 0.18f))
                    .padding(horizontal = Gap.md, vertical = Gap.sm),
            ) {
                Text(pill, fontSize = Type.meta, color = Color.White, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/**
 * 今天没课（或者今天的课都上完了）时首屏那张壁纸。
 *
 * 为什么不是一块空白或者一句灰字：这一块是首页第一眼，空着整屏就塌了。
 * 三张图按一天的时段挑 —— 早上晨雾、下午金树、夜里月亮，正好跟"现在"对得上。
 * 图是打包进 `res/drawable-nodpi` 的（没有网络也能出），上面压一层深色渐变，
 * 白字放在任何一张图上都读得清。
 */
@Composable
private fun HeroWallpaper(style: HeroStyle, hadClassToday: Boolean, known: Boolean, modifier: Modifier = Modifier) {
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    // 自动跟一天的时段对：早上晨雾、下午金树、夜里月亮；钉死就照设置里那张。
    val picture = when (style) {
        HeroStyle.Dawn -> R.drawable.hero_dawn
        HeroStyle.Dusk -> R.drawable.hero_dusk
        HeroStyle.Night -> R.drawable.hero_night
        HeroStyle.Auto -> when (hour) {
            in 5..11 -> R.drawable.hero_dawn
            in 12..17 -> R.drawable.hero_dusk
            else -> R.drawable.hero_night
        }
    }
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
    Box(
        modifier
            .fillMaxWidth()
            .height(168.dp)
            .clip(shape),
    ) {
        Image(
            painter = painterResource(picture),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        // 下半截压暗：白字压在任何一张图上都要能读
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.35f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.58f),
                    )
                ),
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = Gap.lg, vertical = Gap.lg),
        ) {
            Text(
                text = when {
                    // 还不知道今天有没有课：只报时辰，不替学校下结论
                    !known -> when (hour) {
                        in 5..11 -> "早上好"
                        in 12..17 -> "下午好"
                        in 18..22 -> "晚上好"
                        else -> "夜深了"
                    }
                    hadClassToday -> "今天的课都上完了"
                    else -> "今天没有课"
                },
                fontSize = (Type.title.value + 2).sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = when {
                    !known -> "课表还在拉，稍等一下"
                    hadClassToday -> "剩下的时间，留给你自己"
                    else -> "享受你的大学生活"
                },
                fontSize = Type.meta,
                color = Color.White.copy(alpha = 0.82f),
            )
        }
    }
}

/** 区块小标题：字号走 [Type.meta]，但比 SectionTitle 那种灰字稍重一点。 */
@Composable
private fun SectionBlock(text: String) {
    Text(
        text = text,
        fontSize = Type.meta,
        fontWeight = FontWeight.Medium,
        color = LocalInk.current.muted,
        modifier = Modifier.padding(top = Rhythm.sectionGap, bottom = Gap.xs, start = Gap.xxs),
    )
}

/** 只在需要时把 host 拿出来用（服务清单的副标题已经不显示了，留着给详情用）。 */
internal fun hostOf(url: String): String = runCatching { Uri.parse(url).host }.getOrNull().orEmpty()
