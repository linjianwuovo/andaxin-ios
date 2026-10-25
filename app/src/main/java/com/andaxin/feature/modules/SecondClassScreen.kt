package com.andaxin.feature.modules

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.andaxin.app.LocalAppGraph
import com.andaxin.core.design.Gap
import com.andaxin.core.design.LocalInk
import com.andaxin.core.design.Type
import com.andaxin.data.capture.ScApply
import com.andaxin.data.capture.SecondClass
import com.andaxin.feature.brief.ScaffoldPage
import com.andaxin.shell.Hairline
import com.andaxin.shell.Labeled
import com.andaxin.shell.SectionTitle
import com.andaxin.shell.Sheet

/**
 * 第二课堂：学分总览 + 我的申报，一屏看完。
 *
 * 只做有真报文的那两条读口（`credit/overview`、`credit/applications`）—— 可选活动那一屏
 * 参数抄全了但响应形状没实样，不拿猜的字段名往界面上摆；写口（申报、问卷、传附件）一条不接。
 * 数字全部原样显示：服务端给的是字符串（"0.05"），这里不做四舍五入、不换算。
 */
@Composable
fun SecondClassScreen(onBack: () -> Unit) {
    val graph = LocalAppGraph.current
    val ink = LocalInk.current
    var data by remember { mutableStateOf<SecondClass?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        data = graph.secondClass.load()
        loading = false
    }
    ScaffoldPage(title = "第二课堂", onBack = onBack) {
        val d = data
        if (loading && d == null) {
            Text("正在拉第二课堂…", fontSize = Type.body, color = ink.faint)
        }
        if (d != null) {
            d.why?.let {
                Sheet {
                    Text(it, fontSize = Type.meta, color = ink.muted, lineHeight = Type.metaLine)
                }
                Spacer(Modifier.height(Gap.md))
            }
            Sheet {
                Labeled(label = "总学分", value = d.total)
            }
            if (d.modules.isNotEmpty()) {
                Spacer(Modifier.height(Gap.lg))
                SectionTitle("各模块")
                Sheet {
                    Column {
                        d.modules.forEachIndexed { i, m ->
                            Labeled(
                                label = m.name,
                                // 一个模块一条记录都没有时，服务端那个 totalCredit 是空的 ——
                                // 原来直接拼，那一行就变成光秃秃一个「（认定 0 条）」，像漏了字。
                                value = (if (m.credit.isBlank()) "—" else m.credit) +
                                    "（认定 " + m.count + " 条）",
                            )
                            if (i != d.modules.lastIndex) {
                                Spacer(Modifier.height(Gap.sm))
                                Hairline()
                                Spacer(Modifier.height(Gap.sm))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(Gap.lg))
            SectionTitle("我的申报 " + (if (d.applies.isEmpty()) "" else "· ${d.applies.size} 条"))
            if (d.applies.isEmpty()) {
                Sheet {
                    Text(
                        "没有申报记录（学校那一条回的是空列表，不是没拉到）。",
                        fontSize = Type.meta,
                        color = ink.faint,
                        lineHeight = Type.metaLine,
                    )
                }
            } else {
                d.applies.forEachIndexed { i, a -> ApplyRow(a, last = i == d.applies.lastIndex) }
            }
            Spacer(Modifier.height(Gap.xxl))
        }
    }
}

@Composable
private fun ApplyRow(a: ScApply, last: Boolean) {
    val ink = LocalInk.current
    Column(Modifier.fillMaxWidth()) {
        Sheet {
            Column {
                Text(a.title, fontSize = Type.body, fontWeight = FontWeight.Medium, color = ink.strong)
                Spacer(Modifier.height(Gap.xs))
                Text(
                    listOf(a.module, a.category).filter { it.isNotBlank() }.joinToString(" · ") +
                        " · " + a.credit + " 分",
                    fontSize = Type.meta,
                    color = ink.muted,
                    lineHeight = Type.metaLine,
                )
            }
        }
        if (!last) Spacer(Modifier.height(Gap.sm))
    }
}
