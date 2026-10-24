package com.andaxin.feature.me

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.andaxin.BuildConfig
import com.andaxin.R
import com.andaxin.app.LocalAppGraph
import com.andaxin.core.store.AvatarStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/*
 * 平移注：「我的」这一屏搬进共享层了（shared 的 `feature/me/MeScreen.kt`）。
 * RootShell 还按老签名 `MeScreen(open, modifier)` 调它 —— 这一层薄壳只做
 * "从 AppGraph 抓依赖、拼成 Deps 递进去"（登录批的同款收尾）。等总装把 ShelfHost
 * 那五处改成分发 Deps，这一份就删。
 *
 * 文件名不是 MeScreen.kt：共享层那边已有同名文件，同包同名文件的两个 facade 类
 * 会在 R8 撞车（消息批撞过一回）。
 */

/** 入口 route → （主笔画片, 点睛画片）两张 res。共享层认不得 R.drawable，在这层翻译。 */
private val ENTRY_ICONS = mapOf(
    "affair" to (R.drawable.ic_entry_todo to R.drawable.ic_entry_todo_accent),
    "dorm" to (R.drawable.ic_entry_dorm to R.drawable.ic_entry_dorm_accent),
    "card" to (R.drawable.ic_entry_card to R.drawable.ic_entry_card_accent),
    "repair" to (R.drawable.ic_entry_repair to R.drawable.ic_entry_repair_accent),
    "aiclass" to (R.drawable.ic_entry_aiclass to R.drawable.ic_entry_aiclass_accent),
    "opencourse" to (R.drawable.ic_entry_opencourse to R.drawable.ic_entry_opencourse_accent),
    "group" to (R.drawable.ic_entry_group to R.drawable.ic_entry_group_accent),
    "exam" to (R.drawable.ic_entry_exam to R.drawable.ic_entry_exam_accent),
    "credit" to (R.drawable.ic_entry_credit to R.drawable.ic_entry_credit_accent),
    "labor" to (R.drawable.ic_entry_labor to R.drawable.ic_entry_labor_accent),
    "holiday" to (R.drawable.ic_entry_holiday to R.drawable.ic_entry_holiday_accent),
    "sport" to (R.drawable.ic_entry_sport to R.drawable.ic_entry_sport_accent),
    "sport/records" to (R.drawable.ic_entry_sportlog to R.drawable.ic_entry_sportlog_accent),
    "more" to (R.drawable.ic_entry_more to R.drawable.ic_entry_more_accent),
    "theme" to (R.drawable.ic_entry_theme to R.drawable.ic_entry_theme_accent),
    "about" to (R.drawable.ic_entry_about to R.drawable.ic_entry_about_accent),
)

@Composable
fun MeScreen(open: (String) -> Unit = {}, modifier: Modifier = Modifier) {
    val graph = LocalAppGraph.current
    val ctx = LocalContext.current
    val avatarNote = remember { MutableStateFlow<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            avatarNote.value = if (AvatarStore.save(ctx, uri)) "换好了，只在本机生效。" else "这张图没读进来，换一张试试。"
        }
    }

    MeScreen(
        MeDeps(
            session = graph.session,
            who = graph.who,
            loadProfile = { graph.profile.load() },
            loadIntern = { graph.home.intern() },
            avatar = AvatarDeps(
                present = remember { AvatarStore.version.map { it > 0L } },
                draw = { size ->
                    val v by AvatarStore.version.collectAsState()
                    val bmp = remember(v) { AvatarStore.load(ctx) }
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "头像",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(size).clip(androidx.compose.foundation.shape.CircleShape),
                        )
                    }
                },
                pick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                note = avatarNote,
                refresh = { AvatarStore.refresh(ctx) },
            ),
            entryIcon = { key ->
                val pair = ENTRY_ICONS[key]
                if (pair != null) {
                    val (icon, iconAccent) = pair
                    val ink = com.andaxin.core.design.LocalInk.current
                    // 强调色要先取出来：graphicsLayer 那一层不是组合作用域，在里面读 MaterialTheme 会编不过
                    val accent = androidx.compose.material3.MaterialTheme.colorScheme.primary
                    Box(Modifier.size(22.dp)) {
                        Image(
                            painter = androidx.compose.ui.res.painterResource(icon),
                            contentDescription = null,
                            modifier = Modifier.size(22.dp).graphicsLayer {
                                colorFilter = ColorFilter.tint(ink.strong)
                            },
                        )
                        Image(
                            painter = androidx.compose.ui.res.painterResource(iconAccent),
                            contentDescription = null,
                            modifier = Modifier.size(22.dp).graphicsLayer {
                                colorFilter = ColorFilter.tint(accent)
                            },
                        )
                    }
                }
            },
            versionName = BuildConfig.VERSION_NAME,
        ),
        open = open,
        modifier = modifier,
    )
}
