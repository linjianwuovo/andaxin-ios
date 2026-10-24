package com.andaxin.core.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope

/*
 * Android 端沿用 androidx.lifecycle.ViewModel：app 侧的 adxViewModel/collectAsStateWithLifecycle
 * 都认这个类型，viewModelScope 的取消时机（页面销毁）也跟搬来之前一模一样。
 */
actual abstract class SharedViewModel actual constructor() : ViewModel() {
    actual val scope: CoroutineScope = viewModelScope
}
