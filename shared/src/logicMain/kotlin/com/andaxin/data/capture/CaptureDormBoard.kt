package com.andaxin.data.capture

import com.andaxin.core.store.SessionStore
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import com.andaxin.core.json.JSONArray
import com.andaxin.core.json.JSONObject
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.datetime.minus


/*
 * 查寝那一屏的数据：月概览 + 签到月历 + 当天任务（查寝 / 主题签到两节）。
 *
 * 为什么不用现在那套"从 ywlz 待办列表里挑 msgType=2"：那条口给的只有
 * 「签到时间到达」这一句和收到时间，**没有采集窗口、也没有"我签没签"**。
 * 10-20 晚拿我们自己那把活 token 把 fdygl 这四发逐条现打了一遍（`lxtmp/_dorm_probe.md`、
 * `_dorm_subject.md`），三条读口的真值都对上了，所以这一屏换成 fdygl 直连：
 *
 * - `POST /app/dorm/collectionStudentStatics` 体 `{staticDate:"2026-10", createBy:<学号>, taskMajorType:"3"}`
 *   → `data{signinedNum:13, notSigninedNum:1, signiningNum:20, leaveNum:0, leaveSchoolNum:6, qrCodeNum:0}`。
 *   **`createBy` 必带**：同一个体不带 `createBy` 打出去回的是 `data:null`（长度 57），
 *   而 `data:null` 在这套信封里和"这个月一次没签"长得一模一样 —— 所以拿不到就当失败，不渲染成六个 0。
 * - `POST /app/dorm/listDateCheck` 体 `{startTime:"2026-10-01", endTime:"2026-10-31"}`
 *   → `data[]` 实测 30 条 `{taskDate:"2026-10-01", taskNum:1}`，就是月历上那些点。
 * - `POST /app/dorm/pageStudentSignIn` 体 `{pageNum, pageSize, createBy, taskMajorType:"3"}`
 *   → 数组挂**根节点 `rows`**（`data` 是 null），一条 19 键，实测用到的：
 *   `id`(19 位，就是详情那条 `getTaskInfoByDateId` 要的 `dateId`) `title` `taskDate`
 *   `timedStartTime`/`timedEndTime`(`2026-10-20 21:00`) `executionedStatus`("1"=已签)
 *   `executionedStatusTxt`("已签到") `signinMode` `collectionMethod` `createByName` `unit`。
 * - `POST /app/signinSubject/pageStudentSignIn` 同一条形状，**这是"主题签到"那一节**：
 *   现打回的 `rows[]` 是 0 条（这账号今天没有），信封是对的。
 *   注意别指望用 `taskMajorType` 把上面那条查寝的口切成主题签到 ——
 *   我把 3 换成 2 打过，**回的是同一条任务、同一个 id**，也就是这一族这个参数在服务端不作数，
 *   两节靠的是**两个不同路径**。
 *
 * 身份在**请求头**里、体是 JSON（`School.postJson`），跟 in.aiit 那套表单身份不通用。
 */

/** 月概览那六个数。名字照服务端那四个键的意思来，不是我另起的一套。 */
data class DormStatics(
    val signed: Int,
    val notSigned: Int,
    val monthTotal: Int,
    val leave: Int,
    val offCampus: Int,
    val qrCode: Int,
)

/** 月历上的一天：`taskNum` 大于 0 才画点。 */
data class DormDay(val date: String, val taskNum: Int)

/** 一条签到任务。`dateId` 是进详情那条 `getTaskInfoByDateId` 的入参。 */
data class DormTask(
    val dateId: String,
    val title: String,
    val date: String,
    val startAt: String,
    val endAt: String,
    val signed: Boolean,
    val statusTxt: String,
    val mode: String,
    val creator: String,
    val unit: String,
) {
    val isToday: Boolean get() = date == com.andaxin.core.concurrency.todayLocalDate().toString()
}

/**
 * 签到详情里真正驱动界面的那几项。键名照 `getTaskInfoByDateId` 的响应，
 * 不自己另起一套：`signinPhoto="1"` 是"要拍照"、`signinPhotoName` 才是已签到那张图的地址。
 */
