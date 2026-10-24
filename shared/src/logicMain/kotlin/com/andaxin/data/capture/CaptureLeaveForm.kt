package com.andaxin.data.capture

import kotlin.concurrent.Volatile
import com.andaxin.core.concurrency.hoursBetweenLDT
import com.andaxin.core.concurrency.parsePatternTime
import com.andaxin.core.concurrency.formatPatternTime
import com.andaxin.core.store.SessionStore
import com.andaxin.data.brief.Field
import com.andaxin.data.brief.FormSource
import com.andaxin.data.brief.FormSpec
import com.andaxin.core.json.JSONArray
import com.andaxin.core.json.JSONObject

/*
 * 发起请假那张**动态表单**，以及真往学校发那一发。
 *
 * 【取证 · ② 2026-10-20 下午从本机现打，一跳一跳验出来的】
 * 链路两条读口：
 *   `POST ywlz.aiit.edu.cn/zhxy-bfc/mobile/flowRuTask/getFlowDefinitionsplus.do`
 *     → 数组挂在**根节点的 `rows`**（不是 `data`；`data` 是空对象，第一次打在这儿绕了一圈），
 *       `rows[]` 是分类（`name` + `id`(3) + `flowList[]`），`flowList[]` 每条一个流程，
 *       33 个键，我们只用 `id`（**有 32 位十六进制和 18 位数字两种长度**，必须运行时取，不能硬编）、
 *       `flowName`、`icon`、`url`。
 *   `POST …/mobile/process/getFlowSheet.do{processId}`
 *     → `data{flowSheet, specialType}`，`flowSheet` 是**一个 JSON 串**（实样 len=8977），
 *       解开是 `{config, list}`；`list[]` 是三个 `card` 容器，字段挂在容器的 **`list`** 键上
 *       （不是 `children` —— 按 children 走会一个字段都数不到，界面表现就是"表单是空的"），
 *       `grid` 容器再套一层 `columns[]`，所以两个键都要递归（10-23 才发现以前只走 `list`，
 *       grid 里的字段整块漏掉 —— 漏一个就是 dataJson 缺一个键）。
 *   `POST …/mobile/process/findDefinitionBase.do{definitionId}` → `data` 的键**就是 model 名**，
 *     9 个预填值（学号、姓名、班级那一类）。契约里那 7 格 `properties.disabled=true`、`userPhone` 可改，
 *     但**提交时都得照发**，所以它不是占位文字，是数据（逐字段骨架见 `lxtmp/契约_表单与角标现打.md` §1.5–§1.7）。
 *
 * 【提交 · 10-23 接上，照他旧项目那条已经跑通的做法】
 * `POST …/mobile/process/submitForm.do`，体 = `executionId`（首次传**空串**）+ `definitionId`（= 清单里那条 id）
 * + `dataJson` + 身份五件套（走 [School.postH5]）。回 `flag:true` + `data.executionId`。
 * 这一族失败原因在 `result`、不在 `msg`，`postH5` 已经把两处都看了。
 * **dataJson 的键是节点的 `model`，不是 `key`**（§1.6：多数相等，不等的时候发 `key` 就是静默丢一个值）。
 * 值按控件类型分形态，全部有实样：radio/select 发 **value 原文**、`checkbox`/`m-select` 发逗号串、
 * `dateRange` 发**二元数组**、`address` 发**对象**、`formula` 发**数字**（起止的小时差）、`text` 无值不进表。
 *
 * 【没做的两件事，都写明白了】
 * ① `uploadFile` / `uploadImg` 那一格**界面上不出现**：附件要先打
 *    `…/zhxy-bfc/file/upload.do` 往学校传文件，那是另一条写口，一次没现打过。
 *    但那一格的键照样进 dataJson、发空数组（§1.7 写明本表 18 个 model 一条不能少），
 *    交上去就是"一张没有附件的单" —— 这句写在表单末尾，不藏着。
 * ② 控件的显示条件（`condition`）没实现：官方 H5 用它决定 ADDRESS 显不显示，
 *    我们一律显示、一律发（他旧仓也是这么发的，没有反例）。所以别把"按 condition 只发该发的"当成已验的事。
 *
 * 【界面】这颗键连按两次才发、失败**不自动重试** —— 重发一次就是多造一张真单。
 */
