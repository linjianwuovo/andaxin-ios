package com.andaxin.feature.notify

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.andaxin.app.LocalAppGraph
import com.andaxin.core.design.Gap
import com.andaxin.core.design.LocalInk
import com.andaxin.core.design.Type
import com.andaxin.core.notification.LiveUpdates
import com.andaxin.feature.brief.ScaffoldPage
import com.andaxin.shell.Chip
import com.andaxin.shell.Hairline
import com.andaxin.shell.RowLine
import com.andaxin.shell.Sheet
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.SwitchDefaults

/*
 * 通知这一屏：凡是"要不要响、响在哪、系统让不让响"的设置都归在这儿。
 *
 * 以前上课提醒摆在「我的」最底下那一格「本机」里，跟"这台机器存了什么"混在一起；
 * 实况更新那两行又单独挂在「更多功能」上（他 10-22 那句「这个最底下的上课提醒
 * 归到通知的设置里」）。两处拆开没人找得到，所以并成一屏。
 */

@Composable
fun NotifyScreen(onBack: () -> Unit) {
    ScaffoldPage(title = "通知", onBack = onBack) {
        Sheet { ClassReminderRow() }
        Spacer(Modifier.height(Gap.lg))
        Sheet { LiveUpdateRows() }
    }
}

/**
 * 上课提醒那一行。四句话得说明白，少一句都会让人误解它，但**不摆在大路上**：
 * ① 节次时间是我们自己排的那张表，校方没有一条接口给过；
 * ② 它用系统闹钟那条路，状态栏会亮一个闹钟图标；
 * ③ 重启之后能不能自己排回去，看的是 ROM 的"自启动"，那个开关我们碰不到；
 * ④ 从提前量那一分钟起，同一条通知每分钟刷一次"还有 N 分钟"，只有第一条出声。
 */
@Composable
private fun ClassReminderRow() {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    val ink = LocalInk.current
    var on by remember { mutableStateOf(false) }
    var lead by remember { mutableStateOf(com.andaxin.core.schedule.ClassReminder.DEFAULT_LEAD) }
    var status by remember { mutableStateOf("正在看本机存了什么…") }

    var needPermission by remember { mutableStateOf(false) }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        needPermission = false
        if (granted) status = "通知权限有了。下一节课前 $lead 分钟起，倒计时会开始往通知上刷。"
    }

    LaunchedEffect(Unit) {
        val p = graph.store.snapshot()
        on = com.andaxin.core.schedule.ClassReminder.isOn(p, graph.store)
        lead = com.andaxin.core.schedule.ClassReminder.leadOf(p, graph.store)
        status = com.andaxin.core.schedule.ClassReminder.reschedule(graph.context, graph.store, graph.timetable)
    }

    fun write(nv: Boolean, nLead: Int) {
        on = nv; lead = nLead
        scope.launch {
            graph.store.setMany(
                com.andaxin.core.schedule.ClassReminder.KEY_ON to if (nv) "1" else "0",
                com.andaxin.core.schedule.ClassReminder.KEY_LEAD to nLead.toString(),
            )
            // fromUser = 他亲手拨的：正在跑的倒计时链一起撤掉，别让他按了关还看见"还有 12 分钟"
            status = com.andaxin.core.schedule.ClassReminder.reschedule(
                graph.context, graph.store, graph.timetable, fromUser = true,
            )
        }
        if (nv && !androidx.core.app.NotificationManagerCompat.from(graph.context).areNotificationsEnabled()) {
            needPermission = true
            ask.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Row(
        Modifier.fillMaxWidth().padding(vertical = Gap.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("上课提醒", fontSize = Type.title, fontWeight = FontWeight.Medium, color = ink.strong)
            Spacer(Modifier.height(1.dp))
            Text(
                text = if (on) "开 · 课前 $lead 分钟起，通知每分钟把倒计时刷一次" else "没开",
                fontSize = Type.meta,
                color = if (needPermission) MaterialTheme.colorScheme.error else ink.muted,
            )
        }
        Switch(
            checked = on,
            onCheckedChange = { write(it, lead) },
            // Miuix 的"关"那一档默认拿 secondary（我们这儿是青绿）当轨道色 —— 白钮停在左边、
            // 轨道却是饱和的青绿，看着像开着。他 10-20 深夜定过一条：关闭态给灰底。
            colors = SwitchDefaults.switchColors(
                uncheckedTrackColor = ink.offFill,
            ),
        )
    }
    if (on) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Gap.sm),
        ) {
            com.andaxin.core.schedule.ClassReminder.LEADS.forEach { n ->
                Chip("$n 分", n == lead) { write(on, n) }
            }
        }
    }
    // 状态那句和上面那行副标题是两套话（一个说开关、一个说排程结果），
    // 撞车的时候（两边都是"没开"）就别重复印两遍 —— 装机读屏看到的就是连着两行"没开"
    if (status.isNotBlank() && !(status.startsWith("没开") && !on)) {
        Spacer(Modifier.height(Gap.xs))
        Text(status, fontSize = Type.micro, color = ink.faint)
    }
}

/**
 * 实况更新（状态栏那颗胶囊）这两行。三条坑为什么这么问，见 `core/notification/LiveUpdates.kt`。
 */
@Composable
private fun LiveUpdateRows() {
    val ctx = LocalContext.current
    // 每次进屏、每次点完都重新问一遍系统：ROM 那个开关是用户随时能在设置里拨的，
    // 缓存下来就成了"我明明开了它还说不行"
    var askTick by remember { mutableIntStateOf(0) }
    val canPost = askTick.let { LiveUpdates.canPost(ctx) }
    val askPromoted = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) {
        askTick++
        // 系统要是没弹这一发（权限本来就给了、或者这个 ROM 根本没那套弹层），就地把他送到
        // 应用的通知设置页 —— 点了没反应比点了报错更难查
        if (!LiveUpdates.canPost(ctx)) LiveUpdates.openNotificationSettings(ctx)
    }

    RowLine(
        title = "实况更新权限",
        // 这一行右边还要摆状态那两个字，副标题写长了会被裁成"…"，所以压到一句
        sub = "不给也能用，只是不上状态栏那颗胶囊。",
        right = when {
            !LiveUpdates.supported() -> "这台没有"
            canPost -> "已允许"
            else -> "没允许"
        },
        onClick = {
            if (LiveUpdates.supported() && !canPost) {
                askPromoted.launch(android.Manifest.permission.POST_PROMOTED_NOTIFICATIONS)
            } else {
                LiveUpdates.openNotificationSettings(ctx)
            }
        },
    )
    Hairline()
    RowLine(
        title = "系统里的实况开关",
        sub = "总开关、允许哪些应用上岛，都在那一页。",
        right = "›",
        onClick = { LiveUpdates.openNotificationSettings(ctx) },
    )
}

