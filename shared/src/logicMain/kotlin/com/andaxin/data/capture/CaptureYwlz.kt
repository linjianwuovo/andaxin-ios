package com.andaxin.data.capture

import com.andaxin.data.brief.Action
import com.andaxin.data.brief.BriefDetail
import com.andaxin.data.brief.BriefRow
import com.andaxin.data.brief.DetailSource
import com.andaxin.data.brief.Flag
import com.andaxin.data.brief.ListSource
import com.andaxin.core.net.formUrlDecode
import com.andaxin.core.concurrency.nowMillis
import com.andaxin.core.concurrency.todayLocalDate
import com.andaxin.core.concurrency.minusMonthsCompat
import kotlinx.coroutines.async
import com.andaxin.core.json.JSONArray
import com.andaxin.core.json.JSONObject

/*
 * 业务流转（ywlz）那一套：待办事务、请假读链、销假，以及查寝。
 *
 * 为什么查寝也在这个文件里：查寝（晚点名）在学生这侧根本不是一个独立接口，
 * 它就是待办列表里 `msgType=2` 的那批条目 —— 10-19 23:12 现打的 21 条全是它，
 * 每条的 `url` 里带着 `dateId/taskId/id/viewTitle`（"♥ 数媒 ♥ 视传 晚点名"）。
 * 另一条 `kq.aiit.edu.cn/studentMobile/queryNewActivity.do` 我也打了：
 * 旧抓包 23 条加我这次一条，回的都是同一个 `activityName:"测试活动"`，
 * 拿它当查寝就是拿测试数据冒充，所以不用。
 *
 * 取证等级（本仓规矩：①我读的代码 ②我自己发的请求 ③目标 App 发出被我抓到的报文）：
 * - getRunTaskByUserCode：③（xj.flow 两条）+ ②（10-19 现打，21 条）
 * - getYwdtCount：③（44 条实样）+ ②（10-19 现打，redDot 四个计数）
 * - getApprovedInfoByExeId / getForm / getXjxx：③（各 1 条实样）
 * - agree.do（销假提交）：③ 有**请求**实样（xj2.flow 第 3 次，真 App 发的那次销假），
 *   **响应没有实样**（当时被本地插件拦了，回的是 capture-blocked）。所以提交这一侧判成功
 *   只能按这一族信封的惯例看 `flag`，代码里和界面上都把这句话带给用户，不含糊过去。
 */

/** 待办列表里的一条。详情要用的字段都原样留着，不做加工。 */
data class YwlzTask(
    val nodeId: String,
    val executionId: String,
    val title: String,
    val category: String,
    val creator: String,
    val status: String,
    val statusTxt: String,
    val receiveTime: String,
    val msgType: String,
    val url: String,
    /**
     * 「我的申请」那一发才有的三个标记：附件图、能不能催办、能不能撤回。待办那一发里没这几个键，给默认值。
     *
     * 【10-23 更正】第三个以前叫 `withdrawn`、界面上写成"已撤回" —— 那句是我的猜。
     * 键是 `isWithdraw`，他旧仓那条已经跑通的列表把它当**可撤回**用（`withdrawable = isWithdraw == "1"`），
     * 而我们现存实样两条都是 `isWithdraw="0"`、`statusTxt` 都是"已完结"，两种读法在那两条上根本分不出来。
     * 所以按他验证过的那份读，并且**不再在屏上写"已撤回"** —— 状态一律看 `statusTxt`。
     */
    val attachmentUrl: String = "",
    val urgeable: Boolean = false,
    val withdrawable: Boolean = false,
) {
    /** 详情按哪条主键找：待办类有节点 id 就用节点 id，「我的申请」那种只有流程 id。 */
    val key: String get() = nodeId.ifBlank { executionId }

    /** `msgType=2` 就是查寝/消息类；`1` 才是流程类（有 executionId 能查审批链）。 */
    val isBedCheck: Boolean get() = msgType == "2"

    /** 查寝那批的 `url` 里带的四个参数，别的条目没有。 */
    val query: Map<String, String> get() = parseQuery(url)

    /** 查寝的真标题在 url 的 `viewTitle` 里，列表上那个 `flowTitle` 只是"签到时间到达"。 */
    val bedTitle: String get() = query["viewTitle"]?.trim().orEmpty()
}

/** `url` 是 `…/#/路由?a=b&c=d` 这种，问号在井号后面，标准解析器切不出来，自己切。 */
internal fun parseQuery(url: String): Map<String, String> {
    val q = url.substringAfter('?', "")
    if (q.isBlank()) return emptyMap()
    return q.split('&').mapNotNull { kv ->
        val i = kv.indexOf('=')
        if (i <= 0) return@mapNotNull null
        val v = kv.substring(i + 1)
        kv.substring(0, i) to runCatching { formUrlDecode(v) }.getOrDefault(v)
    }.toMap()
}

/**
 * ywlz 待办条目的取数口。待办和查寝两屏共用这一份，60 秒内不重复发。
 *
 * 【取证】`POST ywlz.aiit.edu.cn/zhxy-bfc/mobile/flowRuTask/getRunTaskByUserCode.do`，
 * H5 那套身份（见 `School.postH5`，用原生那套会被回"非法访问"），
 * 参数 `approvedType=1&currentPageNo=1&pageSize=10`；数组挂**根节点 `rows`**。
 *
 * 两个坑，都是我现打看见的：
 * ① `total` 不能当总数用 —— 这次 `rows` 21 条而 `total=0`（它只数流程类，消息类不计入），
 *    所以官方前端那句 `rows.length >= total` 判停在这儿是不成立的，别照抄。
 * ② 一次拉完比逐页拉好：`pageSize` 给 50 服务端照给（我试过，21 条一次回全），
 *    界面上也就不用先出一半再蹦一半。
 */
