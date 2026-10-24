package com.andaxin.data.capture

import kotlin.concurrent.Volatile
import com.andaxin.data.brief.Field
import com.andaxin.data.brief.FormSource
import com.andaxin.data.brief.FormSpec
import com.andaxin.core.json.JSONArray
import com.andaxin.core.json.JSONObject

/*
 * 新建报修那张表：**下拉的可选值是校方字典给的**，不是我们在代码里列的五个词。
 *
 * 【取证 · ② 2026-10-20 现打，逐字段骨架在 `lxtmp/契约_报修现打.md` §2.4–§2.8】
 * 三条字典口，都在 `repair.aiit.edu.cn/bxjlSjd/`，POST 表单、身份两键：
 * - `getBxlxForSjd` → 66 条**扁平** `{name,parent,value}`，**只有两级**（一级 9、二级 57、三级 0）。
 *   提交时 `bxlx` 要的是**二级 value（32 位 id）**。一级里有 3 条没子节点，H5 会给它们补一条
 *   空 name 的占位 —— 我们不做那套：没子的一级根本不该出现在选择里，过滤掉比补占位诚实。
 * - `getBxddForSjd` → 99 条三级树（校区 1 / 楼栋 36 / 楼层 62）。提交时
 *   `xqdm`=一级 value(2 位数字)、`lyId`=二级 value(32)、`qyfjh`=**三级 value(32)**；
 *   而响应回来的 `qyfjh` 是**名字**、`qyfjhId` 才是 id —— **同名字段发 id 回名字**，这条不对称是现打打出来的。
 * - `getDictListForSjd{typeId=<JS 里写死那个 32 位>}` → 12 个时间段，`label == value`，
 *   形如 `9:00`/`17:30`，**提交发的是时间串本身不是 id**，`kssj`/`jssj` 各填一个。
 *
 * 本仓的 `Field.Choice` 是**一排平铺的选项、没有级联**，所以：
 * 故障类型合成「一级 · 二级」一条一项（57 项，选完就知道两级的 id）；
 * 地点合成「楼栋 楼层」一条一项（有楼层的按楼层，没楼层的楼栋自己当叶子）。
 * 这比在界面上摆三排、而第三排永远是空的要老实（旧笔记就踩过：`RepairFormScreen.TreePicker` 画三排，
 * 而这一族三级 0 条）。
 *
 * 【没做的两件事，都是有理由的】
 * ① `getSjhForSjd`（报修人姓名+手机号）不预填：那是 PII，而且这张表**不能提交**，
 *    预填一个看不见的值只会让人以为我们存了它。
 * ② `getBxxzForSjd` 那 885 字的「报修须知」（HTML 片段，实测只有 p/br/span/strong、零 img）
 *    这一版没往界面上放 —— `FormSpec` 只有字段位，塞成一条输入框的提示文字太委屈它。
 *    要做就单独开一屏或做成详情页那种正文，下一版连提交一起做。
 *
 * **提交 10-23 夜里接上了**（见下面 [submit]）：十个键的形状是 `xqdm/lyId/qyfjh/xxdz/sjh/gzms/xm/userCode/tpId/bxlx`
 * ＋可选 `kssj/jssj`，姓名和手机号提交时现问 `getSjhForSjd`。它是写口，界面上连按两次才发，
 * 按完只报两个字，成没成回「我的报修」那一栏数 —— 判据照他旧仓跑通的那一份。
 * `tpId` 现在恒为空串：官方那份是 `imgList.map(n => n.id).join(",")`，照片先过一发 `uploadForSjd` 换回 `data.file.id`。
 * 那一发**不是 multipart** —— 图压缩后整个当 base64 数据 URL 塞进 `fileImg` 一个字段，
 * URL 上还挂 `?timestap=<毫秒>`（学校拼错的那三个字母照抄）。原文出处见 `docs/接入清单.md` 一点三之二十八。
 * `loginType` 不发：官方那发带它，可我们没验过不给会怎样，而读那一族去掉它照样回全量。
 */
class CaptureRepairForm(private val school: School) : FormSource {

