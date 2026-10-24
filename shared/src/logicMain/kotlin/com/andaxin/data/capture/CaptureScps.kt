package com.andaxin.data.capture

import com.andaxin.core.store.SessionStore
import com.andaxin.data.MessageCategory
import com.andaxin.data.MessageFeed
import com.andaxin.data.MessageItem
import com.andaxin.data.NoticeBody
import com.andaxin.data.NoticeFeed
import com.andaxin.data.NoticeItem
import com.andaxin.data.TimetableFeed
import com.andaxin.data.TimetableSlot
import com.andaxin.data.WeekTable
import com.andaxin.core.json.JSONArray
import com.andaxin.core.json.JSONObject

/*
 * 这三个是 2026-10-19 那批抓包里证据最厚的三块，形状照实样来，一条都不猜。
 * 每条接口下面写的是我实际发过、并且回了真数据的那一次的参数。
 */

/**
 * 消息中心。
 *
 * 【取证】`POST zhxy-new-scps/msgCenter/mesDetailListNew.do`，
 * 业务参数 `currentPage=1&pageSize=10&sourceType=2` 之外就是身份那套；
 * 回 `data` 是分页壳（`totalRecords:29`、`totalPage:3`、`data:[]`）。
 * 单条字段：`id / title / content / url / contentTypeCode / sendDate("2026-10-19 07:19") /
 * yearMonth("2026-10") / readFlag("0"|"1") / pushType / hours / minutes`。
 * 两个坑：① `createDate` 是 Java `LocalDateTime` 序列化出来的**对象**，不是字符串，别去 parse 它，
 * 要时间看 `sendDate`；② 那句"我试过 `dealFlag=0` 不带 `sourceType`、回 0 条，所以 `sourceType` 必填"
 * **10-23 降级**：官方 App 自己那一发就是 `currentPage=1&pageSize=10&dealFlag=0`、也没带 `sourceType`，
 * 回的同样是 `flag:true` + `totalRecords:0` + 空数组。0 条只证明那个组合是空集，不证明必填。
 * `sourceType` 到底是必填还是换分类口径，没现打验过之前别再当结论用。
 * 顺带一条 `_userType`：这一发官方打的是 **2**，不是登录响应里那个 1；同口的只有
 * `checkServiceVersion`/`click`（"从服务入口进去那一侧"），App 外壳自己那 15 条口都是 1。
 *
 * 分类：10-20 现打 30 条全量定死了 —— **`contentTypeCode` 没有字典口**，`contentTypeName` 恒空串，
 * 那 28 条 `getSystemInfoDict.do` 管的是来源系统不是消息类型。所以只按有实样的短码分，
 * 判据和踩过的坑写在 `categorize` 那条注释上。
 */
class CaptureMessages(private val school: School, private val session: SessionStore) : MessageFeed {

