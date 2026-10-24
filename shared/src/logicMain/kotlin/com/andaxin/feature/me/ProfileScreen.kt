package com.andaxin.feature.me

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.andaxin.core.design.Gap
import com.andaxin.core.design.LocalInk
import com.andaxin.core.design.Palette
import com.andaxin.core.design.Radius
import com.andaxin.core.design.Type
import com.andaxin.core.store.Session
import com.andaxin.core.store.SessionStore
import com.andaxin.core.store.WhoIs
import com.andaxin.data.capture.Profile
import com.andaxin.feature.brief.ScaffoldPage
import com.andaxin.shell.Hairline
import com.andaxin.shell.Labeled
import com.andaxin.shell.Sheet
import com.andaxin.shell.pressClick
import com.andaxin.shell.rememberArmConfirm
import kotlinx.coroutines.launch

/*
 * 平移注：从 app 的 `feature/me/ProfileScreen.kt` 搬进共享层（底栏五格那批）。
 * 相册选择（ActivityResultContracts）、头像解码与落盘（AvatarStore）都是平台的事，
 * 全部收进 `AvatarDeps` 宿主口；学籍资料那发只读口和「我是谁」缓存改成递进来。
 * app 侧 Bits.kt 的 `LoadWhy` 还牵着 LocalAppGraph（一堆 app 侧的屏在用），搬不动 ——
 * 这里留了一份私有同款，判据改成明着递的 `needsLogin`。
 */

/** 「个人信息」这一屏要接的。 */
class ProfileDeps(
    val session: SessionStore,
    val who: WhoIs,
    /** 学籍资料那发（app = CaptureProfile.load）。 */
    val loadProfile: suspend () -> Result<Profile>,
    /** 本机头像那一格。null = 这一端还没接头像，画名字首字的兜底。 */
    val avatar: AvatarDeps? = null,
)

/**
 * 个人信息。点头像进来这一屏，里面是：
 * 头像（点一下从相册换）→ 姓名 / 学号 → 校方那两条只读接口给的身份信息 → 退出登录。
 *
 * 为什么把「退出登录」搬到这里：它原来躺在「我的」最底下，跟主题、关于混在一起；
 * 而退出是账号级动作，放进"这个人的资料"里才讲得通（他 10-21 09:3x 的说法）。
 *
 * 头像**只在本机生效** —— 校方没有改头像的接口（`getPersonalInfo.do` 只读），
 * 所以下面那行小字要写明这一点，不能让人以为换到学校那边去了。
 */
