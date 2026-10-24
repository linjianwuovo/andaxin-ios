package com.andaxin.feature.sport

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andaxin.shell.LoadWhy
import com.andaxin.shell.pressClick
import com.andaxin.shell.rememberArmConfirm
import com.andaxin.app.LocalAppGraph
import com.andaxin.core.design.Gap
import com.andaxin.core.design.Type
import com.andaxin.core.design.InkSet
import com.andaxin.core.design.LocalInk
import com.andaxin.core.design.Palette
import com.andaxin.core.design.Radius
import com.andaxin.core.map.LatLng
import com.andaxin.core.map.TileMap
import com.andaxin.data.RouteTemplate
import com.andaxin.data.RunDraft
import com.andaxin.data.SportPlan
import com.andaxin.shell.Chip
import com.andaxin.shell.Hairline
import com.andaxin.shell.Labeled
import com.andaxin.shell.RowLine
import com.andaxin.shell.Sheet
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

// 地图默认中心。这个坐标不是本校，是从一个报文实样里抄来占位的 ——
// 真正的校区中心要等我们自己定位/抓包再定，别拿它当事实。
internal val CAMPUS = LatLng(31.146501, 118.635061)

/* ───────────────────────── ① 开始运动 ───────────────────────── */

@Composable
fun SportStartScreen(open: (String) -> Unit, onBack: () -> Unit) {
    val graph = LocalAppGraph.current
    val ink = LocalInk.current
    var plan by remember { mutableStateOf<SportPlan?>(null) }
    var planWhy by remember { mutableStateOf<String?>(null) }
    var tryNo by remember { mutableIntStateOf(0) }
    var denied by remember { mutableStateOf<String?>(null) }
    var starting by remember { mutableStateOf(false) }
    var startWhy by remember { mutableStateOf<String?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    /*
     * 调试那一档不在这一屏里：beta 那份给一颗「模拟路线」加选线面板（`core/debug/DebugKit.kt`
     * 的 sportEntry），正式版那份是 null，这一屏就什么都画不出来。
     * 这里只留一样东西：选上了哪条线（armed）—— 开始那一处要拿它喂端内回放。
     */
    var armed by remember { mutableStateOf<com.andaxin.core.debug.Armed?>(null) }

    /*
     * 先向校方要这一场的会话 id（大一大二 extraId/memberId，大三大四 startRunning.do），
     * 拿到了才开 GPS。拿不到就停在原地把原因写出来 —— 跑完再发现交不上去，那才叫白跑。
     */
    fun begin() {
        if (starting) return
        starting = true
        startWhy = null
        scope.launch {
            val r = try { graph.sports.beginRun() } catch (e: Exception) { Result.failure(e) }
            starting = false
            r.onSuccess { session ->
                // 选上了线就让 beta 那份的端内回放沿这条线三秒喂一个点（他自己写的那套）；
                // 没选上就是真 GPS。两条路进的是同一条采集链。正式版这边 debug 是 null，永远走真 GPS。
                val mock = graph.debug?.mock
                val plan0 = armed
                if (plan0 != null) mock?.startRoute(plan0.points, plan0.paceKmh) else mock?.stop()
                graph.engine.startReal(graph.gps, session)
                com.andaxin.core.run.RunService.start(ctx)
                open("sport/run")
            }.onFailure {
                startWhy = it.message ?: "校方没让开始这一场"
            }
        }
    }

    val ask = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result[android.Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            begin()
        } else {
            // 没给定位就不开始。不退回"沿画好的线走"那种演示：那一圈点是算出来的，
            // 报上去就是假成绩，界面上也绝不用"演示"两个字给它留门。
            denied = "没给定位权限，这一趟没法开始 —— 学校要的是你真实跑出来的轨迹。"
        }
    }

    /*
     * 这一屏照官方 startIndex 那张来：计划进度、今日/最长、有效阈值、体育课，全是校方给的真数。
     * 旧项目里那五条口（checkDsStudent / currentTerm / extraInfo / extraDetailInfo / clubInfo）
     * 10-21 傍晚拿活 token 逐条重打过，`CaptureSports.plan()` 里写着实样。
     */
    LaunchedEffect(tryNo) {
        planWhy = null
        val r = try { graph.sports.plan() } catch (e: Exception) { Result.failure(e) }
        plan = r.getOrNull()
        planWhy = r.exceptionOrNull()?.message
    }

    Page("开始运动", onBack) {
        PlanCard(plan, ink)
        planWhy?.let {
            Spacer(Modifier.height(Gap.md))
            LoadWhy(it, wide = false)
            Text(
                "重试",
                fontSize = Type.meta,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.pressClick(shrink = false) { tryNo++ }.padding(vertical = Gap.xs),
            )
        }

        Spacer(Modifier.height(Gap.xl))
        BigButton(if (starting) "正在向学校要这一场…" else "开始") {
            // 模拟跑不碰系统定位，也就不去要定位权限
            if (armed != null) {
                begin()
            } else {
                ask.launch(
                    arrayOf(
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION,
                        android.Manifest.permission.POST_NOTIFICATIONS,
                    )
                )
            }
        }
        (denied ?: startWhy)?.let {
            Spacer(Modifier.height(Gap.md))
            Text(it, fontSize = Type.meta, lineHeight = Type.metaLine, color = MaterialTheme.colorScheme.error)
        }

        graph.debug?.sportEntry?.invoke(armed, { armed = it }, open)

        // 官方这一屏右上角那颗「运动记录」和记录页里的维护入口，收进这一排。
        Spacer(Modifier.height(Gap.md))
        Row(horizontalArrangement = Arrangement.spacedBy(Gap.sm)) {
            HalfButton("运动记录") { open("sport/records") }
            HalfButton("考勤维护记录") { open("sport/maintain") }
        }

        Spacer(Modifier.height(Gap.xl))
        TipsCard(ink)
        Spacer(Modifier.height(Gap.xxl))
    }
}

