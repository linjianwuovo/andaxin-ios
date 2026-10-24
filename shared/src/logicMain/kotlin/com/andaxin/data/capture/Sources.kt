package com.andaxin.data.capture

import com.andaxin.core.store.SessionStore
import com.andaxin.data.Dashboard
import com.andaxin.data.DashboardFeed
import com.andaxin.data.MessageFeed
import com.andaxin.data.NoticeFeed
import com.andaxin.data.SportsFeed
import com.andaxin.data.TimetableFeed
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * 三路数据源的开关：登录了就只走校方，没登录才走占位。
 *
 * 为什么"登录了就不许回落到假数据"：那会让人把假课表当成真课表看，比报错严重得多。
 * 校方那条挂了，界面上就该是错误 + 重试，而不是悄悄换成一组编出来的值。
 */
class TimetableSource(
    private val capture: TimetableFeed,
    private val local: TimetableFeed,
    private val session: SessionStore,
) : TimetableFeed {
    override suspend fun week(weekOffset: Int) =
        if (session.signedIn()) capture.week(weekOffset) else local.week(weekOffset)

    // 周次表是真数据那一发才有的东西；没登录时那份占位没有周次表，界面上会退回「本周/下周」两颗
    override suspend fun weeks() = if (session.signedIn()) capture.weeks() else local.weeks()
}

class MessageSource(
    private val capture: MessageFeed,
    private val local: MessageFeed,
    private val session: SessionStore,
) : MessageFeed {
    override suspend fun page(cursor: Int) =
        if (session.signedIn()) capture.page(cursor) else local.page(cursor)

    /**
     * 标已读**也要过同一道闸**。10-21 凌晨装机扫出来的：没登录时列表是 `LocalMessages` 那几条，
     * 可这颗红点还是发给了学校，于是点一下只换回底下一句红色的"还没登录，这一屏要校方数据" ——
     * 看着像我们把哪一屏点坏了，而那几条消息本来就不是学校的东西。
     */
    override suspend fun markRead(id: String) =
        if (session.signedIn()) capture.markRead(id) else local.markRead(id)
}

class NoticeSource(
    private val capture: NoticeFeed,
    private val local: NoticeFeed,
    private val session: SessionStore,
) : NoticeFeed {
    override suspend fun list() = if (session.signedIn()) capture.list() else local.list()

    override suspend fun detail(id: String) =
        if (session.signedIn()) capture.detail(id) else local.detail(id)
}

/**
 * 首页那一块聚合卡。
 *
 * 【取证】`msgCenter/notReadCount.do` 回 `data:{date:"晚上好", count:0}` ——
 * 那串问候语是**服务器给的**，不是我们按小时算的，照它显示。
 *
 * **但那个 `count` 不是未读消息数**，这一条是 10-20 上午现打对出来的：
 * 同一个账号，`mesDetailListNew.do(sourceType=2)` 一页 30 条里有 1 条 `readFlag="0"`，
 * 而 `notReadCount.do` 和 `getNotReadPushCount.do` 两个口**同时回 `count:0`**
 * （两个口的返回体长得一模一样，连 `date` 都带）。
 * 装机表现就是首页写"未读消息 0 条"、消息那一屏右上角写"1 未读"，两屏互相打脸。
 * 所以未读数改成**从消息列表自己数** —— 跟消息屏同一个来源、同一个口径，才可能一致。
 * `count` 这一发留着只取问候语，不再当数字用。
 *
 * 今日几节课、下一节是什么，用全周课表接口筛今天（见 `CaptureTimetable`）。
 *
 * 查寝那一格 10-19 接上了真数据：来源是 ywlz 待办列表里 `msgType=2` 的条目
 * （每条带 `receiveTime` 和 url 里的 `viewTitle`），显示最近一次晚点名的时间。
 * 还剩**本周跑量**这一格没有取证过的来源（跑量在 sports:8082 那一域，报文还没抓到），
 * 所以它继续从 Local 那侧拿占位值，界面上那条说明也一直挂着，直到它接上为止。
 */
