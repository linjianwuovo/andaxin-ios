package com.andaxin.data.capture

import com.andaxin.core.net.HttpBody
import com.andaxin.core.net.HttpCall
import com.andaxin.core.net.HttpEngine
import com.andaxin.core.net.HttpEngineConfig
import com.andaxin.core.net.createHttpEngine
import com.andaxin.core.net.formUrlEncode
import com.andaxin.core.store.SchoolIdentity
import com.andaxin.core.store.SessionStore
import com.andaxin.core.json.JSONArray
import com.andaxin.core.json.JSONObject

/**
 * 校方业务接口的公共外壳：身份参数怎么带、表单怎么发、JSON 怎么钻。
 *
 * 证据：2026-10-19 20:47 米11 挂代理冷启动安小信（2.0.5 / versionCode 79）走到登录成功，
 * 抓到它自己发出的 `zhxy-new-scps` 那一圈 POST —— 每一条的表单里都带同一套身份字段：
 * `access_token` + `_userCode` + `userId` + `userCode` + `_userName` + `_userType` + `appId`。
 * `appId` 是 32 个 `1`，写死在客户端里，不是我们编的。
 *
 * 前三个坑，都是这次实样里看见的：
 * ① `_userName` 有两种编码：原生那圈（okhttp UA）给一次 URL 编码的 `%E9%BB%84…`，
 *    另有几条（Dalvik UA）给两次编码的 `%25E9%25BB%2584…`。两种服务端都认（都回过 flag:true），
 *    这里统一按一次编码发 —— 少一层歧义，也免得有人以为名字里该带百分号。
 * ② 响应一律是 `{flag, code, msg, data}`，`data` 有时是对象、有时直接是数组、有时是分页壳
 *    （数组藏在 `data.data` 里）。只钻根节点会静默拿到空，界面看着像"没数据"。
 * ③ 服务端不给你 4xx：token 过期、参数缺了，都回 200 + `flag:false` + 一句中文 `msg`，
 *    或者干脆回一坨 HTML（我拿旧清单里那条 `selectStuSelfTimeTable.do` 试过来着，回的是登录页）。
 *    所以判成功只看 `flag` 和能不能钻到结构，别用 HTTP 状态码。
 *
 * 第四个坑是 10-19 23:05 用设备上那把活 token 一条条试出来的（`stuExamScore.do`）：
 * 表单里**同一个键重复出现**，服务端不报错，只把结果清空 ——
 * `kkxn=2026&kkxq=1&userCode=<学号>` 回 1 条；再补一个 `userCode` 就回 0 条。
 * 身份那七个字段单独加哪个都不影响，两次编码的名字也不影响。
 * 所以 `form()` 里做了去重：调用方自己给了同名参数，身份那份就让开。
 *
 * 平移注（2026-10-24）：底层从直用 OkHttp 换成共享层那台 HttpEngine（Android 端实际还是
 * 同一台 OkHttp 池、同样的超时与表单顺序，报文路径不变）；`postFile` 的参数从 File
 * 换成 bytes+fileName（commonMain 没有 java.io.File），调用方照改。
 */
class School(private val session: SessionStore) {

    private val _kicked = kotlinx.coroutines.flow.MutableStateFlow(false)

    /**
     * 「这把 token 已经被别的客户端顶掉了」的信号。
     *
     * 【取证】10-19 深夜实测：同一个账号，安小信一登录，我们这把 token 再打 news 那一组接口就回
     * `flag:false msg:"没有访问权限"`，而 `timeTable`、`msgCenter` 那几口还认它。
     * 换一把新 token 立刻全部恢复正常 —— 所以校方是**一个账号只留一个活会话**，
     * 不是权限没开，也不是我们参数写错。这种情况界面上一句"系统异常"是骗人的。
     */
    val kicked: kotlinx.coroutines.flow.StateFlow<Boolean> = _kicked

    fun consumeKicked() { _kicked.value = false }

    private val engine: HttpEngine = createHttpEngine(
        HttpEngineConfig(
            connectTimeoutMs = 15_000,
            readTimeoutMs = 20_000,
        ),
    )

    /** 当前会话的学号。没登录给空串，调用方自己决定要不要发。 */
    suspend fun userCode(): String = session.identity()?.userCode.orEmpty()

    /** 当前会话的 token。同上。 */
    suspend fun token(): String = session.identity()?.accessToken.orEmpty()

    /** 当前会话的姓名。sports 上传那条表单要带 `_userName`。 */
    suspend fun userName(): String = session.identity()?.userName.orEmpty()