/**
 * 官方那张「跑步计划」卡。
 *
 * 数字全是可选：缺哪条就把那一格写成"—"，**不拿 0 冒充**（0 是这个学期真的没跑，
 * 和"没拉到"是两件事）。进度条只在计划里程 > 0 时画。
 */
@Composable
private fun PlanCard(plan: SportPlan?, ink: InkSet) {
    val primary = MaterialTheme.colorScheme.primary
    val planKm = plan?.planKm
    val doneKm = plan?.doneKm
    val ratio = if (planKm != null && planKm > 0 && doneKm != null) {
        (doneKm / planKm).toFloat().coerceIn(0f, 1f)
    } else null
    val left = plan?.leftKm ?: if (planKm != null && doneKm != null) (planKm - doneKm) else null

    Sheet {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("本学期跑步", fontSize = Type.micro, color = ink.faint)
                Spacer(Modifier.height(Gap.xs))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = km2(doneKm),
                        style = Type.tabular,
                        fontSize = Type.num,
                        fontWeight = FontWeight.SemiBold,
                        color = ink.strong,
                    )
                    if (planKm != null) {
                        Text(
                            text = " / ${km1(planKm)} km",
                            fontSize = Type.meta,
                            color = ink.faint,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = plan?.termTitle.orEmpty().ifBlank { "—" },
                    fontSize = Type.micro,
                    color = ink.faint,
                )
                if (ratio != null) {
                    Text(
                        text = "${(ratio * 100).roundToInt()}%",
                        fontSize = Type.title,
                        fontWeight = FontWeight.SemiBold,
                        color = primary,
                    )
                }
            }
        }
        if (ratio != null) {
            Spacer(Modifier.height(Gap.md))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(Radius.pill)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(ratio)
                        .height(8.dp)
                        .clip(Radius.pill)
                        .background(primary),
                )
            }
            Spacer(Modifier.height(Gap.sm))
            val from = md(plan?.planStart)
            val to = md(plan?.planEnd)
            val range = if (from.isNotBlank() && to.isNotBlank()) "$from – $to" else ""
            Text(
                text = listOfNotNull(
                    left?.let { "还差 ${km2(it)} km" },
                    range.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                fontSize = Type.micro,
                color = ink.faint,
            )
        }

        Spacer(Modifier.height(Gap.md))
        Hairline()
        Spacer(Modifier.height(Gap.md))
        Row(Modifier.fillMaxWidth()) {
            TodayCell("今日", km2(plan?.todayKm), md(plan?.todayDate), Modifier.weight(1f))
            TodayCell("最长一次", km2(plan?.bestKm), md(plan?.bestDate), Modifier.weight(1f))
        }

        val morning = plan?.morningKm
        val groupCount = plan?.groupCount ?: 0
        if (morning != null || groupCount > 0) {
            Spacer(Modifier.height(Gap.md))
            Hairline()
            Spacer(Modifier.height(Gap.md))
            Text(
                text = buildString {
                    if (morning != null) append("其中晨跑 ${km2(morning)} km")
                    if (groupCount > 0) {
                        if (isNotEmpty()) append(" · ")
                        append("团体 $groupCount 次")
                    }
                },
                fontSize = Type.meta,
                color = ink.muted,
            )
        }

        val rule = validity(plan)
        if (rule.isNotBlank()) {
            Spacer(Modifier.height(Gap.md))
            Text(
                text = "有效标准：$rule",
                fontSize = Type.micro,
                lineHeight = Type.metaLine,
                color = ink.faint,
            )
        }

        val clubName = plan?.clubName.orEmpty()
        if (clubName.isNotBlank()) {
            Spacer(Modifier.height(Gap.md))
            Hairline()
            Spacer(Modifier.height(Gap.md))
            Text(
                text = listOf(clubName, plan?.memberLevel.orEmpty())
                    .filter { it.isNotBlank() }.joinToString(" · "),
                fontSize = Type.meta,
                color = ink.muted,
            )
            val who = listOf(
                plan?.teacher?.takeIf { it.isNotBlank() }?.let { "教师 $it" },
                plan?.studentCount?.let { "$it 人" },
            ).filterNotNull()
            if (who.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(who.joinToString(" · "), fontSize = Type.micro, color = ink.faint)
            }
        }
    }
}