class CaptureLeaveForm(
    private val school: School,
    private val session: SessionStore,
) : FormSource {

    /**
     * [spec] 解出来、[submit] 要用的那张结构。一次进屏解一次，重新进屏覆盖旧的。
     * 键全是校方的 `model`；[pairs] 给 dateRange 那两个输入格（`<model>.start` / `<model>.end`）。
     */
    private class Shape(
        val definitionId: String,
        val models: List<String>,
        val types: Map<String, String>,
        val labels: Map<String, String>,
        val required: Set<String>,
        val options: Map<String, List<Pair<String, String>>>,
        val pairs: Map<String, Pair<String, String>>,
        val formats: Map<String, String>,
        val formulaRef: Map<String, String>,
    )

    @Volatile
    private var shape: Shape? = null

    override suspend fun spec(): Result<FormSpec> {
        val flows = school.postH5("$YWLZ/mobile/flowRuTask/getFlowDefinitionsplus.do")
            .mapCatching { root ->
                val groups = root.optJSONArray("rows") ?: JSONArray()
                buildList {
                    for (g in 0 until groups.length()) {
                        val list = groups.optJSONObject(g)?.optJSONArray("flowList") ?: JSONArray()
                        for (i in 0 until list.length()) {
                            list.optJSONObject(i)?.let { add(it) }
                        }
                    }
                }
            }.getOrElse { return Result.failure(it) }
        if (flows.isEmpty()) return Result.failure(Exception("校方清单里一条可发起的流程都没给"))

        // 优先挑名字里带"请假"的那条；没有就用第一条，并把实际用的名字写进标题，别假装是请假
        val pick = flows.firstOrNull { it.optString("flowName").contains("请假") } ?: flows[0]
        val pid = pick.optString("id")
        if (pid.isBlank()) return Result.failure(Exception("流程清单里那条没有 id，取不了表单"))

        val sheet = school.postH5("$YWLZ/mobile/process/getFlowSheet.do", "processId" to pid)
            .mapCatching { root ->
                val raw = root.optJSONObject("data")?.optString("flowSheet").orEmpty()
                if (raw.isBlank()) error("这张流程没回表单（flowSheet 是空的）")
                JSONObject(raw)
            }.getOrElse { return Result.failure(it) }

        val fields = ArrayList<Field>()
        val models = ArrayList<String>()
        val types = HashMap<String, String>()
        val labels = HashMap<String, String>()
        val required = LinkedHashSet<String>()
        val opts = HashMap<String, List<Pair<String, String>>>()
        val pairs = HashMap<String, Pair<String, String>>()
        val formats = HashMap<String, String>()
        val formulaRef = HashMap<String, String>()
        val skipped = ArrayList<String>()

        walk(
            sheet.optJSONArray("list"), fields, models, types, labels, required, opts,
            pairs, formats, formulaRef, skipped,
        )
        if (fields.isEmpty()) return Result.failure(Exception("表单解开了但一个字段都没认出来（容器键名可能变了）"))
        shape = Shape(pid, models.toList(), types, labels, required.toSet(), opts, pairs, formats, formulaRef)

        val name = pick.optString("flowName").ifBlank { "发起流程" }
        val defaults = prefill(pid, models)
        val note = when {
            skipped.isEmpty() -> null
            else -> "附件那一格（${skipped.joinToString("、")}）本机没做，交上去就是一张没有附件的单。"
        }
        return Result.success(FormSpec(title = name, fields = fields, defaults = defaults, footnote = note))
    }

    /**
     * `findDefinitionBase.do` 那 9 个预填值。取不到不当失败 —— 表照样能填，
     * 只是那几格空着，提交时 [submit] 会按 required 拦住并说清是哪一格。
     * 数字型的默认值要抹掉尾巴那个 `.0`（服务端给的是 JSON 数字，`optString` 会捞成 "2026.0" 这种）。
     */
    private suspend fun prefill(pid: String, models: List<String>): Map<String, String> {
        val root = school.postH5("$YWLZ/mobile/process/findDefinitionBase.do", "definitionId" to pid)
            .getOrNull() ?: return emptyMap()
        val data = root.optJSONObject("data") ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (m in models) {
            if (!data.has(m)) continue
            val raw = when (val v = data.opt(m)) {
                null, JSONObject.NULL -> ""
                is Number -> v.toString().removeSuffix(".0")
                else -> v.toString()
            }
            if (raw.isNotBlank()) out[m] = raw
        }
        return out
    }

    /**
     * 递归收字段。容器认 `card` / `grid` / `batch` 三种（H5 的渲染分支里就这三个带子 `list`），
     * 而 `grid` 的子节点挂在 **`columns[]`** 上，不是 `list` —— 两条都得走。
     *
     * 认不出来的类型**不当错误抛**：就在原位放一条带说明的只读项，写清"这一项本机渲染不了"。
     * 10-20 第一版是把它们攒成一句塞进标题，装机回读就成了
     * 「学生请假申请（「」这一条是 grid 控件，本机没实现；「地址」…）」——
     * 空标签的容器被当成字段报了出来，一整串还压在标题那一行里，谁都得读三遍。
     */
    private fun walk(
        items: JSONArray?,
        out: ArrayList<Field>,
        models: ArrayList<String>,
        types: HashMap<String, String>,
        labels: HashMap<String, String>,
        required: LinkedHashSet<String>,
        opts: HashMap<String, List<Pair<String, String>>>,
        pairs: HashMap<String, Pair<String, String>>,
        formats: HashMap<String, String>,
        formulaRef: HashMap<String, String>,
        skipped: ArrayList<String>,
    ) {
        if (items == null) return
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val type = o.optString("type")
            val label = o.optString("label")
            // ★ dataJson 的键是 model；model 空着的老节点才退回设计器 key
            val model = o.optString("model").ifBlank { o.optString("key") }
            val props = o.optJSONObject("properties") ?: JSONObject()
            val children = o.optJSONArray("list")
            val columns = o.optJSONArray("columns")
            if ((children != null && children.length() > 0) || (columns != null && columns.length() > 0)) {
                walk(children, out, models, types, labels, required, opts, pairs, formats, formulaRef, skipped)
                if (columns != null) {
                    for (c in 0 until columns.length()) {
                        val col = columns.optJSONObject(c) ?: continue
                        walk(col.optJSONArray("list"), out, models, types, labels, required, opts,
                            pairs, formats, formulaRef, skipped)
                    }
                }
                continue
            }
            if (type in CONTAINERS || model.isBlank()) continue     // 空容器只是排版，不是字段
            if (props.optBoolean("hidden", false)) continue
            val need = o.optJSONArray("rules")?.optJSONObject(0)?.optBoolean("required", false) == true
            fun register(kind: String, shown: Field?) {
                models += model
                types[model] = kind
                labels[model] = label.ifBlank { model }
                if (need) required += model else required.remove(model)
                shown?.let { out.add(it) }
            }
            when (type) {
                // 校方那种"说明文字"节点：内容在 value/content/text 这几个键里挑第一个有的。
                // 一个都没有就整条不报 —— 原来摆一个能输入的框、里面写"（这一条是说明文字）"，
                // 装机看着像有两项没填完。text 不进 dataJson（它没有值）。
                "text" -> plain(props)?.let { out.add(Field.Note(model, label, it)) }

                "input", "textarea" -> register(type, Field.Text(model, label, hint = ""))
                "number", "money" ->
                    register(type, Field.Number(model, label, unit = props.optString("unit")))
                "radio", "checkbox", "select", "m-select", "dept", "course", "person" -> {
                    val list = options(props)
                    opts[model] = list
                    register(type, Field.Choice(model, label, list.map { it.first }))
                }
                "date" -> {
                    val f = props.optString("format").ifBlank { "YYYY-MM-DD" }
                    formats[model] = f
                    register(type, Field.Text(model, label, hint = example(f)))
                }
                // 起止时间是**一发里的两个格子**，dataJson 那边是一个二元数组，所以给两个输入键
                "dateRange" -> {
                    val f = props.optString("format").ifBlank { "YYYY-MM-DD HH:mm" }
                    val rng = props.optJSONArray("rangePlaceholder")
                    val s = "$model.start"; val e = "$model.end"
                    formats[model] = f
                    pairs[model] = s to e
                    out.add(Field.Text(s, rng?.optString(0).orEmpty().ifBlank { "${label}·开始" }, hint = example(f)))
                    out.add(Field.Text(e, rng?.optString(1).orEmpty().ifBlank { "${label}·结束" }, hint = example(f)))
                    register(type, null)
                }
                // formula 是算出来的，不给人填：值由它引用的那条 dateRange 求小时差（§1.6 formula[] 元素带 model）
                "formula" -> {
                    o.optJSONArray("formula")?.let { arr ->
                        for (k in 0 until arr.length()) {
                            val ref = arr.optJSONObject(k)?.optString("model").orEmpty()
                            if (ref.isNotBlank()) formulaRef[model] = ref
                        }
                    }
                    register(type, null)
                }
                // 附件：要往学校传文件，另一条写口，本机没做（见文件头那句）。
                // 但那一格**照样进 dataJson**、发一个空数组 —— §1.7 写明本表 18 个 model "一条不多一条不少"，
                // 缺一个键就是少发一格；H5 那边它是个数组，没附件就是 `[]`。
                "uploadFile", "uploadImg" -> {
                    skipped += label.ifBlank { model }
                    register(type, null)
                }
                "" -> {}
                // 认不出来的控件：只读一条摆回原位，不给输入框，也不写"去官方 App 填"那种话
                else -> {
                    out.add(Field.Note(model, label, "这一项本机填不了（控件类型 ${type.ifBlank { "空" }}）"))
                    // 填不了又必填 —— 直接进 required，提交时那句"还没填"会把它点出来，
                    // 而不是默默少发一个键（服务端对缺键是不吭声的，跟成绩那条同一个脾气）
                    if (need) required += model
                }
            }
        }
    }

    /**
     * 从"说明文字"那一类节点里把给人看的正文抠出来：
     * 键名不固定（value / content / text 都见过），里面还常带一层 HTML 标签。
     * 全空就回 null —— 宁可不摆这一条，也不摆一个空框。
     */
    private fun plain(props: JSONObject): String? {
        val raw = listOf("value", "content", "text", "html")
            .map { props.optString(it) }
            .firstOrNull { it.isNotBlank() && it != "null" }
            ?: return null
        val txt = raw.replace(Regex("<[^>]*>"), " ")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return txt.takeIf { it.isNotBlank() }
    }

    /** options 元素是 `{label, value}`：界面显示 label、dataJson 发 value（本表实样 13/13 两者相同）。 */
    private fun options(props: JSONObject): List<Pair<String, String>> {
        val arr = props.optJSONArray("options") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val l = o.optString("label")
            if (l.isBlank()) return@mapNotNull null
            l to o.optString("value").ifBlank { l }
        }
    }

    /**
     * 提交。值全按 §1.6 的形态拼，缺一个必填就**不发**（少发一个键学校是不吭声的）。
     * 成功回校方的 `executionId`。
     */
    override suspend fun submit(values: Map<String, String>): Result<String> {
        val s = shape ?: return Result.failure(Exception("这张表还没解出来，退出去再进一次"))
        val json = JSONObject()
        val missing = ArrayList<String>()
        for (m in s.models) {
            val label = s.labels[m].orEmpty()
            when (s.types[m]) {
                "text" -> {}
                // 附件：这一格本机填不了，但键要发，形态是没附件的空数组（§1.6：值就是那个对象数组）
                "uploadFile", "uploadImg" -> json.put(m, JSONArray())
                "dateRange" -> {
                    val (sk, ek) = s.pairs[m] ?: ("" to "")
                    val f = s.formats[m].orEmpty()
                    // 空着的必填先按"还没填"报，别让他看见一句"格式不对" —— 没填和填错是两件事
                    if (values[sk].isNullOrBlank() && s.required.contains(m)) { missing += label; continue }
                    val a = parseTime(values[sk].orEmpty(), f)
                        ?: return Result.failure(Exception("${label}的开始时间格式不对，要像 ${example(f)}"))
                    val b = parseTime(values[ek].orEmpty(), f)
                        ?: return Result.failure(Exception("${label}的结束时间格式不对，要像 ${example(f)}"))
                    if (b < a) return Result.failure(Exception("${label}：结束时间不能早于开始时间"))
                    json.put(m, JSONArray().put(fmt(values[sk].orEmpty(), f)).put(fmt(values[ek].orEmpty(), f)))
                }
                "formula" -> {
                    val ref = s.formulaRef[m]
                    val pair = ref?.let { s.pairs[it] }
                    val a = pair?.first?.let { parsePatternTime(values[it].orEmpty()) }
                    val b = pair?.second?.let { parsePatternTime(values[it].orEmpty()) }
                    if (a == null || b == null || b < a) {
                        // 宁可不发，也不交一个 0 小时的数过去 —— 旧仓那版给 0，学校照收，
                        // 收进去就是一张"时长 0"的单，比报错坏得多
                        return Result.failure(Exception("${label}算不出来：先把它上面那行起止时间填对"))
                    }
                    json.put(m, hoursBetweenLDT(a, b))
                }
                "address" -> {
                    val v = values[m].orEmpty().trim()
                    if (v.isBlank() && s.required.contains(m)) { missing += label; continue }
                    json.put(m, JSONObject().put("province", "").put("city", "").put("county", "").put("address", v))
                }
                "checkbox", "m-select" -> {
                    val parts = values[m].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    if (parts.isEmpty() && s.required.contains(m)) { missing += label; continue }
                    val table = s.options[m].orEmpty().toMap()
                    json.put(m, parts.joinToString(",") { table[it] ?: it })
                }
                "radio", "select", "dept", "course", "person" -> {
                    val v = values[m].orEmpty()
                    if (v.isBlank() && s.required.contains(m)) { missing += label; continue }
                    val table = s.options[m].orEmpty().toMap()
                    json.put(m, table[v] ?: v)
                }
                else -> {
                    val v = values[m].orEmpty()
                    if (v.isBlank() && s.required.contains(m)) { missing += label; continue }
                    json.put(m, if (s.types[m] == "number" || s.types[m] == "money") v.toDoubleOrNull() ?: v else v)
                }
            }
        }
        if (missing.isNotEmpty()) return Result.failure(Exception("还没填：" + missing.joinToString("、")))

        val root = school.postH5(
            "$YWLZ/mobile/process/submitForm.do",
            "executionId" to "",
            "definitionId" to s.definitionId,
            "dataJson" to json.toString(),
        ).getOrElse { return Result.failure(it) }
        val exe = root.optJSONObject("data")?.optString("executionId").orEmpty()
        return if (exe.isBlank()) Result.failure(Exception("学校说发出去了，可没给回这张单号（executionId 是空的）"))
        else Result.success(exe)
    }

    private companion object {
        const val YWLZ = "https://ywlz.aiit.edu.cn/zhxy-bfc"

        /** H5 的渲染分支里带子 list / columns 的三种排版节点。 */
        val CONTAINERS = setOf("card", "grid", "batch")
    }
}

