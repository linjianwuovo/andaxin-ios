package com.andaxin.core.schedule

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.andaxin.AndaXinApp
import com.andaxin.MainActivity
import com.andaxin.R
import com.andaxin.core.store.Store
import com.andaxin.data.TimetableFeed
import com.andaxin.data.TimetableSlot
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/*
 * 上课提醒。
 *
 * 五件事决定它长这样：
 *
 * ① **节次表是我们自己拍的**。抓到的接口里没有一条给"第几节几点上课"（见 `Periods` 顶上那段），
 *    所以这条通知说的时间不是校方给的。设置那一行把这句话写明白了，不藏。
 *    真按那张表提醒，误差在"学校改作息没通知"这种时候才会咬人。
 * ② **一次只挂一颗闹钟**：只排"下一节"，响了之后由接收者再排下一颗。
 *    把一整天八颗都排下去的话，状态栏那个闹钟图标会一直挂着，而且课表一改就得整批重排。
 * ③ 先排**精确闹钟**（`setAlarmClock`），系统不给权限就退化成 `setAndAllowWhileIdle`，
 *    并在界面上明说"可能晚几分钟"。
 *    这条是 10-20 装机教出来的：我原来以为 `setAlarmClock` 不要任何权限，
 *    结果这台机器直接抛 `SecurityException` 把 App 炸了（见 `arm` 上面那段）。
 *    现在权限照实声明、排之前先问、问不到就降级 —— 三条都写上，就是不让一个开关点出闪退。
 * ④ **窗口内每分钟刷一次倒计时**（10-25 他要求的"上课前 20 分钟每分钟更新一次通知"）。
 *    滴答一颗一颗接：这一颗响了才排下一颗，所以系统里任何时刻只挂着一条待发的滴答。
 *    这么排是因为一次排二十颗既费电，也容易被 ROM 的闹钟调度掐掉几条 —— 链断了最多是少刷一次，
 *    下一节的闹钟照旧在那儿。"还剩几分钟"每次都**从墙钟现减**，不靠"我醒了几次"（跟
 *    `RunEngine` 读秒同一个道理）：Doze 把某一颗压晚了，醒来那一趟照样报对的数。
 * ⑤ **一声不响**（同日他补的一句"不要响"）。做法两道一起上，少一道都盖不全，见 `ensureChannel`。
 */
object ClassReminder {

    const val CHANNEL_ID = "andaxin.class"
    /** 倒计时那一条（课前窗口里每分钟换一次文字，始终是这一条）。 */
    const val NOTE_ID = 3201
    /** 老号：0.8.45 那一版的"现在上课"发在这条上（当时为了让它能再响一声）。
     *  现在一条都不响，两条并成 NOTE_ID 了；这个号只留着"见到就清掉"，
     *  免得从那一版覆盖安装上来的人，通知栏里挂着一张没人管的旧通知。 */
    const val STARTED_ID = 3202

    const val KEY_ON = "remind.on"
    const val KEY_LEAD = "remind.leadMinutes"

    /** 提前几分钟。给的是几档离散地选，不是一根滑杆 —— "提前 7 分钟"这种事没人真需要。 */
    val LEADS = listOf(5, 10, 15, 20, 30)
    const val DEFAULT_LEAD = 10

    const val ACTION = "com.andaxin.action.CLASS_REMIND"
    const val ACTION_TICK = "com.andaxin.action.CLASS_TICK"

    /** 滴答用固定的请求号：同一节课的链只有一条待发，`FLAG_UPDATE_CURRENT` 直接换文字。 */
    private const val TICK_CODE = 3200

    /** 窗口那颗也用固定的请求号。
     *  以前这里拿"触发时间戳低 31 位"当请求号，理由写的是"每节课一颗，改课表重排时旧的自己就废了" ——
     *  那句话是反的：取消是**按请求号匹配**的（extras 不参与匹配），所以请求号一漂，
     *  `cancel(请求号 0)` 永远顶不掉刚排下去那颗，"关了"它还在，重排时旧的那颗也还在。
     *  固定成一个号，`FLAG_UPDATE_CURRENT` 就天然做到"同时只挂一颗窗口闹钟"。 */
    private const val WINDOW_CODE = 3210

    /** 通知和设置那一行显示的是同一个格式，别一处"10-19 08:30"一处"8:30"。
     *  每次现做一只：`SimpleDateFormat` 不是线程安全的，而排程这条路有时在主线程、有时在 Default。 */
    private fun timeFmt(): SimpleDateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

