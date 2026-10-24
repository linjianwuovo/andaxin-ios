package com.andaxin.feature.me

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.andaxin.core.design.Gap
import com.andaxin.core.design.LocalInk
import com.andaxin.core.design.Palette
import com.andaxin.core.design.Radius
import com.andaxin.core.design.Type
import com.andaxin.core.store.Session
import com.andaxin.core.store.SessionStore
import com.andaxin.core.store.WhoIs
import com.andaxin.core.store.forAccount
import com.andaxin.data.capture.Profile
import com.andaxin.shell.Hairline
import com.andaxin.shell.RowLine
import com.andaxin.shell.SectionTitle
import com.andaxin.shell.Sheet
import com.andaxin.shell.ShelfTail
import com.andaxin.shell.enterFade
import com.andaxin.shell.pressClick
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/*
 * 平移注：从 app 的 `feature/me/MeScreen.kt` 搬进共享层（底栏五格那批）。
 * 屏组件改成依赖注入：
 *   会话/学籍缓存/两发只读口全是共享层类型；R.drawable 的入口小图抽成 `entryIcon`
 *   宿主槽（key = 入口 route，没给就画纯文字格 —— 本来这些图标也只有 android 那边画得出）；
 *   「关于」那格的版本号（BuildConfig.VERSION_NAME）由 `versionName` 递进来；
 *   头像（AvatarStore 的 Context+Bitmap 那一套）抽成 `AvatarDeps`。
 * LocalContext 一处不剩，四目标通用。
 */

/**
 * 本机头像那一格的宿主口。校方没有改头像的接口（`getPersonalInfo.do` 只读），
 * 头像只在本机生效 —— 存哪、怎么解码全是平台的事（android = AvatarStore 的
 * filesDir/avatar.img；iOS 总装给沙盒里那份），共享层只画"有图/没图"两种样子。
 */
class AvatarDeps(
    /** 有没有本机头像。版本号驱动：换完自增，界面自动换帧（app = AvatarStore.version > 0）。 */
    val present: Flow<Boolean>,
    /** 有图的时候怎么画：画进 [size] 的圆里，裁切（ContentScale.Crop）由宿主负责。 */
    val draw: @Composable (size: Dp) -> Unit,
    /** 点头像换图那一下（android = 相册选择器；iOS = PHPicker）。null = 这一端没接。 */
    val pick: (() -> Unit)? = null,
    /** 换图结果的那句提示（"换好了…"/"没读进来…"），头像下面那一行小字。 */
    val note: StateFlow<String?> = MutableStateFlow(null),
    /** 进屏时宿主对一遍文件（app = AvatarStore.refresh），冷启动也能把上次那张读出来。 */
    val refresh: () -> Unit = {},
)

/**
 * 「我的」这一格要接的。
 *
 * 名字、学号从会话落盘的键里读（[session]），不发网络请求 —— 这一页要能离线打开。
 * 学院/专业/班级/辅导员那两发只读口走 [loadProfile]/[loadIntern]，结果缓在 [who]
 * （进程上而不是这一屏的 remember 里）：去一趟二级页回来这一屏会整个重建，
 * 本地那份归零，那行就先画成兜底文案、等接口回来再跳回真值 ——
 * 他 10-22 那句「学院哪个班会闪」就是这个跳。
 */
class MeDeps(
    val session: SessionStore,
    val who: WhoIs,
    /** 学籍资料那发（app = CaptureProfile.load）。 */
    val loadProfile: suspend () -> Result<Profile>,
    /** 实习生标记那发（app = CaptureHome.intern）。 */
    val loadIntern: suspend () -> Result<Boolean>,
    /** 本机头像那一格。null = 这一端还没接头像，画名字首字的兜底。 */
    val avatar: AvatarDeps? = null,
    /** 入口格那一枚双色小图（key = [Entry.route]，"affair"/"dorm"/…）。null = 纯文字格。 */
    val entryIcon: (@Composable (key: String) -> Unit)? = null,
    /** 「关于」那一格的版本号（app = BuildConfig.VERSION_NAME）。 */
    val versionName: String = "",
)

/** 一个入口。原来的 icon/iconAccent 两张画片归了 [MeDeps.entryIcon]，key 就是 [route]。 */
private class Entry(
    val title: String,
    val note: String,
    val route: String? = null,
)

private val ENTRIES = listOf(
    Entry("待办事务", "待办·申请·已办", "affair"),
    Entry("查寝签到", "记录与详情", "dorm"),
    Entry("校园卡", "余额与流水", "card"),
    Entry("报修", "单据与新建", "repair"),
    Entry("AI 课堂", "课件与签到", "aiclass"),
    Entry("公开课", "目录与进度", "opencourse"),
    Entry("班级群", "班内名单", "group"),
    Entry("考试成绩", "列表与明细", "exam"),
    Entry("素质学分", "五育分类", "credit"),
    Entry("劳动教育", "工时认定", "labor"),
    Entry("离返校", "记录与登记", "holiday"),
    Entry("运动", "开始与记录", "sport"),
    Entry("运动记录", "明细/维护", "sport/records"),
    Entry("更多功能", "全部入口", "more"),
    Entry("主题", "材质与玻璃", "theme"),
)