class YwlzTasks(private val school: School) {

    private var cached: List<YwlzTask>? = null
    private var cachedAt = 0L

    suspend fun all(): Result<List<YwlzTask>> {
        val hit = cached
        if (hit != null && nowMillis() - cachedAt < TTL) return Result.success(hit)
        return school.postH5(
            "$YWLZ/mobile/flowRuTask/getRunTaskByUserCode.do",
            "approvedType" to "1",
            "currentPageNo" to "1",
            "pageSize" to PAGE,
        ).mapCatching { root ->
            val arr = School.rows(root)
            (0 until arr.length()).mapNotNull { i -> parse(arr.optJSONObject(i)) }
        }.also { r ->
            r.getOrNull()?.let {
                cached = it
                cachedAt = nowMillis()
            }
        }
    }

    /**
     * 待办红点计数。**这条现在没人用，也别接回去** —— 10-20 现打把它定死了：
     * `getYwdtCount.do` 零业务参数，`approvedType` 收了完全忽略（无参 / 1 / 2 / 0 / 99 / 带分页
     * 六次响应**逐字节相同 171 B**），键是 `data.redDot{runCount, hiCount, myExeCount, dyCount, url}`。
     * 问题是同一把会话下 `getRunTaskByUserCode` 回 21 条、`myExecution` 的 total 回 2，
     * 而 redDot 四个数**全是 0** —— 两边不是一个口径。
     * 所以角标要用哪个 tab 的数，就去数那个 tab 自己那条列表口的 total，别拿这条当权威。
     * 出处：`lxtmp/契约_表单与角标现打.md` §2。
     */
    @Suppress("unused")
    suspend fun counts(): RedDot? =
        school.postH5("$YWLZ/mobile/flowRuTask/getYwdtCount.do")
            .getOrNull()
            ?.optJSONObject("data")
            ?.optJSONObject("redDot")
            ?.let {
                RedDot(
                    mine = it.optInt("myExeCount"),
                    toRead = it.optInt("dyCount"),
                    copied = it.optInt("hiCount"),
                    todo = it.optInt("runCount"),
                )
            }

    /**
     * 「待阅」那一栏 = 同一条列表口、`approvedType=2`。
     *
     * 【取证 · ③】10-13 从他旧仓那次抓包里定死的口径：`approvedType` 是 **tab 号**不是类型号
     * （H5 的 `activeName` 初值 "1"，`onLoad` 直接把它当参数发出去）—— 待处理=1、待阅=2。
     * 以前我们「待我处理」发的是 2，等于一直在显示抄送件，改回 1 之后待办立刻出真数据。
     * 这一栏的请求形状和 [all] 完全同一条口，只是那个 tab 号不同，所以条目字段按 [parse] 那一套读。
     */
    suspend fun toRead(): Result<List<YwlzTask>> =
        cached("read") {
            school.postH5(
                "$YWLZ/mobile/flowRuTask/getRunTaskByUserCode.do",
                "approvedType" to "2",
                "currentPageNo" to "1",
                "pageSize" to PAGE,
            ).mapCatching { root ->
                val arr = School.rows(root)
                (0 until arr.length()).mapNotNull { i -> parse(arr.optJSONObject(i)) }
            }
        }

    /** 待办 / 待阅两条口共用的条目解析（同一条 endpoint、只差 `approvedType`）。 */
    private fun parse(o: JSONObject?): YwlzTask? {
        if (o == null) return null
        val node = o.optString("ruTaskNodeId")
        if (node.isBlank()) return null
        return YwlzTask(
            nodeId = node,
            executionId = o.optString("executionId"),
            title = o.optString("flowTitle"),
            category = o.optString("categoryName"),
            creator = o.optString("createUser"),
            status = o.optString("status"),
            statusTxt = o.optString("statusTxt"),
            receiveTime = o.optString("receiveTime"),
            msgType = o.optString("msgType"),
            url = o.optString("url"),
        )
    }

