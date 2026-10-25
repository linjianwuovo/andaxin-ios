package com.andaxin.data.capture

import com.andaxin.core.net.Http
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.Request

/*
 * 第二课堂（`dekt.aiit.edu.cn`）。契约与全部 36 条端点见 `docs/契约_第二课堂.md`，
 * 这一份只接**有真报文的那两条读口**（10-25 16:41 那一轮抓的，字段名是从实样里抄的，不是猜的）：
 *
 * - `GET /app/credit/overview` → `{flag:true, msg:"查询成功", data:{totalCredit:"0.05",
 *   modules:[{moduleCode, moduleName, recordCount, totalCredit, records:[…]}]}}`
 * - `GET /app/credit/applications?status=` → `data.records:[{publishName, moduleName,
 *   categoryName, credit, status, applyReason, …}]`
 *
 * 三条要点：
 * ① 身份走**请求头** `accessToken` + `userCode`（驼峰），不是表单体 —— 跟报修那一族同写法，
 *    跟门户 fdygl 也同类，但**这一族没有 `_userCode`/`xh` 那一套**。
 * ② 判成没成看 `flag`（实样里就是 `flag:true` + `msg:"查询成功"`），**不是 `code`**。
 *    契约笔记里那条"信封 {code,msg,data}"是 grep 到别的响应得出的，真报文打脸了：以这里为准。
 * ③ `credit`/`totalCredit` 服务端给的是**字符串**（"0.05"），不是数 —— 别 toDouble 之后自己四舍五入。
 *
 * 没接的：`/app/project/list`（可选活动那一屏）参数抄全了但响应形状没实样，
 * `/app/act/page`、`/app/actReg/myList` 有真报文但参数名没抄到 —— 三条都等有形状再接，
 * 不拿猜的字段名往界面上摆。写口（申报、问卷提交、附件上传）一条都不接。
 */
private const val DEKT = "https://dekt.aiit.edu.cn"

/** 一个模块的学分：模块名 + 该模块学分 + 认定条数。 */
data class ScModule(val name: String, val credit: String, val count: String)

/** 我的一条申报：活动名 / 模块 / 类别 / 学分 / 状态。 */
data class ScApply(
    val title: String,
    val module: String,
    val category: String,
    val credit: String,
    val status: String,
)

data class SecondClass(
    val total: String,
    val modules: List<ScModule>,
    val applies: List<ScApply>,
    /** 哪一发没成，界面上原样报出来。空 = 两发都成。 */
    val why: String?,
) {
    val isEmpty: Boolean get() = modules.isEmpty() && applies.isEmpty()
}

class CaptureSecondClass(private val school: School) {

    private val client = Http.client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun load(): SecondClass {
        val ov = get("/app/credit/overview")
        val ap = get("/app/credit/applications", "status" to "")
        val why = listOfNotNull(
            ov.exceptionOrNull()?.let { "总览没拉到：${it.message}" },
            ap.exceptionOrNull()?.let { "我的申报没拉到：${it.message}" },
        ).joinToString("；").takeIf { it.isNotBlank() }
        val o = ov.getOrNull()
        val data = o?.optJSONObject("data")
        val mods = data.optArray("modules")
        val recs = ap.getOrNull()?.optJSONObject("data").optArray("records")
        return SecondClass(
            total = data?.optString("totalCredit").orEmpty().ifBlank { "—" },
            modules = (0 until mods.length()).mapNotNull { i ->
                mods.optJSONObject(i)?.let {
                    ScModule(
                        name = it.optString("moduleName"),
                        credit = it.optString("totalCredit"),
                        count = it.optInt("recordCount").toString(),
                    )
                }
            },
            applies = (0 until recs.length()).mapNotNull { k ->
                recs.optJSONObject(k)?.let {
                    ScApply(
                        title = it.optString("publishName"),
                        module = it.optString("moduleName"),
                        category = it.optString("categoryName"),
                        credit = it.optString("credit"),
                        status = it.optString("status"),
                    )
                }
            },
            why = why,
        )
    }

    private suspend fun get(path: String, vararg q: Pair<String, String>): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = if (q.isEmpty()) DEKT + path else DEKT + path + "?" +
                    q.joinToString("&") { (k, v) ->
                        k + "=" + URLEncoder.encode(v, "UTF-8")
                    }
                val request = Request.Builder().url(url).get()
                    // 这一族只认这两个头，驼峰写法（见文件头 ①）
                    .header("accessToken", school.token())
                    .header("userCode", school.userCode())
                    .header("Accept", "application/json")
                    .build()
                client.newCall(request).execute().use { r ->
                    val body = r.body?.string().orEmpty()
                    if (body.isBlank()) error("学校回了个空 body（HTTP ${r.code}）")
                    JSONObject(body)
                }
            }
        }

    /** `data` 里那个数组有时给成 null、有时整个键没有 —— 一律回一个空数组，调用方不判空。 */
    private fun JSONObject?.optArray(key: String): JSONArray =
        this?.optJSONArray(key) ?: this?.optJSONObject("data")?.optJSONArray(key) ?: JSONArray()
}
