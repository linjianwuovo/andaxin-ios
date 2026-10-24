package com.andaxin.core.media

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.andaxin.core.json.JSONObject

/**
 * 录播看到第几分 —— 存在本机。
 *
 * 为什么自己存：校方那两条进度上报**实测被服务端拒**。清点表 D4/D5/D6 三条
 * （`thirdParty/liveRecordLog`、`video/progressLog`、`weike/progressLog`）
 * 10-20 又在 PC 上各打了一遍，三条一律回 `{message:"访问受限", status:"0", success:false}`。
 * 也就是说官方 App 那边也记不住进度，这不是我们少接了一条接口。
 *
 * 存 `filesDir/play_progress.json` 而不是 DataStore：这是一张"资源 id → 位置"的表，
 * 条数会长（一学期上百节），塞进偏好里会把那份小文件撑成一堆散键。
 * （运动那边的路线文件 10-21 随着路线规划整块删了，这里不再有同类。）
 *
 * 一个 json 装得下就整读整写，不加索引、不做增量 —— 这个量级不值得。
 */
class PlayProgress(dir: File) {

    private val file = File(dir, "play_progress.json")

    /** 上次看到哪儿（毫秒）。没存过、或者已经看完的，回 0 = 从头放。 */
    suspend fun positionOf(id: String): Int = withContext(Dispatchers.IO) {
        read().optJSONObject(id)?.optInt("at", 0) ?: 0
    }

    /** 存过进度的那些，一屏列表上要显示"看到 X:XX"。 */
    suspend fun all(): Map<String, Int> = withContext(Dispatchers.IO) {
        val o = read()
        buildMap {
            for (k in o.keys()) {
                val at = o.optJSONObject(k)?.optInt("at", 0) ?: 0
                if (at > 0) put(k, at)
            }
        }
    }

    /**
     * 记一次位置。
     *
     * `length` 给 0（还没准备好）时不写 —— 那种情况下 `currentPosition` 也是 0，
     * 写下去会把已有的进度冲成"从头"。这条不是猜的，是 MediaPlayer 的 prepare 是异步的。
     *
     * 看到 98% 以后当成看完，位置清掉：下次从头放，比停在最后十几秒反复重播有用。
     */
    suspend fun save(id: String, atMs: Int, lengthMs: Int) = withContext(Dispatchers.IO) { write(id, atMs, lengthMs) }

    /**
     * 同步存一次。只给"离开这一屏"那一下用 ——
     * 那里已经不能在协程里待着了（协程可能还没跑完组合就被拆掉），
     * 而这一张 json 就几 KB，主线程写一次换不来什么问题。
     */
    fun saveNow(id: String, atMs: Int, lengthMs: Int) = write(id, atMs, lengthMs)

    private fun write(id: String, atMs: Int, lengthMs: Int) {
        if (id.isBlank() || lengthMs <= 0 || atMs < 0) return
        runCatching {
            val o = read()
            if (atMs >= lengthMs * 0.98) {
                o.remove(id)
            } else {
                o.put(
                    id,
                    JSONObject()
                        .put("at", atMs)
                        .put("len", lengthMs)
                        .put("ts", System.currentTimeMillis()),
                )
            }
            // 只留最近这些：一学期攒下来上百条，而人其实只会回看最近几节
            val keep = 120
            if (o.length() > keep) {
                val byAge = o.keys().asSequence()
                    .map { it to (o.optJSONObject(it)?.optLong("ts") ?: 0L) }
                    .sortedBy { it.second }
                    .take(o.length() - keep)
                    .map { it.first }
                byAge.forEach { o.remove(it) }
            }
            file.parentFile?.mkdirs()
            // 先写临时文件再改名：中途被杀就不会留下半份 json 把整张表废掉
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(o.toString())
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }
    }

    private fun read(): JSONObject = runCatching {
        if (!file.exists()) JSONObject() else JSONObject(file.readText())
    }.getOrElse { JSONObject() }

    companion object {
        /** 毫秒 → "12:34"，超过一小时带小时。界面上"上次看到"用这个。 */
        fun clock(ms: Int): String {
            if (ms <= 0) return ""
            val s = ms / 1000
            val h = s / 3600
            val m = (s % 3600) / 60
            val r = s % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%02d:%02d".format(m, r)
        }
    }
}
