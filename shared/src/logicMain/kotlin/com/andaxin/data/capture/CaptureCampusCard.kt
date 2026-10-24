package com.andaxin.data.capture

import com.andaxin.core.concurrency.localDateOffset
import com.andaxin.core.json.JSONArray
import com.andaxin.core.json.JSONObject
import com.andaxin.core.net.CookieStore
import com.andaxin.core.net.HttpBody
import com.andaxin.core.net.HttpCall
import com.andaxin.core.net.HttpEngine
import com.andaxin.core.net.HttpEngineConfig
import com.andaxin.core.net.RawCookie
import com.andaxin.core.net.appendQueryParams
import com.andaxin.core.net.createHttpEngine
import com.andaxin.core.net.formUrlEncode
import com.andaxin.core.net.matches
import com.andaxin.core.net.queryParamOf
import com.andaxin.core.store.AppFiles
import com.andaxin.core.store.SessionStore
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/*
 * 校园卡（第三方：新开普 17wanxiao）。
 *
 * 【取证 · ② 我今天从 PC 现打，链路一跳一跳验通过的】2026-10-20 14:3x–14:5x 那一轮，
 * 逐跳输入输出、全部键名与长度掩码在 `lxtmp/契约_校园卡现打.md`。这里只写实现要用到的结论。
 *
 * **校方 token 换不出校园卡数据，中间差三跳：**
 * `getAppServiceMore.do` 里 `mc=完美校园` 那条的 `sjdfwdz`（租户串 `flag` 就在里面，别硬编）
 * → `GET hub.17wanxiao.com/bsacs/light.action`（**这一跳落 hub 的 JSESSIONID**）
 * → `POST hub.17wanxiao.com/bsacs/redirect.action`，body 只有 `userData=<10 键 JSON>`，
 * 回一个**新开普自己的 token**（36 位，和校方那把不同值）。之后业务口只认这把新的。
 *
 * 两个坑不现打看不出来：
 * ① 少了 `light.action` 那一跳，`redirect.action` 回的是 **HTTP 200 + text/plain + 14 字节
 *    纯文本 `Access timeout`** —— 当 JSON 解直接崩，而状态码是 200，看着像"接口通了"。
 * ② 这一族认**下划线那套身份**（`access_token`）。H5 那套驼峰 `accessToken` 被
 *    `redirect.action` 明确拒掉，服务端原话 `{"error":true,"message":"请求参数token不能为空"}`。
 *    报修那一族正好相反（只认驼峰），**两边的写法不许互相套**。
 *
 * **业务口走 `POST ecardh5.17wanxiao.com/ecardh5/xyk`，三个键 `token`/`method`/`param`，
 * 一个 cookie 都不需要**（空 jar 实测出数据）。信封是 `{code_, result_, message_, body}`，
 * 而 `body` 还是**一个 JSON 串**，解开才是 `_result/_code/_message` + 业务键 —— 判活得判两层。
 * 另一条 `bootcallback`（`gotowhere=`）要 `SESSION@ecardh5` 那条 cookie，这条路线用不上。
 *
 * **流水的分页键叫 `count`，不叫 pageSize。** 不给 `count` 就只回 `{totalCount:34, data:[]}`，
 * HTTP 200、`code_:0`、`result_:true`，看着完全像"这个月没消费"。`pageSize`/`pageIndex`/`start`/
 * `limit`/`pageNum` 五个名字全无效（现打）。`type` 在这条口上被忽略，收支只能自己按 `flag` 分
 * （**0=收入/充值，1=支出/消费**）。
 *
 * **换 token 会踢掉上一把**：每次 `redirect.action` 都发新 token，同账号旧的那把立刻作废
 * （实测拿旧的打 `/xyk` 回 `code_:4001 message_:"token验证失败!"`）。所以换出来的那把**存本机复用**，
 * 别每屏重换一次；真撞到 4001 才重换，而且只给一次重试，防死循环。
 *
 * 【没接的】挂失/冻结/充值那三个 `*_ENCRYPT` 写口 —— 加密算法与密钥编码没抠出来，
 * 而且那是往学校系统里写，一律停在他手按那一步。「今日已消费」也没有现成口
 * （`XYK_COST_*` 四条统计口在本租户服务端是坏的：bootcallback 侧甩 hibernate 异常，
 * /xyk 侧 `code_:8003 命令验证失败!`），所以那个数是自己按当天流水加出来的，界面上要写明。
 *
 * 隐私：`cardNo`/`asn`/`scardsnr`/`name`/`outid` 这些键服务端给了也不落盘、不显示，
 * 界面只用「有效期」「上次账务变动」这类不含卡号的项。
 *
 * 平移注（2026-10-24）：transport 换引擎缝（hub 那两跳共用一个带 cookie 罐的临时引擎，
 * JSESSIONID 由引擎自动带过去，口径同原 MapJar）；`cache: File` 换 `files: AppFiles`
 * （token 落 filesDir/ecard_token.txt，还是原来那份文件）；Calendar/SimpleDateFormat 换日期帮手。
 * 注意：`_userName` 在原来那版就是 enc 过再交给 okhttp 二次编码（线上口径即双重编码），
 * appendQueryParams 同样会再编一次，线上报文不变。
 */
