package com.andaxin.shared

actual fun platformName(): String = "jvm (" + System.getProperty("os.name") + ")"
