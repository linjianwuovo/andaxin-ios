package com.andaxin.feature.brief

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.andaxin.data.capture.RepairPhotos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.andaxin.shell.LoadWhy
import com.andaxin.shell.pressClick
import com.andaxin.app.LocalAppGraph
import com.andaxin.core.design.Gap
import com.andaxin.core.design.LocalInk
import com.andaxin.core.design.Palette
import com.andaxin.core.design.Radius
import com.andaxin.core.design.Type
import com.andaxin.data.brief.BriefDetail
import com.andaxin.data.brief.BriefRow
import com.andaxin.data.brief.DetailSource
import com.andaxin.data.brief.Field
import com.andaxin.data.brief.Flag
import com.andaxin.data.brief.FormSpec
import com.andaxin.data.brief.FormSource
import com.andaxin.data.brief.ListSource
import com.andaxin.shell.Chip
import com.andaxin.shell.Hairline
import com.andaxin.shell.Refreshable
import com.andaxin.shell.adxViewModel
import com.andaxin.shell.Labeled
import com.andaxin.shell.RowLine
import com.andaxin.shell.Sheet
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.TextField as MiuixTextField
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/* ───────────────────────── 状态 ───────────────────────── */

/** 按下去就是真发的那一档：底色给错误色，字给白。 */
@Composable
private fun dangerColors() = ButtonDefaults.buttonColors(
    color = MaterialTheme.colorScheme.error,
    disabledColor = MaterialTheme.colorScheme.error.copy(alpha = 0.4f),
    contentColor = androidx.compose.ui.graphics.Color.White,
    disabledContentColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.6f),
)

@Immutable
internal data class ListUi(val rows: List<BriefRow> = emptyList(), val error: String? = null, val loading: Boolean = true)

internal class ListModel(private val source: ListSource) : ViewModel() {
    private val _state = MutableStateFlow(ListUi())
    val state: StateFlow<ListUi> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val r = try { source.rows() } catch (e: Exception) { Result.failure(e) }
            _state.value = ListUi(rows = r.getOrDefault(emptyList()), error = r.exceptionOrNull()?.message, loading = false)
        }
    }
}

@Immutable
internal data class DetailUi(
    val data: BriefDetail? = null,
    val error: String? = null,
    val done: String? = null,
    val acting: Boolean = false,
    /**
     * 详情那一屏以前没有这一档：数据没回来、错误也没有的时候，页面就是一片空白加底下那句说明，
     * 看着像"这单什么都没有了"。10-20 读待办详情时实测过：审批链加表单要七八秒，
     * 那七八秒里屏幕上就是空的。
     */
    val loading: Boolean = true,
)

internal class DetailModel(private val source: DetailSource, private val id: String) : ViewModel() {
    private val _state = MutableStateFlow(DetailUi())
    val state: StateFlow<DetailUi> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val r = try { source.detail(id) } catch (e: Exception) { Result.failure(e) }
            _state.value = DetailUi(
                data = r.getOrNull(),
                error = r.exceptionOrNull()?.message,
                loading = false,
            )
        }
    }

    /**
     * 详情里那颗写操作键。界面已经连按两次确认过才会走到这儿。
     *
     * 成了**立刻把这详情重新拉一次**（他旧仓也是这个顺序：`onSuccess = { writeOk = true; load() }`）——
     * 状态有没有真动，看的是学校那一份，不是这里报了什么。重拉失败不影响刚那句"已同意"。
     */
    fun act(action: String, answers: Map<String, String> = emptyMap()) {
        if (_state.value.acting) return
        _state.value = _state.value.copy(acting = true, error = null)
        viewModelScope.launch {
            val r = try { source.act(id, action, answers) } catch (e: Exception) { Result.failure(e) }
            _state.value = _state.value.copy(
                acting = false,
                done = r.getOrNull(),
                error = r.exceptionOrNull()?.message,
            )
            if (r.isSuccess) {
                val again = runCatching { source.detail(id) }.getOrNull()
                again?.getOrNull()?.let { _state.value = _state.value.copy(data = it) }
            }
        }
    }
}

