package com.andaxin.core.ui

import kotlinx.coroutines.CoroutineScope

/*
 * ViewModel 的缝。shared 里的 state 层引不了 androidx.lifecycle（那是 Android 专属物），
 * 所以把"谁给我一个协程作用域"抽出来：Android 端 actual 挂 viewModelScope（行为跟原 app 一致），
 * iOS/JVM 端 actual 自己起一个。state 层只认 scope，不认平台。
 */
expect abstract class SharedViewModel() {
    val scope: CoroutineScope
}