@Composable
private fun TodayCell(label: String, value: String, date: String, modifier: Modifier) {
    val ink = LocalInk.current
    Column(modifier) {
        Text(label, fontSize = Type.micro, color = ink.faint)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = if (value == "—") "—" else "$value km",
                fontSize = Type.title,
                fontWeight = FontWeight.Medium,
                color = ink.strong,
            )
            if (date.isNotBlank()) {
                Spacer(Modifier.width(Gap.xs))
                Text(date, fontSize = Type.tinier, color = ink.faint, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
    }
}

/** 官方那四条安全提示，原样落在这一屏最底下。 */
@Composable
private fun TipsCard(ink: InkSet) {
    Sheet {
        Text("跑步提示", fontSize = Type.body, fontWeight = FontWeight.Medium, color = ink.strong)
        Spacer(Modifier.height(Gap.sm))
        listOf(
            "1、请勿戴口罩跑步！",
            "2、运动之前，请准备活动！",
            "3、运动过程中，出现任何身体不适，请立即停止！",
            "4、运动结束后，请慢走几分钟！",
        ).forEach {
            Text(it, fontSize = Type.meta, lineHeight = Type.metaLine, color = ink.muted)
            Spacer(Modifier.height(Gap.xs))
        }
    }
}

private fun validity(plan: SportPlan?): String {
    if (plan == null) return ""
    val parts = buildList {
        plan.minOnceKm?.let { add("单次不少于 ${num(it)} 公里") }
        plan.maxDayKm?.let { add("每天最多累计 ${num(it)} 公里") }
        if (plan.minSpeedKmh != null && plan.maxSpeedKmh != null) {
            add("速率 ${num(plan.minSpeedKmh ?: 0.0)}–${num(plan.maxSpeedKmh ?: 0.0)} km/h")
        }
    }
    return parts.joinToString("，")
}

private fun km1(v: Double?): String = v?.let { "%.1f".format(it) } ?: "—"
private fun km2(v: Double?): String = v?.let { "%.2f".format(it) } ?: "—"
private fun num(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(v)
private fun md(s: String?): String =
    s?.takeIf { it.length >= 5 }?.takeLast(5) ?: ""

/* ───────────────────────── ② 运动中 ───────────────────────── */

@Composable
fun SportRunScreen(onBack: () -> Unit) {
    val graph = LocalAppGraph.current
    val ink = LocalInk.current
    val state by graph.engine.state.collectAsStateWithLifecycle()
    val mockOn by (graph.debug?.mock?.isOn ?: com.andaxin.core.debug.DebugKit.off)
        .collectAsStateWithLifecycle()
    val stopArm = rememberArmConfirm()
    var draft by remember { mutableStateOf<RunDraft?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var submitWhy by remember { mutableStateOf<String?>(null) }
    var submittedId by remember { mutableStateOf<String?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // 进屏先把"上次已知位置"拿出来垫地图，别拿写死的 CAMPUS 冒充他的位置
    val lastKnown = remember { graph.gps.lastKnown() }

    // 系统返回也走二次确认；普通点击右下返回在同一处
    androidx.activity.compose.BackHandler(enabled = state.active && draft == null) {
        confirmLeave = true
    }

    fun abandon() {
        graph.engine.abandon()
        graph.debug?.mock?.stop()
        onBack()
    }

    Page(
        if (draft == null) "运动中" else "这一趟",
        onBack = {
            if (state.active && draft == null) confirmLeave = true else onBack()
        },
    ) {
        val done = draft
        if (done != null) {
            // 结束页：地图 + 三个数 + 一颗明确写着"提交到学校"的键。不自动交。
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.2f)
                    .background(Palette.Sand, Radius.card)
            ) {
                if (done.points.size >= 2) {
                    TileMap(
                        centerWgs = done.points.last().where,
                        pathWgs = done.points.map { it.where },
                        zoomStart = 17,
                        modifier = Modifier.fillMaxSize().clip(Radius.card),
                    )
                } else {
                    Text(
                        "这一趟没有采到可用轨迹（原地/室内，精度不到 35 米）。",
                        fontSize = Type.meta,
                        color = ink.faint,
                        modifier = Modifier.align(Alignment.Center).padding(Gap.lg),
                    )
                }
            }
            Spacer(Modifier.height(Gap.lg))
            Sheet {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat("距离", "%.2f km".format(done.km))
                    Stat("时长", "%d:%02d".format(done.seconds / 60, done.seconds % 60))
                    Stat(
                        "配速",
                        if (done.km > 0.05) {
                            val sec = done.seconds / done.km
                            "%d'%02d\"".format((sec / 60).toInt(), (sec % 60).toInt())
                        } else "—",
                    )
                }
                Spacer(Modifier.height(Gap.md))
                Hairline()
                Spacer(Modifier.height(Gap.md))
                Text(
                    text = "轨迹点 ${done.points.size} 个 · 这次只在本机，按下「提交到学校」才发出去。",
                    fontSize = Type.micro,
                    color = ink.faint,
                )
            }

            val id = submittedId
            when {
                id != null -> {
                    Spacer(Modifier.height(Gap.lg))
                    Sheet {
                        Text("已提交", fontSize = Type.title, fontWeight = FontWeight.SemiBold, color = ink.strong)
                        Spacer(Modifier.height(Gap.xs))
                        Text(
                            "学校回执 id：$id。运动记录里要刷新一下才看得到。",
                            fontSize = Type.meta,
                            lineHeight = Type.metaLine,
                            color = ink.muted,
                        )
                    }
                    Spacer(Modifier.height(Gap.lg))
                    BigButton("返回") { onBack() }
                }
                else -> {
                    submitWhy?.let {
                        Spacer(Modifier.height(Gap.md))
                        Text(
                            it,
                            fontSize = Type.meta,
                            lineHeight = Type.metaLine,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(Gap.lg))
                    BigButton(if (submitting) "正在提交…" else "提交到学校") {
                        if (submitting) return@BigButton
                        submitting = true
                        submitWhy = null
                        scope.launch {
                            val r = try { graph.sports.submit(done) } catch (e: Exception) {
                                Result.failure(e)
                            }
                            submitting = false
                            submittedId = r.getOrNull()
                            submitWhy = r.exceptionOrNull()?.message
                        }
                    }
                    Spacer(Modifier.height(Gap.sm))
                    Text(
                        "不提交，直接返回",
                        fontSize = Type.meta,
                        color = ink.muted,
                        modifier = Modifier
                            .pressClick(shrink = false) { onBack() }
                            .padding(vertical = Gap.sm),
                    )
                }
            }
            Spacer(Modifier.height(Gap.xxl))
            return@Page
        }

        val here = state.here ?: state.points.lastOrNull()?.where ?: lastKnown
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(Palette.Sand, Radius.card)
        ) {
            if (here != null) {
                TileMap(
                    centerWgs = here,
                    pathWgs = state.points.map { it.where },
                    markersWgs = listOf(here),
                    zoomStart = 17,
                    follow = true,
                    modifier = Modifier.fillMaxSize().clip(Radius.card),
                )
            } else {
                Text(
                    "正在定位…（到室外开阔处，第一颗星可能要几十秒）",
                    fontSize = Type.meta,
                    color = ink.faint,
                    modifier = Modifier.align(Alignment.Center).padding(Gap.lg),
                )
            }
        }

        Spacer(Modifier.height(Gap.lg))
        Sheet {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("距离", "%.2f km".format(state.km))
                Stat("时长", "%d:%02d".format(state.seconds / 60, state.seconds % 60))
                Stat("配速", state.pacePerKm)
            }
            Spacer(Modifier.height(Gap.sm))
            Text(
                text = buildString {
                    val acc = state.accuracy
                    if (mockOn) {
                        append(graph.debug?.mockNote(acc?.toInt()).orEmpty())
                    } else {
                        if (acc != null) append("GPS 精度 ±${acc.toInt()} 米 · ")
                        append("只认真实位移，踏步/原地不动距离不会涨。")
                    }
                },
                fontSize = Type.tinier,
                color = ink.faint,
            )
        }

        if (state.error != null) {
            Spacer(Modifier.height(Gap.md))
            LoadWhy(state.error)
        }

        Spacer(Modifier.height(Gap.xl))
        Row(horizontalArrangement = Arrangement.spacedBy(Gap.sm)) {
            HalfButton(if (state.paused) "继续" else "暂停") {
                if (state.paused) {
                    graph.engine.resume()
                    graph.debug?.mock?.paused = false
                } else {
                    graph.engine.pause()
                    graph.debug?.mock?.paused = true
                }
            }
            HalfButton(if (stopArm.armed) "再点一次确认结束 ${stopArm.countdown}s" else "结束") {
                if (!stopArm.armed) {
                    stopArm.arm()
                } else {
                    draft = graph.engine.finish()
                    graph.debug?.mock?.stop()
                    stopArm.disarm()
                }
            }
        }
        Spacer(Modifier.height(Gap.md))
        Text(
            "取消这一场",
            fontSize = Type.meta,
            color = ink.muted,
            modifier = Modifier
                .pressClick(shrink = false) { confirmLeave = true }
                .padding(vertical = Gap.xs),
        )
        Spacer(Modifier.height(Gap.md))
        Text(
            // 运动开始只有一条路：手机 GPS。以前这句在 Free 档写"沿演示圈走"，
            // 而 startReal() 给的正是 Free —— 真跑反而被标成假轨迹，读着正好反了。
            text = if (mockOn) {
                graph.debug?.mockBanner.orEmpty()
            } else {
                "采的是手机 GPS 的真实位置。楼边、室内会飘，飘出来的那几段学校那边也可能判无效。"
            },
            fontSize = Type.micro,
            color = ink.faint,
        )
        if (confirmLeave) {
            Spacer(Modifier.height(Gap.md))
            Sheet {
                Text("放弃这一场？", fontSize = Type.title, fontWeight = FontWeight.SemiBold, color = ink.strong)
                Spacer(Modifier.height(Gap.xs))
                Text(
                    "这一趟不会提交，也不会留在本机。",
                    fontSize = Type.meta,
                    color = ink.muted,
                )
                Spacer(Modifier.height(Gap.md))
                Row(horizontalArrangement = Arrangement.spacedBy(Gap.sm)) {
                    HalfButton("继续跑") { confirmLeave = false }
                    HalfButton("放弃并返回") { abandon() }
                }
            }
        }
        Spacer(Modifier.height(Gap.xxl))
    }
}