@Composable
fun ProfileScreen(deps: ProfileDeps, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ink = LocalInk.current
    val session by deps.session.state.collectAsState(Session())

    var profile by remember { mutableStateOf<Profile?>(null) }
    var why by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val quitArm = rememberArmConfirm()

    // 头像不是 Compose 状态，宿主给个版本号，变了就换帧
    val avatar = deps.avatar
    LaunchedEffect(avatar) { avatar?.refresh() }
    val avatarPresent = avatar?.present?.collectAsState(false)?.value ?: false
    val avatarNote = avatar?.note?.collectAsState()?.value

    LaunchedEffect(session.generation, session.loggedIn) {
        loading = true
        why = null
        if (session.loggedIn) {
            deps.loadProfile()
                .onSuccess {
                    profile = it
                    // 顺手回填「我是谁」那份缓存：从这一屏退回「我的」，名字下面那行
                    // 用的就是刚刷新过的值，不会再各拉各的
                    deps.who.putProfile(session.account, it)
                }
                .onFailure { why = it.message ?: "资料没拉到" }
        }
        loading = false
    }

    ScaffoldPage(title = "个人信息", onBack = onBack) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(Gap.lg))
            Box(
                Modifier
                    .size(88.dp)
                    .clip(CircleShape)
                    .background(Palette.Sand, Radius.pill)
                    .pressClick { avatar?.pick?.invoke() },
                contentAlignment = Alignment.Center,
            ) {
                if (avatarPresent && avatar != null) {
                    avatar.draw(88.dp)
                } else {
                    Text(
                        text = session.userName.firstOrNull()?.toString() ?: "安",
                        fontSize = Type.display,
                        fontWeight = FontWeight.Medium,
                        color = if (session.loggedIn) ink.strong else ink.muted,
                    )
                }
            }
            /*
             * 头像下面原来挂着一行蓝字「点一下换头像」，他 10-2x 说太碍眼，去掉。
             * 头像本身还是可点的（点开相册），只是不再拿一行字教人点它。
             */
            avatarNote?.let {
                Spacer(Modifier.height(Gap.xs))
                Text(it, fontSize = Type.micro, color = ink.faint)
            }

            Spacer(Modifier.height(Gap.lg))
            Text(
                text = session.userName.ifBlank { if (session.loggedIn) session.account else "未登录" },
                fontSize = Type.display,
                fontWeight = FontWeight.SemiBold,
                color = ink.strong,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (session.account.isBlank()) "校园账号" else "校园账号 ${session.account}",
                fontSize = Type.meta,
                color = ink.muted,
            )
            Spacer(Modifier.height(Gap.lg))

            if (!session.loggedIn) {
                LoadWhy("登录后才看得到学籍资料。", needsLogin = true,
                    onLogin = { scope.launch { deps.session.leaveBrowsing() } }, wide = false)
            } else {
                val p = profile
                if (p != null) {
                    Sheet {
                        val rows = listOf(
                            "学院" to p.college,
                            "专业" to p.major,
                            "班级" to p.className,
                            "学制" to p.length,
                            "学籍状态" to p.status,
                            "入学年月" to p.enrolledAt,
                            "辅导员" to p.counselor,
                            "辅导员电话" to p.counselorPhone,
                            "手机" to p.mobile,
                            "昵称" to p.nickname,
                        ).filter { it.second.isNotBlank() }
                        rows.forEachIndexed { i, (label, value) ->
                            Labeled(label = label, value = value)
                            if (i != rows.lastIndex) {
                                Spacer(Modifier.height(Gap.xs))
                                Hairline()
                                Spacer(Modifier.height(Gap.xs))
                            }
                        }
                    }
                } else if (loading) {
                    Text("正在拉资料…", fontSize = Type.meta, color = ink.faint)
                } else {
                    LoadWhy(why ?: "资料没拉到。", needsLogin = !session.loggedIn,
                        onLogin = { scope.launch { deps.session.leaveBrowsing() } })
                }
            }

            Spacer(Modifier.height(Gap.xl))

            if (session.loggedIn) {
                /*
                 * 退出登录：一颗圆角矩形按钮，字居中，就写"退出登录"（他 10-21 09:3x：
                 * "直接写退出登录、用圆矩框"）。连按两次才真退 —— 不留神碰到就掉登录太亏。
                 */
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(ink.offFill, Radius.field)
                        .pressClick {
                            if (quitArm.armed) {
                                /*
                                 * **退出登录不再把口令忘掉**（他 10-21 12:1x：
                                 * 「退出登录后为什么要重新输入账密，不是早就让你写记住密码了吗」）。
                                 * 他勾了记住密码，退出就只退会话；回到登录页时账号、口令都已经填好，
                                 * 按一下「登录」就回来（10-23 起口令直接进那一格，不再另设一颗一键重登）。
                                 * 真想忘掉口令，去那一页把勾去掉再登一次 —— 那是另一件事。
                                 */
                                scope.launch {
                                    deps.session.signOut(keepPassword = true)
                                    // 学院/班级那两份是内存里的，人退了就把上一位的资料一起放掉
                                    deps.who.clear()
                                    quitArm.disarm()
                                    // 退完要回到根：留在这一屏只会看见"未登录"和一颗没反应的「去登录」
                                    // （Gate 在 SHELF 那一层，这一屏是它上面的独立路由）。
                                    onBack()
                                }
                            } else {
                                quitArm.arm()
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (quitArm.armed) "再点一次就退出 ${quitArm.countdown}s" else "退出登录",
                        fontSize = Type.body,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Spacer(Modifier.height(Gap.xxl))
        }
    }
}

/**
 * 一屏没拉到数据时那一句 —— app 侧 Bits.kt 那份的私有同款。
 *
 * 两种语气，别混着涂：没登录是一种状态不是故障（走灰 + 给一条「去登录」，
 * 只清"先不登录逛逛"那个标志，外层 Gate 会把登录那一屏端上来）；
 * 真失败（连不上、网关给 HTML、结构不对）才用红色那句。
 * 判据不问协议层要新异常类型 —— 界面这一层本来就知道 `session.loggedIn`。
 */
@Composable
private fun LoadWhy(
    why: String?,
    needsLogin: Boolean,
    onLogin: () -> Unit,
    modifier: Modifier = Modifier,
    wide: Boolean = true,
) {
    if (why.isNullOrBlank()) return
    val ink = LocalInk.current
    Column(modifier) {
        Text(
            text = why,
            fontSize = Type.meta,
            lineHeight = Type.metaLine,
            color = if (needsLogin) ink.muted else MaterialTheme.colorScheme.error,
        )
        if (needsLogin) {
            Spacer(Modifier.height(if (wide) Gap.lg else Gap.sm))
            Text(
                text = "去登录",
                fontSize = Type.meta,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.pressClick(onClick = onLogin),
            )
        }
    }
}