@Immutable
internal data class FormUi(
    val spec: FormSpec? = null,
    val error: String? = null,
    val done: String? = null,
    val sending: Boolean = false,
)

internal class FormModel(private val source: FormSource) : ViewModel() {
    private val _state = MutableStateFlow(FormUi())
    val state: StateFlow<FormUi> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val r = try { source.spec() } catch (e: Exception) { Result.failure(e) }
            _state.value = FormUi(spec = r.getOrNull(), error = r.exceptionOrNull()?.message)
        }
    }

    /**
     * 这一发是**往学校造一张真单**，所以第二次按下之前先挡一道：
     * 连点两下、或者失败后急着再点，绝不能变成两张单子。`busy` 在协程里置、回来才放，
     * 界面上那颗键在 [FormUi.sending] 期间是灰的。
     */
    private var busy = false

    fun submit(values: Map<String, String>) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            _state.value = _state.value.copy(sending = true, error = null)
            val r = try { source.submit(values) } catch (e: Exception) { Result.failure(e) }
            _state.value = _state.value.copy(
                sending = false,
                done = r.getOrNull(),
                error = r.exceptionOrNull()?.message,
            )
            busy = false
        }
    }
}

/* ───────────────────────── 三种页 ───────────────────────── */

@Composable
fun BriefListPage(
    title: String,
    source: ListSource,
    onRow: (String) -> Unit,
    onBack: () -> Unit,
    empty: String = "暂时没有条目。",
    newLabel: String? = null,
    onNew: (() -> Unit)? = null,
) {
    val model = adxViewModel { ListModel(source) }
    val state by model.state.collectAsStateWithLifecycle()
    val ink = LocalInk.current

    ScaffoldPage(title = title, onBack = onBack) {
        if (state.loading && state.rows.isEmpty()) {
            Text("正在拉…", fontSize = Type.meta, color = ink.faint)
        } else if (state.rows.isEmpty() && !state.loading && state.error == null) {
            // 加了 `error == null` 这一条：拉失败时这里以前会**同时**写"暂时没有条目。"
            // 和底下一句红色原因 —— 空列表是没问成的结果，不是"校方说没有"（同一屏自相矛盾）。
            Text(empty, fontSize = Type.body, color = ink.faint)
        }
        // 「新建」那一行放在**列表上面**。它原来画在所有行的下面 —— 待办那一屏有 21 行，
        // 于是「＋ 发起请假」被推到屏幕外，装机时读屏根本找不到它（10-20 读屏验出来）。
        // 有 onNew 才画这一行，没有就一行都不多。
        if (newLabel != null && onNew != null) {
            Sheet(modifier = Modifier.pressClick(onClick = onNew)) {
                Text(newLabel, fontSize = Type.body, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(Gap.sm))
        }
        // 一张卡里一行一条、中间分隔线。以前是一条一张卡：二十条待办就是二十张卡，
        // 卡之间的缝比内容还显眼，滚两屏才看完 —— 这就是"肿"。
        if (state.rows.isNotEmpty()) {
            Sheet {
                state.rows.forEachIndexed { i, row ->
                    RowLine(
                        title = row.title,
                        sub = row.sub,
                        right = row.right,
                        flag = row.flag,
                        onClick = { onRow(row.id) },
                    )
                    if (i != state.rows.lastIndex) {
                        Spacer(Modifier.height(Gap.xs))
                        Hairline()
                        Spacer(Modifier.height(Gap.xs))
                    }
                }
            }
        }
        LoadWhy(state.error)
        Spacer(Modifier.height(Gap.sm))
    }
}

@Composable
fun BriefDetailPage(
    title: String,
    source: DetailSource,
    id: String,
    onBack: () -> Unit,
) {
    val model = adxViewModel { DetailModel(source, id) }
    val state by model.state.collectAsStateWithLifecycle()
    val ink = LocalInk.current
    // 写操作要连按两次：第一次只是把按下的那颗变成"再按一次真的××"，第二次才发报文。
    // 记的是**哪一颗**被 arm 了 —— 三颗键挤在同一屏，按「驳回」却发出去「同意」是不能发生的事。
    var armedKey by remember { mutableStateOf<String?>(null) }
    // 有的键按下去之前得先问两句（报修那两颗：完成情况 / 服务评价）。就地摊开问，
    // 不跳二级页、也不弹窗 —— 这屏本来就在一层页上，弹层里再套确认容易按错。
    val answers = remember { mutableStateMapOf<String, String>() }
    var promptWhy by remember { mutableStateOf<String?>(null) }

    ScaffoldPage(title = state.data?.title ?: title, onBack = onBack) {
        val d = state.data
        if (d == null && state.loading && state.error == null) {
            Text("正在拉这一条…", fontSize = Type.body, color = ink.faint)
        }
        if (d != null) {
            Sheet {
                d.fields.forEachIndexed { i, (label, value) ->
                    Labeled(label = label, value = value)
                    if (i != d.fields.lastIndex) {
                        Spacer(Modifier.height(Gap.sm)); Hairline(); Spacer(Modifier.height(Gap.sm))
                    }
                }
            }
            if (d.steps.isNotEmpty()) {
                Spacer(Modifier.height(Gap.md))
                Sheet {
                    d.steps.forEachIndexed { i, s -> StepRow(s, last = i == d.steps.lastIndex) }
                }
            }
            d.paragraphs.forEach {
                Spacer(Modifier.height(Gap.md))
                Text(it, fontSize = Type.body, color = ink.body, lineHeight = Type.bodyLine)
            }
            if (d.actions.isNotEmpty()) Spacer(Modifier.height(Gap.md))
            d.actions.forEach { a ->
                Spacer(Modifier.height(Gap.sm))
                val armed = armedKey == a.key
                val prompt = a.prompt
                if (prompt != null && armed) {
                    // 第一次按下去先把要问的摊开，选齐了第二下才真发
                    prompt.groups.filter { visibleGroup(it, answers) }.forEach { g ->
                        Spacer(Modifier.height(Gap.sm))
                        Text(g.label, fontSize = Type.meta, color = ink.muted)
                        Spacer(Modifier.height(Gap.xs))
                        if (g.freeText) {
                            MiuixTextField(
                                value = answers[g.key].orEmpty(),
                                onValueChange = { answers[g.key] = it },
                                label = g.options.firstOrNull().orEmpty(),
                            )
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(Gap.xs)) {
                                g.options.forEach { opt ->
                                    Chip(opt, answers[g.key] == opt) { answers[g.key] = opt }
                                }
                            }
                        }
                    }
                    prompt.note.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(Gap.xs))
                        Text(it, fontSize = Type.micro, color = ink.faint, lineHeight = Type.metaLine)
                    }
                }
                MiuixButton(
                    onClick = {
                        if (armed) {
                            // 该选的没选齐 —— 在这儿说清楚缺哪一句，**一发都不往外发**
                            val miss = prompt?.groups
                                ?.filter { visibleGroup(it, answers) && !it.optional && answers[it.key].isNullOrBlank() }
                                ?.map { it.label }
                            if (!miss.isNullOrEmpty()) {
                                promptWhy = "还没选：" + miss.joinToString("、")
                                return@MiuixButton
                            }
                            promptWhy = null
                            armedKey = null
                            model.act(a.key, answers.toMap())
                        } else {
                            promptWhy = null
                            armedKey = a.key
                        }
                    },
                    enabled = !state.acting,
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (armed || a.danger) dangerColors() else ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(
                        text = when {
                            state.acting -> "正在发…"
                            armed -> "再按一次，真的${a.label}"
                            else -> a.label
                        },
                        fontSize = Type.title,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
        promptWhy?.let {
            Spacer(Modifier.height(Gap.sm))
            Text(it, fontSize = Type.meta, color = MaterialTheme.colorScheme.error)
        }
        state.done?.let {
            Spacer(Modifier.height(Gap.md))
            Text(it, fontSize = Type.body, color = if (ink.dark) Palette.NightTeal else Palette.TealInk,
                lineHeight = Type.bodyLine)
        }
        state.error?.let { Text(it, fontSize = Type.body, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(Gap.md))
    }
}

@Composable
fun BriefFormPage(
    title: String,
    source: FormSource,
    onBack: () -> Unit,
) {
    val model = adxViewModel { FormModel(source) }
    val state by model.state.collectAsStateWithLifecycle()
    val values = remember { mutableStateMapOf<String, String>() }
    val ink = LocalInk.current
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    val session by graph.session.state.collectAsStateWithLifecycle(com.andaxin.core.store.Session())
    val dorm = session.dorm
    // 学校给的预填值（学号、姓名、班级那一类）要**填进格子本身**：提交时这几格是跟着 dataJson 一起发过去的，
    // 不是给人看的说明文字。只在格子是空的时候填，别把他改过的值又盖回去。
    val defaults = state.spec?.defaults
    LaunchedEffect(defaults) {
        defaults?.forEach { (k, v) -> if (values[k].isNullOrBlank()) values[k] = v }
    }
    // 这颗键是"往学校造一张真单"，跟销假同一档规矩：连按两次才发，中间那下只把键变色
    var armed by remember { mutableStateOf(false) }

    ScaffoldPage(title = state.spec?.title ?: title, onBack = onBack) {
        val sample = state.spec?.sample == true
        if (sample) {
            /*
             * 没登录时这张表是编的形状：字段是假的、提交也发不出去。
             * 原来照样摆一颗蓝色「提交」，按下去只会回一句失败 —— 一颗点了没用的主按钮
             * 比少一颗按钮坏得多，所以那颗先摘了。
             * 但摘完之后整屏只剩一行灰字，人得自己想到按返回 —— 那是另一头。
             * 这一屏该给的是**一条往前走的路**：这颗键不导航，只把"先不登录逛逛"这个标志清掉，
             * 外层的 Gate 看见"没登录又不在逛"就把登录那一屏端上来（和「我的」里那颗「去登录」同一条路）。
             */
            Text(
                text = "登录后才能填这张表。",
                fontSize = Type.title,
                color = ink.strong,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = Gap.md),
            )
            Spacer(Modifier.height(Gap.xs))
            Text(
                text = "这张单子是往学校那边发的，得用你自己的身份。",
                fontSize = Type.meta,
                color = ink.faint,
                lineHeight = Type.metaLine,
            )
            Spacer(Modifier.height(Gap.lg))
            MiuixButton(
                onClick = { scope.launch { graph.session.leaveBrowsing() } },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text("去登录", fontSize = Type.title, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(Gap.md))
        }
        if (!sample) state.spec?.fields?.forEach { field ->
            FieldRow(
                field,
                get = { values[it].orEmpty() },
                put = { k, v -> values[k] = v },
                dorm = dorm,
            )
            Spacer(Modifier.height(Gap.md))
        }
        if (!sample && state.spec?.canSubmit == false) {
            // 提交那条写口还没接 —— 不摆一颗按下去只会报错的"提交"，直接说清楚
            Text(
                text = "这张表本机还交不出去：提交那一口（学校那边的写操作）还没接。填了也只是看看。",
                fontSize = Type.meta,
                color = ink.faint,
                lineHeight = Type.metaLine,
            )
            Spacer(Modifier.height(Gap.md))
        }
        if (!sample && state.spec?.canSubmit != false) {
            state.spec?.footnote?.let {
                Text(it, fontSize = Type.meta, color = ink.faint, lineHeight = Type.metaLine)
                Spacer(Modifier.height(Gap.md))
            }
            MiuixButton(
                onClick = {
                    if (armed) {
                        armed = false
                        model.submit(values.toMap())
                    } else {
                        armed = true
                    }
                },
                enabled = !state.sending,
                modifier = Modifier.fillMaxWidth(),
                colors = if (armed) dangerColors() else ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(
                    text = when {
                        state.sending -> "正在发…"
                        armed -> "再按一次，真的发给学校"
                        else -> "提交"
                    },
                    fontSize = Type.title,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        state.done?.let {
            Spacer(Modifier.height(Gap.md))
            Text("已提交：$it", fontSize = Type.body,
                color = if (ink.dark) Palette.NightTeal else Palette.TealInk)
        }
        if (state.error != null) {
            Spacer(Modifier.height(Gap.md))
            LoadWhy(state.error)
        }
        Spacer(Modifier.height(Gap.md))
    }
}

/** 照片那一格一 tile 的边长（选中的和那颗「＋」一样大，格子排起来才齐）。 */
private val PhotoTile = 76.dp

/**
 * 一格里的那张缩略图。
 *
 * 解码走 IO 线程、按 2 的幂降采样 —— 十张原图整个进内存能把这一屏撑爆。
 * 读不出来就明写"读不出"，不拿一块灰底顶着：那看着像还没加载，其实这张图已经没了。
 */
@Composable
private fun PhotoThumb(uri: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val ink = LocalInk.current
    val px = with(LocalDensity.current) { (PhotoTile * 2).roundToPx() }
    val bmp by produceState<ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) { RepairPhotos.thumb(ctx, uri, px) }?.asImageBitmap()
    }
    bmp?.let {
        Image(
            bitmap = it,
            contentDescription = "已选的照片",
            modifier = modifier.clip(Radius.tag),
            contentScale = ContentScale.Crop,
        )
    } ?: Box(
        modifier = modifier
            .clip(Radius.tag)
            .background(ink.offFill),
        contentAlignment = Alignment.Center,
    ) {
        Text("读不出", fontSize = Type.micro, color = ink.faint)
    }
}

@Composable
private fun FieldRow(
    field: Field,
    get: (String) -> String,
    put: (String, String) -> Unit,
    dorm: String,
) {
    val ink = LocalInk.current
    var picking by remember { mutableStateOf(false) }
    when (field) {
        is Field.Text -> Column {
            Text(field.label, fontSize = Type.meta, color = ink.muted)
            Spacer(Modifier.height(Gap.xs))
            MiuixTextField(
                value = get(field.key),
                onValueChange = { put(field.key, it) },
                // Miuix 这个输入框没有 placeholder 槽，提示语走 `label`
                label = field.hint,
                singleLine = true,
            )
        }
        // 只读的一条：说明文字、以及本机渲染不出来的控件。不给输入框 —— 摆个空框
        // 等于告诉用户"这还没填"，而那一项根本没有可填的东西。
        is Field.Note -> Column {
            if (field.label.isNotBlank()) {
                Text(field.label, fontSize = Type.meta, color = ink.muted)
                Spacer(Modifier.height(Gap.xs))
            }
            if (field.text.isNotBlank()) {
                Text(field.text, fontSize = Type.body, color = ink.body, lineHeight = Type.bodyLine)
            }
        }
        // 照片那一格：格子里只放本机选中的图。选图、删图都不碰网络 ——
        // 传图那几发排在「提交」后面（`CaptureRepairForm.uploadPhotos`），这一格只是把 uri 攒着。
        is Field.Photos -> {
            val picked = get(field.key).split(',').map { it.trim() }.filter { it.isNotBlank() }
            val pick = rememberLauncherForActivityResult(
                ActivityResultContracts.PickMultipleVisualMedia(field.max),
            ) { got ->
                put(
                    field.key,
                    (picked + got.map { it.toString() }).distinct().take(field.max).joinToString(","),
                )
            }
            Text(field.label, fontSize = Type.meta, color = ink.muted)
            Spacer(Modifier.height(Gap.xs))
            Row(horizontalArrangement = Arrangement.spacedBy(Gap.sm)) {
                picked.forEach { uri ->
                    Box(Modifier.size(PhotoTile)) {
                        PhotoThumb(uri = uri, modifier = Modifier.fillMaxSize())
                        Text(
                            "✕",
                            fontSize = Type.meta,
                            color = ink.strong,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .clip(Radius.tag)
                                .background(ink.offFill)
                                .pressClick { put(field.key, (picked - uri).joinToString(",")) }
                                .padding(horizontal = 7.dp, vertical = 1.dp),
                        )
                    }
                }
                if (picked.size < field.max) {
                    Box(
                        modifier = Modifier
                            .size(PhotoTile)
                            .clip(Radius.tag)
                            .background(ink.offFill)
                            .pressClick {
                                pick.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly,
                                    ),
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("＋", fontSize = Type.title, color = ink.muted)
                    }
                }
            }
            Spacer(Modifier.height(Gap.xs))
            Text(
                text = if (picked.size >= field.max) {
                    "已经 ${picked.size} 张，到顶了（学校那一版最多也是 ${field.max} 张）。"
                } else {
                    "已选 ${picked.size} 张，最多 ${field.max} 张，单张原图 5MB 以内。" +
                        "图是按下「提交」之后才往学校传的。"
                },
                fontSize = Type.micro,
                color = ink.faint,
                lineHeight = Type.metaLine,
            )
        }
        // 宿舍这种"本机设置里填的东西"：填了什么就显示什么，没填就明说去哪儿填，
        // 不拿一个假房间号顶着 —— 那会让报修单看着像已经填好了
        is Field.Person -> Labeled(
            label = field.label,
            value = dorm.ifBlank { "没填 · 去「我的 · 宿舍」填一下" },
        )
        // 单位是可选的：原来固定拼"未填 · 单位"，单位为空就留下一个孤零零的间隔号
        is Field.Number -> Labeled(
            label = field.label,
            value = if (field.unit.isBlank()) "未填" else "未填 · ${field.unit}",
        )
        // 日期终于能点了。以前这一行只写着"（日期控件批次三补）"，
        // 而 DatePicker 的 import 一直挂在这个文件里没人用 —— 表单里所有日期字段都填不上。
        is Field.Date -> {
            if (picking) {
                val ps = rememberDatePickerState()
                DatePickerDialog(
                    onDismissRequest = { picking = false },
                    confirmButton = {
                        Text(
                            text = "选定",
                            fontSize = Type.body,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .pressClick(shrink = false) {
                                    ps.selectedDateMillis?.let(::formatUtcDay)?.let { put(field.key, it) }
                                    picking = false
                                }
                                .padding(horizontal = Gap.md, vertical = Gap.sm),
                        )
                    },
                    dismissButton = {
                        Text(
                            text = "取消",
                            fontSize = Type.body,
                            color = ink.muted,
                            modifier = Modifier
                                .pressClick(shrink = false) { picking = false }
                                .padding(horizontal = Gap.md, vertical = Gap.sm),
                        )
                    },
                ) {
                    DatePicker(state = ps, showModeToggle = false)
                }
            }
            val chosen = get(field.key)
            Column(Modifier.pressClick(shrink = false) { picking = true }) {
                Text(field.label, fontSize = Type.meta, color = ink.muted)
                Spacer(Modifier.height(Gap.xs))
                Text(
                    text = chosen.ifBlank { "点选日期" },
                    fontSize = Type.title,
                    color = if (chosen.isBlank()) ink.ghost else ink.strong,
                )
            }
        }
        is Field.Choice -> Column {
            Text(field.label, fontSize = Type.meta, color = ink.muted)
            Spacer(Modifier.height(Gap.sm))
            // 必须是会换行的流式排：原来是一整排不折行的 Row，
            // 「学院」这种十几个选项被硬挤进一屏宽 —— 每颗字都竖着排成三四行，
            // 高的那颗撑到 600 多像素，后面几颗直接挤出屏幕外看不见。
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Gap.sm),
                verticalArrangement = Arrangement.spacedBy(Gap.sm),
            ) {
                field.options.forEach { opt ->
                    // 原来这一排每个选项都是同一层 hairline，选了哪个完全看不出来
                    Chip(opt, get(field.key) == opt) { put(field.key, opt) }
                }
            }
        }
    }
}

/**
 * M3 的 DatePicker 给的是 **UTC 零点**的毫秒数，所以这里也按 UTC 格式化。
 * 换成机器时区的话，在东八区没事、在西五区就会把用户点的日期整整挪后一天。
 */
private fun formatUtcDay(millis: Long): String =
    java.time.Instant.ofEpochMilli(millis)
        .atZone(java.time.ZoneOffset.UTC)
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))