/* ───────────────────── ③ 记录 / ④ 明细 ───────────────────── */

@Composable
fun SportRecordsScreen(open: (String) -> Unit, onBack: () -> Unit) {
    val graph = LocalAppGraph.current
    val ink = LocalInk.current
    var items by remember { mutableStateOf<List<com.andaxin.data.RunRecord>>(emptyList()) }
    // 空列表有两种完全不同的意思：真的一条没跑，和这台 sports 没连上。
    // 以前只用 getOrDefault(emptyList()) 把失败咽掉了，界面上就是一屏空白，
    // 看着像他没跑步 —— 10-20 中午明文那条坑就是被这样掩盖的。
    var why by remember { mutableStateOf<String?>(null) }
    LaunchedLoad {
        val r = graph.sports.records(0)
        items = r.getOrDefault(emptyList())
        why = com.andaxin.data.capture.whyOf(r.exceptionOrNull())
    }

    Page("运动记录", onBack) {
        items.forEach { r ->
            Sheet(modifier = Modifier.pressClick { open("sport/detail/${r.id}") }) {
                Labeled(label = r.date, value = "%.2f km".format(r.km))
                Spacer(Modifier.height(Gap.sm))
                Hairline()
                Spacer(Modifier.height(Gap.sm))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(r.pacePerKm + " · " + "%.1f 分钟".format(r.minutes), fontSize = Type.meta, color = ink.faint)
                    Text(
                        text = if (r.valid) "有效" else r.invalidReason,
                        fontSize = Type.meta,
                        color = if (r.valid) (if (ink.dark) Palette.NightTeal else Palette.TealInk)
                        else MaterialTheme.colorScheme.error,
                    )
                }
            }
            Spacer(Modifier.height(Gap.sm))
        }
        Spacer(Modifier.height(Gap.md))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Gap.sm)) {
            HalfButton("考勤维护记录") { open("sport/maintain") }
        }
        if (items.isEmpty() && why != null) {
            Spacer(Modifier.height(Gap.md))
            Text(
                text = "这一屏暂时没数：" + why,
                fontSize = Type.body,
                lineHeight = Type.bodyLine,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(Gap.md))
        Spacer(Modifier.height(Gap.xxl))
    }
}

