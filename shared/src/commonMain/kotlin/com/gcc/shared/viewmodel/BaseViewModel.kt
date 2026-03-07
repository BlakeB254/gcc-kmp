package com.gcc.shared.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Base ViewModel providing shared coroutine scope, loading state, and error handling.
 *
 * All UI-facing ViewModels should extend this class to get consistent:
 * - Coroutine scope with SupervisorJob (child failure doesn't cancel siblings)
 * - Loading and error state flows
 * - Safe launch wrapper with automatic error capture
 *
 * Note: Uses Dispatchers.Default because Dispatchers.Main is not universally available
 * in KMP commonMain for JVM targets (requires kotlinx-coroutines-swing or -javafx).
 * StateFlow collectors in Compose already dispatch to the main thread, so this is safe
 * for UI state updates. If a coroutine needs the main dispatcher explicitly, subclasses
 * can use withContext(Dispatchers.Main) on platform-specific source sets.
 */
abstract class BaseViewModel {
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    protected fun setLoading(loading: Boolean) {
        _isLoading.value = loading
    }

    protected fun setError(message: String?) {
        _error.value = message
    }

    protected fun clearError() {
        _error.value = null
    }

    /**
     * Launch a coroutine with automatic error handling.
     * Clears any previous error, and captures exceptions into the error state flow.
     * Callers can set [showLoading] to true to automatically toggle loading state.
     */
    protected fun launchSafe(
        showLoading: Boolean = false,
        block: suspend CoroutineScope.() -> Unit,
    ) {
        scope.launch {
            try {
                _error.value = null
                if (showLoading) _isLoading.value = true
                block()
            } catch (e: Exception) {
                _error.value = e.message ?: "Unknown error"
            } finally {
                if (showLoading) _isLoading.value = false
            }
        }
    }

    open fun onCleared() {
        scope.cancel()
    }
}