    override suspend fun page(cursor: Int): Result<List<MessageItem>> {
        val r = school.post(
            "$SCPS/msgCenter/mesDetailListNew.do",
            "currentPage" to (cursor + 1).toString(),
            "pageSize" to PAGE,
            "sourceType" to SOURCE,
        )
        return r.mapCatching { root ->
            val arr = School.page(root)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                MessageItem(
                    id = o.optString("id"),
                    title = o.optString("title").ifBlank { o.optString("contentPrefix", "消息") },
                    brief = o.optString("content"),
                    sentAt = o.optString("sendDate"),
                    unread = o.optString("readFlag") == "0",
                    category = categorize(o.optString("contentTypeCode")),
                )
            }
        }
    }

    /**
     * 标已读。
     *
     * 【取证 · ① 我读到的官方前端代码】`in.aiit.edu.cn/app-portal` 的消息中心 chunk
     * （`lxtmp/axx/portal/chunk-2a2e09ea.a4b42477.js`）里两条声明挨着放：
     * ```
     * c = t => r.a.post("/msgCenter/batchReadPushMessage.do", t)
     * d = t => r.a.post("/msgCenter/readPushMessage.do",   t)
     * ```
     * 调用点在同一个文件的 `messageItem` 组件里，形状是**一个字段 `id`**：
     * ```
     * toRead(){ … this.$emit("show-dialog",{title:…, message:…}),
     *           "1" !== this.record.readFlag && d({ id: this.record.id })
     *                     .then(t => { this.record.readFlag = "1" }) }
     * ```
     * 也就是：点开一条消息 = 弹正文 + 顺手标已读，**服务端回成功后前端才把红点去掉** ——
     * 跟下面这条实现一个顺序。`readFlag` 是 "0"/"1" 字符串。
     *
     * 两条边界写清楚：
     * ① **这一发我自己还没现打过**（要真改服务端状态，得等他点，或者我拿一条真消息试一次再说）；
     * ② `batchReadPushMessage.do`（批量已阅）**声明有、这个 chunk 里没有任何调用点**，
     *    所以载荷里是什么字段完全没有依据 —— 那条不做，别哪天看见"批量已阅"四个字就照猜的填。
     *
     * 旧清单里那句"原生有 `msgCenter/read.do`"是**错的**：官方 APK 四个 dex 的字符串池里
     * `msgCenter/` 只有 `mesDetailListNew.do`、`notReadCount.do`、`readPushMessage.do` 三条，
     * 没有 `read.do`。
     */
    override suspend fun markRead(id: String): Result<Unit> {
        if (id.isBlank()) return Result.failure(Exception("这条消息没有 id，标不了"))
        return school.post("${School.SCPS}/msgCenter/readPushMessage.do", "id" to id).map {
            // 这一族的判成功口径跟列表一样看 flag；服务端没给 false 就算成了
            Unit
        }
    }

    /**
     * 分类：**只按有实样的短码分，不许再拿子串猜。**
     *
     * 原来这五条 `contains("ts")` / `contains("ty")` / `contains("jw")` / `contains("ks")`
     * 全部没有服务端依据，而且第一条是**真出过错的**：30 条实样里 27 条的短码是 `grrcts`，
     * 它结尾正好是 `ts`，于是那 27 条全被判成了"查寝"（10-20 现打 30 条全量去重对出来的，
     * 见 `lxtmp/契约_表单与角标现打.md` §3.2）。
     *
     * 现打定下来的三件事，别再去找第四条路：
     * ① `contentTypeCode` **没有任何字典口** —— 扫过 247 个 JS + 7 份 dex 的字符串池，
     *    命中处全是字段读取，没有一处带 `.do`；
     * ② 响应里 `contentTypeName` 这个键**存在但 30/30 是空串**（老那一代 feed 有值，
     *    `mesDetailListNew` 这条路不给），所以"直接读现成分类名"也不行；
     * ③ `msgCenter/getSystemInfoDict.do` 那条字典管的是**来源系统**（28 个 code，如
     *    `ywlz`/`swp`/`labour`），能当列表的 `systemCode` 筛选用（实测 `ywlz` 把 30 条筛成 2 条），
     *    但推送件的 `systemCode` 全是 `null`，逐条反查不了名字。
     *
     * 所以下面这张表**每加一条都要有实样**（一条消息 + 它的 title/content 形态）。
     * 现在只有 `grrcts` 这一条落得下去：27 条实样标题清一色「今日日程信息」，是课表日程，归学业。
     * `dysx`(2 条) / `xfsqshtg`(1 条) 语义没证据，一律归"系统"，别硬凑中文名。
     */
    private fun categorize(code: String): MessageCategory = when (code) {
        "grrcts" -> MessageCategory.Academic
        else -> MessageCategory.System
    }

    private companion object {
        const val SCPS = School.SCPS
        const val PAGE = "10"

        /** 抓包里给的就是 2；别的值没验过。 */
        const val SOURCE = "2"
    }
}

/**
 * 公告 / 资讯。
 *
 * 【取证】`news/getNewsTypeList.do` 回四条分类：`{typeNum, type}` ——
 * 学校要闻 / 学院动态 / 通知公告 / 学术信息（`typeNum` 是 `ff8080815ecc…` 这种 32 位十六进制）。
 * `news/getNewsList.do` 参数 `isActivity=0&type=<typeNum>&currentPage=1&pageSize=10`，
 * 回分页壳（`totalRecords:3430`），单条字段 `id / bt(标题) / image1..3 / urlList[] /
 * publishPerson / publishTime("2026-10-19 10:57:24") / content:null`。
 * 列表里 `content` 是 null —— 正文要另打 `news/getNewsDetail.do`，那条我只发了 `id` 一个参数、
 * 没验成，所以 detail 现在明确回失败。
 * 封面图是 `https://www.aiit.edu.cn/uploads/...jpg` 直链，能直接喂给 `Images`。
 */
class CaptureNotices(private val school: School) : NoticeFeed {

