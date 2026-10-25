package com.andaxin.feature.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import top.yukonga.miuix.kmp.basic.Slider as MiuixSlider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andaxin.R
import com.andaxin.shell.pressClick
import com.andaxin.app.LocalAppGraph
import com.andaxin.core.design.Gap
import com.andaxin.core.design.Type
import com.andaxin.core.design.LocalInk
import com.andaxin.core.design.Radius
import com.andaxin.core.launcher.LauncherIcon
import com.andaxin.core.launcher.LauncherIcons
import com.andaxin.core.settings.Appearance
import com.andaxin.core.settings.BarMaterial
import com.andaxin.core.settings.DayNight
import com.andaxin.core.settings.HeroStyle
import com.andaxin.shell.Hairline
import com.andaxin.shell.Sheet
import kotlinx.coroutines.launch

@Composable
fun ThemeScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val graph = LocalAppGraph.current
    val saved by graph.appearance.state.collectAsStateWithLifecycle(initialValue = Appearance())
    var draft by remember(saved) { mutableStateOf(saved) }
    val scope = rememberCoroutineScope()
    val ink = LocalInk.current

    val commit: () -> Unit = { scope.launch { graph.appearance.save(draft) } }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Gap.screen),
    ) {
        Spacer(Modifier.height(Gap.xl))
        Text(
            "‹ 返回",
            fontSize = Type.body,
            color = ink.muted,
            modifier = Modifier.pressClick(shrink = false, onClick = onBack).padding(vertical = Gap.sm),
        )
        Spacer(Modifier.height(Gap.sm))
        Text("主题", fontSize = Type.display, fontWeight = FontWeight.SemiBold, color = ink.strong)

        Spacer(Modifier.height(Gap.xl))
        GroupTitle("明暗")
        Sheet {
            ChoiceRow(
                options = listOf("跟随系统" to DayNight.System, "白天" to DayNight.Light, "夜间" to DayNight.Dark),
                picked = draft.dayNight,
            ) { draft = draft.copy(dayNight = it); commit() }
        }

        Spacer(Modifier.height(Gap.xl))
        GroupTitle("首页壁纸")
        /*
         * 参考图里那句"壁纸是材质，不是一张贴图"：首页没课/课都上完时那一块用它。
         * 四档缩略图直接能看到长什么样，不用先选了再回首页试 —— 自动按早/中/晚挑。
         */
        Sheet {
            Row(horizontalArrangement = Arrangement.spacedBy(Gap.sm)) {
                HEROES.forEach { (label, style, res) ->
                    val on = draft.hero == style
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .pressClick { draft = draft.copy(hero = style); commit() },
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .clip(Radius.field)
                                .then(
                                    if (on) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, Radius.field)
                                    else Modifier
                                ),
                        ) {
                            if (res == null) {
                                // 「自动」= 三张图并排各切一条：一看就知道它跟着时段换
                                Row(Modifier.fillMaxSize()) {
                                    listOf(R.drawable.hero_dawn, R.drawable.hero_dusk, R.drawable.hero_night)
                                        .forEach { id ->
                                            Image(
                                                painter = painterResource(id),
                                                contentDescription = null,
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .fillMaxHeight(),
                                                contentScale = ContentScale.Crop,
                                            )
                                        }
                                }
                            } else {
                                Image(
                                    painter = painterResource(res),
                                    contentDescription = label,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                )
                            }
                        }
                        Spacer(Modifier.height(Gap.xs))
                        Text(
                            text = label,
                            fontSize = Type.tiny,
                            color = if (on) ink.strong else ink.faint,
                            fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                        )
                    }
                }
            }
            Text(
                text = "只在首页那一块出现；自动：早上晨雾、下午金树、夜里月亮。",
                fontSize = Type.micro,
                color = ink.faint,
                modifier = Modifier.padding(top = Gap.md),
            )
        }

        Spacer(Modifier.height(Gap.xl))
        GroupTitle("底栏材质")
        Sheet {
            ChoiceRow(
                options = listOf(
                    "液态玻璃" to BarMaterial.Liquid,
                    "毛玻璃" to BarMaterial.Frosted,
                    "纯色" to BarMaterial.Solid,
                ),
                picked = draft.material,
            ) { draft = draft.copy(material = it); commit() }
            Text(
                text = "毛玻璃和纯色只吃下面第一根滑杆，另外三根只对液态玻璃有效。",
                fontSize = Type.micro,
                color = ink.faint,
                modifier = Modifier.padding(top = Gap.md),
            )
        }

        Spacer(Modifier.height(Gap.xl))
        GroupTitle("强调色")
        Sheet {
            Row(horizontalArrangement = Arrangement.spacedBy(Gap.md)) {
                ACCENTS.forEach { (name, hex) ->
                    val on = hex == draft.accent
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.pressClick { draft = draft.copy(accent = hex); commit() },
                    ) {
                        Box(
                            Modifier
                                .size(30.dp)
                                .background(
                                    if (hex.isEmpty()) ink.hairline else Color(android.graphics.Color.parseColor(hex)),
                                    CircleShape,
                                )
                                .padding(Gap.xs),
                        )
                        Spacer(Modifier.height(Gap.xs))
                        Text(
                            name,
                            fontSize = Type.tiny,
                            color = if (on) ink.strong else ink.faint,
                            fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                        )
                    }
                }
            }
            Spacer(Modifier.height(Gap.sm))
            Text("只换主色，底栏玻璃跟着走。", fontSize = Type.micro, color = ink.faint)
        }

        Spacer(Modifier.height(Gap.xl))
        GroupTitle("启动图标")
        LauncherIconSection()

        Spacer(Modifier.height(Gap.xl))
        GroupTitle("玻璃参数")
        Sheet {
            SliderRow("模糊度", draft.blur, commit) { draft = draft.copy(blur = it) }
            SliderRow("折射度", draft.refraction, commit) { draft = draft.copy(refraction = it) }
            SliderRow("扭曲度", draft.distortion, commit) { draft = draft.copy(distortion = it) }
            SliderRow("色散", draft.dispersion, commit) { draft = draft.copy(dispersion = it) }
            Text(
                text = "调完松手就存，底栏实时跟着变。",
                fontSize = Type.micro,
                color = ink.faint,
                modifier = Modifier.padding(top = Gap.sm),
            )
        }
        Spacer(Modifier.height(Gap.xxl))
    }
}