    /** 带身份表单发一次 POST，回根节点 JSON。没登录直接给失败，不发网络。 */
    suspend fun post(url: String, vararg fields: Pair<String, String>): Result<JSONObject> {
        val id = session.identity()
            ?: return Result.failure(Exception("还没登录，这一屏要校方数据"))
        return send(url, dedupe(identityFields(id), fields.toList()))
    }

    /**
     * H5 那一套身份字段的 POST。ywlz（业务流转）域上的接口是学校的 WebView 页面在调，
     * 带的身份跟原生那圈**不是一套**，混着用服务端直接拒。
     *
     * 【取证】① 旧抓包里 ywlz 的 H5 调用一律是 `accessToken`(驼峰) + `_userCode` + `_userType`
     * + `userCode` + `xh`（xh 与 userCode 同值），由 axios 拦截器统一追加；
     * ② 10-19 23:10 我拿设备上那把活 token 对着 `getRunTaskByUserCode.do` 现打了一遍：
     * H5 这套回 21 条数据；换成原生那套（`access_token`/`appId`/`_userName`/`userId`）
     * 回 `flag:false result:"非法访问"`。所以这不是风格差异，是服务端真在挑。
     */
    suspend fun postH5(url: String, vararg fields: Pair<String, String>): Result<JSONObject> =
        postH5Timed(url, null, *fields)

    /**
     * 只给这一发限时的那一个口。为什么单开一个函数：`vararg` 只能待在参数表最后，
     * 中间插一个带默认值的参数，所有按位置传字段的调用点就全撞位了（编译器直接把 Pair
     * 往 Long? 上塞）。
     */
    suspend fun postH5Timed(
        url: String,
        timeoutMs: Long?,
        vararg fields: Pair<String, String>,
    ): Result<JSONObject> {
        val id = session.identity()
            ?: return Result.failure(Exception("还没登录，这一屏要校方数据"))
        return send(url, dedupe(h5IdentityFields(id), fields.toList()), timeoutMs)
    }

    /**
     * fdygl（学生工作平台，查寝/晚点名在这儿）用的是**JSON 体 + 身份放请求头**，
     * 跟前面两种（form 体 + 身份放表单）都不一样，所以单开一个口。
     *
     * 【取证】等级①，我读的前端代码：`fdygl.aiit.edu.cn/swp-app/js/chunk-464b727d.8a22efb7.js`
     * 里 axios 的请求拦截器把身份写进 header —— `accessToken` / `userCode` / `userType` /
     * `xh`(=userCode) / `role` / `roleId`，`Content-Type: application/json`；
     * 响应是 `{flag, code, msg, data}`，响应拦截器里 `code===203` 就是把人踢回登录页（token 失效）。
     *
     * **这一条我还没现打过**：写它的那会儿本机存着的四把 token 全被判死了（见 `YWLZ_DENIED` 那条取证），
     * 没法验证。所以两个地方是明写的问号：① `role`/`roleId` 该填什么不知道，拦截器原文是
     * `t.code || ""`，学生端很可能就是空串，先按空串发；② `code=203` 之外还有没有别的失效码没见过。
     * 打不通的时候界面显示的是服务端原话，不装作有数据。
     */
    suspend fun postJson(url: String, body: JSONObject): Result<JSONObject> {
        val id = session.identity()
            ?: return Result.failure(Exception("还没登录，这一屏要校方数据"))
        val reply = runCatching {
            engine.execute(
                HttpCall(
                    url = url,
                    headers = listOf(
                        "Accept" to "application/json",
                        "User-Agent" to AGENT,
                        "X-Requested-With" to "XMLHttpRequest",
                        "accessToken" to id.accessToken,
                        "userCode" to id.userCode,
                        "userType" to id.userType,
                        "xh" to id.userCode,
                        "role" to "",
                        "roleId" to "",
                    ),
                    body = HttpBody.Text(body.toString(), "application/json; charset=utf-8"),
                ),
            )
        }.getOrElse { e ->
            return Result.failure(Exception("连不上学校：${e.message ?: e::class.simpleName}"))
        }
        return parseFdygl(reply)
    }

    /**
     * fdygl 的图片上传。旧项目走的是这条路：
     * `POST /app/file/uploadFileToFastdfs`，multipart 的字段名就是 `file`，
     * 身份仍放请求头（和 [postJson] 同一套），响应里的 `data.path` 回填签到表的 `signinPhoto`。
     * 平移注：参数从 File 换成 bytes+fileName，Mime 按 extension 判断的口径不变。
     */
    suspend fun postFile(url: String, bytes: ByteArray, fileName: String, field: String = "file"): Result<JSONObject> {
        val id = session.identity()
            ?: return Result.failure(Exception("还没登录，这一屏要校方数据"))
        val mime = when (fileName.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }
        val reply = runCatching {
            engine.execute(
                HttpCall(
                    url = url,
                    headers = listOf(
                        "Accept" to "application/json",
                        "User-Agent" to AGENT,
                        "X-Requested-With" to "XMLHttpRequest",
                        "accessToken" to id.accessToken,
                        "userCode" to id.userCode,
                        "userType" to id.userType,
                        "xh" to id.userCode,
                        "role" to "",
                        "roleId" to "",
                    ),
                    body = HttpBody.Upload(field, fileName, mime, bytes),
                ),
            )
        }.getOrElse { e ->
            return Result.failure(Exception("连不上学校：${e.message ?: e::class.simpleName}"))
        }
        return parseFdygl(reply)
    }