    override suspend fun list(): Result<List<NoticeItem>> {
        val typesR = school.post("${School.SCPS}/news/getNewsTypeList.do").mapCatching { School.array(it) }
        val types = typesR.getOrElse { return Result.failure(it) }
        // 一次只拉一条分类不够看：抓包里"学校要闻"那一类就有 3430 条，
        // 所以这里把每条分类各拉一页 10 条拼起来，界面上就是一次出的完整列表。
        val out = ArrayList<NoticeItem>()
        var firstError: Throwable? = null
        for (i in 0 until types.length()) {
            val t = types.getJSONObject(i)
            val num = t.optString("typeNum")
            if (num.isBlank()) continue
            val r = school.post(
                "${School.SCPS}/news/getNewsList.do",
                "isActivity" to "0",
                "type" to num,
                "currentPage" to "1",
                "pageSize" to "10",
            )
            if (r.isFailure) {
                firstError = firstError ?: r.exceptionOrNull()
                continue
            }
            val arr = School.page(r.getOrThrow())
            for (k in 0 until arr.length()) {
                val o = arr.getJSONObject(k)
                out += NoticeItem(
                    id = o.optString("id"),
                    title = o.optString("bt"),
                    publishedAt = o.optString("publishTime").substringBeforeLast(':'),
                    source = o.optString("publishPerson").ifBlank { t.optString("type") },
                    coverUrl = o.optString("image1").takeIf { it.startsWith("http") },
                )
            }
        }
        return if (out.isEmpty()) {
            // 一条都没拿到时，把服务器/网络给的第一句原话带出去，别只说"没拉到"
            Result.failure(Exception(firstError?.message ?: "分类是空的，校方没给任何一条公告"))
        } else {
            Result.success(out)
        }
    }

    /**
     * 公告正文。
     *
     * 【取证 · ② 10-20 上午现打到的】`POST ${SCPS}/news/getNewsDetail.do`，参数就一个 `id`（新闻 id），
     * 回 `{flag:true, data:{…21 键}}`，真用到的那几个：
     * `bt`(标题) `content`(**一整段 HTML**) `urlList`(图片直链数组) `publishPerson` `publishTime` `collect`。
     * 列表那一发里 `content` 是 null，正文只有这一发给。
     *
     * 两条绕过来的弯路记在这儿别再走：
     * ① 官方**原生包里没有 `getNewsDetail` 这个字样**（四个 dex 全扫过 0 命中）—— 详情是 H5 那一层做的，
     *    原生只是开 WebView 指向 `index.do#/headline/news/<id>`；
     * ② H5 的 bundle 里写的是 `/api/news/getNewsDetail.do`，但那个前缀从 PC 直发**404**
     *    （`/zhxy-new-scps/api/…` 全 404，`in.aiit.edu.cn/api/…` 是 nginx 静态层回 405），
     *    能通的只有原生那个不带 `/api` 的前缀。所以别照 H5 的字面量抄。
     *
     * `content` 是服务端拼的 HTML（`<p style='text-align: center;'>…<img src=…>`），
     * 仓里没有富文本依赖（离线只能解析缓存里已有的包），所以这里自己按块级标签切段、
     * 把标签和实体清掉，图片单独交给 `attachments` 让界面画 —— 比塞一个 WebView 进去轻得多。
     */
    override suspend fun detail(id: String): Result<NoticeBody> =
        school.post("${School.SCPS}/news/getNewsDetail.do", "id" to id).mapCatching { root ->
            val d = root.optJSONObject("data")
                ?: throw Exception(if (root.optBoolean("flag", false)) "服务端回了数据但没有 data 这一层" else "服务端没给正文")
            NoticeBody(
                title = d.optString("bt").ifBlank { d.optString("title") },
                publishedAt = listOf(
                    d.optString("publishTime").substringBeforeLast(':'),
                    d.optString("publishPerson"),
                ).filter { it.isNotBlank() }.joinToString(" · "),
                paragraphs = htmlParagraphs(d.optString("content").ifBlank { d.optString("contentExceptPic") }),
                attachments = imagesOf(d),
            )
        }

    /** 图片优先用服务端自己算好的 `urlList`，没有再退回从 HTML 里抠 `<img src>`。 */
    private fun imagesOf(d: com.andaxin.core.json.JSONObject): List<String> {
        val arr = d.optJSONArray("urlList")
        if (arr != null) {
            val direct = (0 until arr.length()).map { arr.optString(it) }.filter { it.startsWith("http") }
            if (direct.isNotEmpty()) return direct
        }
        return Regex("src=['\"]?([^'\"\\s>]+)").findAll(d.optString("content"))
            .map { it.groupValues[1] }.filter { it.startsWith("http") }.toList()
    }
}

/**
 * 把校方那段 HTML 切成自然段。
 *
 * 只在**块级标签边界**断行 —— 校方正文里 `<br>` 用得很随意，按 `<br>` 断会切出一堆一行两行的碎段，
 * 读起来像坏了。标签一律去掉，实体手工解几个常见的（`HtmlCompat` 能用但会把 `<img>` 的 alt 混进正文，
 * 我们图片走单独的键，不需要它）。
 */
