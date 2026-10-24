package com.andaxin.data

import com.andaxin.core.map.LatLng

/*
 * 运动这一块的"要什么"。上传那一侧的报文还没进这仓：
 * 学校那台 sports 是明文 http 且只有校内可达，十个键里九个 RSA、runningRoute 走明文 ——
 * 这些细节要等我们自己再抓一次实样才写进 CaptureSports，不照旧仓的注释当已知。
 */

data class RunPoint(
    val atMillis: Long,
    val where: LatLng,      // WGS-84，交给学校前才转 BD-09
    val metersPerSecond: Double,
)

/** 本机画的一条模拟线（只在高级入口里可见/可用）。 */
data class RouteTemplate(
    val id: String,
    val name: String,
    val points: List<LatLng>,
)

data class RunRecord(
    val id: String,
    val date: String,
    val km: Double,
    val minutes: Double,
    val pacePerKm: String,
    val valid: Boolean,
    val invalidReason: String,
)

data class RunDetail(
    val record: RunRecord,
    val track: List<LatLng>,
    val lane: String,
    val schoolComment: String,
)

data class SportSummary(
    val weekKm: Double,
    val weekRequiredKm: Double,
    val weekCount: Int,
    val bestKm: Double,
    val semesterKm: Double,
    /**
     * 其中晨跑多少。校方那发 `totalInfo` 里是 `morningExtraMile`，
     * **已经含在 `totalMile` 里**，别加两遍。
     */
    val morningKm: Double = 0.0,
)

/**
 * 运动首页那张计划卡要的东西。
 *
 * 来源全是校方那台 sports 的读侧，10-21 傍晚用设备上这把活 token 现打对过：
 * `checkDsStudent`（年级）、`common/currentTerm`（学年学期）、`index/extraInfo`（计划/已完成）、
 * `extra/extraDetailInfo`（日期/今日/最长/有效阈值）、`index/clubInfo`（体育课）、
 * `check/getTotalInfo`（晨跑/团体）。数字能缺就缺 —— 缺了界面写"—"，不拿 0 冒充。
 */
data class SportPlan(
    val grade: String = "",
    val termTitle: String = "",
    val planStart: String = "",
    val planEnd: String = "",
    val planKm: Double? = null,
    val doneKm: Double? = null,
    val leftKm: Double? = null,
    val todayKm: Double? = null,
    val todayDate: String = "",
    val bestKm: Double? = null,
    val bestDate: String = "",
    val minOnceKm: Double? = null,
    val maxDayKm: Double? = null,
    val minSpeedKmh: Double? = null,
    val maxSpeedKmh: Double? = null,
    val morningKm: Double? = null,
    val groupKm: Double? = null,
    val groupCount: Int? = null,
    val clubName: String = "",
    val memberLevel: String = "",
    val teacher: String = "",
    val studentCount: Int? = null,
)

/** 开始一场时校方给的那两个 id：上传时 `exerciseId` / `memberId` 就填它们。 */
data class RunSession(
    val exerciseId: String,
    val memberId: String,
    val runningType: String = "1",
)

data class RunDraft(
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val km: Double,
    val seconds: Int,
    val points: List<RunPoint>,
    /** 开始那一场时拿到的会话；空串表示没拿到，提交会明确失败而不是拼一个空 id。 */
    val exerciseId: String = "",
    val memberId: String = "",
    val runningType: String = "1",
)

interface SportsFeed {
    suspend fun summary(): Result<SportSummary>
    suspend fun plan(): Result<SportPlan>

    /**
     * 开始一场之前必须拿到的会话（大一大二：extraInfo.extraId + extraDetail.memberId；
     * 大三大四：startRunning.do 回的 rows[0]）。拿不到就别开始 —— 跑完也交不上去。
     */
    suspend fun beginRun(): Result<RunSession>
    suspend fun records(page: Int): Result<List<RunRecord>>
    suspend fun detail(id: String): Result<RunDetail>
    suspend fun maintenanceLog(): Result<List<Pair<String, String>>>

    /** 写操作：界面停在确认页，按下那一下由用户完成，失败绝不自动重试。 */
    suspend fun submit(draft: RunDraft): Result<String>

    // 模拟线只存在本机，不走校方；高级模式下才会被界面取出来。
    suspend fun routes(): List<RouteTemplate>
    suspend fun saveRoute(route: RouteTemplate)
    suspend fun deleteRoute(id: String)
}
