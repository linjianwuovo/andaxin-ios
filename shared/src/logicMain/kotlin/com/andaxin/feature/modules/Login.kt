package com.andaxin.feature.modules

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.andaxin.core.concurrency.ioDispatcher
import com.andaxin.core.design.Gap
import com.andaxin.core.design.LocalInk
import com.andaxin.core.design.Palette
import com.andaxin.core.design.Radius
import com.andaxin.core.design.Type
import com.andaxin.core.store.SessionStore
import com.andaxin.core.store.Vault
import com.andaxin.data.capture.AuthFeed
import com.andaxin.feature.brief.ScaffoldPage
import com.andaxin.shell.pressClick
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextField as MiuixTextField

/**
 * 登录页要接的四路。
 *
 * 原来这些全从 app 的 `LocalAppGraph` 里抓 —— 共享层认不得那个图，所以改成明着递进来。
 * app 侧由 AppGraph 拼（auth/session/vault/keepAlive 本来就是现成的四样）；
 * iOS 壳总装时照这四样各给一份就行。类型全是共享层自己的，这里不见 android 任何东西。
 */
class LoginDeps(
    /** 登录那条口：`login()` 出 TokenBundle；`AuthRejected` 是"学校明说不行"的那种失败。 */
    val auth: AuthFeed,
    /** 会话落盘：学号、「记住密码」那几个键都在它手里。 */
    val session: SessionStore,
    /** 口令的密封/解封（android=Keystore、iOS=Keychain，契约见 `Vault`）。 */
    val vault: Vault,
    /** 被请回登录页的原因（被顶号 / 到期续不上）；给人看过一次就该 [consumeKicked] 抹掉。 */
    val kicked: StateFlow<String?>,
    val consumeKicked: () -> Unit,
)

/**
 * 登录。口令在本机用服务器的公钥 RSA 加密后直发 `getToken.do`，不经过任何第三方。
 *
 * 报文的形状是自己 10-19 打实样验过的，见 `data/capture/AuthFeed.kt` 顶上那段注释。
 */
