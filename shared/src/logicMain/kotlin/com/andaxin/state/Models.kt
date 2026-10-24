package com.andaxin.state

import com.andaxin.core.concurrency.todayDayOfWeek
import com.andaxin.core.ui.SharedViewModel
import com.andaxin.data.Dashboard
import com.andaxin.data.DashboardFeed
import com.andaxin.data.MessageFeed
import com.andaxin.data.MessageItem
import com.andaxin.data.NoticeBody
import com.andaxin.data.NoticeFeed
import com.andaxin.data.NoticeItem
import com.andaxin.data.TimetableFeed
import com.andaxin.data.TimetableSlot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/*
 * UiState 都是只读数据类（val + List + 基本类型），Compose 按不可变值处理。
 * 原来在 app 里还挂着 @Immutable（编译器提示，帮跳过相等性检查），共享层不带注解 ——
 * 少的是个提示，不是正确性。
 * 一次出：加载态只在"第一次什么都没有"时占位，刷新期间保留旧数据，别整屏闪一下骨架。
 */

data class HomeUiState(
    val firstLoad: Boolean = true,
    val data: Dashboard? = null,
    /** 屏幕上这批数字是上一份快照、正在换新的。界面据此写"更新中"，不把旧数字冒充成刚拉的。 */
    val refreshing: Boolean = false,
    val today: List<TimetableSlot> = emptyList(),
    /** 今天这份课表拿到了没有。没拿到就别把"今天没课"的壁纸摆出来 —— 那不是"没课"，是"没拉到"。 */
    val todayKnown: Boolean = false,
    val error: String? = null,
)

/**
 * 首页。
 *
 * 一次出：仪表数据和今天的课两路并发取，都回来了才落一次 state ——
 * 不许一路加载一路蹦（他验收过的手感）。
 */
class HomeModel(
    private val feed: DashboardFeed,
    private val timetable: TimetableFeed,
) : SharedViewModel() {
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        // 冷启动先用上一次的数字垫屏：六条并行接口回来之前，屏幕不再是一片空白
        _state.value = _state.value.copy(data = feed.cached())
        reload()
    }

    fun reload() {
        scope.launch { refresh() }
    }

    /**
     * 挂起版：下拉刷新要等这一趟真回来才收指示器，
     * 所以给一个能等的（[reload] 是后台起协程，不等）。
     */
    suspend fun refresh() {
        _state.value = _state.value.copy(refreshing = true)
        val dash = try { feed.snapshot() } catch (e: Exception) { Result.failure(e) }
        val slots = try { timetable.week(0) } catch (e: Exception) { Result.failure(e) }
        val list = slots.getOrDefault(emptyList())
        val dash0 = dash.getOrNull()
        // 冷启动那一路已经把消息列表拉了一遍（为了首页那一格"未读几条"），
        // 顺手把底栏的角标填上 —— 不然要等他点进「消息」那一格才看得到有未读
        dash0?.let { com.andaxin.core.store.Unread.messages.value = it.unreadMessages }
        _state.value = HomeUiState(
            firstLoad = false,
            data = dash0 ?: _state.value.data,
            // 今天星期几由 todayDayOfWeek 说（口径对齐 app 侧 Periods.today，周一起 1..7），
            // 跟周网格、首页"几节课"同一张表 —— 这儿原来写死 ==1，
            // 周一看着对，周二起就把周一的课当今天显示（10-20 上午装机读屏撞中的就是这个）。
            // `startPeriod >= 1` 是"这是课"的闸：校方那条 `ksjc=0` 的阳光晨跑是活动占位，不算课。
            today = list.filter { it.day == todayDayOfWeek() && it.startPeriod >= 1 },
            todayKnown = slots.isSuccess,
            error = (dash.exceptionOrNull() ?: slots.exceptionOrNull())?.message,
            refreshing = false,
        )
    }
}

data class TimetableUiState(
    val firstLoad: Boolean = true,
    val weekOffset: Int = 0,
    val slots: List<TimetableSlot> = emptyList(),
    val error: String? = null,
    /** 校方那一学期的全部周次（`selectZcList.do` 的 `rows`），界面上排成"第一周…第N周"一行。 */
    val weeks: List<Int> = emptyList(),
    /** 现在第几周。0 = 还没拿到。 */
    val now: Int = 0,
)

