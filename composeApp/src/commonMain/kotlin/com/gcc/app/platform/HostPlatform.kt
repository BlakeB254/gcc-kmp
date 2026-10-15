package com.gcc.app.platform

/**
 * True only on Android. Used to gate the UIC Office of Community Collaboration
 * donor credit, which acknowledges the Android device and service plan that
 * made an Android build possible — so it has no meaning on iOS or desktop.
 */
expect fun isAndroidHost(): Boolean