/**
 * 「启动图标」那一格：七枚可选（现在那枚临时标记 + 六套尖顶 A 配色）。
 *
 * 点一下就换 —— 这一步动的是系统里的组件开关（见 core/launcher/LauncherIcons.kt），
 * 不碰学校、不碰网络，所以没上连按两次；但失败一定把原因贴出来，不闷声。
 */
@Composable
private fun LauncherIconSection() {
    val ink = LocalInk.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var now by remember { mutableStateOf(LauncherIcons.current(ctx)) }
    var why by remember { mutableStateOf<String?>(null) }

    /*
     * 每次回到这一屏都从系统重读一次"现在立着的是哪枚"。
     * 只 remember 一次会骗人：他在桌面上动过、或者换过一次之后再看这一格，
     * 圈可能还圈在旧的那枚上（10-25 他截图里就是这么个错位）。
     */
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        now = LauncherIcons.current(ctx)
    }

    Sheet {
        LauncherIcons.ALL.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Gap.lg)) {
                row.forEach { ic ->
                    val on = ic.key == now.key
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.pressClick(shrink = false) {
                            if (on) return@pressClick
                            LauncherIcons.apply(ctx, ic).fold(
                                onSuccess = {
                                    now = ic
                                    why = "已换上「${ic.label}」。桌面那枚一般马上变；" +
                                        "多出来那枚旧的是桌面缓存，重启桌面就没了。"
                                },
                                onFailure = { why = "没换成：${it.message ?: it.javaClass.simpleName}" },
                            )
                        },
                    ) {
                        IconPreview(ic, on)
                        Spacer(Modifier.height(Gap.xs))
                        Text(
                            ic.label,
                            fontSize = Type.tiny,
                            color = if (on) ink.strong else ink.faint,
                            fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                        )
                    }
                }
            }
            Spacer(Modifier.height(Gap.md))
        }
        Text(
            text = why ?: "换的是桌面那枚和最近任务里那枚。MIUI 这类桌面换完会多留一枚旧的" +
                "（旧那枚的名字可能还是以前那个），系统里其实只有一条入口 —— 重启桌面或重启手机就干净了。",
            fontSize = Type.micro,
            color = if (why == null) ink.faint else ink.muted,
            lineHeight = Type.metaLine,
        )
    }
}

/**
 * 这一格画的就是桌面上那一枚会长的样子 —— 同一批数、同一个裁法。
 *
 * 桌面不是把 108 的画布整个铺满一格，而是取中间那扇 72×72 的窗放大 1.5 倍。这条是量出来的：
 * 米11 那一格 218 px 宽、里面那枚方框（资源里占 56/108）实测 170 px，52 变 78（百分比），比值正好 1.5。
 * 所以预览也按这扇窗画。而"预览那个大小"是他挑图标时看到的那个：要外面照里面来，
 * 动的是 `res/drawable/ic_launcher_fg_*.xml` 那六份的坐标（缩到 0.665），不是把预览改成桌面上那个。
 */
