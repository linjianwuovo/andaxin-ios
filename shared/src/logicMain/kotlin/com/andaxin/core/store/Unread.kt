package com.andaxin.core.store

import kotlinx.coroutines.flow.MutableStateFlow

/*
 * 底栏那一格「消息」上的未读角标。
 *
 * 为什么单独立一个：未读数本来在 `MessageModel` 里，可底栏和消息屏不是一个组合根 ——
 * 底栏在 `ShelfHost` 那一层，切到首页时消息屏根本还没进组合。
 * 所以谁拉到新数据谁往这儿写一次，底栏只读这一份。
 *
 * 首页冷启动那一路（`DashboardSource`）已经把消息列表拉了一遍，未读数顺手就写进来，
 * 因此不用先点进「消息」那一格才看到角标。
 *
 * 「公告」那一格**故意没有角标**：校方那份公告列表里没有已读/未读这个概念
 * （`NoticeItem` 只有标题、时间、来源、封面），造一个"未读"就是我自己编的数。
 */
object Unread {
    val messages = MutableStateFlow(0)
}
