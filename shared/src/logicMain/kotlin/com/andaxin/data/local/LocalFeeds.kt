package com.andaxin.data.local

import com.andaxin.data.Dashboard
import com.andaxin.data.DashboardFeed
import com.andaxin.data.MessageCategory
import com.andaxin.data.MessageFeed
import com.andaxin.data.MessageItem
import com.andaxin.data.NoticeBody
import com.andaxin.data.NoticeFeed
import com.andaxin.data.NoticeItem
import com.andaxin.data.TimetableFeed
import com.andaxin.data.TimetableSlot
import kotlinx.coroutines.delay

/*
 * 假数据源：让每一屏在没有抓包之前就能点、能看、能量帧率。
 * 这些值不是校方给的，是排版用的占位，界面上不许拿它当真实数据展示给用户。
 */

class LocalTimetable : TimetableFeed {
    override suspend fun week(weekOffset: Int): Result<List<TimetableSlot>> {
        delay(120)
        return Result.success(
            listOf(
                TimetableSlot("c1", "高等数学", "王老师", "教三 204", day = 1, startPeriod = 1, periodCount = 2),
                TimetableSlot("c2", "大学英语", "Ms. Li", "教二 111", day = 1, startPeriod = 3, periodCount = 2),
                TimetableSlot("c3", "数据结构", "张老师", "实验楼 A501", day = 2, startPeriod = 5, periodCount = 2),
                TimetableSlot("c4", "体育（跑步考勤）", "教练组", "南操场", day = 3, startPeriod = 1, periodCount = 1),
                TimetableSlot("c5", "毛概", "刘老师", "教一 302", day = 4, startPeriod = 7, periodCount = 2),
                TimetableSlot("c6", "操作系统", "陈老师", "实验楼 B203", day = 5, startPeriod = 2, periodCount = 2),
            )
        )
    }
}

class LocalMessages : MessageFeed {
    override suspend fun page(cursor: Int): Result<List<MessageItem>> {
        delay(120)
        return Result.success(
            listOf(
                MessageItem("m1", "课表变更通知", "周四毛概调至第 9-10 节", "10-19 09:12", true, MessageCategory.Academic),
                MessageItem("m2", "查寝提醒", "今晚 22:30 开始查寝", "10-18 21:40", true, MessageCategory.Dorm),
                MessageItem("m3", "跑步未达标", "本周还差 1.2 公里", "10-18 08:02", false, MessageCategory.Sports),
                MessageItem("m4", "系统消息", "校园网计费周期调整", "10-17 15:20", false, MessageCategory.System),
            )
        )
    }

    override suspend fun markRead(id: String) = Result.success(Unit)
}

class LocalNotices : NoticeFeed {
    override suspend fun list(): Result<List<NoticeItem>> {
        delay(120)
        return Result.success(
            listOf(
                NoticeItem("n1", "关于本学期期中考试安排的通知", "10-19", "教务处", null),
                NoticeItem("n2", "图书馆开放时间调整", "10-18", "图书馆", null),
                NoticeItem("n3", "校园卡系统维护公告", "10-16", "信息化中心", null),
            )
        )
    }

    override suspend fun detail(id: String) = Result.success(
        NoticeBody(
            title = "关于本学期期中考试安排的通知",
            publishedAt = "10-19 08:30",
            paragraphs = listOf("各学院：", "本学期期中考试定于第 9-10 教学周进行，具体安排见附件。", "请各教学单位按时提交监考名单。"),
            attachments = emptyList(),
        )
    )
}

class LocalDashboard(
    private val messages: MessageFeed,
    private val timetable: TimetableFeed,
) : DashboardFeed {
    override suspend fun snapshot(): Result<Dashboard> {
        val unread = messages.page(0).getOrDefault(emptyList()).count { it.unread }
        val lessons = timetable.week(0).getOrDefault(emptyList())
            .count { it.day == com.andaxin.core.concurrency.todayDayOfWeek() }
        return Result.success(
            Dashboard(
                greeting = greetAt(com.andaxin.core.concurrency.currentHour()),
                todayLessons = lessons,
                nextLesson = "第 3 节 · 大学英语 · 教二 111",
                unreadMessages = unread,
                dormCheckState = "今晚 22:30",
                // 跑量那两格没有可编的口径 —— 校方那发只给"一共/其中晨跑"，
                // 编一个"3.2 公里"会让人拿假数当真数看，所以给 null，界面上是"—"。
                runSemesterKm = null,
                runMorningKm = null,
                runWhy = null,
            )
        )
    }
}

/** 问候语跟表走，不跟着占位数据走：这一格是唯一一处本机就能算对的话。 */
internal fun greetAt(hour: Int) = when (hour) {
    in 5..10 -> "早上好"
    in 11..12 -> "中午好"
    in 13..17 -> "下午好"
    in 18..22 -> "晚上好"
    else -> "夜深了"
}
