package com.andaxin.feature.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andaxin.app.LocalAppGraph
import com.andaxin.core.design.Gap
import com.andaxin.core.design.LocalInk
import com.andaxin.core.design.Palette
import com.andaxin.core.design.Radius
import com.andaxin.core.design.Type
import com.andaxin.core.schedule.Periods
import com.andaxin.core.schedule.shortRoom
import com.andaxin.data.TimetableSlot
import com.andaxin.shell.Chip
import com.andaxin.shell.Hairline
import com.andaxin.shell.RowLine
import com.andaxin.shell.Refreshable
import com.andaxin.shell.Sheet
import com.andaxin.shell.enterFade
import com.andaxin.shell.pressClick
import com.andaxin.shell.adxViewModel
import com.andaxin.shell.ErrorLine
import com.andaxin.shell.ShelfTail
import com.andaxin.state.TimetableModel
import kotlinx.coroutines.delay

private val DAYS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/** 每节课的起止时刻。表在 `Periods` 里，和首页"下一节"共用一份。 */
private val PERIOD_TIMES = Periods.START_AT

/**
 * 一行多高。58 → 44：11 节课原来 638dp，一屏装不下必须滚，
 * 而中午 12:00–14:00 那几行空档也是 58dp，看着像课表缺了一大块。
 */
private const val ROW_HEIGHT = 44
private const val GUTTER = 28

/** 星期头那一行的高度：左列也要垫同样高，不然时间轴和格子对不齐。 */
private const val HEADER_HEIGHT = 36

/**
 * 一格多高：**放得下就把行拉开，放不下就还是 44dp 让它滚**。
 *
 * 原来写死 44dp，12 节一共 528dp；在 411×914dp 这块屏上第 12 节底下会空出 100dp 出头一条白，
 * 而他一眼看见的就是那条白（10-21 早上他说的就是这句"底下留了一大块白"）。
 *
 * 减数 138dp 是**在这台机器上量出来的**（1440×3200 / density 3.5，App 内容区 783dp 高）：
 * 节次区从内容区 y=130dp 起，目标是让最后一行收在 775dp（离内容区底 8dp）。
 * 于是 `(783 − 138) / 10 = 64.5dp` 一行；这 138 就是"网格以上的固定高度"（标题 + 周次芯片 +
 * 表头 + 几处行距），和行高无关，所以只从视口里减一次。
 * **底栏那条玻璃画在内容区之外**（上沿在屏幕 818dp，内容区到 816dp 就完了），
 * 肉眼上看是网格离底栏约 43dp 的一段呼吸。
 *
 * 上限 66dp：再往上拉，一格空格子会显得比课本身还重。
 * 换了字号或改了标题区，这 138dp 会跟着变，偏了的表现是"底下多一条窄白"或"贴住底栏"，
 * 都不是错位；真要跟着变，量一次这个数就行。
 *
 * `extraChrome` 是网格上面**这一周额外多出来的那一行**（目前只有"晨跑活动"那条：
 * 校方给的 `ksjc=0` 占位，没节次，不能塞进格子）。它多高，就从视口里再减多高。
 */
private fun rowHeightFor(viewport: Dp, extraChrome: Dp = 0.dp): Dp {
    val gridRoom = viewport - 138.dp - extraChrome
    return (gridRoom / PERIOD_TIMES.size.toFloat()).coerceIn(ROW_HEIGHT.dp, 66.dp)
}

