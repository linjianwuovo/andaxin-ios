package com.andaxin.data.capture

/*
 * AI 课堂"有哪几门课"。
 *
 * 走 FIF 自己的 `myClassroom`〔B1〕，不再拿课表的 `jxbh` 去猜它的班号 ——
 * 清点完抓包发现这条**有 A 级实样**（24 次、`data.dataList` 15 条、27 个键带样例），
 * 而"课表 jxbh == FIF teachClassId"从头到尾只是我形状对得上的猜测，
 * 用它当入参的结果是：对不上就一直显示空，而空看着像"这门课没内容"。那是最坏的一种错。
 *
 * 顺序是先过单点登录、再拿学期、最后按选中的那个学期要课。
 * 学期那条〔B2〕53 条，`selected` 那一条才是现在这个学期。
 */
class AiCourseFeed(
    private val fif: FifSession,
    private val api: FifApi,
) {

    private var cached: List<AiCourse>? = null

    suspend fun courses(force: Boolean = false): Result<List<AiCourse>> {
        val hit = cached
        if (!force && hit != null) return Result.success(hit)
        return fif.ensure().mapCatching {
            val terms = api.terms()
            val term = terms.firstOrNull { it.selected } ?: terms.firstOrNull()
                ?: throw Exception("学期列表是空的（那条接口回了数据但一条学期都没有）")
            api.courses(term.year, term.num).also { cached = it }
        }
    }

    suspend fun find(id: String): AiCourse? = courses().getOrNull()?.firstOrNull { it.id == id }

    /** 当前学期。直录播那发要它的 `id` 当 `termId`。 */
    suspend fun currentTerm(): AiTerm? = fif.ensure().getOrNull()?.let { api.terms().selected() }

    private fun List<AiTerm>.selected(): AiTerm? = firstOrNull { it.selected } ?: firstOrNull()

    fun invalidate() { cached = null }
}