class CampusCardClient(
    private val school: School,
    private val session: SessionStore,
    private val files: AppFiles,
) {

    data class Flow(
        val at: String,
        val what: String,
        val term: String,
        val yuan: Double,
        val income: Boolean,
    )

    data class Card(
        val balance: Double,
        val subsidy: Double,
        val statusText: String,
        val validUntil: String,
        val lastChange: String,
        val flows: List<Flow> = emptyList(),
        /** 近 30 天窗口里自己加出来的支出合计。服务端没有"本月"这个口径，界面上要写清是 30 天。 */
        val windowOut: Double = 0.0,
        val todayOut: Double = 0.0,
        /** 流水那一条挂了但余额出来了 —— 分开报，别把半屏失败说成整屏没数据。 */
        val flowWhy: String? = null,
        /** true = 「最近一笔」这一格还定不下来（余额到了，能定它的那一发还没到）。 */
        val flowsPending: Boolean = false,
        /** 今天那一发（只查当天）回来了没有。没回来时「今天支出」摆 `…`，不许摆 0。 */
        val todayKnown: Boolean = false,
        /** 近 30 天那 200 条还在路上 —— 「近 30 天支出」只有这一发能算。 */
        val windowPending: Boolean = false,
    )

    /**
     * 后台预热：只把三步换会话跑掉，让用户点进校园卡时不用再等握手。
     * 旧项目就是这么干的（它注释里写"首页数据加载完后在后台跑一次，出错不抛"）。
     * 已经有可用的 token 就什么都不做。
     */
    suspend fun warm() {
        if (cachedToken() != null) return
        val id = session.identity() ?: return
        runCatching { exchange(id.accessToken, id.userCode, id.userName, id.userType) }
    }

    /**
     * 余额 + 今天 + 近 30 天流水，分三刀交出去：[onStage] 会先收到只有余额的那一份，
     * 再收到补上「今天支出」的那一份，最后 return 的是完整的一份。撞 4001 会重换一次会话。
     */
    suspend fun card(onStage: (Card) -> Unit = {}): Result<Card> {
        val id = session.identity()
            ?: return Result.failure(Exception("还没登录，校园卡要校方会话"))

        var token = cachedToken()
            ?: exchange(id.accessToken, id.userCode, id.userName, id.userType)
                .getOrElse { return Result.failure(it) }

        var first = attempt(token, onStage)
        if (first.isFailure && first.exceptionOrNull()?.message?.contains("4001") == true) {
            token = exchange(id.accessToken, id.userCode, id.userName, id.userType)
                .getOrElse { return Result.failure(it) }
            first = attempt(token, onStage)
        }
        return first
    }

    /**
     * 学校那一页的地址：`getAppServiceMore.do` 里「校园卡交易」那条的 `sjdfwdz`，
     * 后面挂上和 App 里其它服务一样的身份参数（H5 就是从 query 里读 `access_token` 的）。
     *
     * 为什么要这条：10-21 校方把「完美校园」那条入口换成了自己的 H5
     * （`in.aiit.edu.cn/dist/index.html#!/card-transaction/...`），换 17wanxiao 的旧链路断了。
     * 原生那屏还能读的地方就拿原生，读不到时至少给一条官方页面能点。
     */
    suspend fun pageUrl(): String? {
        val id = session.identity() ?: return null
        val root = school.post(
            "${School.SCPS}/appService/getAppServiceMore.do",
            "pageIndex" to "1", "pageSize" to "60",
        ).getOrNull() ?: return null
        val arr = root.optJSONArray("data") ?: return null
        val entries = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        val raw = (entries.firstOrNull { it.optString("sjdfwdz").contains("17wanxiao", true) }
            ?: entries.firstOrNull {
                it.optString("mc").contains("校园卡") && it.optString("sjdfwdz").startsWith("http")
            })?.optString("sjdfwdz") ?: return null
        return appendQueryParams(
            raw,
            listOf(
                "access_token" to id.accessToken,
                "_userCode" to id.userCode,
                "userCode" to id.userCode,
                "code" to id.userCode,
                "_userName" to id.userName,
                "_userType" to id.userType,
                "appId" to School.APP_ID,
            ),
        )
    }

    private suspend fun attempt(token: String, onStage: (Card) -> Unit = {}): Result<Card> = coroutineScope {
        // 三发一起发：余额最快、只查当天那发其次、近 30 天 200 条最慢。
        // 串行的话这一屏要多等两个来回（实测每跳几百毫秒到两秒）。
        val balTask = async { xyk(token, "XYK_BALANCE", "{}") }
        val todayTask = async {
            xyk(
                token, "XYK_TRADE_DETAIL",
                JSONObject()
                    .put("beginIndex", 0).put("count", TODAY_PAGE)
                    .put("beginDate", day(0)).put("endDate", day(0))
                    .toString(),
            )
        }
        val detailTask = async {
            xyk(
                token, "XYK_TRADE_DETAIL",
                JSONObject()
                    .put("beginIndex", 0).put("count", 200)
                    .put("beginDate", day(-29)).put("endDate", day(0))
                    .toString(),
            )
        }
        val bal = balTask.await().getOrElse { return@coroutineScope Result.failure(it) }
        val head = balanceOf(bal)
        // 第一刀：余额一到就上屏。卡面那个大数字、补助钱包、状态、有效期都只吃这一发。
        onStage(head.copy(flowsPending = true, windowPending = true))
        /*
         * 第二刀：只查当天那一发。它比 200 条那发回来得早，能当场定下「今天支出」；
         * 今天有消费时连「最近一笔」也一起定了 —— 今天的行必然是整个窗口里最新的那批。
         *
         * 这一刀**不假设服务端按时间倒序给行**（没验过，见 docs/接入清单.md）：
         * 今天的行自己排、自己加。「今天到底有没有拉全」用条数判：拿回来的行数不到一页，
         * 或者 `totalCount` 说就这么多，才算全 —— 否则这一刀直接放弃，等第三刀，
         * 宁可那一格多摆一会儿 `…`，也不摆一个算漏了的合计。
         */
        val todayBody = todayTask.await().getOrNull()
        val todayRows = todayBody?.let { flowsOf(it.rows()) }
        val todayCount = todayBody?.optInt("totalCount", -1) ?: -1
        if (todayRows != null && (todayRows.size < TODAY_PAGE || todayCount in 0..todayRows.size)) {
            onStage(
                head.copy(
                    flows = todayRows.sortedByDescending { it.at },
                    todayOut = todayRows.filter { !it.income }.sumOf { it.yuan },
                    todayKnown = true,
                    // 今天有行 → 「最近一笔」当场定；今天一行没有 → 那还得等全窗口那一发
                    flowsPending = todayRows.isEmpty(),
                    windowPending = true,
                ),
            )
        }
        // 第三刀：近 30 天那 200 条。只有它能算「近 30 天支出」。
        val detail = detailTask.await()
        val rows = detail.mapCatching { it.rows() }.getOrElse { JSONArray() }
        val flows = flowsOf(rows)
        val today = day(0)
        Result.success(
            head.copy(
                flows = flows.sortedByDescending { it.at },
                windowOut = flows.filter { !it.income }.sumOf { it.yuan },
                todayOut = flows.filter { !it.income && it.at.startsWith(today) }.sumOf { it.yuan },
                todayKnown = true,
                flowWhy = if (rows.length() == 0) detail.exceptionOrNull()?.message else null,
                flowsPending = false,
                windowPending = false,
            ),
        )
    }

    /** 卡面上只吃余额那一发的几样东西：流水没到时先拿它上屏。 */
    private fun balanceOf(bal: JSONObject) = Card(
        balance = bal.optString("mainFare").toDoubleOrNull() ?: 0.0,
        subsidy = bal.optString("subsidyFare").toDoubleOrNull() ?: 0.0,
        // 现打只见过 "1"。3/4/100 那几档是从 H5 源码读来的，没法在不挂失的前提下验，
        // 所以未知码老老实实说"状态未知"，别拿源码里的映射表冒充实测。
        statusText = when (bal.optString("status")) {
            "1" -> "正常"
            "" -> "状态没给"
            else -> "状态未知（码 ${bal.optString("status")}）"
        },
        validUntil = bal.optString("curNouseDate"),
        lastChange = bal.optString("lastFareDate"),
    )

    /** 流水行。`flag` 是 0 才算进账，`opfare` 是元。 */
    private fun flowsOf(rows: JSONArray) = (0 until rows.length()).mapNotNull { i ->
        val o = rows.optJSONObject(i) ?: return@mapNotNull null
        val yuan = o.optString("opfare").toDoubleOrNull() ?: return@mapNotNull null
        Flow(
            at = o.optString("opdt"),
            what = o.optString("description").ifBlank { o.optString("accdscrp") },
            term = o.optString("termName"),
            yuan = yuan,
            income = o.optInt("flag", 1) == 0,
        )
    }

    /** 一次业务调用：发出去、判两层信封、把 `body` 那层解开交出去。 */
    private suspend fun xyk(token: String, method: String, param: String): Result<JSONObject> {
        val reply = runCatching {
            http.execute(
                HttpCall(
                    url = "$ECARD/xyk",
                    headers = listOf(
                        "Accept" to "application/json",
                        "Content-Type" to "application/x-www-form-urlencoded;charset=UTF-8",
                        "Referer" to "$ECARD/ecard_t/index.html?v=1",
                    ),
                    body = HttpBody.Form(listOf("token" to token, "method" to method, "param" to param)),
                ),
            )
        }.getOrElse { e ->
            return Result.failure(Exception("连不上校园卡：" + (e.message ?: e::class.simpleName)))
        }
        val root = runCatching { JSONObject(reply.bodyText) }.getOrNull()
            ?: return Result.failure(Exception("校园卡回的不是 JSON（HTTP ${reply.status}）"))
        if (!root.optBoolean("result_", false)) {
            // 4001 = 会话被顶掉，调用方要凭这个码决定要不要重换一次
            return Result.failure(
                Exception("${root.opt("code_")}：${root.optString("message_").ifBlank { "校园卡拒了这次调用" }}"),
            )
        }
        val inner = root.optString("body").ifBlank { "{}" }
        val b = runCatching { JSONObject(inner) }.getOrNull()
            ?: return Result.failure(Exception("校园卡的 body 解不开"))
        if (b.has("_result") && !b.optBoolean("_result", false)) {
            return Result.failure(
                Exception("校园卡：${b.optString("_message").ifBlank { "_code=${b.opt("_code")}" }}"),
            )
        }
        // 有的 method 把行放在 `data`，有的直接给对象，这里统一交出去让调用方取
        return Result.success(b)
    }

    /** 业务口 `XYK_TRADE_DETAIL` 的行在 `_result` 同级的 `data` 里。 */
    private fun JSONObject.rows(): JSONArray = optJSONArray("data") ?: JSONArray()

    private fun cachedToken(): String? = runCatching {
        files.readText(TOKEN_FILE)?.trim()?.lineSequence()?.firstOrNull()
    }.getOrNull()?.takeIf { it.length >= 20 }

    /** H9 → H1 → H2：从校方会话换出第三方 token。hub 那一跳的 cookie 只在本次交换里用。 */
    private suspend fun exchange(
        accessToken: String, userCode: String, userName: String, userType: String,
    ): Result<String> {
        val light = school.post(
            "${School.SCPS}/appService/getAppServiceMore.do",
            "pageIndex" to "1", "pageSize" to "60",
        ).mapCatching { root ->
            val arr = root.optJSONArray("data") ?: JSONArray()
            // 出错时把这一发"到底回了什么"带出来：条数 + flag。
            // 只写"没有那一条"分不清是"清单变了"还是"这一发根本没成"——11:5x 就是卡在这儿。
            val entries = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            // 10-20 那会儿这一条叫「完美校园」，10-21 再看已经改名成「校园卡交易」——
            // 按名字硬比会静默失配。所以先认**地址**（17wanxiao 那套），认不到再按名字里带"校园卡"兜。
            /*
             * 入口先看清单里有没有 17wanxiao 那条；没有就**用他旧项目里那套写死的入口**：
             * `hub.17wanxiao.com/bsacs/light.action?flag=ahxx_ecardh5_1000538`。
             * 那个 flag 是学校在完美校园那边的租户串（他从旧项目带过来的），
             * 不依赖校方服务清单 —— 10-21 清单里那条改名成「校园卡交易」并指向学校自己的 H5，
             * 但 hub 这跳本身还在。
             */
            (entries.firstOrNull { it.optString("sjdfwdz").contains("17wanxiao", true) }
                ?: entries.firstOrNull {
                    it.optString("mc").contains("校园卡") && it.optString("sjdfwdz").startsWith("http")
                })
                ?.optString("sjdfwdz")
                ?.takeIf { it.contains("17wanxiao", true) }
                ?: FALLBACK_LIGHT
        }.getOrElse { return Result.failure(Exception(it.message)) }

        // hub 那两跳共用一台带 cookie 罐的临时引擎：light.action 落的 JSESSIONID 由罐自动带到 redirect 那跳
        val hub = createHttpEngine(
            HttpEngineConfig(connectTimeoutMs = 15_000, readTimeoutMs = 20_000, cookieStore = MapJar()),
        )
        val withIdentity = appendQueryParams(
            light,
            listOf(
                "access_token" to accessToken,
                "_userCode" to userCode,
                "code" to userCode,
                "userCode" to userCode,
                "_userName" to enc(userName),
                "_userType" to userType,
                "appId" to School.APP_ID,
                "returnFromIscToAppFunc" to "ReturnDefault",
            ),
        )
        val code = runCatching {
            hub.execute(
                HttpCall(method = "GET", url = withIdentity, headers = listOf("Accept" to "*/*")),
            ).status
        }.getOrElse { return Result.failure(Exception("连不上校园卡入口：" + (it.message ?: ""))) }
        if (code != 200) return Result.failure(Exception("校园卡入口回 $code"))

        val userData = JSONObject()
            .put("access_token", accessToken)
            .put("returnFromIscToAppFunc", "ReturnDefault")
            .put("ecardFunc", queryParamOf(withIdentity, "ecardFunc") ?: "index")
            .put("flag", queryParamOf(withIdentity, "flag").orEmpty())
            .put("code", userCode)
            .put("_userName", userName)
            .put("_userType", userType)
            .put("appId", School.APP_ID)
            .put("_userCode", userCode)
            .put("userCode", userCode)
            .toString()
        val reply = runCatching {
            hub.execute(
                HttpCall(
                    url = "$HUB/redirect.action",
                    headers = listOf(
                        "Accept" to "application/json",
                        "Content-Type" to "application/x-www-form-urlencoded;charset=UTF-8",
                    ),
                    body = HttpBody.Form(listOf("userData" to userData)),
                ),
            )
        }.getOrElse { return Result.failure(Exception("连不上校园卡换会话那跳：" + (it.message ?: ""))) }
        val text = reply.bodyText
        // 少 cookie 时这里是 14 字节纯文本 "Access timeout"，HTTP 仍是 200 —— 别当 JSON 解
        val root = runCatching { JSONObject(text) }.getOrNull()
            ?: return Result.failure(
                Exception(
                    "换校园卡会话没成（服务端回的是纯文本：${text.take(24)}；" +
                        "入口 ${withIdentity.substringBefore('?')}）",
                ),
            )
        val token = root.optString("token")
        if (!root.optBoolean("result_", false) || token.length < 20) {
            return Result.failure(
                Exception("换校园卡会话：${root.optString("message").ifBlank { "没给 token" }}"),
            )
        }
        runCatching { files.writeText(TOKEN_FILE, token) }   // 存起来复用，见文件注释那条"会踢掉上一把"
        return Result.success(token)
    }

    private val http: HttpEngine = createHttpEngine(
        HttpEngineConfig(connectTimeoutMs = 15_000, readTimeoutMs = 20_000),
    )

    private fun day(offset: Int): String = localDateOffset(offset)

    private companion object {
        const val HUB = "https://hub.17wanxiao.com/bsacs"
        const val ECARD = "https://ecardh5.17wanxiao.com/ecardh5"
        /** 换出来的第三方 token 落这份文件（还是原来那个 ecard_token.txt）。 */
        const val TOKEN_FILE = "ecard_token.txt"

        /** 只查当天那一发要多少条。一天内刷卡刷过 50 次不算常态，
         *  真撞上了由 `totalCount` 那条判据兜住：这一刀直接放弃，等全窗口那一发。 */
        const val TODAY_PAGE = 50

        /**
         * 校方清单里那条 17wanxiao 入口没了之后的兜底（旧项目 `ApiConstants` 那对常量）：
         * hub 地址 + 学校在完美校园那边的租户串。**不依赖校方服务清单** ——
         * 10-21 清单里那条改名成「校园卡交易」并指向学校自己的 H5，但 hub 这跳本身还在。
         */
        const val FALLBACK_LIGHT =
            "https://hub.17wanxiao.com/bsacs/light.action?flag=ahxx_ecardh5_1000538&ecardFunc=index"

        fun enc(s: String): String = formUrlEncode(s)
    }
}

/** 够把 hub 那一跳的 JSESSIONID 带到下一跳就行，不做持久化。 */
private class MapJar : CookieStore {
    private val store = HashMap<String, MutableList<RawCookie>>()

    override fun save(host: String, cookies: List<RawCookie>) {
        val bucket = store.getOrPut(host) { mutableListOf() }
        cookies.forEach { c ->
            bucket.removeAll { it.name == c.name }
            bucket.add(c)
        }
    }

    override fun load(host: String, path: String): List<RawCookie> =
        store[host].orEmpty().filter { it.matches(host, path) }
}
