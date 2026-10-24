package com.andaxin.core.store

import com.andaxin.data.capture.Profile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * 平移注：从 app 的 `core/store/WhoIs.kt` 原样搬进共享层（底栏五格那批，「我的」要用）。
 * 包名没变，app 侧 AppGraph 的 import 一行不用改。纯内存 Kotlin，四目标通用。
 */

/**
 * 「我是谁」这一格的两条只读结果：学院/专业/班级（queryStudentStatus）和
 * 实习生标记（checkIsInternStu）。
 *
 * 为什么要从屏幕上摘出来：这两条是网络口，值以前存在 MeScreen 自己的 remember 里。
 * 从「我的」点进「关于」，MeScreen 整个被 dispose；退回来时那份状态归零，
 * 名字下面那行只能先画兜底文案「校园账号 xxx」，等接口回来再跳成
 * 「<学院> · <专业> · <班级> · 非实习生」—— 他 10-22 说的
 * 「学院哪个班会闪」就是这一跳。缓在进程上，退回来第一帧画的就是真那行。
 *
 * 两份都记着**它是哪个账号的**：退出、被顶号后自动重登成别人时，账号一对不上
 * 就当没有，不会把上一个人的班级画到这一屏上。
 */
class WhoIs {

    private val _profile = MutableStateFlow<Labelled<Profile>?>(null)
    private val _intern = MutableStateFlow<Labelled<Boolean>?>(null)

    val profile: StateFlow<Labelled<Profile>?> = _profile
    val intern: StateFlow<Labelled<Boolean>?> = _intern

    fun putProfile(account: String, value: Profile) {
        _profile.value = Labelled(account, value)
    }

    fun putIntern(account: String, value: Boolean) {
        _intern.value = Labelled(account, value)
    }

    /** 退出登录时清掉，别把上一位的资料留在内存里给下一位看一眼。 */
    fun clear() {
        _profile.value = null
        _intern.value = null
    }
}

/** 一个值 + 它属于哪个账号。 */
data class Labelled<T>(val account: String, val value: T)

/** 账号对得上才给值，对不上等于没拉到过。 */
fun <T> Labelled<T>.forAccount(account: String): T? = if (this.account == account) value else null