@Composable
fun LoginScreen(deps: LoginDeps, onDone: () -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ink = LocalInk.current
    var account by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    // 默认勾上：这台机器上"每次到期都要手打口令"才是问题，不记是例外
    var rememberPw by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    // 被"续期失败"请回来的原因：进这一屏时按当前值取一次。
    // 别用 StateFlow + LaunchedEffect 往 note 里灌 —— 装机实测那样那一行根本不出来：
    // 会话翻转和 reason 落地不在同一帧，effect 跑到第二次时 reason 已经被消费成 null 了。
    var note by remember { mutableStateOf(deps.kicked.value) }
    DisposableEffect(Unit) {
        onDispose { deps.consumeKicked() }
    }
    // 本机留着学号时先把账号这一格填上：会话掉了只说明那把票不作数了，账号还是那个账号，
    // 让人把十位数再敲一遍是白折腾。
    //
    // 口令这一格也填，而且填的是真从 Keystore 里解出来的那一条 —— 不是摆几个点糊弄人。
    // 10-23 早上他对着「口令空着、勾却打着、顶上多一颗"用这台机器记住的口令登进去"」这一屏问
    // "怎么还是没有记住密码"：机器其实记着（现读 `andaxin.preferences_pb`：`auth.pwBlob` 有值、
    // `auth.userCode` 有值、`auth.pwWhy` 这个键不存在 = 加密那一步没报错），可这件事在屏幕上看不出来。
    // 「记住密码」在屏幕上就该长成"账号口令都在，按一下登录"，不该是一颗没人会去按的额外键。
    // 解出来的明文只进这个输入框的内存：不写盘、不进日志、不进上报。
    //
    // 原来那颗一键重登的键一并撤了：口令填进格子之后，「登录」那一颗就是它，留两颗做同一件事
    // 只会让人猜哪颗才是正路。
    LaunchedEffect(Unit) {
        if (account.isBlank()) account = deps.session.accountNow().orEmpty()
        if (secret.isNotBlank() || account.isBlank()) return@LaunchedEffect
        val blob = deps.session.passwordBlob()
        if (blob.isNullOrBlank()) return@LaunchedEffect
        // 第一次碰系统密钥那把锁可能慢，别在帧里等它
        val pw = withContext(ioDispatcher) { deps.vault.open(blob) }
        if (pw.isNullOrBlank()) {
            // 解不开就是真解不开：改过锁屏/指纹会把那把密钥带走，密文坏了同理。
            // 留着它，每次进这一屏都白试一遍还说不出原因，所以当场忘掉并说清下一步。
            deps.session.forgetPassword()
            rememberPw = false
            note = "机器上那份口令解不开了（系统那把密钥不在了），重输一次就好。"
        } else {
            secret = pw
        }
    }

    ScaffoldPage(title = "登录", onBack = onBack) {
        Column(verticalArrangement = Arrangement.spacedBy(Gap.md)) {
            MiuixTextField(
                value = account,
                onValueChange = { account = it },
                label = "校园账号",
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            MiuixTextField(
                value = secret,
                onValueChange = { secret = it },
                label = "口令",
                useLabelAsPlaceholder = true,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(Gap.md))
        // 「记住密码」这一格不是方便少打六个字：校方那条 refresh.do 服务端是死的（404），
        // 续期只能拿口令重登一次。不记住 = 每 8 小时、以及每次被顶号，都得回到这一屏手打。
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("记住密码", fontSize = Type.body, color = ink.strong)
                Text(
                    "口令用系统 Keystore 里一把不可导出的密钥加密后存本机；到期或被顶号时代你自动重登（会把对方客户端挤下线，校方一个账号只留一个活会话）。",
                    fontSize = Type.micro, color = ink.faint, lineHeight = Type.microLine,
                )
            }
            Spacer(Modifier.width(Gap.md))
            /*
             * 这颗勾**不用 Miuix 的 Switch**。装机回读发现"勾着没存上"，
             * 而两个分支都会写盘（不勾也要显式清掉），所以只要 `rememberPw` 是 true
             * 就一定有键 —— 键完全不存在，说明进来的那次 `rememberPw` 是 false。
             * 默认值是 true，中间只可能有一次我没预期的回调把它翻掉。
             * 与其去猜第三方组件在组合期会不会自己发一次 onCheckedChange，
             * 不如把这一格换成**只有我们自己那下 clickable 才改状态**的样子。
             */
            Box(
                Modifier
                    .size(30.dp)
                    .background(if (rememberPw) MaterialTheme.colorScheme.primary else ink.offFill, Radius.field)
                    .pressClick { rememberPw = !rememberPw },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (rememberPw) "✓" else "",
                    fontSize = Type.title, color = Palette.Cream, fontWeight = FontWeight.Medium,
                )
            }
        }
        Spacer(Modifier.height(Gap.lg))
        MiuixButton(
            onClick = {
                busy = true
                note = null
                scope.launch {
                    val r = deps.auth.login(account.trim(), secret)
                    busy = false
                    r.fold(
                        onSuccess = {
                            /*
                             * **顺序不能反**：口令那两个键要先写，`session.save()` 放最后。
                             * `save()` 一落 token，外层 Gate 立刻把这一屏拆出组合，
                             * 而 `scope` 是 `rememberCoroutineScope()` —— 组合一走它的协程就取消，
                             * 排在后面的那两次 `setText` 一个都不会执行。
                             * 装机回读的表现就是：登录成功、首页全是真数据，
                             * 可「我的」那一格说"没记住"，而且连"存不下的原因"那个键都不存在
                             * （两个分支都没跑到，才是什么键都不留）。
                             */
                            if (rememberPw) {
                                val sealed = deps.vault.seal(secret)
                                deps.session.rememberPassword(sealed.getOrNull())
                                deps.session.rememberPasswordWhy(
                                    sealed.exceptionOrNull()?.let {
                                        "${it::class.simpleName}: ${it.message?.take(90)}"
                                    },
                                )
                            } else {
                                deps.session.rememberPassword(null)
                                deps.session.rememberPasswordWhy(null)
                            }
                            deps.session.save(it)
                            onDone()
                        },
                        onFailure = { note = it.message ?: "登录失败" },
                    )
                }
            },
            enabled = !busy && account.isNotBlank() && secret.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColorsPrimary(),
        ) {
            Text(
                text = if (busy) "正在登录…" else "登录",
                fontSize = Type.title, fontWeight = FontWeight.Medium,
            )
        }
        note?.let {
            Spacer(Modifier.height(Gap.md))
            Text(it, fontSize = Type.body, color = MaterialTheme.colorScheme.error, lineHeight = Type.bodyLine)
        }
        Spacer(Modifier.height(Gap.lg))
        Text(
            text = "先不登录，随便看看",
            fontSize = Type.body,
            color = ink.muted,
            modifier = Modifier.pressClick(shrink = false) {
                scope.launch { deps.session.browseWithoutLogin() }
                onBack()
            }.padding(vertical = Gap.sm),
        )
    }
}

/** 第一次打开的免责声明。文案是占位，真要对外得他自己写。 */
@Composable
fun OnboardingScreen(onAgree: () -> Unit) {
    val ink = LocalInk.current
    Column(
        Modifier.fillMaxSize().padding(Gap.xl),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Spacer(Modifier.height(Gap.xxl))
            Text("用之前说一句", fontSize = Type.display, fontWeight = FontWeight.SemiBold, color = ink.strong)
            Spacer(Modifier.height(Gap.lg))
            Text(
                text = "这个应用是给本校学生自己用的，数据直连学校接口，不经过任何第三方服务器；" +
                    "口令只存在你自己手机上。学校改了流程或者关掉某个入口，对应那一屏就会取不到数，" +
                    "这不是坏了，是学校那边变了。",
                fontSize = Type.body,
                color = ink.body,
                lineHeight = Type.bodyLine,
            )
            Spacer(Modifier.height(Gap.md))
            Text(
                text = "还有：它不替你答题、不替你签到、不在后台刷任何进度。该你按的那一下，永远留给你按。",
                fontSize = Type.body,
                color = ink.muted,
                lineHeight = Type.bodyLine,
            )
        }
        Box(
            Modifier.fillMaxWidth().height(50.dp).background(MaterialTheme.colorScheme.primary, Radius.card)
                .pressClick(onClick = onAgree),
            contentAlignment = Alignment.Center,
        ) {
            Text("知道了", fontSize = Type.title, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onPrimary)
        }
    }
}