class DashboardSource(
    private val school: School,
    private val session: SessionStore,
    private val local: DashboardFeed,
    private val today: TimetableFeed,
    private val tasks: YwlzTasks,
    private val messages: MessageFeed,
    private val sports: SportsFeed,
    private val dormBoard: CaptureDormBoard,
    /** 冷启动垫屏用的上一份快照（由 AppGraph 用 `Snap` 递进来，数据层不直接拿 Context）。 */
    private val readSnap: () -> String? = { null },
    private val writeSnap: (String) -> Unit = {},
) : DashboardFeed {

    override fun cached(): Dashboard? = readSnap()?.let { text ->
        runCatching {
            val o = com.andaxin.core.json.JSONObject(text)
            /*
             * 快照里必须带"是哪一天"。10-21 13:3x 他打开首页，顶卡写着"下一节 高等数学 10:25–11:10"，
             * 而那节课 12:00 就上完了 —— 垫屏的是**昨天/上一趟**留下的那份，里面没有时间戳，
             * 夜里存下的"下一节"第二天早上照样上屏。日期对不上就整份作废，
             * 宁可先显示"—"等这一发回来（1 秒上下），也不把昨天的课冒充成今天的。
             */
            if (o.optString("day") != todayKey()) return@runCatching null
            Dashboard(
                greeting = o.optString("greeting"),
                todayLessons = if (o.isNull("todayLessons")) null else o.optInt("todayLessons"),
                nextLesson = if (o.isNull("nextLesson")) null else o.optString("nextLesson"),
                unreadMessages = o.optInt("unreadMessages"),
                dormCheckState = o.optString("dormCheckState"),
                runSemesterKm = if (o.isNull("runSemesterKm")) null else o.optDouble("runSemesterKm"),
                runMorningKm = if (o.isNull("runMorningKm")) null else o.optDouble("runMorningKm"),
                runWhy = if (o.isNull("runWhy")) null else o.optString("runWhy"),
            )
        }.getOrNull()
    }

    override suspend fun snapshot(): Result<Dashboard> {
        if (!session.signedIn()) return local.snapshot()
        // 五路互不相干，一起发：首页要"一次出"，不该一路加载一路蹦
        return coroutineScope {
            /*
             * 每路各自封顶 8 秒：这几条在学校那边快慢差很远（fdygl 那条偶尔要十几秒），
             * 没有闸的话首页就跟着最慢的那条一起卡着 —— 用户看到的是"整屏都不出来"。
             * 超时的那一路按"没拿到"处理，格子上是"—"，别拖累其它几路。
             */
            val cap = 8_000L
            val counts = async { kotlinx.coroutines.withTimeoutOrNull(cap) { school.post("$SCPS/msgCenter/notReadCount.do").getOrNull() } }
            val week = async { kotlinx.coroutines.withTimeoutOrNull(cap) { today.week(0).getOrDefault(emptyList()) } }
            val dorm = async { kotlinx.coroutines.withTimeoutOrNull(cap) { tasks.all().getOrNull() } }
            val nextDormTask = async { kotlinx.coroutines.withTimeoutOrNull(cap) { dormBoard.nextTask() } }
            val msgs = async { kotlinx.coroutines.withTimeoutOrNull(cap) { messages.page(0).getOrNull() } }
            val run = async { kotlinx.coroutines.withTimeoutOrNull(cap) { sports.summary() } }
            val data = counts.await()?.optJSONObject("data")
            val greeting = data?.optString("date").orEmpty().ifBlank { "你好" }
            // 未读只数列表里 readFlag=0 的那几条，理由见上面那段取证
            val unread = msgs.await()?.count { it.unread } ?: 0
            // 全周接口一次给七天，首页那一格只要今天
            val allWeek = week.await() ?: emptyList()
            /*
             * `ksjc=0` 的那几条不是课：校方用一条「阳光晨跑 / 体育场」占位表示"这天早上有晨跑"，
             * 前后节次都是 0（10-21 13:5x 拿活 token 现打第 8 周确认，`阳光晨跑 ksjc=0 jsjc=0 jsmc=体育场`）。
             * 以前它被当成"第 1 节"，于是周二周四的 08:30 多出一块课，还会进"今天几节/下一节"。
             * 这里开始，所有"是不是一节课"的判断都先过 `startPeriod >= 1`。
             */
            val lessons = allWeek.filter { it.startPeriod >= 1 }
            val slots = lessons.filter { it.day == com.andaxin.core.concurrency.todayDayOfWeek() }
            /*
             * 只报"还没结束的那一节"。以前这里兜底 `slots.lastOrNull()`，
             * 于是 13:37 打开首页，顶卡还在写"下一节 高等数学 10:25–11:10" —— 那是一节早就上完的课。
             * 现在按作息表的真实下课时刻筛；今天一节不剩就回 null，首页改摆壁纸。
             */
            val nowMin = com.andaxin.core.concurrency.minuteOfDay()
            val next = slots
                .filter { slot ->
                    val end = SchoolPeriods.endMinute(slot.startPeriod + slot.periodCount - 1) ?: return@filter false
                    end > nowMin
                }
                .minByOrNull { it.startPeriod }
            // 这一格要的是"下一次查寝几点"。原来读的是 ywlz 待办里第一条晚点名消息的收到时间，
            // 那条口只在采集窗口打开时才来消息 —— 10-21 凌晨装机时它显示 10-20 21:00（昨晚那次），
            // 而 fdygl 列表里今天 21:00 那条明明挂着"未开始"。所以先问 fdygl，它没排任务才退回原来那个来源。
            val bed = dorm.await()?.firstOrNull { it.isBedCheck }
            val upcoming = nextDormTask.await()
            val bedAt = if (upcoming != null) {
                "${upcoming.date.takeLast(5)} ${upcoming.startAt}".trim()
            } else {
                bed?.receiveTime?.let { t -> if (t.length > 5) t.substring(5) else t }.orEmpty()
            }
            val runR = run.await() ?: Result.failure(Exception("跑步那两条超时了"))
            val sports0 = runR.getOrNull()
            val dash = Dashboard(
                    greeting = greeting,
                    // 整周一条都没有 = 这份数据没拿到，别报成"今天 0 节" ——
                    // 10-21 凌晨装机就撞上这一条：课表那一屏还有课，首页已经写"今天有课 0 节"
                    // （晨跑那条不算课，上面 `lessons` 已经滤掉）
                    todayLessons = if (allWeek.isEmpty()) null else slots.size,
                    // 连堂要写成"第 3-4 节"：只给起始节次，首页那颗药丸会把 10:25–12:00 的课
                    // 算成 10:25–11:10（10-21 13:3x 顶卡上真发生过）。
                    nextLesson = next?.let {
                        val last = it.startPeriod + it.periodCount - 1
                        val span = if (last > it.startPeriod) "${it.startPeriod}-$last" else "${it.startPeriod}"
                        "第${span}节 · ${it.course} · ${it.room}"
                    },
                    unreadMessages = unread,
                    dormCheckState = bedAt.ifBlank { "—" },
                    runSemesterKm = sports0?.semesterKm,
                    runMorningKm = sports0?.morningKm,
                runWhy = if (sports0 == null) whyOf(runR.exceptionOrNull()) else null,
            )
            // 落一份给下次冷启动垫屏用（只存首页这几个数，不存任何身份/单据）
            runCatching {
                writeSnap(
                    com.andaxin.core.json.JSONObject()
                        .put("day", todayKey())
                        .put("greeting", dash.greeting)
                        .put("todayLessons", dash.todayLessons ?: com.andaxin.core.json.JSONObject.NULL)
                        .put("nextLesson", dash.nextLesson ?: com.andaxin.core.json.JSONObject.NULL)
                        .put("unreadMessages", dash.unreadMessages)
                        .put("dormCheckState", dash.dormCheckState)
                        .put("runSemesterKm", dash.runSemesterKm ?: com.andaxin.core.json.JSONObject.NULL)
                        .put("runMorningKm", dash.runMorningKm ?: com.andaxin.core.json.JSONObject.NULL)
                        .put("runWhy", dash.runWhy ?: com.andaxin.core.json.JSONObject.NULL)
                        .toString(),
                )
            }
            Result.success(dash)
        }
    }

    /** 现在第几节由 `core/schedule/Periods` 说，那张表周网格也在用，不留第二份。 */
    private companion object {
        const val SCPS = School.SCPS

        /** 快照的"哪一天"，用设备本地时区；和界面上的日期是同一块表。 */
        fun todayKey(): String =
            com.andaxin.core.concurrency.todayLocalDate().toString()
    }
}

