package com.andaxin.data.capture

import com.andaxin.core.net.Http
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import com.andaxin.core.json.JSONArray
import com.andaxin.core.json.JSONObject

/*
 * 笔记（FIF 的笔记站）。**独立域名、独立 client、独立错误态**，跟主站不是一套，
 * 所以另开一个文件，不塞进 `FifApi`。
 *
 * 【取证 · ② 2026-10-20 16:3x 现打，八条口全通】逐字段骨架在 `lxtmp/契约_AI课堂剩余读口.md` §4。
 * Base `https://note.fifedu.com/note-app-api/`，一律 POST 表单。
 * 必带：`Authorization: Basic <同一个 FIF JWT>` + `Origin` + `Referer`（指到那个静态 note-center 页）
 * + `FIF-Accept-Version: 1.0`。**官方不带 `Visit-Type`**（带上也过，但按官方样子别带），
 * 所以这一族不能复用 `FifSession.postForm` —— 它每次都硬塞 `Visit-Type`，而且非 2xx 直接抛、
 * 把响应体丢了。
 *
 * 判活和主站不一样：这里是 **`status` 为 int `1` 才算成功**（`0` 是失败），
 * 信封 `{data, status:int, message, location, jtzy, code}`。
 * 而且**失败会给 HTTP 500 + Spring 那套错误体**，不是主站那种 200 + 信封 ——
 * 所以这里无论状态码都先把体读出来再判，别一看 500 就报"连不上"。
 *
 * **最大的坑：`schoolId`（19 位）是隐式必填。** 只发 `userId` 时 `getApplicationList` 回**空数组**，
 * 补上 `schoolId` 才回 5 条 —— 空数组在这儿不是"没内容"，是"参数没给全"。
 * 这一条决定了界面上"笔记是空的"这句话能不能说：只有 `schoolId` 给到了、`status==1`、
 * `datalist` 还是空，才准说"没有笔记"。
 *
 * 实测这个账号**笔记列表恒空**（`getNoteLevelList` 三种 `menuType` 都空、`getCatalogList` 空、
 * `getCourseList` 空），所以这一屏现在能验的是"链路通、口径对"，不是"有内容"。
 * 真数据的是那三条字典：`getUserInfo`（姓名/头像/学校）、`getPublicTypeList`（私有笔记…）、
 * `getApplicationList`（akt / iplat / ai-assistant 五个应用）。
 */
class NoteApi(private val fif: FifSession) {

    data class NoteFolder(val id: String, val name: String)

    data class NoteEntry(val id: String, val title: String, val sub: String, val right: String)

    /** 笔记列表。`menuType` 官方给字符串 "0"/"1"/"2"（2 是文件夹下钻，要带 `parentId`）。 */
    suspend fun notes(
        userId: String, schoolId: String, menuType: String = "0",
        courseId: String = "", page: Int = 1, pageSize: Int = 20,
    ): Result<Pair<List<NoteEntry>, Int>> = post(
        "noteManager/getNoteLevelList",
        "userId" to userId, "schoolId" to schoolId, "menuType" to menuType,
        "applicationCode" to "akt", "courseId" to courseId, "openType" to "", "type" to "",
        "currentPage" to page.toString(), "pageSize" to pageSize.toString(),
    ).mapCatching { root ->
        val data = root.optJSONObject("data") ?: JSONObject()
        val arr = data.optJSONArray("datalist") ?: JSONArray()
        val list = (0 until arr.length()).map { i ->
            val o = arr.optJSONObject(i) ?: return@map NoteEntry("", "（这一条不是对象）", "", "")
            // 条目键名**没有实样**（这个账号列表恒空），所以一律按候选键试，试不出来就明说
            val title = firstOf(o, "title", "noteName", "name", "content")
            NoteEntry(
                id = firstOf(o, "id", "noteId", "uuid"),
                title = title.ifBlank { "一条笔记（${o.length()} 个键，键名没有实样可对）" },
                sub = firstOf(o, "createTime", "updateTime", "gmtCreate"),
                right = firstOf(o, "typeName", "openType"),
            )
        }
        val total = data.optJSONObject("pages")?.optInt("totalCount", list.size) ?: list.size
        Pair(list, total)
    }

    /** 文件夹列表。实测这个账号恒空 —— 空是"没建过"，不是没打通。 */
    suspend fun folders(userId: String, schoolId: String): Result<List<NoteFolder>> = post(
        "noteManager/getCatalogList", "userId" to userId, "schoolId" to schoolId,
    ).mapCatching { root ->
        val arr = root.optJSONObject("data")?.optJSONArray("folderList") ?: JSONArray()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            NoteFolder(firstOf(o, "id"), firstOf(o, "name", "folderName").ifBlank { return@mapNotNull null })
        }
    }

    /** 可见范围字典（"私有笔记"那两条）。真数据。 */
    suspend fun types(userId: String, schoolId: String): Result<List<String>> = post(
        "commonManager/getPublicTypeList", "userId" to userId, "schoolId" to schoolId,
    ).mapCatching { root ->
        val arr = root.optJSONArray("data") ?: JSONArray()
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.optString("typeName")?.takeIf { it.isNotBlank() } }
    }

    /** 一次 POST，两层都判：HTTP 码不算判据（失败会给 500），只看信封 `status == 1`。 */
    private suspend fun post(path: String, vararg fields: Pair<String, String>): Result<JSONObject> {
        val jwt = fif.authHeader() ?: return Result.failure(Exception("笔记站要先有 FIF 登录态"))
        return withContext(Dispatchers.IO) {
            val form = FormBody.Builder().apply {
                fields.forEach { (k, v) -> add(k, v) }
            }.build()
            val req = Request.Builder().url("$BASE$path").post(form)
                .header("User-Agent", UA)
                .header("Accept", "application/json, text/plain, */*")
                .header("Authorization", jwt)
                .header("Origin", BASE.trimEnd('/'))
                .header("Referer", REFERER)
                .header("FIF-Accept-Version", "1.0")
                .build()
            try {
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    val root = runCatching { JSONObject(text) }.getOrNull()
                        ?: return@use Result.failure(
                            Exception("笔记站回的不是 JSON（HTTP ${resp.code}，多半是网关那层挂了）"),
                        )
                    // 注意是 int 的 status：1 = 成功，0 = 失败。主站那套 "success" 字符串在这儿不适用
                    if (root.optInt("status", -1) != 1) {
                        return@use Result.failure(
                            Exception("笔记站：" + root.optString("message")
                                .ifBlank { "HTTP ${resp.code}，服务端没给原因" }),
                        )
                    }
                    Result.success(root)
                }
            } catch (e: IOException) {
                Result.failure(Exception("连不上笔记站：${e.message ?: e.javaClass.simpleName}"))
            }
        }
    }

    private fun firstOf(o: JSONObject, vararg keys: String): String =
        keys.firstNotNullOfOrNull { o.optString(it).takeIf { v -> v.isNotBlank() } }.orEmpty()

    private val client = Http.client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private companion object {
        const val BASE = "https://note.fifedu.com/note-app-api/"
        const val UA = "okhttp/4.12.0"
        /** 官方那个静态笔记页，Referer 指它；换掉会被网关拒。 */
        const val REFERER =
            "https://note.fifedu.com/static/fiforal/note-center-static/v100/app/index.html"
    }
}