    internal const val EXTRA_COURSE = "course"
    internal const val EXTRA_ROOM = "room"
    internal const val EXTRA_AT = "at"
    internal const val EXTRA_PERIOD = "period"
    /** 真正的上课时刻（墙钟毫秒）。倒计时每次都拿它现算，不靠"我醒了几次"。 */
    internal const val EXTRA_START_MS = "startMs"

    fun isOn(p: androidx.datastore.preferences.core.Preferences, s: Store): Boolean =
        s.peekString(p, KEY_ON) == "1"

    fun leadOf(p: androidx.datastore.preferences.core.Preferences, s: Store): Int =
        s.peekString(p, KEY_LEAD)?.toIntOrNull()?.takeIf { it in LEADS } ?: DEFAULT_LEAD

    /**
     * 这条通知**不出声**（10-25 他一句"不要响"）。两道一起上：
     * ① 新装的机器：这条道上的通知渠道本身就带默认铃声，所以建渠道时把声音和震动清掉；
     * ② 已经装着 0.8.44/0.8.45 的机器：渠道建完就不许应用再改声音了（那是用户的权利），
     *    只能每条通知自己按下去 —— `setSilent(true)`（31 起才有，闸门写在被调那一行本体上）。
     * 渠道还是 DEFAULT 那一档：这一档本来就不弹横幅，只是原来会响，现在连响也没有了。
     */
    fun ensureChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "上课提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "从课前那个提前量起，每分钟把这条通知的倒计时刷一遍，直到上课。不响。" +
                    "节次时间是我们自己排的表，不是校方给的。"
                setSound(null, null)
                enableVibration(false)
                vibrationEffect = null
            },
        )
    }

    /**
     * 把"下一节"那颗闹钟排下去。回一句人话，设置那一行直接显示它 —— 别只回个布尔。
     *
     * `fromUser` = 这一趟是他自己在设置屏拨的开关/挑的提前量，不是闹钟响完之后自动接续那一趟。
     * 只有这一趟会把屏幕上那两条通知撤掉：他明确拨到"关"，还留着"还有 12 分钟"是说不过去的；
     * 而另外几支（课表没拉到、这两周排完了）常常只是他进这一屏看了一眼 —— 那一声问不该
     * 把他正看着的倒计时抹掉。
     *
     * 正在跑的滴答链在这儿**不主动撤**（关掉开关那一支除外）：链数的是"离上课还有几分钟"，
     * 他把提前量从 20 改成 10 并不改变上课那一刻，剩下的几分钟照数是对的；
     * 中途把它掐了反而更糟 —— 这一节的"现在上课"就没了。
     */
    suspend fun reschedule(
        context: Context,
        store: Store,
        timetable: TimetableFeed,
        now: Calendar = Calendar.getInstance(),
        fromUser: Boolean = false,
    ): String {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val p = store.snapshot()
        if (!isOn(p, store)) {
            alarm.cancel(pendingIntent(context, WINDOW_CODE, ACTION, "", "", "", 0, 0L))
            // 开关不在"开"，滴答链没有继续的理由
            cancelTick(context)
            if (fromUser) context.getSystemService(NotificationManager::class.java).run {
                cancel(NOTE_ID); cancel(STARTED_ID)
            }
            return "没开。"
        }
        val lead = leadOf(p, store)
        // 连排两周：只排本周的话，周五晚上最后一颗闹钟响完就再也接不上了 ——
        // 那时候"本周剩下的课"是空的，于是取消闹钟，然后一直等到下周一有人打开 App 才恢复。
        // 那几天里上课是不会提醒的，而"我明明开了它怎么不响"是最难查的一种坏。
        // `startPeriod = 0` 是校方那条"阳光晨跑"活动占位，不是课 —— 别给它排闹钟。
        // 这一条 10-21 下午跟他那句"为什么课表里多了阳光晨跑"一起查出来的。
        val slots = listOf(0, 1).flatMap { off ->
            timetable.week(off).getOrElse { emptyList() }
                .filter { it.startPeriod >= 1 }
                .map { off to it }
        }
        if (slots.isEmpty()) {
            return "课表没拉到，暂时排不上提醒（登录掉了先去登一次）。"
        }
        // 提前 lead 分钟触发；已经过去的（含正在上的那一节）一律跳过
        val next = slots.mapNotNull { (off, slot) ->
            val begin = Periods.slotStart(slot.day, slot.startPeriod, now, off) ?: return@mapNotNull null
            // 先留下"真正上课那一刻"，再把它往前拨 lead 分钟 —— 倒计时要拿前者现算，
            // 拨完就没法还原了（Calendar 是改自己，不是回新值）。
            val startMs = begin.timeInMillis
            begin.add(Calendar.MINUTE, -lead)
            val at = begin.timeInMillis
            if (at <= now.timeInMillis) null else Armed(at, slot, startMs)
        }.minByOrNull { it.triggerAt }

        if (next == null) {
            alarm.cancel(pendingIntent(context, WINDOW_CODE, ACTION, "", "", "", 0, 0L))
            cancelTick(context)
            return "这两周的课都排完了，不提醒了。"
        }

        val at = timeFmt().format(Date(next.startMs))
        // 课名空的那一节也给个名字再排下去：接收者那边认"课名是空的"=这颗是拿来取消的占位，
        // 真课被排成空串就会响到了也什么都不发（界面上那句兜底文案在这儿用不上，得先落到 extras 里）。
        val course = next.slot.course.ifBlank { "一节课" }
        val pi = pendingIntent(
            context, WINDOW_CODE, ACTION,
            course, next.slot.room, at, next.slot.startPeriod, next.startMs,
        )
        ensureChannel(context)
        return "已排：%s · %s（提前 %d 分钟起，每分钟刷一次倒计时）%s".format(
            course, at, lead, arm(context, alarm, next.triggerAt, pi),
        )
    }

    /** 排好待武装的那一节：触发时刻、是哪节课、真正上课的墙钟毫秒。
     *  起名不用 Triple —— 下面取值和排序都在认字段，位置读太容易读错。 */
    private data class Armed(val triggerAt: Long, val slot: TimetableSlot, val startMs: Long)

    /**
     * 真把那颗闹钟排下去，回一句"排成什么样了"接在状态后面。
     *
     * 【取证 · ① 它自己抛出来给我们看的】10-20 09:53 装机一打开这个开关就闪退：
     *   java.lang.SecurityException: Caller com.andaxin needs to hold
     *   android.permission.SCHEDULE_EXACT_ALARM or android.permission.USE_EXACT_ALARM
     *   to set exact alarms.
     *     at android.app.AlarmManager.setAlarmClock(AlarmManager.java:986)
     * 我上一版注释里写的是"`setAlarmClock` 不要任何权限"，那是从通用知识推的，这台机器不认。
     * 所以三条一起补：权限照实声明、排之前先问系统答不答应、答应不答应都不许把 App 炸掉
     * —— 一个开关点一下就退出，比不提醒难用十倍。
     */
    private fun arm(context: Context, alarm: AlarmManager, at: Long, pi: PendingIntent): String {
        val exact = runCatching {
            // canScheduleExactAlarms 是 31 才有的。SDK 闸门写在被调这一句本体上，
            // 不指望调用方记得判 —— 这是这台机器上撞过的老规矩。
            Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()
        }.getOrDefault(false)
        if (exact) {
            runCatching { alarm.setAlarmClock(AlarmManager.AlarmClockInfo(at, pi), pi) }
                .onSuccess { return "，走的是系统闹钟那条路，状态栏会亮一个小闹钟图标。" }
        }
        // 不让排精确闹钟就走这条：Doze 里也会醒，但点不精确，可能晚几分钟
        return runCatching {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            if (exact) "，但这次系统没让排精确闹钟，改走了一条可能晚几分钟的路。"
            else "，但系统没给「闹钟和提醒」那条权限（去设置里给一下），现在走的这条可能晚几分钟。"
        }.getOrElse { "，没排上：${it.message?.take(46) ?: it.javaClass.simpleName}" }
    }

    private fun pendingIntent(
        context: Context,
        requestCode: Int,
        action: String,
        course: String,
        room: String,
        at: String,
        period: Int,
        startMs: Long,
    ): PendingIntent {
        val i = Intent(context, ReminderReceiver::class.java).setAction(action)
            .putExtra(EXTRA_COURSE, course)
            .putExtra(EXTRA_ROOM, room)
            .putExtra(EXTRA_AT, at)
            .putExtra(EXTRA_PERIOD, period)
            .putExtra(EXTRA_START_MS, startMs)
        // 排闹钟那颗：requestCode 用触发时间戳的低 31 位 —— 每节课一颗，改课表重排时旧的自己就废了。
        // 滴答那颗：固定用 TICK_CODE（见 tickIntent），同一节课的链永远只有一颗待发。
        return PendingIntent.getBroadcast(
            context, requestCode, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 下一分钟那颗滴答。请求号固定，所以取消时不用手里有这几节课的参数也能造出同一颗。 */
    private fun tickIntent(
        context: Context, course: String, room: String, at: String, period: Int, startMs: Long,
    ): PendingIntent = pendingIntent(context, TICK_CODE, ACTION_TICK, course, room, at, period, startMs)

    private fun cancelTick(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        // 匹配只看 action + 组件 + 数据，extras 不参与，所以拿空串现造一颗就能把待发的那颗顶掉
        alarm.cancel(pendingIntent(context, TICK_CODE, ACTION_TICK, "", "", "", 0, 0L))
    }

    /** 把通知上那句"MM-dd HH:mm"还原成毫秒。
     *  只在一种情况下用得上：覆盖安装**之前**排下去的那颗闹钟里没有 `startMs`（0.8.45 新加的字段）。
     *
     *  那个格式里没有年份，`SimpleDateFormat` 会给你 1970 —— 直接拿去减就是"早就上过课了"，
     *  于是刚装完包的第一节会当场报"现在上课"。所以按"下一个还没到的那一刻"来还原：
     *  1970 那个基准往上加年，加到它落在今天之后为止（最多加四档，加不出来就当没有）。 */
    fun parseAt(at: String): Long {
        val base = runCatching { timeFmt().parse(at)?.time ?: 0L }.getOrNull() ?: return 0L
        val now = System.currentTimeMillis()
        if (base <= 0L) return 0L
        val c = Calendar.getInstance().apply { timeInMillis = base }
        repeat(4) {
            if (c.timeInMillis > now) return c.timeInMillis
            c.add(Calendar.YEAR, 1)
        }
        return 0L
    }

    /** 还剩几分钟，向上取整：61 秒报"2 分钟"而不是"1 分钟"—— 倒计时不许少报。 */
    private fun minutesLeft(startMs: Long, now: Long = System.currentTimeMillis()): Int {
        if (startMs <= 0L) return 0
        val left = startMs - now
        if (left <= 0L) return 0
        return ((left + 59_999L) / 60_000L).toInt().coerceAtMost(9_999)
    }

    /**
     * 课前窗口里每分钟醒一次要做的两件事：把同一条通知的文字换成"还有 N 分钟"，再排下一分钟那颗。
     *
     * 全程不出声（他 10-25 一句"不要响"）：整条链刷的都是同一个号、都带 `setSilent`，
     * 所以数到 0 也只是把那一行换成"现在上课"，不再另开一条。
     *
     * 回 true = 链还在跑，接收者什么都不用做；回 false = 这条链到此为止，接收者去接下一节的窗口。
     * 两种"到此为止"：
     * ① N 数到 0，"现在上课"那一条已经发出去；
     * ② 下一分钟那颗排不上（这条广播里调闹钟被 ROM 顶回来了）。当场补救一次：只排在上课
     *    那一刻发那一发；再排不上就彻底放手，让接收者去排下一节 ——
     *    链断在哪儿就在哪儿停，不炸 App，也不把往后全天搭进去。
     *
     * 某一分钟被 Doze 压晚了也不会把数算歪：N 每次都从 `startMs` 现减，不是"上次数 + 1"。
     */
    fun tick(context: Context, course: String, room: String, period: Int, at: String, startMs: Long): Boolean {
        ensureChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        val left = minutesLeft(startMs)
        // 0.8.45 那一版把"现在上课"发在另一个号上，为的是让它能再响一声。现在全程不许响，
        // 两条并成一条；那个旧号见到就顺手清掉，不然从那一版覆盖安装上来的人，
        // 通知栏里会一直挂着一条没人管的"现在上课"。
        nm.cancel(STARTED_ID)
        nm.notify(NOTE_ID, build(context, course, room, period, at, left))
        if (left <= 0) return false
        val pi = tickIntent(context, course, room, at, period, startMs)
        val now = System.currentTimeMillis()
        // 排在"这个数该翻牌的那一刻"再晚 1.5 秒：早一步会把同一个数再报一遍；
        // 晚一步只是这一分钟多等一会儿，文字还是现算的，不会越走越偏。
        val nextAt = (startMs - (left - 1) * 60_000L + 1_500L).coerceAtLeast(now + 1_000L)
        if (armTick(context, nextAt, pi)) return true
        return !armTick(context, startMs.coerceAtLeast(now + 1_000L), pi)
    }

    /** 真把下一分钟那一颗排下去，成没成回个布尔。
     *  滴答不占 `setAlarmClock` 那条路 —— 那是"系统闹钟"，一节课二十颗会把状态栏点满小闹钟图标。 */
    private fun armTick(context: Context, at: Long, pi: PendingIntent): Boolean {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val exact = runCatching {
            Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()
        }.getOrDefault(false)
        return runCatching {
            if (exact) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            true
        }.getOrDefault(false)
    }

    private fun build(
        context: Context,
        course: String,
        room: String,
        period: Int,
        at: String,
        left: Int,
    ): Notification {
        val open = PendingIntent.getActivity(
            context, NOTE_ID,
            Intent(context, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val body = listOf(
            if (left > 0) "还有 $left 分钟" else "现在上课",
            "第 $period 节",
            at,
            room.takeIf { it.isNotBlank() },
        ).filterNotNull().joinToString(" · ")
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tab_schedule)
            .setContentTitle(course.ifBlank { if (left > 0) "快上课了" else "上课了" })
            .setContentText(body)
            .setSubText("上课提醒 · 节次时间是我们自己排的")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$course\n$body"))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // 一条都不响（10-25 他"不要响"）。两道一起上：建渠道时就把声音清了，
            // 这一句管已经装着旧渠道的机器 —— 渠道建完应用就改不动它的声音了，只能按条压。
            // FLAG_SILENT 是 31 起的，NotificationCompat 里带了闸门；26~30 上从 0.8.45
            // 覆盖安装上来的那些机器仍会照旧渠道响一次，那是渠道的事，不是这条的。
            .setSilent(true)
            .setOnlyAlertOnce(true)
            // 一条活到上课就作废的通知，角上挂个"几分钟前"只会让人误读它是什么时候发的
            .setShowWhen(false)
            .setContentIntent(open)
            .build()
    }

    /** 撤干净：窗口那颗 + 正在跑的滴答链。调用方得自己再发一次 reschedule 才会重新排。 */
    fun clear(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        alarm.cancel(pendingIntent(context, WINDOW_CODE, ACTION, "", "", "", 0, 0L))
        cancelTick(context)
    }
}

/**
 * 广播收起来那两侧用的排程：`goAsync()` 的票要在活儿干完才作废，
 * 所以协程跑完（哪怕抛了）必须 `finish()`，不然系统算我们一次未完成广播。
 * 排不上就算了 —— 这条通知不是关键路径，课表那一屏还看着。
 */
private fun BroadcastReceiver.rescheduleLater(context: Context) {
    val pending = goAsync()
    val graph = (context.applicationContext as AndaXinApp).graph
    CoroutineScope(Dispatchers.Default).launch {
        try {
            ClassReminder.reschedule(context, graph.store, graph.timetable)
        } catch (_: Exception) {
        } finally {
            pending.finish()
        }
    }
}

/**
 * 闹钟响了、滴答也响了，走的是同一件事：刷一次倒计时，并排下一分钟那颗。
 *
 * 数刷到"现在上课"为止 —— 链自己收尾，然后接下一节的闹钟（不接的话一天只提醒第一节）。
 * 为什么链只挂一颗、为什么数从墙钟现算，都写在 `ClassReminder` 顶上第 ④ 条。
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val course = intent.getStringExtra(ClassReminder.EXTRA_COURSE).orEmpty()
        // 空课名的那颗只是拿来取消的占位（WINDOW_CODE / TICK_CODE 各有一颗），收到就什么也不做
        if (course.isBlank()) return
        val room = intent.getStringExtra(ClassReminder.EXTRA_ROOM).orEmpty()
        val at = intent.getStringExtra(ClassReminder.EXTRA_AT).orEmpty()
        val period = intent.getIntExtra(ClassReminder.EXTRA_PERIOD, 0)
        // 覆盖安装之前排下去的那一颗里没有 startMs（这字段是 10-25 新加的），
        // 那就照通知上那句"MM-dd HH:mm"倒推 —— 认不出来就当已经上课，别把整条链卡死。
        val startMs = intent.getLongExtra(ClassReminder.EXTRA_START_MS, 0L)
            .takeIf { it > 0L } ?: ClassReminder.parseAt(at)
        val chainGoesOn = ClassReminder.tick(context, course, room, period, at, startMs)
        /*
         * 什么时候去接下一节的窗口：
         * · 这一节课的窗口刚响（ACTION）—— 立刻接，不等倒计时数完。不然这二十分钟里
         *   万一链被 ROM 掐了、进程被清了，往后全天都没人排了（旧版就是"响完立刻排下一颗"）。
         * · 倒计时走到底 / 排不上下一颗（链断了）—— 也接一次，幂等：同一节课重排还是那一颗。
         */
        if (!chainGoesOn || intent.action == ClassReminder.ACTION) rescheduleLater(context)
    }
}

/**
 * 重启之后把闹钟排回去。
 *
 * 这条只是兜底：MIUI 上开机广播收不收得到，取决于"自启动"给没给，那个开关我们读不到也改不了。
 * 所以设置那一行写的是"重启后要打开一次 App 才稳"，不装作开机就万事大吉。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) rescheduleLater(context)
    }
}