    /**
     * 「我的申请」= 我自己发起过的流程。
     *
     * 【取证 · ③ 真 App 发出、我们抓到的那条】`POST /mobile/flowRuTask/myExecution.do`，
     * 请求侧**四个业务参数**（`lxtmp/_shape7.txt` 里那一条唯一的形状）：
     * `currentPageNo=1&pageSize=10&timeType=1&status=3` —— 没有 title、没有日期窗，
     * 比已办那一发少两个键。数组挂根节点 `rows`（实测 2 条，`total` 也回 2）。
     * 条目字段和待办那一发**不完全一样**，这三处必须照它自己的形状读：
     * ① **没有 `ruTaskNodeId`**（null），主键是 `executionId`，另有一个 `dbNodeId`；
     * ② 时间叫 `createDate`（"2026-10-13 20:10:55"）而不是 `receiveTime`；
     * ③ 多了 `fileUrl`（附件图直链）、`isWithdraw`（"0"）、`urgeButton`（"0"）、`urgeTotal`。
     * `status=3` 这发是唯一有实样的档位（回的两条 `statusTxt` 都是"已完结"）。
     * **其它状态值服务端接受什么、返回什么，我们一条实样都没有**，所以这里不猜、不做"全部状态"那个 tab：
     * 这一屏就是"我发起的、已完结的"，在办的那些本来就在待办那一屏。
     */
    suspend fun mine(): Result<List<YwlzTask>> =
        cached("mine") {
            school.postH5(
                "$YWLZ/mobile/flowRuTask/myExecution.do",
                "currentPageNo" to "1",
                "pageSize" to PAGE,
                "timeType" to "1",
                "status" to "3",
            ).mapCatching { root ->
                val arr = School.rows(root)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val exe = o.optString("executionId")
                    if (exe.isBlank()) return@mapNotNull null
                    YwlzTask(
                        nodeId = "",                       // 这一族真没有这个键，别拿 dbNodeId 冒充
                        executionId = exe,
                        title = o.optString("flowTitle"),
                        category = o.optString("categoryName"),
                        creator = o.optString("createUserName").ifBlank { o.optString("createUser") },
                        status = o.optString("status"),
                        statusTxt = o.optString("statusTxt"),
                        receiveTime = o.optString("createDate"),
                        msgType = o.optString("msgType").ifBlank { "1" },
                        url = o.optString("url"),
                        attachmentUrl = o.optString("fileUrl"),
                        urgeable = o.optString("urgeButton") == "1",
                        withdrawable = o.optString("isWithdraw") == "1",
                    )
                }
            }
        }

    /**
     * 「已办」= 我已经处理完的（含别人批完抄送给我的）。
     *
     * 【取证 · ③】`POST /mobile/flowRuTask/getHiTaskByUserCode.do`，请求侧比 myExecution 多三个键：
     * `currentPageNo=1&pageSize=10&timeType=1&status=3&title=&beginTime=<三个月前>&endTime=<今天>`。
     * 那个日期窗是实样里现成的（抓到的那一次 beginTime 正好是 endTime 减三个整月），
     * 所以我按同样的规则算，而不是写死一个日子。
     *
     * **条目的键名没有实样**：现存抓包里这一发命中的每一次 `rows` 都是 0 条
     * （`_shape4.md` 数过：命中 2 次、都是空数组）。所以 `parse` 走的是候选键，
     * 顺序按"待办那一族叫什么"来猜的：`ruTaskNodeId` → `executionId` → `hiTaskId`。
     * 界面上这一栏回空时说的是"这一发回的是空列表"，不是"没有已办"，
     * 因为条目形状本身还没被一条真数据证实过。
     */
    suspend fun history(): Result<List<YwlzTask>> =
        cached("hi") {
            val today = todayLocalDate()
            school.postH5(
                "$YWLZ/mobile/flowRuTask/getHiTaskByUserCode.do",
                "currentPageNo" to "1",
                "pageSize" to PAGE,
                "timeType" to "1",
                "status" to "3",
                "title" to "",
                "beginTime" to today.minusMonthsCompat(3).toString(),
                "endTime" to today.toString(),
            ).mapCatching { root ->
                val arr = School.rows(root)
                (0 until arr.length()).mapNotNull { i -> parseLoose(arr.optJSONObject(i)) }
            }
        }

    /**
     * 这一环校方给了哪些按钮。
     *
     * 【取证 · ③ 请求与响应都有实样】`POST /mobile/process/getButtons.do`，
     * 参数 `executionId` + `ruTaskNodeId`（两个都是 18~19 位的数字串），
     * 回根节点 `rows[]`，每条就两个键：`{label:"抄送", value:"cc"}`。
     * 这是**唯一一条有实样的"按钮门禁"证据**：所以这一发的用处是把校方给的按钮**念出来**，
     * 让我们知道这一环到底能点什么；`value` 对应的提交口一个都没抓到实样，
     * 所以那些口一律不接 —— 界面上看到的是"校方说这一步能抄送"，而不是我们能抄送。
     */
    suspend fun buttons(executionId: String, nodeId: String): List<Pair<String, String>> {
        if (executionId.isBlank()) return emptyList()
        val root = school.postH5(
            "$YWLZ/mobile/process/getButtons.do",
            "executionId" to executionId,
            "ruTaskNodeId" to nodeId,
        ).getOrNull() ?: return emptyList()
        val arr = School.rows(root)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val label = o.optString("label")
            if (label.isBlank()) return@mapNotNull null
            label to o.optString("value")
        }
    }

    /**
     * 三个列表都过一遍，详情按 id 找条目用。
     *
     * 先查"刚从哪一栏拉回来的"那一栏：从列表点进详情，那一栏的 60 秒缓存一定是热的，
     * 一发网络都不用。原来不管三七二十一三栏一起重拉，装机量过：
     * 进一次详情光找这一条就 5 秒（待办那一发学校那边给到 4.5 秒）。
     */
    suspend fun findAny(id: String): YwlzTask? {
        if (id.isBlank()) return null
        lastLoaded?.let { name ->
            caches[name]?.first?.firstOrNull { it.key == id }?.let { return it }
        }
        val pools = kotlinx.coroutines.coroutineScope {
            listOf(async { all() }, async { mine() }, async { history() })
                .map { it.await().getOrNull() }
        }
        return pools.asSequence().filterNotNull().flatten().firstOrNull { it.key == id }
    }

    /** 每个 tab 各有一份 60 秒缓存，key 是 tab 名。 */
    private val caches = HashMap<String, Pair<List<YwlzTask>, Long>>()

    /** 最近一次真从服务端拉回来的那一栏（缓存命中不算——命中说明那份本来就热）。 */
    private var lastLoaded: String? = null

    private suspend fun cached(name: String, fetch: suspend () -> Result<List<YwlzTask>>): Result<List<YwlzTask>> {
        caches[name]?.let { (list, at) ->
            if (nowMillis() - at < TTL) return Result.success(list)
        }
        val r = fetch()
        r.getOrNull()?.let {
            caches[name] = it to nowMillis()
            lastLoaded = name
        }
        return r
    }

    /** [history] 那种"节点 id 可能根本没有"的条目：主键按候选键轮一遍。 */
    private fun parseLoose(o: JSONObject?): YwlzTask? {
        if (o == null) return null
        val node = listOf("ruTaskNodeId", "hiTaskId").asSequence()
            .map { o.optString(it) }.firstOrNull { it.isNotBlank() }.orEmpty()
        val exe = o.optString("executionId")
        if (node.isBlank() && exe.isBlank()) return null
        return YwlzTask(
            nodeId = node,
            executionId = exe,
            title = o.optString("flowTitle").ifBlank { o.optString("title") },
            category = o.optString("categoryName"),
            creator = o.optString("createUserName").ifBlank { o.optString("createUser") },
            status = o.optString("status"),
            statusTxt = o.optString("statusTxt"),
            receiveTime = o.optString("receiveTime").ifBlank { o.optString("complateTime") }
                .ifBlank { o.optString("createDate") },
            msgType = o.optString("msgType"),
            url = o.optString("url"),
            attachmentUrl = o.optString("fileUrl"),
            urgeable = o.optString("urgeButton") == "1",
            withdrawable = o.optString("isWithdraw") == "1",
        )
    }

    private companion object {
        const val YWLZ = School.YWLZ
        const val PAGE = "50"
        const val TTL = 60_000L
    }
}