@Composable
fun SportDetailScreen(recordId: String, onBack: () -> Unit) {
    val graph = LocalAppGraph.current
    val ink = LocalInk.current
    var detail by remember { mutableStateOf<com.andaxin.data.RunDetail?>(null) }
    var why by remember { mutableStateOf<String?>(null) }
    LaunchedLoad {
        val r = graph.sports.detail(recordId)
        detail = r.getOrNull()
        why = com.andaxin.data.capture.whyOf(r.exceptionOrNull())
    }

    Page("运动明细", onBack) {
        val d = detail
        if (d == null) {
            // 10-21 接上了 getExtraCheck.do；这时 null 只表示"没拉到/还在拉"，
            // 原因照 `why` 显示，别停在"加载中…"上装转。
            Text(
                text = why ?: "明细拉取中…",
                fontSize = Type.body,
                lineHeight = Type.bodyLine,
                color = if (why == null) ink.faint else MaterialTheme.colorScheme.error,
            )
            return@Page
        }
        if (d.track.isNotEmpty()) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(1.1f).background(Palette.Sand, Radius.card)
            ) {
                TileMap(
                    centerWgs = d.track.first(),
                    pathWgs = d.track,
                    zoomStart = 16,
                    modifier = Modifier.fillMaxSize().clip(Radius.card),
                )
            }
        } else {
            Sheet {
                Text(
                    "校方这一条没有存轨迹（明细里的 runningRoute 是空的）。",
                    fontSize = Type.meta,
                    color = ink.faint,
                )
            }
        }
        Spacer(Modifier.height(Gap.lg))
        val mins = d.record.minutes
        Sheet {
            Labeled(label = "日期", value = d.record.date)
            Spacer(Modifier.height(Gap.sm)); Hairline(); Spacer(Modifier.height(Gap.sm))
            Labeled(label = "距离", value = "%.2f km".format(d.record.km))
            Spacer(Modifier.height(Gap.sm)); Hairline(); Spacer(Modifier.height(Gap.sm))
            Labeled(label = "用时", value = "%d:%02d".format(mins.toInt() / 60, mins.toInt() % 60))
            Spacer(Modifier.height(Gap.sm)); Hairline(); Spacer(Modifier.height(Gap.sm))
            Labeled(
                "平均速度",
                if (d.record.km > 0.01 && mins > 0) "%.2f km/h".format(d.record.km / (mins / 60.0)) else "—",
            )
            Spacer(Modifier.height(Gap.sm)); Hairline(); Spacer(Modifier.height(Gap.sm))
            Labeled(label = "跑道", value = d.lane.ifBlank { "—" })
            Spacer(Modifier.height(Gap.sm)); Hairline(); Spacer(Modifier.height(Gap.sm))
            Labeled(label = "学校判定", value = d.schoolComment)
        }
        Spacer(Modifier.height(Gap.md))
        Spacer(Modifier.height(Gap.xxl))
    }
}

