package com.andaxin.data.capture

import android.os.Build
import com.andaxin.core.map.Geo
import com.andaxin.core.map.LatLng
import com.andaxin.core.net.Http
import com.andaxin.data.RunDetail
import com.andaxin.data.RunDraft
import com.andaxin.data.RunRecord
import com.andaxin.data.RunSession
import com.andaxin.data.SportPlan
import com.andaxin.data.SportSummary
import com.andaxin.data.SportsFeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt
import okhttp3.Request
import com.andaxin.core.json.JSONArray
import com.andaxin.core.json.JSONObject

/*
 * 校方那台运动（`sports.aiit.edu.cn:8082`）的读侧：总量、记录列表、维护申请列表。
 *
 * 【取证 · ③ 官方 App 发出被我们抓到的请求 + ② 我今天拿活 token 原样重发验成的响应】
 * 三条读链在 `lxtmp/m11b.flow`（10-14）里有完整请求体；10-20 上午把同一套参数换成现在这把
 * token 重发，三条一起回 `flag:true`：
 *
 * - `POST /mobile/check/getTotalInfo.do` → `data.totalInfo{totalMile, groupMile, groupCnt, extraMile, morningExtraMile}`
 *   （10-14 那次 totalMile=32.54，10-20 早上重发=42.55 —— 数在动，读的是当前考核期，不是快照）
 * - `POST /mobile/check/getCheckList.do` 多带 `pageNum pageSize queryDate("2026-10") queryType`
 *   → `rows[]`：`id exerciseName("跑步锻炼") exerciseType("2") exerciseTime(**null**)
 *   exerciseMile exerciseMileCoefficient startDate("2026-10-20 07:47")
 *   endDate(**只有时分** "07:57") exerciseState("1"|"2")`
 * - `POST /mobile/check/getApplyRecordList.do` 多带 `auditState`（空串=全部）→ `rows[]` + `total`，
 *   实样 `rows:[] total:0`（这学期没提过维护申请）
 *
 * **身份其实只要三个键**：`accessToken` + `userCode` + `studentCode`。
 * 官方那条请求体里还挂着 `_userCode`、`userName`、`xh`、`grade="大一大二学生"`，
 * 以及一个被 axios 序列化成 `_userType[0]=1&_userType[1]=1` 的**数组式重复键**。
 * 我把 grade 去掉、`_userType` 收成单值、甚至只发那三个键，**四种写法回的数据一模一样**。
 * 所以这里走 `postH5` 再补一个 `studentCode`，不去猜 `grade` 从哪个口取。
 *
 * 端口和协议别写错：是 **`http://`…:8082**，不是 https、也没有 context path。
 * 用 `https://sports.aiit.edu.cn/mobile/…` 打回来的是一坨登录页 HTML（不是 404，
 * 看着像"接口挂了"）—— 跟课表那条 `zhxy-information` 前缀写错的坑同一类。
 *
 * 【这一块仍然没接的】`submit()`（跑步上传）：十条键里九条要 RSA、`runningRoute` 走明文，
 * 而**一次真上传的报文都没抓到**（10-13 那轮 lr.flow 里只有读链）。所以它明确回失败，
 * 界面上那条说明不摘。**单次明细 10-21 接上了**（见 `detail()`，含 BD-09 轨迹）。
 */
