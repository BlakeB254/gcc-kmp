package com.gcc.app.ui.components

import java.util.UUID

actual fun generateSessionId(): String =
    UUID.randomUUID().toString()