@Composable
private fun IconPreview(ic: LauncherIcon, on: Boolean) {
    val tile = 58.dp
    val shape = RoundedCornerShape(26)
    Box(
        Modifier
            .size(tile)
            .clip(shape)
            .background(Color(ic.bg.toInt()))
            .then(if (on) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(tile)) {
            // 一像素对多少 dp：桌面那套是"72 个视口单位铺满整格"，不是 108 铺满
            val s = size.width / 72f
            val dx = -18f * s
            val dy = -18f * s
            val line = Color(ic.ink.toInt())
            val bar = Color(ic.bar.toInt())
            if (ic.shape == "box") {
                drawRoundRect(
                    line,
                    Offset(dx + 26f * s, dy + 32f * s),
                    Size(56f * s, 44f * s),
                    CornerRadius(8f * s),
                )
                drawRect(bar, Offset(dx + 30f * s, dy + 46f * s), Size(48f * s, 6f * s))
            } else {
                // 数要和 res 里那六份 vector 一模一样（同一批坐标、同一根线宽 9.4）。
                // 那批坐标是绕中心缩过一遍的（0.665）：桌面会把 108 中间 72 那扇窗放大 1.5 倍，
                // 缩完再放大，落到桌面上才是这一排预览原来那个大小 —— 他要的是外面照里面改，
                // 不是把里面改成外面那样，所以动的是资源，这里只是跟着抄。
                val w = 9.4f * s
                drawLine(line, Offset(dx + 36.42f * s, dy + 71.11f * s), Offset(dx + 54f * s, dy + 36.57f * s), w, StrokeCap.Round)
                drawLine(line, Offset(dx + 54f * s, dy + 36.57f * s), Offset(dx + 70.25f * s, dy + 71.11f * s), w, StrokeCap.Round)
                drawLine(bar, Offset(dx + 45.02f * s, dy + 57.92f * s), Offset(dx + 62.98f * s, dy + 57.92f * s), w, StrokeCap.Round)
            }
        }
    }
}

private val ACCENTS = listOf(
    "跟随" to "",
    "蓝" to "#2563EB",
    "青" to "#0D9488",
    "琥珀" to "#B45309",
    "紫" to "#7C3AED",
    "玫红" to "#E5484D",
)

/** 首页壁纸四档：自动 + 三张实图。第三项 null = 自动（画三段渐变）。 */
private val HEROES = listOf(
    Triple("自动", HeroStyle.Auto, null),
    Triple("晨雾", HeroStyle.Dawn, R.drawable.hero_dawn),
    Triple("金树", HeroStyle.Dusk, R.drawable.hero_dusk),
    Triple("月夜", HeroStyle.Night, R.drawable.hero_night),
)

@Composable
private fun GroupTitle(text: String) {
    Text(
        text = text,
        fontSize = Type.body,
        fontWeight = FontWeight.Medium,
        color = LocalInk.current.muted,
        modifier = Modifier.padding(bottom = Gap.sm, start = Gap.xxs),
    )
}

@Composable
private fun <T : Any> ChoiceRow(options: List<Pair<String, T>>, picked: T, onPick: (T) -> Unit) {
    val ink = LocalInk.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Gap.sm)) {
        options.forEach { (label, value) ->
            val on = value == picked
            Text(
                text = label,
                fontSize = Type.body,
                fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                color = if (on) MaterialTheme.colorScheme.primary else ink.muted,
                modifier = Modifier
                    .background(
                        if (on) MaterialTheme.colorScheme.surfaceVariant else ink.hairline,
                        Radius.pill,
                    )
                    .pressClick { onPick(value) }
                    .padding(horizontal = Gap.lg, vertical = Gap.sm),
            )
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, onDone: () -> Unit, onChange: (Float) -> Unit) {
    val ink = LocalInk.current
    var local by remember(value) { mutableStateOf(value) }
    Column(Modifier.fillMaxWidth().padding(vertical = Gap.xs)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = Type.body, color = ink.body)
            Text("%.2f".format(local), fontSize = Type.meta, color = ink.faint)
        }
        MiuixSlider(
            value = local,
            onValueChange = { local = it; onChange(it) },
            // 松手才落盘：拖的过程中每帧写一次磁盘会把 IO 打满
            onValueChangeFinished = onDone,
        )
    }
}