/**
 * 审批链的一环：左边一个圆点 + 一根连到下一环的竖线，右边"环节 · 谁 · 状态 · 时间"。
 *
 * 原来这些是一整段"1. …\n2. …"的文字（paragraph），一屏审批下来就是一堵字。
 * 竖线用 `IntrinsicSize.Min` 撑：外层是 verticalScroll，高度是无限的，
 * 直接 fillMaxHeight 拿不到这一行的高度。
 */
@Composable
private fun StepRow(s: com.andaxin.data.brief.Step, last: Boolean) {
    val ink = LocalInk.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(androidx.compose.foundation.layout.IntrinsicSize.Min),
    ) {
        Column(
            Modifier
                .width(16.dp)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(5.dp))
            Box(
                Modifier
                    .size(8.dp)
                    .background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape),
            )
            if (!last) {
                Spacer(Modifier.height(2.dp))
                Box(Modifier.width(1.dp).weight(1f).background(ink.hairline))
            }
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = Gap.sm, bottom = if (last) 0.dp else Gap.md),
        ) {
            if (s.head.isNotBlank()) {
                Text(s.head, fontSize = Type.meta, fontWeight = FontWeight.Medium, color = ink.strong)
                Spacer(Modifier.height(1.dp))
            }
            Text(s.who, fontSize = Type.body, color = ink.body)
            if (s.what.isNotBlank()) Text(s.what, fontSize = Type.meta, color = ink.muted)
            if (s.at.isNotBlank()) Text(s.at, fontSize = Type.micro, color = ink.faint)
        }
    }
}