data class DormTaskInfo(
    val dateId: String,
    val title: String,
    val date: String,
    val startAt: String,
    val endAt: String,
    val signed: Boolean,
    val signinAt: String,
    val signinMode: String,
    val needPhoto: Boolean,
    val placeName: String,
    val locationRange: String,
    val requirement: String,
    val creator: String,
    val unit: String,
    val photoName: String,
    val status: String,
    val lateReturn: String,
    val replenish: String,
)

/** fdygl 里缺值是 JSON 的 null，`optString` 会把它读成字符串 "null"，不能原样上屏。 */
private fun JSONObject.clean(key: String): String {
    val v = opt(key)
    return if (v == null || v == JSONObject.NULL) "" else v.toString().trim()
}

/** 历史那两节。分开装是因为界面上它们是两栏，混成一条列表就分不回去了。 */
data class DormHistory(val dorm: List<DormTask>, val subject: List<DormTask>) {
    val all: List<DormTask> get() = dorm + subject
    val isEmpty: Boolean get() = dorm.isEmpty() && subject.isEmpty()

    companion object {
        val EMPTY = DormHistory(emptyList(), emptyList())
    }
}

/** 一屏要的一次拉齐的东西。 */
data class DormBoard(
    val month: String,
    val statics: DormStatics?,
    val days: List<DormDay>,
    val dormTasks: List<DormTask>,
    val subjectTasks: List<DormTask>,
    val staticsWhy: String?,
    /**
     * 这一月的**历史**签到记录。
     *
     * 上面那两列只管"今天的待办"：`pageStudentSignIn` 不回历史，
     * 所以点了月历上过去某一天，光靠那两列永远是空的 —— 他这次问的就是这个。
     * 历史走 `collectionStudentPage` / `app/signin/queryPage`，见 [historyRows]。
     * 空列表有两种意思（学校不认区间 / 这月真没有），所以只拿来做加法，不拿来下结论。
     */
    val history: DormHistory = DormHistory.EMPTY,
)

class CaptureDormBoard(private val school: School, private val session: SessionStore) {

    /**
     * 一次拉五发（概览、月历、今天的查寝、今天的主题签到、这一月的历史），
     * 任何一发挂了都不拖垮整屏：概览挂了界面说"统计没拿到"，任务挂了说任务没拿到，各说各的。
     */
    suspend fun load(date: LocalDate = com.andaxin.core.concurrency.todayLocalDate()): Result<DormBoard> {
        val code = session.identity()?.userCode.orEmpty()
        if (code.isBlank()) return Result.failure(Exception("还没登录，这一屏要校方数据"))
        val month = date.year.toString().padStart(4, '0') + "-" + date.monthNumber.toString().padStart(2, '0')
        val first = LocalDate(date.year, date.monthNumber, 1).toString()
        val last = LocalDate(date.year, date.monthNumber, 1).plus(1, kotlinx.datetime.DateTimeUnit.MONTH).minus(kotlinx.datetime.DatePeriod(days = 1)).toString()
        return coroutineScope {
            val statics = async {
                school.postJson(
                    "$FDY/app/dorm/collectionStudentStatics",
                    JSONObject().put("staticDate", month).put("createBy", code).put("taskMajorType", "3"),
                )
            }
            val days = async {
                school.postJson(
                    "$FDY/app/dorm/listDateCheck",
                    JSONObject().put("startTime", first).put("endTime", last),
                )
            }
            val dorm = async { page("/app/dorm/pageStudentSignIn", code, "3") }
            val subject = async { page("/app/signinSubject/pageStudentSignIn", code, "2") }
            val history = async { historyRows(first, last) }
            val s = statics.await()
            remember(dorm.await() + subject.await())
            Result.success(
                DormBoard(
                    month = month,
                    // `data:null` 和"六个 0"是两回事，见文件头：拿不到对象就是失败
                    statics = s.getOrNull()?.optJSONObject("data")?.let {
                        DormStatics(
                            signed = it.optInt("signinedNum"),
                            notSigned = it.optInt("notSigninedNum"),
                            monthTotal = it.optInt("signiningNum"),
                            leave = it.optInt("leaveNum"),
                            offCampus = it.optInt("leaveSchoolNum"),
                            qrCode = it.optInt("qrCodeNum"),
                        )
                    },
                    days = days.await().getOrNull()?.let { root ->
                        val a = root.optJSONArray("data") ?: JSONArray()
                        (0 until a.length()).mapNotNull { i ->
                            a.optJSONObject(i)?.let { o ->
                                val d = o.optString("taskDate")
                                if (d.isBlank()) null else DormDay(d, o.optInt("taskNum"))
                            }
                        }
                    }.orEmpty(),
                    dormTasks = dorm.await(),
                    subjectTasks = subject.await(),
                    history = history.await(),
                    staticsWhy = if (s.getOrNull()?.optJSONObject("data") == null) {
                        s.exceptionOrNull()?.message ?: "签到统计那条口没回数据"
                    } else null,
                )
            )
        }
    }

