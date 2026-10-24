package com.andaxin.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andaxin.app.LocalAppGraph
import com.andaxin.core.design.Gap
import com.andaxin.core.design.LocalInk
import com.andaxin.core.design.Type
import com.andaxin.core.store.Session
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text

/*
 * 全仓共用的那一层排版零件。
 *
 * 10-20 晚这一版把"字号档 + 节奏"收进 `core/design/Dimens.kt`（Type / Rhythm），
 * 所有屏都从这儿走。原因很直白：以前同一个屏里副标题有 11/12/13 三种、
 * 行主标题有 14/15/16/17 四种、大数字有 17/20/24/30 四种，单看每处都说得通，
 * 摆在一起就是"没对齐、不一目了然"。改一处就能全仓跟着变，是这几个零件存在的全部意义。
 *
 * 平移注：`pressClick`/`Refreshable`（登录批）和排版零件那一族（enterFade/Sheet/SectionTitle/
 * RowLine/MetricCell/Chip/Labeled/Hairline/ShelfTail/SampleTag/ErrorLine/rememberArmConfirm，
 * 底栏五格那批）都进共享层了（shared logicMain 的 `shell/Kit.kt`），包名没变，调用点一行不用改。
 * 这里留下的四件：`LoadWhy` 牵着 app 的 LocalAppGraph（去登录那条路在 graph.session 手里），
 * 其余三件暂无共享层调用者，等各自的屏搬过去再跟着走。
 */

@Composable
fun StatBlock(label: String, value: String, modifier: Modifier = Modifier) {
    val ink = LocalInk.current
    Column(modifier = modifier) {
        Text(value, style = Type.tabular, fontSize = Type.num, fontWeight = FontWeight.SemiBold, color = ink.strong)
        Spacer(Modifier.height(Gap.xxs))
        Text(label, fontSize = Type.micro, color = ink.faint)
    }
}

@Composable
fun FillHeight(modifier: Modifier = Modifier) = Spacer(modifier.fillMaxHeight())

/**
 * 空状态。只说"这里现在没有东西"这一句事实，不解释是哪条接口、为什么没数据 ——
 * 那些是我该写在 `docs/接入清单.md` 里的，不是给用户看的。
 */
@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontSize = Type.meta,
        lineHeight = Type.metaLine,
        color = LocalInk.current.faint,
        modifier = modifier.fillMaxWidth().padding(vertical = Gap.lg),
    )
}

/**
 * 一屏没拉到数据时那一句。
 *
 * 两种语气，别混着涂：
 * ① **没登录是一种状态，不是故障** —— 以前这类屏把"还没登录，这一屏要校方数据"涂成红色，
 *    红的是"出事了"，而这里什么都没坏，人要做的只是登一次。所以这句走灰（`ink.muted`），
 *    并且顺手给一条往前走的路：这颗「去登录」不导航，只清掉"先不登录逛逛"那个标志，
 *    外层 Gate 看见"没登录又不在逛"就把登录那一屏端上来（和「我的」里那颗同一条路）。
 * ② 真失败（连不上、网关给 HTML、结构不对）才用红色那句。
 *
 * 判据不问协议层要新异常类型 —— 界面这一层本来就知道 `session.loggedIn`，
 * 而"这条失败是不是因为没登录"在手机上没有第二种解释。
 *
 * [wide] 给整屏空着的那种用（一颗占满宽的主按钮）；行内、卡片里那种给 false，
 * 只留一句灰字加一颗能按的字，不至于在一行说明底下横下一条大蓝杠。
 */
@Composable
fun LoadWhy(why: String?, modifier: Modifier = Modifier, wide: Boolean = true) {
    if (why.isNullOrBlank()) return
    val graph = LocalAppGraph.current
    val ink = LocalInk.current
    val scope = rememberCoroutineScope()
    val session by graph.session.state.collectAsStateWithLifecycle(Session())
    val needsLogin = !session.loggedIn
    Column(modifier) {
        Text(
            text = why,
            fontSize = Type.meta,
            lineHeight = Type.metaLine,
            color = if (needsLogin) ink.muted else MaterialTheme.colorScheme.error,
        )
        if (needsLogin) {
            Spacer(Modifier.height(if (wide) Gap.lg else Gap.sm))
            if (wide) {
                MiuixButton(
                    onClick = { scope.launch { graph.session.leaveBrowsing() } },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text("去登录", fontSize = Type.title, fontWeight = FontWeight.Medium)
                }
            } else {
                Text(
                    text = "去登录",
                    fontSize = Type.meta,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.pressClick { scope.launch { graph.session.leaveBrowsing() } },
                )
            }
        }
    }
}