/** 待办红点四个计数，字段名照实样。 */
data class RedDot(val mine: Int, val toRead: Int, val copied: Int, val todo: Int)

/** 待办事务列表：校方给什么就摆什么，现在这 21 条全是查寝消息，也照实摆。 */
class CaptureAffairs(private val tasks: YwlzTasks) : ListSource {

    internal val byId = HashMap<String, YwlzTask>()

    override suspend fun rows(): Result<List<BriefRow>> = tasks.all().mapCatching { list ->
        list.map { a ->
            byId[a.nodeId] = a
            BriefRow(
                id = a.nodeId,
                title = a.title.ifBlank { "（校方没给标题）" },
                sub = listOf(a.creator, a.receiveTime).filter { it.isNotBlank() }.joinToString(" · "),
                right = a.statusTxt,
                // status=5 是"待销假"，该动了；实样里见过 2=审批中、5=待销假两种
                flag = if (a.status == "5") Flag.Warn else Flag.None,
            )
        }
    }
}

/**
 * 「待阅」那一栏（`approvedType=2`）。顶上多一颗「全部标已阅」，走 `flowRuTask/mobileReadBulk.do`。
 *
 * 【取证 · ① + 他旧仓实证】body 是 `ids=<这一栏所有 ruTaskNodeId 逗号拼>&approvedType="2"` 加身份五件套。
 * 这条口我们一次没发过成功报文，形状来自两处：读它 H5 的 `markAllRead()` 调用点，
 * 以及他自己项目里 10-13 接好的那版（同一串参数，跑通过）。
 * 所以这一颗键照旧规矩：**连按两次、不自动重试**，服务端那句 `flag` 是不是真代表"已阅生效"没有实样，
 * 按完界面刷新列表自己数，别看返回里说了什么。
 */
class CaptureToRead(
    private val school: School,
    private val tasks: YwlzTasks,
) : ListSource {

    internal val byId = HashMap<String, YwlzTask>()

    override suspend fun rows(): Result<List<BriefRow>> = tasks.toRead().mapCatching { list ->
        list.map { a ->
            byId[a.nodeId] = a
            BriefRow(
                id = a.nodeId,
                title = a.title.ifBlank { "（校方没给标题）" },
                sub = listOf(a.creator, a.receiveTime).filter { it.isNotBlank() }.joinToString(" · "),
                right = a.statusTxt,
                flag = Flag.Unread,
            )
        }
    }

    override fun bulkLabel(): String? = if (byId.isEmpty()) null else "全部标已阅"

    override suspend fun bulk(): Result<String> {
        val ids = byId.keys.filter { it.isNotBlank() }.joinToString(",")
        if (ids.isBlank()) return Result.failure(Exception("这一栏现在没有可标记的条目"))
        return school.postH5(
            "${School.YWLZ}/mobile/flowRuTask/mobileReadBulk.do",
            "ids" to ids,
            "approvedType" to "2",
        ).map { "学校回了 flag:true；这一栏重新拉一遍，剩下的才是真没阅的" }
    }
}

/** 查寝列表：同一份数据里挑 `msgType=2` 的，标题用 url 里的 viewTitle（真名"晚点名"）。 */
class CaptureDorm(private val tasks: YwlzTasks) : ListSource {
    internal val byId = HashMap<String, YwlzTask>()

