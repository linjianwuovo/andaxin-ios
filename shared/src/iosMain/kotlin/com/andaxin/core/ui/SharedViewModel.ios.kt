package com.andaxin.core.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/*
 * iOS 端没有 androidx.lifecycle：自己起一个 SupervisorJob 的作用域顶替 viewModelScope。
 * 用 Main.immediate 是为了对齐 Android viewModelScope 的手感 —— 界面动作当场在主线程执行，
 * 不排一帧队；SupervisorJob 保证一个子协程炸了不连坐兄弟（跟 viewModelScope 同构）。
 */
// expect/actual 的规矩：actual 的 modality 必须跟 expect 一致（都是 abstract），
// 不然 iOS 编译第一个不认。
actual abstract class SharedViewModel actual constructor() {
    actual val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
}
