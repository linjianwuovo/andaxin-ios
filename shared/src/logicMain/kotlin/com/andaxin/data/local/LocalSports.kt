package com.andaxin.data.local

import com.andaxin.core.map.LatLng
import com.andaxin.data.RouteTemplate
import com.andaxin.data.RunDetail
import com.andaxin.data.RunDraft
import com.andaxin.data.RunRecord
import com.andaxin.data.SportPlan
import com.andaxin.data.SportSummary
import com.andaxin.data.SportsFeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.andaxin.core.json.JSONArray
import com.andaxin.core.json.JSONObject

/**
 * 运动的占位源。记录是排版用的假数据，只负责在校方接口拉不到时撑住版面。
 *
 * 画过的模拟线仍存本机 JSON；普通界面看不到它，只有「关于连点十下」打开高级模式后，
 * 开始运动页才会出现模拟跑入口。
 */
/*
 * 平移注（10-24 深夜）：`java.io.File` 换成 `AppFiles`（Android 那台 `ContextFiles` 的根就是
 * filesDir，sport_routes.json 还是原来那份文件，老路线无缝续用）。
 * `UUID.randomUUID()` 这台没有，换成一个本机随机 id —— 它只用来在列表里匹配增删，
 * 不进任何报文。Dispatchers.IO 换共享层的 ioDispatcher。
 */
class LocalSports(private val files: com.andaxin.core.store.AppFiles? = null) : SportsFeed {

    override suspend fun summary() = Result.success(
        SportSummary(weekKm = 3.2, weekRequiredKm = 5.0, weekCount = 2, bestKm = 3.14, semesterKm = 41.6)
    )

    /** 计划卡没有占位值：编一个 60 公里的目标比空着更糟。 */
    override suspend fun plan(): Result<SportPlan> =
        Result.failure(UnsupportedOperationException("运动计划要校方数据，未登录时没有可看的"))

    override suspend fun beginRun(): Result<com.andaxin.data.RunSession> =
        Result.failure(UnsupportedOperationException("开始跑步要校方会话，未登录时开不了"))

    override suspend fun records(page: Int) = Result.success(
        listOf(
            RunRecord("r1", "10-17 07:12", 3.14, 21.4, "6'49\"", true, ""),
            RunRecord("r2", "10-15 18:03", 2.02, 14.1, "6'58\"", true, ""),
            RunRecord("r3", "10-14 06:58", 1.20, 9.8, "8'10\"", false, "配速低于下限"),
        )
    )

    override suspend fun detail(id: String) = Result.success(
        RunDetail(
            record = RunRecord(id, "10-17 07:12", 3.14, 21.4, "6'49\"", true, ""),
            track = emptyList(),
            lane = "南操场 4 道",
            schoolComment = "有效",
        )
    )

    override suspend fun maintenanceLog() = Result.success(
        listOf("10-14 06:58" to "申请维护：配速异常", "10-09 21:11" to "已驳回：轨迹缺失")
    )

    override suspend fun submit(draft: RunDraft): Result<String> =
        Result.failure(UnsupportedOperationException("上传报文还没接：这一块要等自己抓包补 CaptureSports"))

    override suspend fun routes(): List<RouteTemplate> = withContext(com.andaxin.core.concurrency.ioDispatcher) { readRoutes() }

    override suspend fun saveRoute(route: RouteTemplate) = withContext(com.andaxin.core.concurrency.ioDispatcher) {
        val all = readRoutes().toMutableList()
        all.removeAll { it.id == route.id }
        all += route
        writeRoutes(all)
    }

    override suspend fun deleteRoute(id: String) = withContext(com.andaxin.core.concurrency.ioDispatcher) {
        writeRoutes(readRoutes().filterNot { it.id == id })
    }

    private fun readRoutes(): List<RouteTemplate> {
        val fs = files ?: return emptyList()
        if (!fs.exists(ROUTES_FILE)) return emptyList()
        return runCatching {
            val arr = JSONArray(fs.readText(ROUTES_FILE).orEmpty())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val pts = o.getJSONArray("points")
                RouteTemplate(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    points = (0 until pts.length()).map { j ->
                        val p = pts.getJSONObject(j)
                        LatLng(p.getDouble("lat"), p.getDouble("lng"))
                    },
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun writeRoutes(all: List<RouteTemplate>) {
        val fs = files ?: return
        val arr = JSONArray()
        all.forEach { r ->
            val o = JSONObject()
            o.put("id", r.id.ifBlank { newId() })
            o.put("name", r.name)
            o.put("points", JSONArray().apply {
                r.points.forEach { put(JSONObject().put("lat", it.latitude).put("lng", it.longitude)) }
            })
            arr.put(o)
        }
        fs.writeText(ROUTES_FILE, arr.toString())
    }

    /** UUID 是 JVM 专属；16 随机字节的 hex，用途同前（自造路线的临时 id，不进报文）。 */
    private fun newId(): String = kotlin.random.Random.Default.nextBytes(16)
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private companion object {
        /** 还是原来那份 filesDir/sport_routes.json。 */
        const val ROUTES_FILE = "sport_routes.json"
    }
}