@Composable
fun TimetableScreen(modifier: Modifier = Modifier) {
    val graph = LocalAppGraph.current
    val model = adxViewModel { TimetableModel(it.timetable, it.store) }
    val state by model.state.collectAsStateWithLifecycle()
    val ink = LocalInk.current

    /*
     * 周几、现在第几节，都跟着真实时钟走：每分钟重新取一次。
     * 以前这里是 `remember { Calendar... }` 一次算完就不动了 —— 网格开着跨过一节课，
     * 高亮还停在上一节，看着像课表坏了。
     */
    var minute by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            minute++
        }
    }
    val today = remember(minute) { Periods.today() }
    val periodNow = remember(minute) { Periods.periodNow() }
    var showDayMode by remember { mutableStateOf(false) }

    // 这一屏也支持下拉：整周没拿到时，下面会给一条「重试」，但用户更习惯直接拽一下
    Refreshable({ model.refresh() }, modifier.fillMaxSize()) {
        /*
         * 要一层 `BoxWithConstraints` 只是为了拿到"这一屏有多高"：格子铺不满时把行高拉开，
         * 别在 19:55 那一行底下留一条白。里面的 Column 照样滚动 —— 行高只在"能放下"的时候变大，
         * 放不下（节数多、字号大）时仍是 44dp 往下滚，不会把内容裁掉。
         */
        BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewport = maxHeight
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Gap.screen),
        ) {
            Spacer(Modifier.height(Gap.md))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Text("课程表", fontSize = Type.display, fontWeight = FontWeight.SemiBold, color = ink.strong)
                Text(
                    text = if (showDayMode) "看整周" else "只看今天",
                    fontSize = Type.meta,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.pressClick(shrink = false) { showDayMode = !showDayMode }.padding(vertical = Gap.sm),
                )
            }

            /*
             * 周次那一排。原来是「本周 / 下周」两颗 —— 只能往前看一周，第 9 周想调课表就得退出重进。
             * 现在照校方那张周次表（`selectZcList.do` 的 `rows`）排成一横行「第一周…第N周」，
             * 学校给几周就排几格，点哪格看哪周；进屏先滚到本周那一格，不让人自己找。
             */
            Spacer(Modifier.height(Gap.xxs))
            val shownWeek = if (state.now == 0) 0 else state.now + state.weekOffset
            if (state.weeks.isEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(Gap.sm)) {
                    Chip("本周", state.weekOffset == 0) { model.load(0) }
                    Chip("下周", state.weekOffset == 1) { model.load(1) }
                }
            } else {
                val weekList = rememberLazyListState()
                LazyRow(state = weekList, horizontalArrangement = Arrangement.spacedBy(Gap.sm)) {
                    items(state.weeks, key = { it }) { w ->
                        Chip(cnWeek(w), w == shownWeek) { model.load(w - state.now) }
                    }
                }
                LaunchedEffect(state.weeks, state.now) {
                    val i = state.weeks.indexOf(state.now)
                    if (i > 0) weekList.scrollToItem(i)
                }
            }

            Spacer(Modifier.height(Gap.md))

            /*
             * 校方课表里混着一条 `ksjc=0` 的「阳光晨跑 / 体育场」：那是"这天早上有晨跑"的占位，
             * 没有节次也没有时间（10-21 下午拿活 token 现打第 8 周确认）。它不能进格子 ——
             * 塞进第 1 节就是凭空多出一门 08:30 的课；但也不能悄悄扔掉，所以单独在网格上面
             * 用一行"晨跑活动"标出来。课表上真正的课从 `startPeriod >= 1` 起算。
             */
            val lessons = state.slots.filter { it.startPeriod >= 1 }
            val runs = state.slots.filter { it.startPeriod < 1 }
            val todayRuns = runs.filter { it.day == today }
            if (runs.isNotEmpty()) {
                RunNote(runs)
                Spacer(Modifier.height(Gap.sm))
            }

            if (showDayMode) {
                DayList(lessons.filter { it.day == today }.ifEmpty { lessons }, today, todayRuns)
            } else {
                Grid(
                    lessons, today, periodNow,
                    rowHeightFor(viewport, if (runs.isEmpty()) 0.dp else 52.dp),
                    state.weekOffset, state.courseColors,
                )
            }

            if (lessons.isEmpty() && !state.firstLoad && state.error == null) {
                Spacer(Modifier.height(Gap.xl))
                Text("这一周没有课。", fontSize = Type.body, color = ink.faint)
            }

            // 原来这里连着摆两遍同一个原因（一行灰字 + 一条 ErrorLine），只留带「重试」的那条
            Spacer(Modifier.height(Gap.lg))
            state.error?.let { ErrorLine(it, retry = { model.load(state.weekOffset) }) }
            ShelfTail()
        }
        }
    }
}

