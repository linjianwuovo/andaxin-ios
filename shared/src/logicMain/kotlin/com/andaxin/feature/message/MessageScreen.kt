package com.andaxin.feature.message

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andaxin.core.concurrency.clockText
import com.andaxin.core.design.Gap
import com.andaxin.core.design.LocalInk
import com.andaxin.core.design.Rhythm
import com.andaxin.core.design.Type
import com.andaxin.data.MessageItem
import com.andaxin.shell.ErrorLine
import com.andaxin.shell.Hairline
import com.andaxin.shell.Refreshable
import com.andaxin.shell.Sheet
import com.andaxin.shell.ShelfTail
import com.andaxin.shell.pressClick
import com.andaxin.state.MessageModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * 平移注：从 app 的 `feature/message/MessageScreen.kt` 搬进共享层（底栏五格那批）。
 * 屏组件改成依赖注入：Model 由宿主建好递进来（android 侧还是 adxViewModel 那条路，
 * iOS 壳自己管生命周期），轮询器那两路也明着给 —— `Poller` 是 app 侧的类，
 * 共享层认不得，只认它手上那两个 StateFlow（iOS 总装给原生轮询的同形 Flow 就行）。
 * `collectAsStateWithLifecycle` 在共享层没有 lifecycle-compose，换成 compose 自己的
 * `collectAsState` —— 屏幕内容一致，只是少了"后台不重组"那一层优化。
 */

/**
 * 消息屏要接的三路。
 *
 * [model]：共享层自己的 `MessageModel`（state/Models.kt），宿主负责建和活；
 * [lastRunAt]/[busy]：app 侧消息轮询器（`Poller`）手上的两个口 —— 顶上那行
 * "上次刷新：HH:mm，每 5 分钟一次"要说的是它。默认值 = 这一端还没接轮询，
 * 界面就永远写"还没轮询过"，不编一个假时刻。
 */
class MessageDeps(
    val model: MessageModel,
    val lastRunAt: StateFlow<Long> = MutableStateFlow(0L),
    val busy: StateFlow<Boolean> = MutableStateFlow(false),
)

@Composable
fun MessageScreen(deps: MessageDeps, modifier: Modifier = Modifier) {
    val state by deps.model.state.collectAsState()
    val lastRun by deps.lastRunAt.collectAsState()
    val busy by deps.busy.collectAsState()
    // 第一下按下去只是把这一行 arm 起来，第二次才真发 `readPushMessage.do`
    var armedRead by remember { mutableStateOf<String?>(null) }

    // 下拉一次重拉这一屏：新消息不用杀进程重开才看得见
    Refreshable({ deps.model.refresh() }, modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Gap.screen),
        ) {
            Spacer(Modifier.height(Gap.md))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Text("消息", fontSize = Type.display, fontWeight = FontWeight.SemiBold, color = LocalInk.current.strong)
                Text(
                    text = "${state.items.count { it.unread }} 未读",
                    fontSize = Type.meta,
                    color = LocalInk.current.faint,
                )
            }
            Spacer(Modifier.height(Gap.md))

            // 一条一张卡 → 一张卡里一行一条。三十条消息原来就是三十张卡，
            // 缝比字多，一屏数得过来的只有四五条。
            if (state.items.isEmpty() && !state.firstLoad) {
                Text("没有消息。", fontSize = Type.body, color = LocalInk.current.faint)
            } else if (state.items.isNotEmpty()) {
                Sheet {
                    state.items.forEachIndexed { i, item ->
                        // 「标已读」也是一发写操作（`msgCenter/readPushMessage.do`），以前点一下行就替学校发了，
                        // 而这条口的成功响应我们一次都没验过 —— 现在要连按两下，第一下只把这行变成"再按一次"
                        MessageRow(item, armed = armedRead == item.id) {
                            if (!item.unread) return@MessageRow
                            if (armedRead == item.id) {
                                armedRead = null
                                deps.model.markRead(item.id)
                            } else {
                                armedRead = item.id
                            }
                        }
                        if (i != state.items.lastIndex) {
                            Spacer(Modifier.height(Gap.xs))
                            Hairline()
                            Spacer(Modifier.height(Gap.xs))
                        }
                    }
                }
            }

            // 标已读没成，红点就还在那儿 —— 这时候必须说一句为什么，不然看着像点了没反应
            state.readError?.let {
                Spacer(Modifier.height(Gap.md))
                Text(it, fontSize = Type.meta, lineHeight = Type.metaLine, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(Gap.lg))
            Text(
                text = when {
                    busy -> "正在拉新消息…"
                    lastRun == 0L -> "还没轮询过"
                    // 对齐 `SimpleDateFormat("HH:mm").format(Date(lastRun))`：clockText 是
                    // "yyyy-MM-dd HH:mm:ss"，第 11..16 位就是钟点（本机时区，同一口径）。
                    else -> "上次刷新：" + clockText(lastRun).substring(11, 16) + "，每 5 分钟一次"
                },
                fontSize = Type.micro,
                color = LocalInk.current.faint,
            )
            Spacer(Modifier.height(Gap.sm))
            state.error?.let { ErrorLine(it) }
            ShelfTail()
        }
    }
}

@Composable
private fun MessageRow(item: MessageItem, armed: Boolean = false, onClick: () -> Unit) {
    val ink = LocalInk.current
    Row(
        Modifier
            .fillMaxWidth()
            .pressClick(shrink = false, onClick = onClick)
            .padding(vertical = Rhythm.rowV),
    ) {
        // 未读那个点放在最左边：原来它在最右边，跟正文挤在一起，扫的时候看不见
        if (item.unread) {
            Box(
                Modifier
                    .padding(top = 7.dp)
                    .size(6.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
            Spacer(Modifier.width(Gap.sm))
        }
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                fontSize = Type.title,
                lineHeight = Type.bodyLine,
                fontWeight = if (item.unread) FontWeight.Medium else FontWeight.Normal,
                color = ink.strong,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.brief.isNotBlank()) {
                Spacer(Modifier.height(Gap.xxs))
                Text(
                    item.brief,
                    fontSize = Type.meta,
                    lineHeight = Type.metaLine,
                    color = ink.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(Gap.sm))
        // 时间挪到右边那一栏：它以前占整行第三行，一行消息要三行才说完
        Column(Modifier.widthIn(max = 96.dp), horizontalAlignment = Alignment.End) {
            if (armed) {
                // arm 起来的那一下必须当场看得见，不然人只会以为"点了没反应"
                Text(
                    text = "再按一次标已读",
                    fontSize = Type.micro,
                    lineHeight = Type.metaLine,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            Text(
                text = item.sentAt.substringAfter(' ').ifBlank { item.sentAt },
                fontSize = Type.micro,
                color = ink.ghost,
                textAlign = TextAlign.End,
            )
        }
    }
}