class CaptureSports(
    private val school: School,
    private val local: SportsFeed,
) : SportsFeed {

    override suspend fun summary(): Result<SportSummary> {
        val root = post("/check/getTotalInfo.do").getOrElse { return Result.failure(it) }
        val t = root.optJSONObject("data")?.optJSONObject("totalInfo")
            ?: return Result.failure(Exception(reason(root)))
        val total = t.optString("totalMile").toDoubleOrNull() ?: 0.0
        val groupCnt = t.optString("groupCnt").toIntOrNull() ?: 0
        val morning = t.optString("morningExtraMile").toDoubleOrNull() ?: 0.0
        return Result.success(
            SportSummary(
                // 服务端只给"一共多少"和"其中晨跑多少"，**没给"本周"这个口径**。
                // 所以 weekKm 那一格不放真数（放了就是拿学期总量冒充周量），
                // 界面上该用的是 semesterKm。morningExtraMile 已经含在 totalMile 里，不加第二遍。
                weekKm = 0.0,
                weekRequiredKm = 0.0,
                weekCount = groupCnt,
                bestKm = 0.0,
                semesterKm = total,
                morningKm = morning,
            ),
        )
    }

    /**
     * 运动首页那张计划卡。
     *
     * 这一组就是旧项目里"抓透了"的那五条；10-21 傍晚拿设备上现在的 token 逐条重打过：
     * - `/mobile/sportApp/checkDsStudent.do` → `result:"大一大二学生"`（另有大三大四那一套）
     * - `/common/currentTerm.do`（**不在 /mobile 下面**）→ `data{XN:"2026",XQ:"1"}`
     * - `/mobile/index/extraInfo.do`（带 grade）→ `data.extraInfo{planMile:"60.0",completeMile:"45.55",completeRatio:"0.76"}`
     * - `/mobile/extra/extraDetailInfo.do`（带 grade、`runningType=1`）→ `data.extraDetail{extraStartDate,extraEndDate,
     *   completeMile,surplusMile,maxMile,maxMileDate,todayMile,todayDate,mixOnceMile,maxDayMile,minSpeed,maxSpeed}`
     * - `/mobile/index/clubInfo.do`（带 grade）→ `data.clubInfo{clubName,memberLevelName,teacherList,studentNum}`
     * 外加 `getTotalInfo` 的晨跑/团体两格。
     *
     * 五路各发各的：缺其中一条就把那条的格子写成"—"，不拿 0 冒充；全挂才回失败。
     */
    override suspend fun plan(): Result<SportPlan> {
        // 年级先拿：extraInfo / extraDetail / clubInfo 的请求体里都带着它
        val gradeR = post("/sportApp/checkDsStudent.do")
        val grade = gradeR.getOrNull()?.optString("result").orEmpty()
        return coroutineScope {
            val term = async { postRoot("/common/currentTerm.do") }
            val extra = async { post("/index/extraInfo.do", "grade" to grade) }
            val detail = async {
                post("/extra/extraDetailInfo.do", "grade" to grade, "runningType" to "1")
            }
            val club = async { post("/index/clubInfo.do", "grade" to grade) }
            val totals = async { post("/check/getTotalInfo.do") }

            val termR = term.await()
            val extraR = extra.await()
            val detailR = detail.await()
            val clubR = club.await()
            val totalR = totals.await()

            val extraInfo = extraR.getOrNull()?.optJSONObject("data")?.optJSONObject("extraInfo")
            val d = detailR.getOrNull()?.optJSONObject("data")?.optJSONObject("extraDetail")
            val c = clubR.getOrNull()?.optJSONObject("data")?.optJSONObject("clubInfo")
            val t = totalR.getOrNull()?.optJSONObject("data")?.optJSONObject("totalInfo")
            val termData = termR.getOrNull()?.optJSONObject("data")

            val year = termData?.optString("XN").orEmpty()
            val termNo = termData?.optString("XQ").orEmpty()
            val title = if (year.isBlank()) "" else {
                "%s-%d-%s".format(year, (year.toIntOrNull() ?: 0) + 1, termNo.ifBlank { "1" })
            }

            val plan = SportPlan(
                grade = grade,
                termTitle = title,
                planStart = d.str("extraStartDate"),
                planEnd = d.str("extraEndDate"),
                planKm = extraInfo.num("planMile"),
                doneKm = extraInfo.num("completeMile") ?: d.num("completeMile"),
                leftKm = d.num("surplusMile"),
                todayKm = d.num("todayMile"),
                todayDate = d.str("todayDate"),
                bestKm = d.num("maxMile"),
                bestDate = d.str("maxMileDate"),
                minOnceKm = d.num("mixOnceMile"),
                maxDayKm = d.num("maxDayMile"),
                minSpeedKmh = d.num("minSpeed"),
                maxSpeedKmh = d.num("maxSpeed"),
                morningKm = t.num("morningExtraMile"),
                groupKm = t.num("groupMile"),
                groupCount = t.str("groupCnt").toIntOrNull(),
                clubName = c.str("clubName"),
                memberLevel = c.str("memberLevelName"),
                teacher = c?.optJSONArray("teacherList")?.optJSONObject(0)
                    ?.optString("name").orEmpty(),
                studentCount = c.str("studentNum").toIntOrNull(),
            )

            val any = extraInfo != null || d != null || c != null || t != null
            if (!any) {
                val why = listOf(gradeR, termR, extraR, detailR, clubR, totalR)
                    .firstNotNullOfOrNull { it.exceptionOrNull() }
                    ?.message ?: "运动计划没拉到"
                return@coroutineScope Result.failure(IOException(why))
            }
            Result.success(plan)
        }
    }

    /** 某一天的记录列表。`month` 形如 "2026-10"，服务端就要这个格式。 */
    suspend fun recordsOfMonth(month: String, pageSize: Int = 50): Result<List<RunRecord>> {
        val root = post(
            "/check/getCheckList.do",
            "pageNum" to "1", "pageSize" to pageSize.toString(),
            "queryDate" to month, "queryType" to "0",
        ).getOrElse { return Result.failure(it) }
        if (!root.optBoolean("flag", false)) return Result.failure(Exception(reason(root)))
        val rows = root.optJSONArray("rows") ?: return Result.success(emptyList())
        return Result.success(
            (0 until rows.length()).mapNotNull { i ->
                val o = rows.optJSONObject(i) ?: return@mapNotNull null
                val start = o.optString("startDate")
                if (start.isBlank()) return@mapNotNull null
                val km = o.optString("exerciseMile").toDoubleOrNull() ?: return@mapNotNull null
                RunRecord(
                    id = o.optString("id"),
                    date = start,
                    km = km,
                    minutes = minutesOf(start, o.optString("endDate")),
                    pacePerKm = pace(km, minutesOf(start, o.optString("endDate"))),
                    /*
                     * `exerciseState` 现在有两把实样了：
                     * "1" = 有效（07:29 的 2.01km、07:18 的 1.00km、10-20 的 1.01km 都是 1）；
                     * "2" = 无效（10-21 夜我们交的 0.00km 那几条都是 2，明细里校方原话是
                     * "本次运动低于1公里，故无效"）。列表这一行只放两个字，
                     * 那句原话在明细里读 `remark`，别在这儿摆状态码糊成一条报错。
                     */
                    valid = o.optString("exerciseState") == "1",
                    invalidReason = when (val st = o.optString("exerciseState")) {
                        "1" -> ""
                        "2" -> "无效"
                        else -> "校方给的状态码 $st（含义没对上）"
                    },
                )
            },
        )
    }

    override suspend fun records(page: Int): Result<List<RunRecord>> =
        recordsOfMonth(SimpleDateFormat("yyyy-MM", Locale.US).format(java.util.Date()))

    /** 维护申请列表。实样是空的，所以条目键名没验过 —— 取不到标题就明说，不编。 */
    override suspend fun maintenanceLog(): Result<List<Pair<String, String>>> {
        val root = post(
            "/check/getApplyRecordList.do",
            "pageNum" to "1", "pageSize" to "20", "auditState" to "", "queryType" to "0",
        ).getOrElse { return Result.failure(it) }
        if (!root.optBoolean("flag", false)) return Result.failure(Exception(reason(root)))
        val rows = root.optJSONArray("rows") ?: return Result.success(emptyList())
        return Result.success(
            (0 until rows.length()).mapNotNull { i ->
                val o = rows.optJSONObject(i) ?: return@mapNotNull null
                val title = listOf("applyReason", "reason", "checkDate", "createTime")
                    .map { o.optString(it) }.firstOrNull { it.isNotBlank() }
                    ?: "一条申请（${o.length()} 个键，键名没有实样可对）"
                val state = listOf("auditStateTxt", "statusTxt", "auditState")
                    .map { o.optString(it) }.firstOrNull { it.isNotBlank() }.orEmpty()
                title to state
            },
        )
    }

    /**
     * 单次明细。
     *
     * 【10-21 傍晚现打对过】`POST /mobile/extra/getExtraCheck.do`，参数 `checkId` = 列表里的 `id`；
     * 回 `data.extraInfo{checkId,startDate,endDate,mile,time,speed,state,remark,runningRoute,validMile,…}`，
     * `runningRoute` 是 BD-09 的 `[{latitude,longitude}]`（和上传时那份同坐标系）。
     * 旧项目里这条也是抓到的，只是当时测试账号没有无效记录，`remark` 一直是 null。
     */
    override suspend fun detail(id: String): Result<RunDetail> {
        val root = post("/extra/getExtraCheck.do", "checkId" to id)
            .getOrElse { return Result.failure(it) }
        val info = root.optJSONObject("data")?.optJSONObject("extraInfo")
            ?: return Result.failure(IOException(reason(root).ifBlank { "这条明细没回 extraInfo" }))
        val start = info.optString("startDate")
        val km = info.optString("mile").toDoubleOrNull()
            ?: info.optString("exerciseMile").toDoubleOrNull() ?: 0.0
        val minutes = hmsMinutes(info.optString("time"))
        val state = info.optString("state")
        val remark = info.optString("remark")
        val valid = state == "1"
        val record = RunRecord(
            id = info.optString("checkId").ifBlank { id },
            date = start,
            km = km,
            minutes = minutes,
            pacePerKm = pace(km, minutes),
            valid = valid,
            invalidReason = if (valid) "" else remark.ifBlank { "校方判定无效（state=$state）" },
        )
        return Result.success(
            RunDetail(
                record = record,
                track = trackOf(info),
                // 官方明细里没有"跑道"字段，别拿别的东西冒充
                lane = "",
                schoolComment = if (valid) "有效" else remark.ifBlank { "无效（state=$state）" },
            ),
        )
    }

    /**
     * 开始一场之前拿会话 id。
     *
     * 大一大二：`extraInfo.extraId` + `extraDetail.memberId`（10-21 现打：1d22… / c851…）。
     * 大三大四：官方要先 `startRunning.do` 建这一场，`rows[0]` 里给 extraId/memberId；
     * 不在这一步拿，跑完上传时再拿会换一场，交上去也对不上。
     */
    override suspend fun beginRun(): Result<RunSession> {
        val gradeR = post("/sportApp/checkDsStudent.do")
        val grade = gradeR.getOrNull()?.optString("result").orEmpty()
        if (grade.contains("大三大四")) {
            val root = post("/sportApp/startRunning.do").getOrElse { return Result.failure(it) }
            val row = root.optJSONArray("rows")?.optJSONObject(0)
                ?: return Result.failure(IOException("startRunning 没回 rows[0]，这一场开不了"))
            val eid = row.optString("extraId")
            val mid = row.optString("memberId")
            if (eid.isBlank() || mid.isBlank()) {
                return Result.failure(IOException("startRunning 没给会话 id（extraId/memberId）"))
            }
            return Result.success(RunSession(eid, mid))
        }
        val extra = post("/index/extraInfo.do", "grade" to grade)
            .getOrElse { return Result.failure(it) }
        val detail = post("/extra/extraDetailInfo.do", "grade" to grade, "runningType" to "1")
            .getOrElse { return Result.failure(it) }
        val eid = extra.optJSONObject("data")?.optJSONObject("extraInfo")
            ?.optString("extraId").orEmpty()
        val mid = detail.optJSONObject("data")?.optJSONObject("extraDetail")
            ?.optString("memberId").orEmpty()
        if (eid.isBlank() || mid.isBlank()) {
            return Result.failure(IOException("校方没给这一场的会话 id（extraId/memberId）"))
        }
        return Result.success(RunSession(eid, mid))
    }

    /**
     * 交这一场。**只在用户按了结束页那颗「提交」后调用**。
     *
     * 10 个键的拼包在 [SportsUploadCodec]；这里只负责填表单和判"有没有真落库"：
     * 服务端回 `data.id`（旧版也可能是根上的 id），`""` 或 `"0"` 都当没存 —— 那一条
     * 以前被当成成功过，结果学校那边什么都没有。
     */
    override suspend fun routes() = local.routes()

    override suspend fun saveRoute(route: com.andaxin.data.RouteTemplate) = local.saveRoute(route)

    override suspend fun deleteRoute(id: String) = local.deleteRoute(id)

    override suspend fun submit(draft: RunDraft): Result<String> {
        /*
         * 这里不按公里数、时长、点数拦人（10-21 夜他明说：不要限制公里数提交）。
         * "单次不少于 1 公里、速率 3–12 km/h" 是学校自己判的口径，写在开始那一屏；
         * 我们照原样把这一场交上去，判不判有效由服务端说，不替它先扣着不交。
         * 只留一条：`memberId` 空了这包根本挂不到俱乐部上，那是拼包缺件，不是"跑太少"。
         */
        if (draft.memberId.isBlank()) {
            return Result.failure(IOException("这一场没有 memberId，交不上去 —— 开始的时候没拿到"))
        }
        val endText = serverTimeText() ?: fmtTime(draft.endedAtMillis)
        val startText = fmtTime(draft.startedAtMillis)
        val speed = if (draft.seconds > 0) draft.km / (draft.seconds / 3600.0) else 0.0
        val list = SportsUploadCodec.buildListField(
            SportsUploadCodec.Run(
                // 官方那四条上传报文（10-14 17:57，全 200、data.id 是真 id）里
                // `exerciseId` 的值就是空串 —— 十个键照旧加密，值给空。
                // 我们原先把 extraId 塞进去，服务端 iBatis 就报"Could not set parameters"。
                exerciseId = "",
                memberId = draft.memberId,
                runningType = draft.runningType,
                startText = startText,
                endText = endText,
                mileKm = draft.km,
                timeSeconds = draft.seconds.toLong(),
                speedKmh = speed,
                trackWgs = draft.points.map { it.where.latitude to it.where.longitude },
                deviceModel = Build.MODEL.orEmpty(),
            ),
        )
        val code = school.userCode()
        val token = school.token()
        val name = school.userName()
        /*
         * 2026-10-21 22:11 官方安小信自己发出的两条上传（米11 上 tcpdump 抓 8082 现抓现拆）：
         * 第一条 200 + `data.id:"fb592e54…"` 真落库，第二条同一场回 `data.id:"0"`。逐项对出来的形状：
         *   头：`studentCode: <学号>` —— 它是**请求头**，不是表单字段。我们先前把它塞进表单，
         *       服务端读不到，才先报 `studentCode` 为 null 的"无效的列类型 1111"、
         *       补进表单后又改报"Could not set parameters"。
         *   体：就六个键，顺序 `list / access_token / _userCode / userId / _userName / appId`；
         *       姓名在包上是 urlencode 一次的（3 个字 → 27 字节），FormBody 正好给这一份。
         *   list：十个键全加密，第一个（exerciseId）值是空串，第九个（runningRoute）明文。
         * 一个键都不多给。
         */
        val root = school.postExact(
            "$BASE/extra/addExtraCheckNew.do",
            listOf(
                "list" to list,
                "access_token" to token,
                "_userCode" to code,
                "userId" to code,
                "_userName" to name,
                "appId" to SportsUploadCodec.APP_ID,
            ),
            "studentCode" to code,
        ).getOrElse { e ->
            val raw = e.message.orEmpty()
            // 服务端把 iBatis 整段栈甩回来了：留够长度，那一句里点名是哪个参数没设进去
            val short = if (raw.contains("Exception") || raw.contains("SQLException")) {
                "学校数据库拒了这次提交（参数没对上）。原文：" + raw.take(600) + "…"
            } else {
                raw.ifBlank { "提交失败" }
            }
            return Result.failure(IOException(short))
        }
        val id = root.optJSONObject("data")?.optString("id").orEmpty()
            .ifBlank { root.optString("id") }
        if (id.isBlank() || id == "0") {
            return Result.failure(
                IOException(
                    reason(root).ifBlank { "服务器收了但没落库（id=${id.ifBlank { "空" }}），这一场可能不计" },
                ),
            )
        }
        return Result.success(id)
    }

    /**
     * 服务器时间。上传的 `endDate` 官方就是拿这条现取的：
     * `GET /mobile/time/getServerTime.do` → `data.serverTime:"2026-10-21 17:47:18"`（10-21 现打）。
     * 取不到就退回本机时间，别把一场跑步卡死在一个时间接口上。
     */
    private suspend fun serverTimeText(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("$BASE/time/getServerTime.do")
                .get()
                .header("Accept", "application/json")
                .header("User-Agent", School.AGENT)
                .build()
            Http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val text = resp.body?.string().orEmpty()
                JSONObject(text).optJSONObject("data")
                    ?.optString("serverTime")?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    private fun fmtTime(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(java.util.Date(millis))

    /**
     * 三键身份 + 分页/日期等业务参数。见文件注释那段"身份其实只要三个键"。
     *
     * 返回 `Result` 而不是可空 JSONObject：上一版这里 `getOrNull()` 把服务端的原话压成了 null，
     * 于是界面上只能显示我自己写的那句兜底话（"getTotalInfo 没回数据"），
     * 真原因（是没登录、连不上、还是它回了"非法访问"）全丢了 —— 10-20 中午就是卡在这儿多看了一轮。
     */
    private suspend fun post(path: String, vararg fields: Pair<String, String>): Result<JSONObject> {
        val code = school.userCode()
        if (code.isBlank()) return Result.failure(IOException("本机还没登录，这一屏要校方数据"))
        return school.postH5("$BASE$path", *fields, "studentCode" to code)
    }

    /**
     * `/common/currentTerm.do` 这一类**不在 `/mobile` 下面**的口。抓包里它的真实路径是
     * `/common/currentTerm.do`，照着 `$BASE` 拼会变成 `/mobile/common/...`，回 404/登录页。
     */
    private suspend fun postRoot(path: String, vararg fields: Pair<String, String>): Result<JSONObject> {
        val code = school.userCode()
        if (code.isBlank()) return Result.failure(IOException("本机还没登录，这一屏要校方数据"))
        return school.postH5("$HOST$path", *fields, "studentCode" to code)
    }

    private fun JSONObject?.num(key: String): Double? =
        this?.optString(key)?.trim()?.toDoubleOrNull()

    private fun JSONObject?.str(key: String): String =
        this?.optString(key).orEmpty()

    /**
     * 明细里的 `runningRoute` 是 BD-09。服务器有时给 JSON 数组、有时给字符串，
     * 两种都吃；读不出来就回空，不拿猜的点拼轨迹。
     */
    private fun trackOf(info: JSONObject): List<LatLng> {
        val raw = info.opt("runningRoute") ?: return emptyList()
        val arr = when (raw) {
            is JSONArray -> raw
            is String -> runCatching { JSONArray(raw) }.getOrNull()
            else -> null
        } ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val lat = o.optDouble("latitude", Double.NaN)
            val lng = o.optDouble("longitude", Double.NaN)
            if (lat.isNaN() || lng.isNaN()) null else Geo.bdToWgs(lat, lng)
        }
    }

    private fun reason(root: com.andaxin.core.json.JSONObject): String =
        root.optString("msg")
            .ifBlank { root.optString("result") }
            .ifBlank { "校方说没通过" }

    private companion object {
        const val HOST = "http://sports.aiit.edu.cn:8082"
        const val BASE = "$HOST/mobile"

        /** `startDate="2026-10-20 07:47"`、`endDate="07:57"`（**只有时分**）→ 分钟数。 */
        fun minutesOf(start: String, end: String): Double {
            val e = end.trim()
            if (e.length < 5) return 0.0
            val em = e.substring(e.length - 5).split(":").mapNotNull { it.toIntOrNull() }
            val sm = start.substringAfter(' ').split(":").mapNotNull { it.toIntOrNull() }
            if (em.size != 2 || sm.size != 2) return 0.0
            val d = (em[0] * 60 + em[1]) - (sm[0] * 60 + sm[1])
            // 跨零点的夜跑：差成负数就加回一天，别显示 -1420 分钟
            return if (d < 0) d + 1440.0 else d.toDouble()
        }

        fun pace(km: Double, minutes: Double): String {
            if (km <= 0.0 || minutes <= 0.0) return "—"
            val per = minutes / km
            return "%d'%02d\"/km".format(per.toInt(), ((per - per.toInt()) * 60).roundToInt())
        }

        /** 明细的 `time` 是 `"00:15:08"` 这种时长。 */
        fun hmsMinutes(text: String): Double {
            val p = text.trim().split(":").mapNotNull { it.toIntOrNull() }
            return when (p.size) {
                3 -> p[0] * 60 + p[1] + p[2] / 60.0
                2 -> p[0] + p[1] / 60.0
                else -> 0.0
            }
        }
    }
}