    /** 界面上给人选的是名字，提交要发的是 id —— 这两张对照表在 [spec] 里建、在 [submit] 里用。 */
    private class Shape(
        val typeIds: Map<String, String>,
        val placeIds: Map<String, String>,
    )

    @Volatile
    private var shape: Shape? = null

    override suspend fun spec(): Result<FormSpec> {
        val types = tree("getBxlxForSjd")
        val places = tree("getBxddForSjd")
        val times = dictTimes()

        // 故障类型：一级 value -> 名字；二级按 parent 挂回去
        val t1 = types.filter { it.parent.isBlank() || it.parent == "0" }
        val t1values = t1.map { it.value }.toSet()
        val leaves = types.filter { it.parent in t1values }
        val typeOptions = leaves.mapNotNull { l ->
            val top = t1.firstOrNull { it.value == l.parent } ?: return@mapNotNull null
            "${top.name} · ${l.name}" to l.value
        }
        if (typeOptions.isEmpty()) return Result.failure(Exception("故障类型字典回空了（66 条那棵树没解出来）"))

        val placeOptions = placeLeaves(places)
        if (placeOptions.isEmpty()) return Result.failure(Exception("报修地点字典回空了（99 条那棵树没解出来）"))

        val fields = buildList {
            add(Field.Choice(KEY_TYPE, "故障类型", typeOptions.map { it.first }))
            add(Field.Choice(KEY_PLACE, "报修地点", placeOptions.map { it.first }))
            add(Field.Text(KEY_ADDR, "详细地址", hint = "门牌/房间号，自由文本"))
            if (times.isNotEmpty()) {
                add(Field.Choice(KEY_START, "期望上门·开始", times))
                add(Field.Choice(KEY_END, "期望上门·结束", times))
            }
            add(Field.Text(KEY_DESC, "问题描述", hint = "哪里坏了、什么时候开始的，越具体越好"))
        }
        shape = Shape(
            typeOptions.toMap(),
            placeOptions.toMap(),
        )
        return Result.success(
            FormSpec(
                title = "新建报修",
                fields = fields,
                footnote = "照片那一栏本机还没做（要往学校传文件那条口一次没现打过），" +
                    "所以这张单是以「没有照片」交上去的。",
            ),
        )
    }