@Composable
fun MeScreen(deps: MeDeps, open: (String) -> Unit = {}, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val session by deps.session.state.collectAsState(Session())
    val ink = LocalInk.current
    // 学院/专业/班级/辅导员从校方拉，只读两条接口；拉不到就退回"校园账号 xxx"那行。
    val cachedProfile by deps.who.profile.collectAsState()
    // 校方那条 checkIsInternStu 只回一个布尔（真值 `data{isInternStu:false}`），
    // 所以这一行就照它说"是/不是"，不推断它背后的含义。
    val cachedIntern by deps.who.intern.collectAsState()
    // 账号对不上等于没拉到过：换账号（退出、被顶号后登成别人）不能拿上一位的资料先画一帧
    val profile = cachedProfile?.forAccount(session.account)
    val intern = cachedIntern?.forAccount(session.account)
    LaunchedEffect(session.loggedIn, session.generation) {
        // 跟着 generation 再来一遍：被顶号后自动重登时 loggedIn 没变，光 key 它的话
        // 这一屏会一直挂着上一次失败的结果
        if (session.loggedIn) {
            val at = session.account
            deps.loadProfile().onSuccess { deps.who.putProfile(at, it) }
            deps.loadIntern().onSuccess { deps.who.putIntern(at, it) }
        }
    }

    // 头像不是 Compose 状态，宿主给个版本号，变了就换帧
    val avatar = deps.avatar
    LaunchedEffect(avatar) { avatar?.refresh() }
    val avatarPresent = avatar?.present?.collectAsState(false)?.value ?: false

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Gap.screen),
    ) {
        Spacer(Modifier.height(Gap.md))
        // 头像这一整行点进「个人信息」：资料、换头像、退出登录都在那一屏（他 10-21 09:3x）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (session.loggedIn) Modifier.pressClick(shrink = false) { open("profile") } else Modifier)
                .padding(vertical = Gap.xs),
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Palette.Sand, Radius.pill),
                contentAlignment = Alignment.Center,
            ) {
                if (avatarPresent && avatar != null) {
                    avatar.draw(40.dp)
                } else {
                    Text(
                        text = session.userName.firstOrNull()?.toString() ?: "安",
                        fontSize = Type.title,
                        color = if (session.loggedIn) ink.strong else ink.muted,
                    )
                }
            }
            Spacer(Modifier.width(Gap.md))
            Column(Modifier.weight(1f)) {
                Text(
                    text = when {
                        session.loggedIn -> session.userName.ifBlank { session.account }
                        session.browsing -> "未登录，看的是占位数据"
                        else -> "未登录"
                    },
                    fontSize = Type.title,
                    fontWeight = FontWeight.Medium,
                    color = ink.strong,
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    text = when {
                        session.loggedIn && profile != null ->
                            "${profile.college} · ${profile.major} · ${profile.className}" +
                                (intern?.let { " · ${if (it) "实习生" else "非实习生"}" } ?: "")
                        session.loggedIn -> "校园账号 ${session.account}" +
                                (intern?.let { " · ${if (it) "实习生" else "非实习生"}" } ?: "")
                        else -> "登录后这一格会换成你的名字和学号"
                    },
                    fontSize = Type.meta,
                    color = ink.faint,
                )
            }
            if (session.loggedIn) {
                Text("›", fontSize = Type.title, color = ink.faint)
            }
        }

        if (!session.loggedIn) {
            Spacer(Modifier.height(Gap.md))
            RowLine("去登录", sub = "回到登录那一屏", right = "›", onClick = {
                scope.launch { deps.session.leaveBrowsing() }
            })
        }

        SectionTitle("功能")
        EntryGrid(ENTRIES + Entry("关于", deps.versionName, "about"), deps.entryIcon, open)

        ShelfTail()
    }
}

/**
 * 三列文字格。
 *
 * 为什么不用图标：仓里只画了底栏那五枚 vector，剩下这些入口没有图标，
 * 硬凑一堆 emoji 或通用图形反而更花；先把"一屏看全"这件事做到。
 * （后来 android 端补了画片，走 [icon] 这个宿主槽递进来；iOS 没给就还是纯文字格。）
 */
@Composable
private fun EntryGrid(entries: List<Entry>, icon: (@Composable (key: String) -> Unit)?, open: (String) -> Unit) {
    val ink = LocalInk.current
    Sheet(modifier = Modifier.enterFade()) {
        entries.chunked(3).forEachIndexed { rowIdx, row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { e ->
                    Column(
                        Modifier
                            .weight(1f)
                            .pressClick { e.route?.let(open) }
                            .padding(vertical = Gap.md),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (icon != null && e.route != null) {
                            Box(Modifier.size(22.dp)) { icon(e.route) }
                            Spacer(Modifier.height(Gap.xs))
                        }
                        Text(
                            text = e.title,
                            fontSize = Type.body,
                            fontWeight = FontWeight.Medium,
                            color = ink.strong,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(1.dp))
                        Text(
                            text = e.note,
                            fontSize = Type.micro,
                            color = ink.ghost,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            if (rowIdx != entries.chunked(3).lastIndex) Hairline()
        }
    }
}