/**
 * 三种页共用的那一层壳。
 *
 * 换成 Miuix 的 `Scaffold` + `SmallTopAppBar`：HyperOS 那一档标题栏自带状态栏与
 * 刘海的留白，以前是我们手搓一个 `Spacer(Gap.xl)` 顶出来的，换个机型就得重调一次。
 * 返回那颗键仍然写"‹ 返回"三个字 —— 这仓的返回入口一直是这个说法，不换图标。
 */
@Composable
fun ScaffoldPage(
    title: String,
    onBack: () -> Unit,
    /** 给了这一颗，这一屏就能下拉刷新；它是挂起的 —— 拉完才收指示器，不等就是假动作。 */
    onRefresh: (suspend () -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = title,
                navigationIcon = {
                    Text(
                        "‹ 返回",
                        fontSize = Type.body,
                        color = LocalInk.current.muted,
                        modifier = Modifier.pressClick(shrink = false, onClick = onBack).padding(horizontal = Gap.md, vertical = Gap.sm),
                    )
                },
            )
        },
        containerColor = topBarBackdrop(),
    ) { bars ->
        Refreshable(onRefresh, Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(bars)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Gap.screen),
            ) {
                Spacer(Modifier.height(Gap.md))
                content()
                Spacer(Modifier.height(Gap.xxl))
            }
        }
    }
}

@Composable
private fun topBarBackdrop() = MiuixTheme.colorScheme.background

/**
 * 这一组现在要不要出现：`onlyWhen = (组key -> 必须选中的那一条)`。
 * 报修那两颗就是这用法 —— 只有选了"已完成"才需要给评价分，选"未完成"时 H5 那边评价发的是空串。
 */
private fun visibleGroup(g: com.andaxin.data.brief.PromptGroup, answers: Map<String, String>): Boolean {
    val (k, v) = g.onlyWhen ?: return true
    return answers[k] == v
}