    /** fdygl 那一族共用的信封判断（code=203 踢回 / flag:false 带 msg，见 postJson 取证）。 */
    private fun parseFdygl(reply: com.andaxin.core.net.HttpReply): Result<JSONObject> {
        if (!reply.isSuccessful) return Result.failure(Exception("服务器回了 ${reply.status}"))
        val root = runCatching { JSONObject(reply.bodyText) }.getOrNull()
            ?: return Result.failure(Exception("回来的不是 JSON（多半是被踢到登录页了）"))
        if (root.optInt("code") == 203) {
            _kicked.value = true
            return Result.failure(Exception(KICKED))
        }
        if (!root.optBoolean("flag", false)) {
            val msg = root.optString("msg").ifBlank { "校方说没通过" }
            if (msg.contains("没有访问权限")) {
                _kicked.value = true
                return Result.failure(Exception(KICKED))
            }
            return Result.failure(Exception(msg))
        }
        return Result.success(root)
    }

    /**
     * 只发调用方给的那几个字段，一个身份字段都不自动补。
     *
     * 为什么单开这一条：跑步上传 `extra/addExtraCheckNew.do` 的官方报文（10-14 那四条 +
     * 10-21 22:11 在米11 上现抓的两条，全 200、`data.id` 是真 id）表单**只有六个键**
     * `list / access_token / _userCode / userId / _userName / appId`，
     * 而学号是走**请求头** `studentCode` 的。
     * 我们原先走 [postH5]，它还会再塞 `accessToken`、`_userType`、`userCode`、`xh` 四个，
     * 外加我们自己把 `studentCode` 当成表单字段补进去 —— 服务端那套 iBatis 参数表被搅过两次
     * （先报 `studentCode` 为 null 的"无效的列类型 1111"，补上之后改报
     * "Could not set parameters for mapping"）。这一条按官方那六个键 + 那一个头原样发。
     */
    suspend fun postExact(
        url: String,
        fields: List<Pair<String, String>>,
        vararg headers: Pair<String, String>,
    ): Result<JSONObject> = send(url, fields, headers = headers.toList())

    /** 同一个键只留调用方那一份（见类注释第四个坑）。 */
    private fun dedupe(identity: List<Pair<String, String>>, extra: List<Pair<String, String>>): List<Pair<String, String>> {
        val given = extra.mapTo(HashSet()) { it.first }
        return identity.filterNot { it.first in given } + extra
    }

    private suspend fun send(
        url: String,
        pairs: List<Pair<String, String>>,
        timeoutMs: Long? = null,
        headers: List<Pair<String, String>> = emptyList(),
    ): Result<JSONObject> {
        // 单发限时：整个客户端的读超时是 20 秒，那一档是给"课表、成绩这种真在算的"留的。
        // 有些口（`process/getForm.do`）校方现在给到 46 秒还只回 214 字节 ——
        // 跟着 20 秒等，就是详情白屏 20 秒换几行没有的字段。这种口自己报一个短得多的数。
        val reply = runCatching {
            engine.execute(
                HttpCall(
                    url = url,
                    headers = listOf(
                        "Accept" to "application/json",
                        "User-Agent" to AGENT,
                    ) + headers,
                    body = HttpBody.Form(pairs),
                ),
                timeoutMs = timeoutMs,
            )
        }.getOrElse { e ->
            return Result.failure(Exception("连不上学校：${e.message ?: e::class.simpleName}"))
        }
        if (!reply.isSuccessful) {
            return Result.failure(Exception("服务器回了 ${reply.status}"))
        }
        val root = runCatching { JSONObject(reply.bodyText) }.getOrNull()
            ?: return Result.failure(Exception("回来的不是 JSON（多半是会话过期被踢到登录页）"))
        if (!root.optBoolean("flag", false)) {
            // in.aiit 那一族把原因放 `msg`，ywlz 那一族放 `result`（实测回过"非法访问"）。
            // 两处都看，都没有才说"没通过"，别自己编一句。
            val msg = root.optString("msg")
                .ifBlank { root.optString("result") }
                .ifBlank { "校方说没通过" }
            if (msg.contains("没有访问权限") || msg == YWLZ_DENIED) {
                // 顶号是会话级的事，一次命中就该整体认，别每屏各报各的
                _kicked.value = true
                return Result.failure(Exception(KICKED))
            }
            return Result.failure(Exception(msg))
        }
        return Result.success(root)
    }