/* ─────────────────────── ⑤ 考勤维护 ─────────────────────── */

@Composable
fun SportMaintainScreen(onBack: () -> Unit) {
    val graph = LocalAppGraph.current
    val ink = LocalInk.current
    var log by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var why by remember { mutableStateOf<String?>(null) }
    LaunchedLoad {
        val r = graph.sports.maintenanceLog()
        log = r.getOrDefault(emptyList())
        why = com.andaxin.data.capture.whyOf(r.exceptionOrNull())
    }

    Page("考勤维护记录", onBack) {
        log.forEach { (day, what) ->
            Sheet { Labeled(label = day, value = what) }
            Spacer(Modifier.height(Gap.sm))
        }
        if (log.isEmpty() && why != null) {
            LoadWhy(why)
            Spacer(Modifier.height(Gap.md))
        }
        Spacer(Modifier.height(Gap.md))
        Spacer(Modifier.height(Gap.xxl))
    }
}

/* ───────────────────────── 公共小块 ───────────────────────── */

@Composable
internal fun Page(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Gap.screen),
    ) {
        Spacer(Modifier.height(Gap.xl))
        Text(
            "‹ 返回",
            fontSize = Type.body,
            color = LocalInk.current.muted,
            modifier = Modifier.pressClick(shrink = false, onClick = onBack).padding(vertical = Gap.sm),
        )
        Spacer(Modifier.height(Gap.sm))
        Text(title, fontSize = Type.display, fontWeight = FontWeight.SemiBold, color = LocalInk.current.strong)
        Spacer(Modifier.height(Gap.lg))
        content()
    }
}

private typealias ColumnScope = androidx.compose.foundation.layout.ColumnScope

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(value, style = Type.tabular, fontSize = Type.title, fontWeight = FontWeight.Medium, color = LocalInk.current.strong)
        Spacer(Modifier.height(Gap.xs))
        Text(label, fontSize = Type.micro, color = LocalInk.current.faint)
    }
}

@Composable
private fun BigButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(MaterialTheme.colorScheme.primary, Radius.card)
            .pressClick(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = Type.title, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onPrimary)
    }
}

@Composable
internal fun androidx.compose.foundation.layout.RowScope.HalfButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .weight(1f)
            .height(48.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, Radius.field)
            .pressClick(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = Type.body, color = LocalInk.current.body, maxLines = 1)
    }
}

@Composable
private fun LaunchedLoad(block: suspend () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) { block() }
}

internal fun lengthOf(points: List<LatLng>): Double {
    var t = 0.0
    for (i in 1 until points.size) {
        t += com.andaxin.core.map.Geo.metersBetween(points[i - 1], points[i])
    }
    return t
}
