package com.andaxin.core.concurrency

import android.os.Build

actual fun deviceModel(): String = Build.MODEL.orEmpty()