    override suspend fun rows(): Result<List<BriefRow>> = tasks.all().mapCatching { list ->
        list.filter { it.isBedCheck }.map { a ->
            byId[a.nodeId] = a
            BriefRow(
                id = a.nodeId,
                title = a.bedTitle.ifBlank { a.title.ifBlank { "查寝" } },
                sub = a.receiveTime,
                right = a.statusTxt,
                flag = if (a.status == "5") Flag.Warn else Flag.None,
            )
        }
    }
}

/**
 * 「我的申请」那一栏。行的主键用的是 `executionId` ——
 * 这一族没有 `ruTaskNodeId`（见 [YwlzTasks.mine] 的取证），拿它当 id 详情就找不到条。
 * 详情走的是和待办同一个 [CaptureAffairDetail]：审批链那发只要 executionId 就能打。
 */
class CaptureMyAffairs(private val tasks: YwlzTasks) : ListSource {
    override suspend fun rows(): Result<List<BriefRow>> = tasks.mine().mapCatching { list ->
        list.map { a ->
            BriefRow(
                id = a.key,
                title = a.title.ifBlank { "（校方没给标题）" },
                sub = listOf(a.category, a.receiveTime).filter { it.isNotBlank() }.joinToString(" · "),
                right = a.statusTxt,
                flag = when {
                    a.urgeable -> Flag.Warn
                    else -> Flag.None
                },
            )
        }
    }
}

/**
 * 「已办」那一栏。
 *
 * 现存抓包里这一发每次都是空数组，所以**列表本来就是空的**是常态；
 * 空的时候界面上说的是"这一发回的是空列表（条目形状还没有一条真数据证实过）"，
 * 不写"你没有任何已办" —— 那句话我们没证据说。
 */
class CaptureHandled(private val tasks: YwlzTasks) : ListSource {
    override suspend fun rows(): Result<List<BriefRow>> = tasks.history().mapCatching { list ->
        list.map { a ->
            BriefRow(
                id = a.key,
                title = a.title.ifBlank { "（校方没给标题）" },
                sub = listOf(a.creator, a.receiveTime).filter { it.isNotBlank() }.joinToString(" · "),
                right = a.statusTxt,
                flag = Flag.None,
            )
        }
    }
}

/**
 * 查寝详情。
 *
 * 【取证】等级①（我读的前端代码）：`fdygl.aiit.edu.cn/swp-app/js/chunk-464b727d.8a22efb7.js`
 * 里 `bedCheckDetail` 组件 `mounted()` 只 await 一条请求 ——
 * `POST https://fdygl.aiit.edu.cn/app/dorm/getTaskInfoByDateId`，JSON 体 `{"dateId":<路由 query 的 dateId>}`，
 * 身份在**请求头**里（accessToken/userCode/userType/xh/role/roleId）。
 * `dateId` 就是从待办那条 `url` 里拆出来的（实样 `dateId=2111849534790336514`）。
 * 响应挂 `data`，字段名逐个来自组件模板：`title taskDate status signinMode signStatus
 * signinPlaceName signinDate collectionStartTime collectionEndTime lateReturnTime replenish
 * replenishDate requirement createName createOrgName signinPhotoName`。
 *
 * **这条 10-20 00:48 现打过了**（取证升到②）：按前端原文给 `role`/`roleId` 传空串，服务端照回
 * `flag:true` + 63 个非空字段 —— 2026-10-19 那场：定位签到、采集窗口 21:00–23:59、已签到 22:01、
 * 地点"新建的签到地点"、签到要求四条、创建人<教师姓名> / <学院名>、签到照片一张。
 * 所以学生端那两个头确实是空串，不用再猜；`code=203` 之外还有没有别的失效码，仍然没见过。
 * 哪天打不通就退回把待办里已有的真字段摊开，再把服务端原话附在后面，不装成功。
 */
class CaptureDormDetail(
    private val school: School,
    private val tasks: YwlzTasks,
    private val list: CaptureDorm,
) : DetailSource {

    override suspend fun detail(id: String): Result<BriefDetail> {
        val a = list.byId[id] ?: tasks.all().getOrNull()?.firstOrNull { it.nodeId == id }
            ?: return Result.failure(Exception("这一条不在查寝列表里，回列表重进一次"))
        return dormDetailOf(school, a)
    }
}

/**
 * 查寝详情：从待办那条 `url` 里拆出 `dateId` → 打 fdygl → 映射成人话。
 * 待办那一侧点进查寝类的一条也走这儿：两边看到同一份真数据，不是一个给 ID、一个给字段。
 * 打不通就退回 [bedDetail]（列表里已有的真字段 + 服务端原话），不装成功。
 */
internal suspend fun dormDetailOf(school: School, a: YwlzTask): Result<BriefDetail> {
    val dateId = a.query["dateId"].orEmpty()
    if (dateId.isBlank()) return Result.success(bedDetail(a))
    return dormDetailByDateId(school, dateId, bedDetail(a))
}

/**
 * 查寝详情按 `dateId` 直接打。
 *
 * 拆出来是因为新那屏（`CaptureDormBoard`）的任务条目**天生就有 dateId** ——
 * `pageStudentSignIn` 那条 19 键里的 `id` 就是它（10-20 晚现打验过：拿这个 id 打
 * `getTaskInfoByDateId` 回 113 个键、22KB 数据）。老的调用方是从待办那条 url 里拆 dateId，
 * 两条路最后都汇到这里，字段映射只有一份。
 */