    /**
     * 首页那一格只要"下一次查寝几点"。
     *
     * 原来那一格读的是 ywlz 待办里第一条晚点名消息的**收到时间** —— 那条口只在采集窗口
     * 打开时才来消息，所以 10-21 凌晨装机时它显示的是 10-20 21:00：昨晚那次，
     * 而 fdygl 这条列表里今天 21:00 那条明明挂着"未开始"。所以换成从任务列表里挑：
     * 只看今天和以后的，自己按日期排一次 —— 不靠服务端给的顺序。
     * 一条都没有（这月没排晚点名）就回 null，让首页退回原来那个来源。
     */
    suspend fun nextTask(): DormTask? {
        val code = session.identity()?.userCode.orEmpty()
        if (code.isBlank()) return null
        val today = com.andaxin.core.concurrency.todayLocalDate().toString()
        return page("/app/dorm/pageStudentSignIn", code, "3")
            .filter { it.date >= today }
            .minByOrNull { it.date + it.startAt }
    }

    /**
     * 签到详情（要动手签到的那一屏用）。
     *
     * 走 `getTaskInfoByDateId`，字段含混的地方照他旧项目已验证的取法：
     * 签到范围优先取 `signinLocations[0]` 的 `lngLat`/`content`，没有再退顶层 `lng`/`lat`。
     * 是否需要拍照只看 `signinPhoto="1"`；`signinPhotoName` 是**已签到那张图**，不是标记。
     * 这里额外把"签到要求里写了拍照/自拍"也算作要拍照 —— 服务端字段偶尔不给，
     * 但要求那几行是明写的，不能让用户交完才发现少一张图。
     */
    suspend fun taskInfo(dateId: String): Result<DormTaskInfo> {
        if (dateId.isBlank()) return Result.failure(Exception("这一条没有 dateId，进不去详情"))
        val root = school.postJson(
            "$FDY/app/dorm/getTaskInfoByDateId",
            JSONObject().put("dateId", dateId),
        ).getOrElse { return Result.failure(it) }
        val d = root.optJSONObject("data") ?: return Result.failure(Exception("签到详情没回数据"))
        val base = lastTasks[dateId]
        val firstLoc = d.optJSONArray("signinLocations")?.optJSONObject(0)
        val requirement = d.clean("requirement")
        val needPhoto = d.clean("signinPhoto") == "1" ||
            d.clean("photoRequire") == "1" ||
            requirement.contains("拍照") || requirement.contains("自拍")
        val rangeRaw = firstLoc?.opt("content")
            ?.takeUnless { it == JSONObject.NULL }
            ?.toString().orEmpty()
            .ifBlank { d.clean("locationRange") }
        val place = firstLoc?.clean("signinLocation").orEmpty()
            .ifBlank { firstLoc?.clean("name").orEmpty() }
            .ifBlank { d.clean("signinPlaceName") }
            .ifBlank { d.clean("signinPlace") }
        return Result.success(
            DormTaskInfo(
                dateId = d.clean("dateId").ifBlank { dateId },
                title = d.clean("title").ifBlank { base?.title ?: "签到任务" }.trim(),
                date = d.clean("taskDate").ifBlank { base?.date.orEmpty() },
                startAt = d.clean("collectionStartTime")
                    .ifBlank { d.clean("timedStartTime").substringAfter(' ') }
                    .ifBlank { base?.startAt.orEmpty() },
                endAt = d.clean("collectionEndTime")
                    .ifBlank { d.clean("timedEndTime").substringAfter(' ') }
                    .ifBlank { base?.endAt.orEmpty() },
                signed = d.clean("signStatus") == "1" || base?.signed == true,
                signinAt = d.clean("signinDate"),
                signinMode = d.clean("signinMode").ifBlank { base?.mode ?: "1" },
                needPhoto = needPhoto,
                placeName = place,
                locationRange = rangeRaw,
                requirement = requirement,
                creator = d.clean("createName").ifBlank { base?.creator.orEmpty() },
                unit = d.clean("createOrgName").ifBlank { base?.unit.orEmpty() },
                photoName = d.clean("signinPhotoName"),
                status = d.clean("status"),
                lateReturn = d.clean("lateReturnTime"),
                replenish = d.clean("replenish"),
            )
        )
    }

