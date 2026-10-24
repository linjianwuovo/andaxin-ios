package com.andaxin.data.local

import com.andaxin.core.map.LatLng
import com.andaxin.data.RouteTemplate
import com.andaxin.data.RunDetail
import com.andaxin.data.RunDraft
import com.andaxin.data.RunRecord
import com.andaxin.data.SportPlan
import com.andaxin.data.SportSummary
import com.andaxin.data.SportsFeed
import java.io.File
import java.util.UUID
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
class LocalSports(private val routeFile: File? = null) : SportsFeed {

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

    override suspend fun routes(): List<RouteTemplate> = withContext(Dispatchers.IO) { readRoutes() }

    override suspend fun saveRoute(route: RouteTemplate) = withContext(Dispatchers.IO) {
        val all = readRoutes().toMutableList()
        all.removeAll { it.id == route.id }
        all += route
        writeRoutes(all)
    }

    override suspend fun deleteRoute(id: String) = withContext(Dispatchers.IO) {
        writeRoutes(readRoutes().filterNot { it.id == id })
    }

    private fun readRoutes(): List<RouteTemplate> {
        val file = routeFile ?: return emptyList()
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
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
        val file = routeFile ?: return
        val arr = JSONArray()
        all.forEach { r ->
            val o = JSONObject()
            o.put("id", r.id.ifBlank { UUID.randomUUID().toString() })
            o.put("name", r.name)
            o.put("points", JSONArray().apply {
                r.points.forEach { put(JSONObject().put("lat", it.latitude).put("lng", it.longitude)) }
            })
            arr.put(o)
        }
        file.parentFile?.mkdirs()
        file.writeText(arr.toString())
    }
}