suspend fun dormDetailByDateId(school: School, dateId: String, fallback: BriefDetail): Result<BriefDetail> {
    if (dateId.isBlank()) return Result.success(fallback)

    val r = school.postJson(
        "${School.FDYGL}/app/dorm/getTaskInfoByDateId",
        JSONObject().put("dateId", dateId),
    )
    if (r.isFailure) {
        return Result.success(
            fallback.copy(
                paragraphs = fallback.paragraphs +
                    ("详情没取到（${r.exceptionOrNull()?.message ?: "网络不通"}），下面这几行是列表里已有的。"),
            )
        )
    }
    val d = r.getOrThrow().optJSONObject("data") ?: return Result.success(fallback)

    val fields = buildList {
        keep(d, "查寝日期", "taskDate")
        add("现在什么状态" to bedStatusOf(d.optString("status")))
        add("签到了没" to if (d.optString("signStatus") == "1") "已签到" else "还没签到")
        keep(d, "签到地点", "signinPlaceName")
        keep(d, "签到时间", "signinDate")
        add("签到方式" to signinModeOf(d.optString("signinMode")))
        keep(d, "采集开始", "collectionStartTime")
        keep(d, "采集结束", "collectionEndTime")
        keep(d, "开始", "startTimes")
        keep(d, "结束", "endTimes")
        keep(d, "晚归阈值(分钟)", "lateReturnTime")
        add("能不能补签" to yesNo(d.optString("replenish")))
        keep(d, "补签截止", "replenishDate")
        keep(d, "签到要求", "requirement")
        keep(d, "发起人", "createName")
        keep(d, "发起组织", "createOrgName")
        keep(d, "签到照片", "signinPhotoName")
        add("日期 ID" to dateId)
    }
    return Result.success(
        BriefDetail(
            title = d.optString("title").ifBlank { fallback.title },
            fields = fields,
            paragraphs = listOf(
                "要签到得回学校的 App —— 那一步要定位、拍照或扫码。",
            ),
        )
    )
}

/** 状态码含义来自前端组件里的判断分支（`"0"`=进行中、`"2"`=未到签到时间）。 */
private fun bedStatusOf(v: String) = when (v) {
    "0" -> "进行中"
    "2" -> "还没到签到时间"
    "" -> "—"
    else -> v
}

private fun signinModeOf(v: String) = when (v) {
    "1" -> "定位签到"
    "2" -> "扫码签到"
    "" -> "—"
    else -> v
}

/** 这一族的布尔开关是给机器看的 0/1（实样里 `replenish:"0"`），界面上别说人话以外的那种。 */
private fun yesNo(v: String) = when (v) {
    "0" -> "不允许"
    "1" -> "允许"
    "" -> "—"
    else -> v
}

/** 空值三种写法（没这个键 / null / 空串）都当"没有"，不摆空行糊弄人。 */
private fun MutableList<Pair<String, String>>.keep(o: JSONObject?, label: String, key: String) {
    if (o == null || !o.has(key) || o.isNull(key)) return
    val v = o.optString(key)
    if (v.isBlank() || v == "null") return
    add(label to v)
}

/**
 * 同一个意思给单个键用：`optString` 对 JSON 的 `null` 会回出**字面 "null" 四个字符**，
 * 装机读审批链就看到「抄送 · 周建 · 未阅 · null」这种行。空、null、"null" 一律当没有。
 */
private fun JSONObject.real(key: String): String =
    if (!has(key) || isNull(key)) "" else optString(key).trim().takeIf { it != "null" }.orEmpty()

internal fun bedDetail(a: YwlzTask): BriefDetail {
    val p = a.query
    return BriefDetail(
        title = a.bedTitle.ifBlank { a.title.ifBlank { "查寝" } },
        fields = buildList {
            add("收到时间" to a.receiveTime.ifBlank { "—" })
            if (a.creator.isNotBlank()) add("发起人" to a.creator)
            add("状态" to a.statusTxt.ifBlank { a.status })
            p["dateId"]?.let { add("日期 ID" to it) }
            p["taskId"]?.let { add("任务 ID" to it) }
            p["id"]?.let { add("记录 ID" to it) }
        },
        paragraphs = listOf(
            "要签到得回学校的 App —— 那一步要定位、拍照或扫码。",
        ),
    )
}

/**
 * 待办详情。
 *
 * 流程类（有 `executionId`）走真接口：
 * ① `flowRuTask/getApprovedInfoByExeId.do`（`executionId` + `_t` 毫秒时间戳）→ 根节点 `rows`，
 *    一行一个审批环节，按 `sortNum` 排：`{approvedTypeMsg 发起/审批/抄送/待销假,
 *    statusMsg 发起申请/已同意/未阅/待销假, userName 经手人, receiveTime,
 *    complateTime(原文就少个 e), operType 2=已操作, xjStatus}`。
 * ② `process/getForm.do`（`executionId&type=1&ruTaskNodeId`）→ `data` 里
 *    `flowName / isDealDone(0可办 1已被审核 2已被撤回) / stuQjsq{请假业务行} / dataJson(已填数据回显)`。
 * ③ 待销假的单子再补一条 `process/getXjxx.do`（参数名是 `id`，值就是 executionId）→
 *    `data{isCj 是否超假, cjSc 距截止余量小时数(负=还没超), cjStartTimeStr 假止, cjEndTimeStr 服务端此刻}`。
 *
 * 查寝类（`msgType=2`，没有 executionId）校方没给详情接口，只有一个 fdygl 的 H5 链接，
 * 那就走 [bedDetail]：把已有的真字段摊开，并直说哪一段没接。
 */