    /** 先传图，再把 `data.path` 填进签到表的 `signinPhoto`。 */
    suspend fun uploadPhoto(bytes: ByteArray, fileName: String): Result<String> {
        val root = school.postFile("$FDY/app/file/uploadFileToFastdfs", bytes, fileName).getOrElse {
            return Result.failure(it)
        }
        val data = root.opt("data")
        val path = when (data) {
            is JSONObject -> listOf("path", "url", "fileUrl", "fullPath", "filePath")
                .firstNotNullOfOrNull { data.optString(it).takeIf { s -> s.isNotBlank() } }
                .orEmpty()
            is String -> data
            else -> ""
        }
        if (path.isBlank()) return Result.failure(Exception("照片传上去了，但没拿到路径"))
        return Result.success(path)
    }

    /**
     * 提交签到。字段与旧项目一致：`taskDateId` + `signinPhoto` + `signinPlace` + `latLng`（BD-09，经度在前）。
     * 这一发是写操作 —— 界面只在用户按「提交签到」时调用，别自动发。
     */
    suspend fun submitSignIn(
        dateId: String,
        photoUrl: String,
        place: String,
        bdLng: Double,
        bdLat: Double,
    ): Result<Unit> {
        val body = JSONObject()
            .put("taskDateId", dateId)
            .put("signinPhoto", photoUrl)
            .put("signinPlace", place)
            .put("latLng", com.andaxin.core.text.fixed(bdLng, 6) + "," + com.andaxin.core.text.fixed(bdLat, 6))
            .put("outSignin", "0")
            .put("outSigninDesc", "")
        school.postJson("$FDY/app/dorm/signIn", body).getOrElse { return Result.failure(it) }
        return Result.success(Unit)
    }

    /**
     * 某一天的签到记录（查寝 + 主题签到两节）。**点月历上过去某一天要走这一发**。
     *
     * 为什么不能沿用 [load] 里那两列：`pageStudentSignIn` 只管当天待办，历史那批它一条都不回 ——
     * 他旧项目 `checkin/data/CheckinRepository.kt` 的注释里把这条写死了，连症状都记着：
     * "日历有红点、统计有数字，列表却是空的"。官方 H5（fdygl swp-app 的 /studentEvent/bedCheck）
     * 「历史查寝任务」那个 tab 走的是 `app/dorm/collectionStudentPage`，参数照他那一份：
     * `type:"2"` + `taskMajorType:"3"` + 单日 `startTime`。主题签到那节是 `app/signin/queryPage`，
     * 多一个 `releaseStatus:"2"`（已发布/已结束）。这两发的参数名与响应形状都出自他跑通的那份。
     */
    suspend fun historyOn(date: String): DormHistory = historyRows(date, date)

    /**
     * 一段区间内的历史记录。给月历那些点上色用。
     *
     * 【没验过的地方】他那份是**按单日**发的（官方那个 tab 就是一天一页），
     * 我这里多塞一个 `endTime` 想一次拿整月 —— 学校认不认这个区间没有一手证据。
     * 认，月历就整月有色；不认，最坏是只回 `startTime` 那一天，其余日子退回"没记录"那颗灰点，
     * 不会把没签过的日子画成签过 —— 所以这一发只拿来做加法。
     */
    suspend fun historyRows(from: String, to: String): DormHistory {
        // 两发各用一个体：共用一个 JSONObject 会把查寝那儿的 taskMajorType 漏给主题签到那一族
        val dorm = pageWith(
            "/app/dorm/collectionStudentPage",
            JSONObject()
                .put("pageNum", 1).put("pageSize", 60)
                .put("type", "2").put("taskMajorType", "3")
                .put("startTime", from).put("endTime", to),
        )
        val subject = pageWith(
            "/app/signin/queryPage",
            JSONObject()
                .put("pageNum", 1).put("pageSize", 60)
                .put("type", "2").put("releaseStatus", "2")
                .put("startTime", from).put("endTime", to),
        )
        return DormHistory(dorm, subject)
    }

