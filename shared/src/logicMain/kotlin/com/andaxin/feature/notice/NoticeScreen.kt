package com.andaxin.feature.notice

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.andaxin.core.design.Gap
import com.andaxin.core.design.LocalInk
import com.andaxin.core.design.Type
import com.andaxin.core.net.formUrlEncode
import com.andaxin.data.NoticeBody
import com.andaxin.data.NoticeFeed
import com.andaxin.feature.brief.ScaffoldPage
import com.andaxin.shell.ErrorLine
import com.andaxin.shell.Hairline
import com.andaxin.shell.Refreshable
import com.andaxin.shell.RowLine
import com.andaxin.shell.Sheet
import com.andaxin.shell.ShelfTail
import com.andaxin.shell.pressClick
import com.andaxin.state.NoticeModel

/*
 * 平移注：从 app 的 `feature/notice/NoticeScreen.kt` 搬进共享层（底栏五格那批）。
 * 列表屏只认宿主递进来的 `NoticeModel`；详情屏自己按 id 取正文，只认 `NoticeFeed`。
 * `android.net.Uri.encode` → `formUrlEncode`（同口径：字母数字与 . - * _ 之外全编，
 * 斜杠编成 %2F —— 校方那批公告 id 里就带斜杠）；正文配图从 RemoteImage（OkHttp+解码，
 * app 侧的那套）抽成一个宿主给的槽，没给就摆一行占位说明，不静默吞图。
 */

/*
 * 公告：列表是底栏的一格，详情是**真正的二级页**（走导航路由）。
 *
 * 10-22 深夜他报的那条：点进公告后系统返回手势直接把整个应用退掉。根因就在这儿 ——
 * 以前详情是列表页里 `state.open != null` 就地换内容，导航栈里始终只有底栏那一格，
 * 手势没有可弹的东西，于是弹的是 Activity。顺带那个"就地换"也没有二级页的推入转场，
 * 看着就不像进了另一屏。现在列表只负责列表，点一行 `open("notice/detail/<id>")` 推一页。
 */

/** 详情那一格的路由前缀，和 app 侧 `Route.NOTICE_DETAIL` 对齐。id 里可能有斜杠，必须 encode。 */
const val NOTICE_ROUTE = "notice/detail/"

/** 公告列表那一格要接的：Model 由宿主建好递进来。 */
class NoticeDeps(
    val model: NoticeModel,
)

/**
 * 公告详情那一屏要接的。
 *
 * [attachment]：正文里嵌的安大、九安办那种附图怎么画 —— android 走我们自己的
 * OkHttp + 解码（`core/net/Images.kt` 的 RemoteImage），iOS 总装给原生下载。
 * 给 null 就摆一行占位说明：附图必须明说"没画出来"，不能静默吞掉。
 */
class NoticeDetailDeps(
    val feed: NoticeFeed,
    val attachment: (@Composable (url: String) -> Unit)? = null,
)

@Composable
fun NoticeScreen(deps: NoticeDeps, open: (String) -> Unit = {}, modifier: Modifier = Modifier) {
    val state by deps.model.state.collectAsState()

    // 只有列表这一支需要下拉刷新，详情那一屏没什么可刷的
    Refreshable({ deps.model.refresh() }, modifier.fillMaxSize()) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Gap.screen),
        ) {
            Spacer(Modifier.height(Gap.md))
            Text("公告", fontSize = Type.display, fontWeight = FontWeight.SemiBold, color = LocalInk.current.strong)
            Spacer(Modifier.height(Gap.md))

            // 原来一条一行、一行自己画一条分隔线，三十条就是三十块卡片。
            // 现在一张卡里分行，行间细线，时间和来源在右
            if (state.items.isNotEmpty()) {
                Sheet {
                    state.items.forEachIndexed { i, item ->
                        RowLine(
                            title = item.title,
                            sub = item.source,
                            right = item.publishedAt.removePrefix("20").replace('-', '.'),
                            onClick = { open(NOTICE_ROUTE + formUrlEncode(item.id)) },
                        )
                        if (i != state.items.lastIndex) {
                            Spacer(Modifier.height(Gap.xs))
                            Hairline()
                            Spacer(Modifier.height(Gap.xs))
                        }
                    }
                }
            }

            Spacer(Modifier.height(Gap.lg))
            state.error?.let { ErrorLine(it) }
            ShelfTail()
        }
    }
}

/**
 * 公告详情。自己按 id 取那一发正文（不借列表页那份 ViewModel 的 `open` 状态 ——
 * 那样等于把两屏的命脉拴在一起，退回列表时还得手动清）。
 */
@Composable
fun NoticeDetailScreen(deps: NoticeDetailDeps, id: String, onBack: () -> Unit) {
    val ink = LocalInk.current
    var body by remember(id) { mutableStateOf<NoticeBody?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }

    suspend fun load() {
        error = null
        val r = try { deps.feed.detail(id) } catch (e: Exception) { Result.failure(e) }
        body = r.getOrNull()
        error = r.exceptionOrNull()?.message
    }

    LaunchedEffect(id) { load() }

    ScaffoldPage(
        title = body?.title ?: "公告",
        onBack = onBack,
        // 详情页也给下拉刷新：进来时没网，退出去再进来才算数
        onRefresh = { load() },
    ) {
        Spacer(Modifier.height(Gap.md))
        val b = body
        when {
            b != null -> {
                Text(b.publishedAt, fontSize = Type.meta, color = ink.faint)
                Spacer(Modifier.height(Gap.lg))
                b.paragraphs.forEach {
                    Text(
                        it,
                        fontSize = Type.body,
                        lineHeight = Type.bodyLine,
                        color = ink.body,
                        modifier = Modifier.padding(bottom = Gap.md),
                    )
                }
                val drawAttachment = deps.attachment
                b.attachments.forEach { url ->
                    if (drawAttachment != null) {
                        drawAttachment(url)
                        Spacer(Modifier.height(Gap.md))
                    } else {
                        Text(
                            text = "正文附图（这一端还没接图片加载）：" +
                                url.substringAfterLast('/').ifBlank { url },
                            fontSize = Type.meta,
                            color = ink.faint,
                            modifier = Modifier.padding(bottom = Gap.md),
                        )
                    }
                }
            }
            error != null -> ErrorLine(error.orEmpty())
            else -> Text("正在拉这一条…", fontSize = Type.body, color = ink.faint)
        }
        ShelfTail()
    }
}
