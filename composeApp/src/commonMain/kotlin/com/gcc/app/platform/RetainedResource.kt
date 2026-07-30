package com.gcc.app.platform

/**
 * Runs [block] with an acquired resource and always calls [release] after.
 * Returns null when [acquire] yields no resource.
 */
internal inline fun <R : Any, T> useRetainedResourceOrNull(
    acquire: () -> R?,
    release: (R) -> Unit,
    block: (R) -> T,
): T? {
    val resource = acquire() ?: return null
    return try {
        block(resource)
    } finally {
        release(resource)
    }
}