/**
 * 拉不到的原因写成一句人话 —— 首页和运动那几屏共用这一份措辞。
 *
 * 我们自己抛的那些本来就带中文（"本机还没登录…"），原话显示；
 * OkHttp 那串（`Failed to connect to sports.aiit.edu.cn/10.x.x.x:8082`）留着，
 * 因为"连不上"和"服务器拒了"是两回事，得让他分得清。
 * 光秃秃一个英文类名（connect 超时有时就这样）后面补一句中文，别只给类名。
 */
fun whyOf(t: Throwable?): String? {
    if (t == null) return null
    val m = t.message?.takeIf { it.isNotBlank() } ?: return "没连上学校那台 sports（${t::class.simpleName}）"
    return if (m.any { it.code in 0x4e00..0x9fff }) m else "没连上学校那台 sports：" + m
}


/**
 * 课表节次的时间表（common 版）。数据**逐字照抄** app 那份 `core/schedule/Periods.kt`
 * 里 10-21 抓的官方十节表（StartTime/EndTime 那两组），只是 Calendar 换成了纯算术。
 * Sources 的"下一节"用 endMinute；界面上课提醒那套还走 app 侧的 Periods（有闹钟能力）。
 */
object SchoolPeriods {
    // 官方课表那十节（StartTime 列），"HH:mm" 串
    private val START_AT = listOf(
        "08:00", "08:55", "10:00", "10:55", "11:50",
        "14:00", "14:55", "16:00", "16:55", "18:30",
    )
    // 官方课表那十节（EndTime 列）
    private val END_AT = listOf(
        "08:45", "09:40", "10:45", "11:40", "12:35",
        "14:45", "15:40", "16:45", "17:40", "20:10",
    )

    private fun minutes(hhmm: String): Int {
        val (h, m) = hhmm.split(':')
        return h.toInt() * 60 + m.toInt()
    }

    private val END_MINUTES = END_AT.map(::minutes)

    /** 第 n 节（1 起）的结束分钟数。越界回 null。 */
    fun endMinute(n: Int): Int? = END_MINUTES.getOrNull(n - 1)
}