class CaptureAffairDetail(
    private val school: School,
    private val tasks: YwlzTasks,
    private val list: CaptureAffairs,
) : DetailSource {

    /** 销假要原样带回的 `dataJson`（getForm 给的），进程内留着，不落盘。 */
    private val dataJsonById = HashMap<String, String>()
    private val affordById = HashMap<String, YwlzTask>()

    override suspend fun detail(id: String): Result<BriefDetail> {
        // 三个 tab（待办 / 我的申请 / 已办）都进这一个详情：先按各自主键找，找不到再退回待办那份缓存
        val a = tasks.findAny(id) ?: list.byId[id]
            ?: return Result.failure(Exception("这一条不在待办、我的申请、已办这三栏里，回列表重进一次"))
        affordById[id] = a
        // 查寝类的那一条不摆三个 ID 交差：走和查寝那屏同一个详情，同一份真数据
        if (a.isBedCheck || a.executionId.isBlank()) return dormDetailOf(school, a)

        val exe = a.executionId
        // 审批链和表单这两发互不依赖 —— 串着发过一次 30 秒才出画面，并着发同一份数据 6 秒
        val (chainR, form, offered) = kotlinx.coroutines.coroutineScope {
            val chain = async {
                school.postH5(
                    "${School.YWLZ}/mobile/flowRuTask/getApprovedInfoByExeId.do",
                    "executionId" to exe,
                    "_t" to nowMillis().toString(),
                )
            }
            val formA = async {
                school.postH5Timed(
                    "${School.YWLZ}/mobile/process/getForm.do",
                    8_000,                     // 这一发校方现在给到 46 秒；等 8 秒拿不到就先出画面
                    "executionId" to exe,
                    "type" to "1",
                    "ruTaskNodeId" to a.nodeId,
                ).getOrNull()?.optJSONObject("data")
            }
            val btn = async {
                // 「这一环校方给不给按钮」唯一一条有实样的证据：`process/getButtons.do`。
                // 界面上出现哪几颗写操作键由它说，不由我们看状态码猜。跟前两发并着发，不排队。
                if (a.nodeId.isBlank()) emptyList() else tasks.buttons(exe, a.nodeId)
            }
            Triple(chain.await(), formA.await(), btn.await())
        }
        if (chainR.isFailure) return Result.failure(chainR.exceptionOrNull()!!)
        val nodes = School.rows(chainR.getOrThrow())

        val needXj = pendingXj(nodes)
        val xj = if (needXj) {
            school.postH5("${School.YWLZ}/mobile/process/getXjxx.do", "id" to exe)
                .getOrNull()?.optJSONObject("data")
        } else null

        val q = form?.optJSONObject("stuQjsq")
        form?.optString("dataJson")?.takeIf { it.isNotBlank() }?.let { dataJsonById[id] = it }

        val fields = buildList {
            add("单据标题" to a.title.ifBlank { "—" })
            keep(form, "流程", "flowName")
            keep(q, "流程号", "flownumber")
            if (a.category.isNotBlank()) add("类别" to a.category)
            if (a.creator.isNotBlank()) add("发起人" to a.creator)
            // 待办那一发的时间是"收到"，「我的申请」那一发给的是 createDate —— 同一行不能都叫收到
            add(if (a.nodeId.isBlank()) "发起时间" to a.receiveTime.ifBlank { "—" }
            else "收到时间" to a.receiveTime.ifBlank { "—" })
            add("状态" to a.statusTxt.ifBlank { a.status })
            // 「我的申请」那一发才有的两个标记，待办那一发里没这两个键，所以有才写
            if (a.attachmentUrl.isNotBlank()) add("附件" to "有（校方给的是 fs.aiit 上的一张图）")
            form?.optString("isDealDone")?.takeIf { it.isNotBlank() }?.let { add("能不能办" to dealDone(it)) }
            keep(q, "请假类型", "f1677131069644")
            keep(q, "班级", "bjmc")
            keep(q, "开始", "qjrqStartStr")
            keep(q, "结束", "qjrqEndStr")
            keep(q, "时长", "qjscStr")
            keep(q, "事由", "qjyy")
            keep(q, "是否离校", "sfxylx")
            keep(q, "紧急联系人", "jjlxr")
            keep(q, "超假时长", "cjScStr")
            keep(xj, "是否超假", "isCj")
            keep(xj, "距销假截止(小时)", "cjSc")
            keep(xj, "假期到", "cjStartTimeStr")
            keep(xj, "服务端此刻", "cjEndTimeStr")
        }

        // 审批链：一环一条，界面画成带圆点的竖线（原来是一整段"1. …\n2. …"，一屏下来是一堵字）
        val steps = (0 until nodes.length()).mapNotNull { i ->
            val n = nodes.optJSONObject(i) ?: return@mapNotNull null
            com.andaxin.data.brief.Step(
                head = n.real("approvedTypeMsg").ifBlank { "环节" },
                who = n.real("userName").ifBlank { "—" },
                what = n.real("statusMsg"),
                at = n.real("complateTime"),
            )
        }
        val paragraphs = buildList {
            if (nodes.length() == 0) {
                add("还没有审批环节。")
            }
            if (needXj) {
                add("这一单等你自己销假。下面那颗键要连按两次才发得出去。")
            }
        }

        // 这一条能按哪几颗写操作键。能确定的判据只有这三条，其余一律不猜：
        // ① 校方 `getButtons` 报了按钮 → 那颗键的**名字用校方给的 label**，我们只负责把它对上一条口；
        // ② `getForm` 的 `isDealDone=="0"`（可办）→ 给「同意」「驳回」；
        // ③ 审批链里有"待销假"那一环 → 给「销假」；条目自己说 `isWithdraw=="1"` → 给「撤回」；
        //    「删除」只给「我的申请」那一栏的条目（他旧仓的门禁就是 tab==我的申请，别的栏不给）。
        val dealable = a.nodeId.isNotBlank() && form?.optString("isDealDone") == "0"
        val isMine = a.nodeId.isBlank() &&
            tasks.mine().getOrNull()?.any { it.key == id } == true
        fun schoolLabel(word: String): String? = offered.firstOrNull { it.first.contains(word) }?.first
        val actions = buildList {
            if (dealable) {
                add(Action("agree", schoolLabel("同意") ?: "同意"))
                add(Action("reject", schoolLabel("驳回") ?: "驳回", danger = true))
            }
            if (needXj) add(Action("xj", schoolLabel("销假") ?: "销假"))
            if (a.withdrawable) add(Action("withdraw", schoolLabel("撤回") ?: "撤回"))
            if (isMine) add(Action("delete", schoolLabel("删除") ?: "删除", danger = true))
        }

        return Result.success(
            BriefDetail(
                title = q?.optString("flowtitle")?.takeIf { it.isNotBlank() } ?: a.title.ifBlank { "单据详情" },
                fields = fields,
                paragraphs = paragraphs,
                steps = steps,
                actions = actions,
            )
        )
    }

    /**
     * 发其中一颗键按下去的那一发。
     *
     * 【取证】四条口的形状 = 读它 H5 的调用点 + **他旧仓里已经跑通的那一份**（10-13 接的同意/驳回/删除/批量已阅、
     * 10-14 抓包定下来的销假），判据也只认外层 `flag`，跟他那份一模一样：不猜返回里还有什么别的键。
     * 一律不自动重试（重发一次就是多审一次、多删一次），界面上每颗键连按两次才走到这儿。
     * 成了就只报两个字，然后**把这详情重新拉一次** —— 状态有没有真动，看的是学校那一份，不是我这里说了什么。
     *
     * `agree`/`disagree` 的 `dataJson` 用的是 `getForm` 回显的**原文**，一个字都不重拼 ——
     * 拼错就等于替申请人改掉他填的东西。
     */
    override suspend fun act(id: String, action: String, answers: Map<String, String>): Result<String> {
        val a = affordById[id]
            ?: return Result.failure(Exception("这一条已经不在待办列表里了，回列表重进一次"))
        if (a.executionId.isBlank()) return Result.failure(Exception("这条是消息类，没有可提交的流程"))
        val stamp = nowMillis().toString()
        return when (action) {
            "agree", "reject" -> {
                val dataJson = dataJsonById[id]
                    ?: return Result.failure(Exception("表单回显还没拉到，退出详情重进一次再" +
                        if (action == "agree") "同意" else "驳回"))
                school.postH5(
                    "${School.YWLZ}/mobile/process/${if (action == "agree") "agree" else "disagree"}.do",
                    "dataJson" to dataJson,
                    "executionId" to a.executionId,
                    "ruTaskNodeId" to a.nodeId,
                    "remark" to "",
                    "_t" to stamp,
                ).map { if (action == "agree") "已同意" else "已驳回" }
            }
            "xj" -> {
                val dataJson = dataJsonById[id]
                    ?: return Result.failure(Exception("表单回显还没拉到，退出详情重进一次再销假"))
                school.postH5(
                    "${School.YWLZ}/mobile/process/agree.do",
                    "dataJson" to dataJson,
                    "executionId" to a.executionId,
                    "ruTaskNodeId" to a.nodeId,
                    "stuQjsq" to """{"xjfjList":[]}""",
                    "_t" to stamp,
                ).map { "已销假" }
            }
            "withdraw" -> school.postH5(
                "${School.YWLZ}/mobile/flowRuTask/withdrawExecution.do",
                "executionId" to a.executionId,
            ).map { "已撤回" }
            "delete" -> school.postH5(
                "${School.YWLZ}/mobile/flowRuTask/deleteExecution.do",
                "executionId" to a.executionId,
            ).map { "已删除" }
            else -> Result.failure(Exception("这一颗键本机没接"))
        }
    }

    /** 审批链里有没有"轮到你自己销假"的那一环。判据是实样里的 `statusMsg` / `xjStatus`。 */
    private fun pendingXj(nodes: JSONArray): Boolean {
        for (i in 0 until nodes.length()) {
            val n = nodes.optJSONObject(i) ?: continue
            if (n.optString("statusMsg") == "待销假" || n.optString("xjStatus") == "5") return true
        }
        return false
    }

    private fun dealDone(v: String) = when (v) {
        "0" -> "可办"
        "1" -> "已被审核"
        "2" -> "已被撤回"
        else -> v
    }

    /**
     * 这一族的空值有三种写法：键不存在、值为 null、值为空串。三种都当"没有"，
     * 界面上就不摆一行空的糊弄人。
     */
    private fun MutableList<Pair<String, String>>.keep(o: JSONObject?, label: String, key: String) {
        if (o == null || !o.has(key) || o.isNull(key)) return
        val v = o.optString(key)
        if (v.isBlank() || v == "null") return
        add(label to v)
    }
}