    /**
     * 提交这一张单。
     *
     * 【取证 · ① + 我们自己的现打】十个键逐个来自官方 bundle 里那段 `submitAllAjax`
     * （`xqdm/lyId/qyfjh/xxdz/sjh/gzms/xm/userCode/tpId/bxlx`，期望时间 `kssj/jssj` 有才带），
     * 三段 id 的取法（一级码 / 二级楼栋 / 三级楼层）和"发 id、回名字"那条不对称是 10-20 现打定死的。
     * **成功响应没有实样** —— 这一条口一次没发过，所以按完只报"学校答了 flag:true"，
     * 到底有没有成单一句话指向「我的报修」列表自己去数。
     *
     * 姓名和手机号是提交时现问 `getSjhForSjd` 的（H5 也是这么覆盖那两个框的），
     * 界面上不摆、本机不落盘 —— 少了这两个键这张单没有联系人，学校那边多半不认。
     */
    override suspend fun submit(values: Map<String, String>): Result<String> {
        val s = shape
            ?: return Result.failure(Exception("这张表的字典还没解出来，退回列表再进一次"))
        val bxlx = s.typeIds[values[KEY_TYPE].orEmpty()]
            ?: return Result.failure(Exception("故障类型没选上（选中的那条在本机过滤掉的名单里）"))
        val triple = s.placeIds[values[KEY_PLACE].orEmpty()]
            ?: return Result.failure(Exception("报修地点没选上"))
        val bits = triple.split("|")
        val xqdm = bits.getOrElse(0) { "" }
        val lyId = bits.getOrElse(1) { "" }
        val qyfjh = bits.getOrElse(2) { "" }
        if (xqdm.isBlank() || lyId.isBlank() || qyfjh.isBlank()) {
            // H5 那边是"三段没选齐就直接 return false + 提示"，我们照它拦，不发半张单过去
            return Result.failure(Exception("这一条地点校方只给到两段，学校要求校区·楼栋·楼层三段选齐 —— 换一条带楼层的"))
        }
        val addr = values[KEY_ADDR].orEmpty().trim()
        if (addr.isBlank()) return Result.failure(Exception("详细地址还没填"))
        val desc = values[KEY_DESC].orEmpty().trim()
        if (desc.isBlank()) return Result.failure(Exception("问题描述还没写"))
        val who = school.repairJson("getSjhForSjd").getOrNull()?.optJSONObject("data")
        val sjh = who?.optString("sjh").orEmpty()
        val xm = who?.optString("xm").orEmpty()
        if (sjh.isBlank() || xm.isBlank()) {
            return Result.failure(Exception("学校没回报修人的姓名和手机号（getSjhForSjd 那发是空的），这张单发过去会缺联系人"))
        }
        val fields = mutableListOf(
            "xqdm" to xqdm,
            "lyId" to lyId,
            "qyfjh" to qyfjh,
            "xxdz" to addr,
            "sjh" to sjh,
            "gzms" to desc,
            "xm" to xm,
            "userCode" to school.userCode(),
            "tpId" to "",                      // 没有照片就是这个空串，不是没填这个键
            "bxlx" to bxlx,
        )
        values[KEY_START]?.takeIf { it.isNotBlank() }?.let { fields += "kssj" to it }
        values[KEY_END]?.takeIf { it.isNotBlank() }?.let { fields += "jssj" to it }
        val root = school.repairJson("saveOrUpdateBxdForSjd", *fields.toTypedArray())
            .getOrElse { return Result.failure(it) }
        val no = root.optJSONObject("data")?.optString("bxdh").orEmpty()
        return Result.success(
            if (no.isNotBlank()) "学校说成了，单号 $no —— 回「我的报修」列表核对一下它真在里面"
            else "学校回了 flag:true，可没给单号。这一族的提交响应没有实样，" +
                "回「我的报修」列表数一数有没有多出这一张才算数",
        )
    }

    private data class Node(val name: String, val parent: String, val value: String)

    private suspend fun tree(method: String): List<Node> {
        val root = school.repairJson(method).getOrNull() ?: return emptyList()
        val arr = root.optJSONObject("data")?.optJSONArray("data") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                Node(it.optString("name"), it.optString("parent"), it.optString("value"))
            }
        }
    }

    /**
     * 地点叶子 = 有子节点的楼栋下面每条楼层 + 没有子节点的楼栋自己。
     * 值带的是**三段 id**（校区码 / 楼栋 id / 楼层 id），提交时正好对上 `xqdm`/`lyId`/`qyfjh`。
     */
    private fun placeLeaves(nodes: List<Node>): List<Pair<String, String>> {
        val campus = nodes.filter { it.parent == "0" }
        val buildings = nodes.filter { n -> campus.any { it.value == n.parent } }
        val out = ArrayList<Pair<String, String>>()
        buildings.forEach { b ->
            val floors = nodes.filter { it.parent == b.value }
            val code = campus.first { it.value == b.parent }.value
            if (floors.isEmpty()) {
                out.add("${b.name}" to "$code|${b.value}|")
            } else {
                floors.forEach { f -> out.add("${b.name} ${f.name}" to "$code|${b.value}|${f.value}") }
            }
        }
        return out
    }

    private suspend fun dictTimes(): List<String> {
        val root = school.repairJson("getDictListForSjd", "typeId" to TIME_TYPE_ID).getOrNull()
            ?: return emptyList()
        val arr = root.optJSONObject("data")?.optJSONArray("data") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.optString("label")?.takeIf { it.isNotBlank() }
        }
    }

    private companion object {
        /** JS 里写死的那个字典 id（`bxjs/8.*.js` @22286），换别的值服务端回空数组。 */
        const val TIME_TYPE_ID = "8aa867c76a2918b0016a2918ccbe0000"
        const val KEY_TYPE = "bxlx"
        const val KEY_PLACE = "bxdd"
        const val KEY_ADDR = "xxdz"
        const val KEY_START = "kssj"
        const val KEY_END = "jssj"
        const val KEY_DESC = "gzms"
    }
}