/** 校方的 `YYYY-MM-DD HH:mm` 这种写法换给 `DateTimeFormatter`，只动它这两种大写。 */
private fun javaPattern(f: String): String =
    f.replace("YYYY", "yyyy").replace("DD", "dd").ifBlank { "yyyy-MM-dd HH:mm" }

private fun example(f: String): String =
    if (f.contains("HH")) "2026-10-25 08:30" else "2026-10-25"

/** 只有日期没有时分的写法按 00:00 起算；两种都能读出来，读不出来回 null。 */
private fun parseTime(text: String, f: String): kotlinx.datetime.LocalDateTime? = parsePatternTime(text)

/** 发出去的时间串按它自己的格式回写一遍，把人随手打的多余空格和"10-25"这类补全。 */
private fun fmt(text: String, f: String): String {
    val t = text.trim()
    val parsed = parsePatternTime(t) ?: return t
    return formatPatternTime(parsed, f.contains("HH"))
}

/** 登录了走校方那张动态表，没登录才回占位 —— 和列表/详情同一个规矩。 */
class FormSwitch(
    private val capture: FormSource,
    private val local: FormSource,
    private val session: SessionStore,
) : FormSource {
    override suspend fun spec(): Result<FormSpec> =
        if (session.signedIn()) capture.spec() else local.spec()

    override suspend fun submit(values: Map<String, String>): Result<String> = capture.submit(values)
}