internal fun htmlParagraphs(html: String): List<String> {
    if (html.isBlank()) return emptyList()
    return html
        .replace(Regex("(?i)</(p|div|li|h[1-6]|tr|table|section|blockquote)>"), "\n")
        .replace(Regex("(?i)<li[^>]*>"), "\n· ")
        .replace(Regex("(?s)<(script|style)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .lines()
        .map { Regex("\\s+").replace(it, " ").trim() }
        .filter { it.isNotEmpty() }
}

/**
 * 全周课表。
 *
 * 【取证】两条，都是 10-19 深夜拿自己账号真 token 直发打出来的：
 * ① `POST zhxy-csh/dict/selectZcList.do` → 根节点 `rows:[{text:"第8周",value:"8"},…]`，
 *    另外 `data:{week:"8", schoolTerm:"1", schoolYear:"2026"}` 直接给了"现在是哪一学年的第几学期第几周"。
 * ② `POST zhxy-csh/mobile/selectStuSelfTimeTable.do`，参数 `xn=2026&xq=1&zc=8`（外加身份那套），
 *    根节点 `rows` 是七天，每天 `{rq:"10/19", xq:1..7, kcVoList:[…]}`，
 *    单门课 `{id, kcmc(课名), ksjc(开始节次), jsjc(结束节次), kcdm, jxbh, jsmc(教室), sort, kclbdm, mc(课程类别)}`。
 *
 * 三个坑，都撞过：
 * ① 前缀必须是 `/zhxy-csh`。我按旧清单里的 `/zhxy-information` 打，回的是一坨登录页 HTML，
 *    所以那条"接口不通"的结论是错的，错在基址。
 * ② `zc` 不给 → `flag:false`；`xn`/`xq` 写法不对 → 直接 HTTP 500（不是 400，别以为是服务器挂了）。
 * ③ 每天那条 `kcVoList` 里都混着一条 `sort=0`、`kcmc` 为空的占位行，不滤掉界面上就多一格空课。
 *
 * ④ **同一天里同一门课会重复出现好几条，只有 `sort` 不一样** —— 这条是 10-20 上午现打撞出来的：
 * 拿自己账号那把活 token 打第 8 周，`rows` 里非空行 **24 条**，去掉重复只剩 **11 门**
 * （周一 2→1、周五 6→2；`lxtmp/timetable_raw.json` 是那一发的原样）。
 * 重复行的 `kcmc/ksjc/jsjc/jsmc/jxbh` 逐字相同，所以不是"同一门课上两遍"，是服务端自己发的重。
 * 不在这儿合掉的话：首页"今天几节课"直接翻倍（那天显示 6 节，其实 3 节），
 * 今日课程那一列表同一门课连着排两格 —— 周网格反倒看不出来，因为它每格只取第一条。
 *
 * 还有：**校方这份数据里没有教师**。`jsmc` 是教室名称不是老师（我一开始以为是老师），
 * 课名后面那个 `mc` 是课程类别（通识课/学科基础课）。所以真数据下教师那一行是空的，
 * 界面得自己让开，别拿课程类别冒充老师名字填进去。
 *
 * ⑤ **`ksjc=0, jsjc=0` 的条目不是课**：10-21 13:5x 他问"为什么课表里多了阳光晨跑"，
 * 我拿设备上那把活 token 现打第 8 周，周二/周四各有一条
 * `{kcmc:"阳光晨跑", ksjc:"0", jsjc:"0", jsmc:"体育场", sort:"0", kclbdm:""}` ——
 * 校方自己发的，不是我们编的。它是"这天早上有晨跑"的占位：没有节次、没有时间。
 * 以前 `periodCount` 按 `end-start+1` 兜到 1 节，界面上就变成周二周四 08:30 各多一块课，
 * 还进了首页"今天几节"和上课提醒。现在守住 0 节，周网格/首页/提醒都按 `startPeriod >= 1`
 * 判断"这是不是一节课"，课表顶上单独用一行"晨跑活动"标出它。
 */
class CaptureTimetable(private val school: School) : TimetableFeed {

    /**
     * 【取证 · ③】`POST zhxy-csh/dict/selectZcList.do` → 根节点 `rows:[{text:"第8周",value:"8"},…]`
     * 就是这一学期的全部周次，`data.week` 是"现在第几周"（首页那句"第 8 周"用的就是它）。
     * 界面上那一行**照这个排**，不自己数到 20 —— 学校给几周就排几格。
     */
    override suspend fun weeks(): WeekTable {
        val root = school.post("${School.CSH}/dict/selectZcList.do").getOrNull() ?: return WeekTable()
        val arr = root.optJSONArray("rows") ?: JSONArray()
        val all = (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.optString("value")?.toIntOrNull()
        }
        val now = root.optJSONObject("data")?.optString("week")?.toIntOrNull() ?: 0
        return WeekTable(if (all.isEmpty()) (1..20).toList() else all, now)
    }

    override suspend fun week(weekOffset: Int): Result<List<TimetableSlot>> {
        val dict = school.post("${School.CSH}/dict/selectZcList.do")
            .mapCatching { it.optJSONObject("data") ?: JSONObject() }
        if (dict.isFailure) return Result.failure(dict.exceptionOrNull()!!)
        val d = dict.getOrThrow()
        val year = d.optString("schoolYear").ifBlank { return Result.failure(Exception("周次表没给学年")) }
        val term = d.optString("schoolTerm").ifBlank { "1" }
        val now = d.optString("week").toIntOrNull() ?: 1
        val zc = (now + weekOffset).coerceIn(0, 20)

        return school
            .post("${School.CSH}/mobile/selectStuSelfTimeTable.do", "xn" to year, "xq" to term, "zc" to zc.toString())
            .mapCatching { root ->
                val days = root.optJSONArray("rows") ?: JSONArray()
                // 整周一天都没给 = 这一发没成，不是"这周没课"。
                // 10-21 凌晨装机撞上一次：课表屏写"这一周没有课"、首页写"今天有课 0 节"，
                // 同一时刻拿同一把 token 现打这一发回的是 7 天 20 节。当失败处理，
                // 上层（TimetableModel / DashboardSource）才留得住上一次的好数据、界面改口说"没拿到"。
                if (days.length() == 0) throw IllegalStateException("校方这周一天都没给")
                // 有天数、有课名，却一节都没解析出来 —— 那是节次字段变了形状，同样不许装成"没课"
                val named = (0 until days.length()).sumOf { i ->
                    val ks = days.optJSONObject(i)?.optJSONArray("kcVoList") ?: JSONArray()
                    (0 until ks.length()).count { (ks.optJSONObject(it)?.optString("kcmc") ?: "").isNotBlank() }
                }
                val slots = buildList {
                    for (i in 0 until days.length()) {
                        val day = days.getJSONObject(i)
                        val weekday = day.optInt("xq", i + 1)
                        val courses = day.optJSONArray("kcVoList") ?: JSONArray()
                        val seen = HashSet<Int>()
                        for (k in 0 until courses.length()) {
                            val c = courses.getJSONObject(k)
                            val name = c.optString("kcmc")
                            if (name.isBlank()) continue          // 每天那条 sort=0 的空占位行
                            val start = c.optString("ksjc").toIntOrNull() ?: continue
                            val end = c.optString("jsjc").toIntOrNull() ?: start
                            val room = c.optString("jsmc")
                            val klass = c.optString("jxbh").ifBlank { c.optString("kcdm") }
                            // 见上面第 ④ 条：同一天里除 sort 外全同的行只留第一条
                            if (!seen.add(hashOf(name, start, end, room, klass))) continue
                            add(
                                TimetableSlot(
                                    courseId = klass,
                                    course = name,
                                    teacher = "",                 // 校方这份数据里没有教师
                                    room = room,
                                    day = weekday,
                                    startPeriod = start,
                                    // `ksjc=jsjc=0` 是"这天有晨跑"的活动占位，不是一节课（见类注释第 ⑤ 条），
                                    // 记成 0 节；周网格/首页/上课提醒都按 `startPeriod >= 1` 当课。
                                    periodCount = if (start == 0 && end == 0) 0 else (end - start + 1).coerceAtLeast(1),
                                )
                            )
                        }
                    }
                }.sortedWith(compareBy({ it.day }, { it.startPeriod }, { -it.periodCount }))
                // 给了课名却一节都没解析出来 = 节次字段变了形状，同样不许装成"这周没课"
                if (named > 0 && slots.isEmpty()) throw IllegalStateException("校方给了 $named 节课，一节都没解析出来")
                slots
            }
    }

    /** 一条课的"身份"压成一个 int，只用来当天去重，不落盘、不外传。 */
    private fun hashOf(name: String, start: Int, end: Int, room: String, klass: String): Int =
        (((((name.hashCode() * 31 + start) * 31 + end) * 31 + room.hashCode()) * 31) + klass.hashCode())
}
