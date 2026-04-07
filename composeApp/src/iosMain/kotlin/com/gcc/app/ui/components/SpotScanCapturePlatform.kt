package com.gcc.app.ui.components

import platform.Foundation.NSUUID

actual fun generateSessionId(): String =
    NSUUID().UUIDString
