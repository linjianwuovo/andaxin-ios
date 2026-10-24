package com.andaxin.feature.me

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.andaxin.app.LocalAppGraph
import com.andaxin.core.store.AvatarStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/*
 * 平移注：「个人信息」这一屏搬进共享层了（shared 的 `feature/me/ProfileScreen.kt`）。
 * RootShell 还按老签名 `ProfileScreen(onBack)` 调它 —— 这一层薄壳只做
 * "从 AppGraph 抓依赖、拼成 Deps 递进去"（登录批的同款收尾）。等总装把 Route.PROFILE
 * 那一处改成分发 Deps，这一份就删。
 *
 * 文件名不是 ProfileScreen.kt：共享层那边已有同名文件，同包同名文件的两个 facade 类
 * 会在 R8 撞车（消息批撞过一回）。
 */
@Composable
fun ProfileScreen(onBack: () -> Unit) {
    val graph = LocalAppGraph.current
    val ctx = LocalContext.current
    val avatarNote = remember { MutableStateFlow<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            avatarNote.value = if (AvatarStore.save(ctx, uri)) "换好了，只在本机生效。" else "这张图没读进来，换一张试试。"
        }
    }

    ProfileScreen(
        ProfileDeps(
            session = graph.session,
            who = graph.who,
            loadProfile = { graph.profile.load() },
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
        ),
        onBack = onBack,
    )
}
