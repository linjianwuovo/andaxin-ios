package com.andaxin.feature.about

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.andaxin.BuildConfig
import com.andaxin.app.LocalAppGraph
import com.andaxin.core.design.Gap
import com.andaxin.core.design.LocalInk
import com.andaxin.core.design.Type
import com.andaxin.data.capture.LastUpdate
import com.andaxin.data.capture.Release
import com.andaxin.data.capture.plainOf
import com.andaxin.data.capture.versionNewer
import com.andaxin.feature.web.encodeUrl
import com.andaxin.shell.Hairline
import com.andaxin.shell.Labeled
import com.andaxin.shell.SectionTitle
import com.andaxin.shell.Sheet
import com.andaxin.shell.pressClick
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults

/*
 * 安大信自己的检测更新那一格。
 *
 * 三条规矩：
 * ① 进这一屏**不自动查** —— 那等于每次看关于页都替用户打一次第三方。点「查一次」才发。
 * ② 上次查到的结果存本机（版本号 / 是不是新版 / 什么时候查的），所以没网或被 GitHub 挡的时候
 *    屏上不是空的，也不会把"没问到"报成"已是最新"。
 * ③ 下载交给系统 DownloadManager，下完不自动装 —— 装不装是通知栏里那一下，归他。
 *    到不了 github.com（没开节点）的时候这一发会失败，那句失败原因原样贴出来，不糊"下载失败"三个字。
 */
@Composable
internal fun UpdateCard(open: (String) -> Unit) {
    val graph = LocalAppGraph.current
    val ink = LocalInk.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cur = BuildConfig.VERSION_NAME
    // beta 那条线只看带 -beta 的 tag，不然正式版会一直提示"有新版"，而那个新版其实是调试线
    val beta = cur.endsWith("-beta")
    var latest by remember { mutableStateOf<Release?>(null) }
    var newer by remember { mutableStateOf(false) }
    var at by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var why by remember { mutableStateOf<String?>(null) }
    var queued by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        LastUpdate.fromJson(graph.store.peekString(graph.store.snapshot(), LastUpdate.KEY).orEmpty())
            ?.let { (r, _, t) ->
                latest = r
                at = t
                /*
                 * 存下来那个"是不是新版"不能照抄上屏：他从 0.8.28-beta 升到 0.8.29-beta 之后，
                 * 本机版本变了而那条缓存没变，界面上就一直挂着"有新版：0.8.29"（10-25 凌晨他截的那张）。
                 * 所以每次进这一屏，拿本机这版重新比一次。
                 */
                newer = versionNewer(r.version, cur)
            }
    }

    SectionTitle("检测更新")
    Sheet {
        Labeled(label = "这台机器上", value = cur)
        Spacer(Modifier.height(Gap.sm)); Hairline(); Spacer(Modifier.height(Gap.sm))
        Labeled(
            label = "服务器上最新",
            value = when {
                busy -> "正在问…"
                latest != null -> latest!!.version + if (beta) "（beta 线）" else ""
                else -> "还没查过"
            },
        )
        latest?.let { r ->
            if (r.date.isNotBlank()) {
                Spacer(Modifier.height(Gap.sm)); Hairline(); Spacer(Modifier.height(Gap.sm))
                Labeled(label = "发布", value = r.date)
            }
        }
        Spacer(Modifier.height(Gap.md))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Gap.sm)) {
            if (latest != null && newer) {
                MiuixButton(
                    onClick = {
                        val r = latest ?: return@MiuixButton
                        runCatching {
                            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                            val file = r.apkUrl.substringAfterLast('/')
                            dm.enqueue(
                                DownloadManager.Request(Uri.parse(r.apkUrl))
                                    .setTitle(file)
                                    .setDescription("安大信 ${r.version}")
                                    .setNotificationVisibility(
                                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                                    )
                                    .setDestinationInExternalPublicDir(
                                        android.os.Environment.DIRECTORY_DOWNLOADS, file
                                    ),
                            )
                        }.onSuccess { queued = "已交给系统下载：${latest!!.apkUrl.substringAfterLast('/')}，通知栏里点开才装" }
                            .onFailure {
                                queued = "系统下载器没接这单：${it.message ?: it.javaClass.simpleName}"
                            }
                    },
                    modifier = Modifier.weight(1f, fill = false),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text("下载 ${latest!!.version}", fontSize = Type.body, fontWeight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.weight(1f))
            if (latest != null) {
                Text(
                    text = "release 页",
                    fontSize = Type.body,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .pressClick(shrink = false) { open("web/" + encodeUrl(latest!!.pageUrl)) }
                        .padding(horizontal = Gap.sm, vertical = Gap.sm),
                )
            }
            Text(
                text = if (busy) "…" else "查一次",
                fontSize = Type.body,
                color = if (busy) ink.ghost else MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .pressClick(shrink = false, enabled = !busy) {
                        busy = true; why = null; queued = null
                        scope.launch {
                            val r = graph.updates.check(cur, beta)
                            busy = false
                            val ok = r.getOrNull()
                            if (ok == null) {
                                why = r.exceptionOrNull()?.message ?: "没问到"
                            } else {
                                latest = ok.latest
                                newer = ok.newer
                                at = ok.checkedAt
                                graph.store.setText(LastUpdate.KEY, LastUpdate.toJson(ok.latest, ok.newer, ok.checkedAt))
                            }
                        }
                    }
                    .padding(horizontal = Gap.md, vertical = Gap.sm),
            )
        }
        latest?.let {
            Spacer(Modifier.height(Gap.sm))
            Text(
                text = when {
                    it.version == plainOf(cur) && !newer -> "已是最新（$at 查的）"
                    newer -> "有新版：${it.version}（$at 查的）"
                    else -> "这台机器比订阅里挂的还新（$at 查的）"
                },
                fontSize = Type.micro,
                color = ink.faint,
            )
        }
        why?.let {
            Spacer(Modifier.height(Gap.sm))
            Text(it, fontSize = Type.micro, color = MaterialTheme.colorScheme.error)
        }
        queued?.let {
            Spacer(Modifier.height(Gap.sm))
            Text(it, fontSize = Type.micro, color = ink.faint, lineHeight = Type.metaLine)
        }
        Spacer(Modifier.height(Gap.md))
    }
}