    companion object {
        /** 被顶号时统一给这一句，界面上和到期那句要能分开。 */
        const val KICKED = "登录已在别处被顶掉（这台设备的服务端会话失效了），重新登一次。"

        /**
         * ywlz 那一域判会话失效时回的话（在 `result` 字段里，不是 `msg`）。
         *
         * 【取证】10-19 23:12 同一套报文回 21 条；23:40 起逐字节一样的报文改回
         * `flag:false result:"非法访问"`，同一时刻 in.aiit 那些**查会话**的接口
         * （news/getNewsTypeList、personal/queryStudentStatus）也一起回"没有访问权限"，
         * 而**不查会话**的（timeTable、notReadCount、stuExamScore、getYwdtCount）照回数据 ——
         * 所以这句是"会话没了"，不是"参数写错"。当时本机存着的四把 token
         * （我们 app、安小信、老包 access + refresh）全部同时失效，像是校方夜里清了一次会话。
         * 万一哪天它拿这句话表示别的意思，界面上会多说一句"重新登一次"，
         * 那也比沉默地显示一个空列表强。
         */
        const val YWLZ_DENIED = "非法访问"

        /** 安小信自己那圈调用用的就是 okhttp 默认串，跟着它，别自造一个。 */
        const val AGENT = "okhttp/4.9.3"

        /** 32 个 1，抓包里每条都带。 */
        const val APP_ID = "11111111111111111111111111111111"

        const val MAIN = "https://in.aiit.edu.cn"
        const val SCPS = "$MAIN/zhxy-new-scps"
        const val INFO = "$MAIN/zhxy-information"

        /** 课表与成绩那一组在 csh 这个 context 下，前缀错了服务器直接回登录页 HTML。 */
        const val CSH = "$MAIN/zhxy-csh"

        /**
         * 业务流转（待办事务 / 请假 / 销假）。前端 JS 里写的是 `/mobile/...`，
         * axios 的 baseURL 把 `/zhxy-bfc` 补上 —— 抓包里线上真实路径带这个前缀。
         */
        const val YWLZ = "https://ywlz.aiit.edu.cn/zhxy-bfc"

        /** 考勤（查寝/活动）那一域，没有网关前缀。 */
        const val KQ = "https://kq.aiit.edu.cn"

        /**
         * 学生工作平台。查寝（晚点名）的详情在这一域，路径前缀是 `/app/`，
         * 身份放**请求头**、体是 JSON（见 `postJson`），跟前面几族都不一样。
         */
        const val FDYGL = "https://fdygl.aiit.edu.cn"

        /** 身份字段。`userId`/`userCode` 跟 `_userCode` 是同一个值，校方三条接口各认一个，全给。 */
        fun identityFields(id: SchoolIdentity): List<Pair<String, String>> = listOf(
            "access_token" to id.accessToken,
            "_userCode" to id.userCode,
            "userId" to id.userCode,
            "userCode" to id.userCode,
            "_userName" to enc(id.userName),
            "_userType" to id.userType,
            "appId" to APP_ID,
        )

        /** H5 那套身份字段：token 换成驼峰名，多一个 `xh`，不带 appId 和用户名。见 `postH5`。 */
        fun h5IdentityFields(id: SchoolIdentity): List<Pair<String, String>> = listOf(
            "accessToken" to id.accessToken,
            "_userCode" to id.userCode,
            "_userType" to id.userType,
            "userCode" to id.userCode,
            "xh" to id.userCode,
        )

        fun enc(s: String): String =
            if (s.isEmpty()) "" else runCatching { formUrlEncode(s) }.getOrDefault(s)

        /** 分页壳：`data.data` 才是数组。拿不到就回空数组，不抛。 */
        fun page(root: JSONObject): JSONArray = root.optJSONObject("data")?.optJSONArray("data") ?: JSONArray()

        /** ywlz / csh 那一族的信封：数组直接挂根节点的 `rows`。 */
        fun rows(root: JSONObject): JSONArray = root.optJSONArray("rows") ?: JSONArray()

        /** 有的接口 `data` 直接就是数组，有的挂在 `data` 里再套一层，两种都吃。 */
        fun array(root: JSONObject): JSONArray {
            val d = root.opt("data")
            return when (d) {
                is JSONArray -> d
                is JSONObject -> d.optJSONArray("data") ?: JSONArray()
                else -> JSONArray()
            }
        }
    }
}
