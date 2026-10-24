package com.andaxin.shared

import platform.Foundation.NSProcessInfo

actual fun platformName(): String = NSProcessInfo.processInfo.operatingSystemVersionString