    /** 任务列表那一族：数组挂根节点 `rows`，`data` 是 null。 */
    private suspend fun page(path: String, code: String, majorType: String): List<DormTask> =
        pageWith(
            path,
            JSONObject()
                .put("pageNum", 1).put("pageSize", 30)
                .put("createBy", code).put("taskMajorType", majorType),
        )

    private suspend fun pageWith(path: String, body: JSONObject): List<DormTask> {
        val root = school.postJson("$FDY$path", body).getOrNull() ?: return emptyList()
        val rows = root.optJSONArray("rows") ?: JSONArray()
        return (0 until rows.length()).mapNotNull { i ->
            rows.optJSONObject(i)?.let { o ->
                val id = o.optString("id")
                if (id.isBlank()) return@mapNotNull null
                DormTask(
                    dateId = id,
                    title = o.optString("title").trim(),
                    date = o.optString("taskDate"),
                    // 实测给的是 `2026-10-20 21:00` 这种带日期的串；日期本身另有 taskDate，所以只留时分
                    startAt = o.optString("timedStartTime").substringAfter(' '),
                    endAt = o.optString("timedEndTime").substringAfter(' '),
                    signed = o.optString("executionedStatus") == "1",
                    statusTxt = o.optString("executionedStatusTxt"),
                    mode = o.optString("signinMode"),
                    creator = o.optString("createByName"),
                    unit = o.optString("unit"),
                )
            }
        }
    }

    private companion object {
        const val FDY = School.FDYGL
    }

    /**
     * 上一次 `load()` 拿到的任务，按 `dateId` 存着。
     * 详情那一屏要"接口没通时至少把列表里已有的真字段摊开"，靠的就是这一份。
     * 只活在这个进程里，不落盘。
     */
    val lastTasks = HashMap<String, DormTask>()

    /** 记进缓存的那一步放在 load 里做，免得界面各自记得不一致。 */
    internal fun remember(tasks: List<DormTask>) {
        tasks.forEach { lastTasks[it.dateId] = it }
    }
}

/**
 * 一条签到任务的详情。
 *
 * 走的是 `getTaskInfoByDateId`（10-20 凌晨现打通过：回 113 个键、63 个非空），
 * 入参就是列表那条的 `id`。打不通时退回把列表里已有的真字段摆出来 + 服务端原话，
 * 不装成功。
 */
class CaptureDormTaskDetail(
    private val school: School,
    private val board: CaptureDormBoard,
) : com.andaxin.data.brief.DetailSource {

    override suspend fun detail(id: String): Result<com.andaxin.data.brief.BriefDetail> {
        val t = board.lastTasks[id]
        val fallback = com.andaxin.data.brief.BriefDetail(
            title = t?.title.orEmpty().ifBlank { "签到任务" },
            fields = buildList {
                if (t != null) {
                    add("日期" to t.date.ifBlank { "—" })
                    if (t.startAt.isNotBlank()) add("采集窗口" to "${t.startAt} ~ ${t.endAt}")
                    add("签了没" to t.statusTxt.ifBlank { if (t.signed) "已签到" else "没签" })
                    if (t.creator.isNotBlank()) add("发起人" to t.creator)
                    if (t.unit.isNotBlank()) add("发起组织" to t.unit)
                }
                add("日期 ID" to id)
            },
            paragraphs = listOf("这一屏是列表那条接口给的字段；详情那条没打通时只会多这一句说明。"),
        )
        if (id.isBlank()) return Result.failure(Exception("这一条没有 dateId，进不去详情"))
        return dormDetailByDateId(school, id, fallback)
    }
}