class TimetableModel(private val feed: TimetableFeed) : SharedViewModel() {
    private val _state = MutableStateFlow(TimetableUiState())
    val state: StateFlow<TimetableUiState> = _state.asStateFlow()

    init {
        scope.launch {
            val w = runCatching { feed.weeks() }.getOrNull()
            if (w != null && w.weeks.isNotEmpty()) {
                _state.value = _state.value.copy(weeks = w.weeks, now = w.now)
            }
        }
        load(0)
    }

    fun load(offset: Int) {
        scope.launch { refresh(offset) }
    }

    /** 挂起版给下拉刷新用：等这一发回来才收指示器。 */
    suspend fun refresh(offset: Int = _state.value.weekOffset) {
        val r = try { feed.week(offset) } catch (e: Exception) { Result.failure(e) }
        // 用 copy：整份 new 会把刚拿到的周次表冲掉，那一行会闪回「本周/下周」两颗
        _state.value = _state.value.copy(
            firstLoad = false,
            weekOffset = offset,
            slots = r.getOrDefault(_state.value.slots),
            error = r.exceptionOrNull()?.message,
        )
    }
}

data class MessageUiState(
    val firstLoad: Boolean = true,
    val items: List<MessageItem> = emptyList(),
    val error: String? = null,
    /** 标已读失败的原因。跟列表失败分开：列表好好的、只有这一条没标上，是两种事。 */
    val readError: String? = null,
)

class MessageModel(private val feed: MessageFeed) : SharedViewModel() {
    private val _state = MutableStateFlow(MessageUiState())
    val state: StateFlow<MessageUiState> = _state.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        scope.launch { refresh() }
    }

    /** 挂起版给下拉刷新用：等这一发回来才收指示器。 */
    suspend fun refresh() {
        val r = try { feed.page(0) } catch (e: Exception) { Result.failure(e) }
        val items = r.getOrDefault(_state.value.items)
        // 底栏那颗角标跟着这一份走：拉一次就更新一次，标完已读回来也会掉
        com.andaxin.core.store.Unread.messages.value = items.count { it.unread }
        _state.value = MessageUiState(
            firstLoad = false,
            items = items,
            error = r.exceptionOrNull()?.message,
        )
    }

    /**
     * 标已读：**服务端说成了才把红点去掉**。
     *
     * 这不是洁癖 —— 官方前端就是这个顺序（`readPushMessage.do` 的 `.then` 里才改 `readFlag`）。
     * 本地先划掉的话，下一次刷新红点又回来，看着像 App 在乱记。
     * 失败的原因留在 [readError] 上，消息屏那一行下面会显示出来。
     */
    fun markRead(id: String) {
        scope.launch {
            val r = try { feed.markRead(id) } catch (e: Exception) { Result.failure(e) }
            if (r.isSuccess) {
                val next = _state.value.items.map { if (it.id == id) it.copy(unread = false) else it }
                _state.value = _state.value.copy(readError = null, items = next)
                // 底栏那颗角标跟着这一份重算：不然把最后一条点成已读，
                // 红圈还挂在「消息」那一格上，得等下一次进首页/下拉才肯掉
                com.andaxin.core.store.Unread.messages.value = next.count { it.unread }
            } else {
                _state.value = _state.value.copy(readError = r.exceptionOrNull()?.message ?: "服务端没认这条")
            }
        }
    }
}

data class NoticeUiState(
    val firstLoad: Boolean = true,
    val items: List<NoticeItem> = emptyList(),
    val error: String? = null,
)

class NoticeModel(private val feed: NoticeFeed) : SharedViewModel() {
    private val _state = MutableStateFlow(NoticeUiState())
    val state: StateFlow<NoticeUiState> = _state.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        scope.launch { refresh() }
    }

    /** 挂起版给下拉刷新用：等这一发回来才收指示器。 */
    suspend fun refresh() {
        val r = try { feed.list() } catch (e: Exception) { Result.failure(e) }
        _state.value = _state.value.copy(
            firstLoad = false,
            items = r.getOrDefault(_state.value.items),
            error = r.exceptionOrNull()?.message,
        )
    }

    // 详情不再挂在这份状态上：10-22 深夜那条「公告点进去、系统返回手势把整个应用退了」
    // 就是因为详情是列表页就地换内容，导航栈里没有第二页可弹。
    // 现在详情走 Route.NOTICE_DETAIL，自己按 id 取正文（NoticeDetailScreen）。
}