/**
 * 网格上面那一行「晨跑活动」。
 *
 * 它不是课，是校方课表里 `ksjc=0/ jsjc=0` 的占位（第 8 周真报文：`阳光晨跑 / 体育场`），
 * 所以不能给它编一个 08:30 塞进格子。这行只回答"哪几天早上有、在哪"，
 * 节次那一栏空着就是"校方没给时间"。
 */
@Composable
private fun RunNote(runs: List<TimetableSlot>) {
    val ink = LocalInk.current
    val accent = MaterialTheme.colorScheme.primary
    val days = runs.map { it.day }.distinct().sorted()
        .joinToString("、") { DAYS.getOrElse(it - 1) { "周?" } }
    val room = runs.firstOrNull { it.room.isNotBlank() }?.room.orEmpty()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Radius.field)
            .background(accent.copy(alpha = if (ink.dark) 0.14f else 0.07f))
            .padding(horizontal = Gap.md, vertical = Gap.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(accent))
        Spacer(Modifier.width(Gap.sm))
        Text(
            text = listOf("晨跑活动", days, room).filter { it.isNotBlank() }.joinToString(" · "),
            fontSize = Type.meta,
            color = ink.muted,
        )
    }
}

/**
 * 周几 × 第几节的网格。
 *
 * 10-20 晚这一版改了三件事，全冲着"格子读不出来"去的：
 * ① **没有课的周末不再占列**。周六周日实测常年空着，却占掉 2/7 的宽度 ——
 *    七列时每列只有约 59dp，10sp 的字一行放不下五个字，「高等数学I（1）」被断成四行；
 *    五列时每列约 86dp，一行八个字，课名两行就够。
 * ② 行高 58→44，11 节一共 484dp，一屏装得下，不用滚；
 * ③ 色块左边那根独立的小竖杠改成**整块描边**：那根杠要吃掉 5dp 内容宽度，
 *    而"哪块是哪门课"的辨识作用和描边是一样的。
 *
 * 每一列自己按 startPeriod 排，块高 = 连排节数 × 行高，块与块之间用空行垫。
 * 再往前一版不是这么排的：它把课塞进 `byCell[day*100+startPeriod]` 那张表里，
 * 每格固定一格高 —— 于是"第 1-2 节"的课只占第 1 节那一行，块的下沿停在 08:55
 * 而不是 09:55，整张表看着就是"区块没对准"。
 */
@Composable
private fun Grid(
    slots: List<TimetableSlot>,
    today: Int,
    periodNow: Int?,
    rowH: Dp,
    weekOffset: Int,
    courseColors: Map<String, Int>,
) {
    val ink = LocalInk.current
    val accent = MaterialTheme.colorScheme.primary
    // 表头第二行那个日期：这一周的周一 + (day-1)。官方那一版就是"周一 / 10-19"两行。
    val monday = remember(weekOffset) { Periods.weekMonday(offset = weekOffset).timeInMillis }
    val dayFmt = remember { java.text.SimpleDateFormat("M/d", java.util.Locale.US) }
    // 色号是 model 那边从本机读回来的那张表（一门课一个号，换周不改），这里只对色板取色
    val colors = remember(courseColors) {
        courseColors.mapValues { (_, i) -> COURSE_COLORS[i.mod(COURSE_COLORS.size)] }
    }

    val byDay = remember(slots) {
        slots.groupBy { it.day }.mapValues { (_, v) -> v.sortedBy { it.startPeriod } }
    }
    // 只留"这周真有课的几天" + 今天（今天没课也要留着，不然高亮没地方放）
    val days = remember(slots, today) {
        val busy = slots.map { it.day }.toSet()
        (1..7).filter { it in busy || it == today }
    }

    Row(Modifier.fillMaxWidth()) {
        // 左列：先垫一个和星期头等高的占位，节次才对得齐 —— 这一处就是原来错位的根
        Column(Modifier.width(GUTTER.dp)) {
            Spacer(Modifier.height(HEADER_HEIGHT.dp))
            PERIOD_TIMES.forEachIndexed { i, start ->
                /*
                 * 左边这格：**节次 + 开始 + 结束三行，全部贴这一格的上沿**。
                 *
                 * 他 08:50 那句"时间和色块没对齐、乍一看容易看错位"：原来三行是居中的，
                 * 64.5dp 一格下 08:30 落在课块的腰部 —— 眼睛对不上"这一块从几点开始"。
                 * 上沿对齐以后，课块的顶边和"1 / 08:30"是同一条线，跟官方那版一样。
                 * 也别把结束时刻挪到下沿：那样它会和下一行的节次、开始挤成一小坨，
                 * 反而更像错位（这一版中间试过，截图里 09:15 / 2 / 09:20 三行挨在一起）。
                 */
                Box(Modifier.height(rowH).fillMaxWidth()) {
                    Column(
                        Modifier.fillMaxHeight().padding(top = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Top,
                    ) {
                        val on = periodNow == i + 1
                        Text("${i + 1}", fontSize = Type.micro, color = if (on) accent else ink.faint)
                        Text(start, fontSize = Type.tinier, color = ink.ghost)
                        Periods.endAt(i + 1)?.let { end ->
                            Text(end, fontSize = Type.tinier, color = ink.ghost)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.width(Gap.xxs))

        days.forEach { day ->
            val isToday = day == today
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 1.dp),
            ) {
                Column(
                    Modifier.fillMaxWidth().height(HEADER_HEIGHT.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = DAYS.getOrElse(day - 1) { "周" },
                        fontSize = Type.micro,
                        fontWeight = if (isToday) FontWeight.Medium else FontWeight.Normal,
                        color = if (isToday) accent else ink.muted,
                    )
                    // 日期是这一周的，不是今天的 —— 翻到下周时它会跟着走
                    Text(
                        text = dayFmt.format(java.util.Date(monday + (day - 1) * 86_400_000L)),
                        fontSize = Type.tinier,
                        color = if (isToday) accent.copy(alpha = 0.8f) else ink.faint,
                    )
                }
                // 今天这一列：只在课表格子那一段铺一层淡底，圆角收住，别连星期头一起糊。
                // 底色走强调色而不是灰底 —— 夜里 `hairline` 是 8% 白，铺在 12 行高的整列上
                // 就是一块灰板，比课格子本身还抢眼（10-21 凌晨深色扫屏实测）；
                // 强调色压到百分之几只是"这一列被点了一下"。
                Column(
                    Modifier
                        .fillMaxWidth()
                        .then(
                            if (isToday) Modifier.background(
                                accent.copy(alpha = if (ink.dark) 0.07f else 0.05f),
                                Radius.tag,
                            ) else Modifier
                        ),
                ) {
                    val list = byDay[day].orEmpty()
                    var cursor = 1
                    list.forEachIndexed { i, slot ->
                        if (slot.startPeriod > cursor) {
                            Spacer(Modifier.height(rowH * (slot.startPeriod - cursor)))
                        }
                        // 连排几节就占几行；万一校方排出两门课同一节起，前一门裁到一行，
                        // 后一门照排 —— 宁可挤，也不许把课藏掉（藏掉看着像没排课）。
                        val nextStart = list.getOrNull(i + 1)?.startPeriod ?: Int.MAX_VALUE
                        val span = minOf(slot.periodCount, nextStart - slot.startPeriod).coerceAtLeast(1)
                        CourseCell(
                            slot, span,
                            isToday && periodNow != null &&
                                periodNow in slot.startPeriod until (slot.startPeriod + span),
                            rowH,
                            colors[slot.course] ?: COURSE_COLORS[0],
                        )
                        cursor = slot.startPeriod + span
                    }
                    // 尾巴补满，几列才一样高
                    if (cursor <= PERIOD_TIMES.size) {
                        Spacer(Modifier.height(rowH * (PERIOD_TIMES.size - cursor + 1)))
                    }
                }
            }
        }
    }
}

/** 一门课一格。[span] 是连排几节，就占几行高。 */
@Composable
private fun CourseCell(slot: TimetableSlot, span: Int, now: Boolean, rowH: Dp, solid: Color) {
    Column(
        Modifier
            .fillMaxWidth()
            .height(rowH * span - 3.dp)
            .padding(horizontal = 1.5.dp, vertical = 1.5.dp)
            .background(solid, Radius.tag)
            // 正在上的那一节：实心块上用一圈白环点出来（蓝环压在蓝块上看不见）
            .border(
                width = if (now) 2.dp else 0.dp,
                color = if (now) Color.White.copy(alpha = 0.92f) else Color.Transparent,
                Radius.tag,
            )
            .padding(start = 4.dp, end = 3.dp, top = 3.dp, bottom = 2.dp),
    ) {
        Text(
            text = slot.course,
            fontSize = Type.tiny,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            // 三节往上的块高出一大截，课名多给一行（"大学生心理健康教育" 不再被掐断）
            maxLines = if (span >= 3) 4 else if (span >= 2) 3 else 2,
            lineHeight = Type.tinyLine,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(1.dp))
        Text(
            text = shortRoom(slot.room),
            fontSize = Type.tinier,
            color = Color.White.copy(alpha = 0.86f),
            // 教室只给一行，而一列才五十几 dp ——「博雅A1区 102」这种就被掐成「博雅A1 …」，
            // 块的下半截却空着一大片。让它按块高换行：两节两行、三节往上三行。
            // 单节那一格反过来卡在一行：块高就那么多，再把字号档调大一点就要溢到块外面去了。
            // （行宽不够是列宽定的，六列除屏幕宽，改不了；能改的是别浪费块里已有的高度。）
            maxLines = if (span >= 3) 3 else if (span >= 2) 2 else 1,
            lineHeight = Type.tinierLine,
            overflow = TextOverflow.Ellipsis,
        )
        // 不再写"第 1-2 节"：块占几行本身已经说了这件事，而格子很窄，
        // 多这一行就是把课名挤成"高等数…"的那只手（10-20 装机他嫌难看的就是这个）。
    }
}

/**
 * 课表色块用的十二颗实心色，白字对比度都在 4.9:1 以上（10-21 早上逐颗量过）。
 *
 * 为什么从五颗加到十二颗：他 10-21 09:5x 一句「每个科目不要重复一个颜色」——
 * 五颗按课名排序轮流发，一周七门课必然有两门撞色，而撞在一起的常常还是相邻两天。
 * 现在一门一颗，十二颗够一张课表用；真选了十几门课才会绕回来，那时候也只能绕。
 *
 * 顺序是故意冷暖交替的：按课名排序一路发下去，相邻两门不会一个青接一个蓝，
 * 远看像同一门课的两个时间。
 */
private val COURSE_COLORS = listOf(
    Color(0xFF0F766E), // 墨绿
    Color(0xFFB45309), // 琥珀
    Color(0xFF1D4ED8), // 蓝
    Color(0xFFBE123C), // 玫红
    Color(0xFF15803D), // 绿
    Color(0xFF6D28D9), // 紫
    Color(0xFF0E7490), // 青
    Color(0xFFA16207), // 赭
    Color(0xFF4338CA), // 靛
    Color(0xFF9D174D), // 洋红
    Color(0xFF4D7C0F), // 橄榄
    Color(0xFF475569), // 石板
)

/*
 * 「课名 → 第几颗色」这张表不在这一屏算 —— 它在 `state.TimetableModel` 里，本机存着。
 *
 * 以前这一屏自己算：把**这一周**的课名排个序，一颗一颗往下发。于是同一门课换一周就可能
 * 换个号（那一周多了两门课、排序一变，它就从第 0 颗挪到第 2 颗），他 10-24 晚撞见的就是这个：
 * 第八周绿的课，第九周变紫。现在改成"一门课第一次见到时占一个最小没用过的号，往后哪一周都认它"。
 * 十二颗以内一门一色；真超过十二门，多的按 `mod` 回头重用 —— 那是色板到头，不是算错。
 */

/** 「只看今天」那一档：一张卡里一行一节、中间分隔线，不再一门课一张卡。 */
@Composable
private fun DayList(slots: List<TimetableSlot>, today: Int, runs: List<TimetableSlot>) {
    val ink = LocalInk.current
    val sorted = slots.sortedBy { it.startPeriod }
    Text(
        text = DAYS.getOrElse(today - 1) { "周一" } + " · ${sorted.size} 节",
        fontSize = Type.meta,
        color = ink.muted,
        modifier = Modifier.padding(bottom = Gap.xs, start = Gap.xxs),
    )
    // 今天有晨跑时，在这一档里也明说一句：它没有节次，不是"漏了一节"
    val runRoom = runs.firstOrNull { it.room.isNotBlank() }?.room.orEmpty()
    if (runs.isNotEmpty()) {
        Text(
            text = "另有晨跑活动" + if (runRoom.isNotBlank()) " · $runRoom" else "",
            fontSize = Type.meta,
            color = ink.faint,
            modifier = Modifier.padding(bottom = Gap.sm, start = Gap.xxs),
        )
    }
    Sheet {
        sorted.forEachIndexed { i, slot ->
            RowLine(
                title = slot.course,
                sub = listOf(
                    // 这一屏原来只有"第 1-2 节"，没有钟点；时间放最前面，扫一眼就是几点到几点。
                    // 右边那句已经写了"第 1-2 节"，所以这里不再重复"N 节连排"。
                    Periods.rangeText(slot.startPeriod, slot.periodCount).orEmpty(),
                    shortRoom(slot.room),
                ).filter { it.isNotBlank() }.joinToString(" · "),
                right = if (slot.periodCount > 1)
                    "第 ${slot.startPeriod}-${slot.startPeriod + slot.periodCount - 1} 节"
                else "第 ${slot.startPeriod} 节",
            )
            if (i != sorted.lastIndex) {
                Spacer(Modifier.height(Gap.xs))
                Hairline()
                Spacer(Modifier.height(Gap.xs))
            }
        }
    }
}

/**
 * 周次的中文写法：`7 → 第七周`、`12 → 第十二周`、`21 → 第二十一周`。
 * 校方 `rows` 里给的是"第12周"那种阿拉伯数字，他 10-23 深夜要的是"第一周第二周"这一排，
 * 所以数字在这儿翻成中文；超过 30（没有这种学期）直接回阿拉伯数字，不硬编一套错的读法。
 */
private fun cnWeek(n: Int): String = "第${cnNum(n)}周"

private fun cnNum(n: Int): String {
    val d = listOf("", "一", "二", "三", "四", "五", "六", "七", "八", "九")
    return when {
        n in 1..10 -> if (n == 10) "十" else d[n]
        n in 11..19 -> "十" + d[n - 10]
        n in 20..29 -> d[n / 10] + "十" + d[n % 10]
        n == 30 -> "三十"
        else -> n.toString()
    }
}
