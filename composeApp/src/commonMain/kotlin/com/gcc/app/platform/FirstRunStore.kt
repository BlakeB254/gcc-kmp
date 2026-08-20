package com.gcc.app.platform

import com.russhwolf.settings.Settings

/**
 * Device-local first-run flags: which one-shot explainers (intro carousel,
 * first-block tutorial, gesture hints) the user has already seen.
 *
 * Deliberately separate from [com.gcc.shared.auth.TokenManager]'s
 * `onboarding_complete` flag: that one tracks *account* setup (auth-scoped,
 * secure storage), these track *device-local* "seen it" state and are
 * non-sensitive, so plaintext [Settings] is fine.
 *
 * Version-independent: bare boolean keys, absent = not seen.
 */
class FirstRunStore(private val settings: Settings = Settings()) {

    /** §3 intro carousel (Scan / Claim your block / Earn / See the change). */
    var introSeen: Boolean
        get() = settings.getBoolean(KEY_INTRO_SEEN, false)
        set(value) = settings.putBoolean(KEY_INTRO_SEEN, value)

    /** §4 first-block-assignment tutorial (Dashboard sheet + claim coach mark). */
    var tutorialBlockSeen: Boolean
        get() = settings.getBoolean(KEY_TUTORIAL_BLOCK_SEEN, false)
        set(value) = settings.putBoolean(KEY_TUTORIAL_BLOCK_SEEN, value)

    /** F3 one-time draw-mode hint chip on the scan surface. */
    var hintDrawModeSeen: Boolean
        get() = settings.getBoolean(KEY_HINT_DRAW_MODE_SEEN, false)
        set(value) = settings.putBoolean(KEY_HINT_DRAW_MODE_SEEN, value)

    /**
     * "Replay intro & tips" from Settings — re-arms the intro carousel and
     * the first-block tutorial on next launch. Gesture hints stay dismissed.
     */
    fun resetIntroAndTips() {
        settings.remove(KEY_INTRO_SEEN)
        settings.remove(KEY_TUTORIAL_BLOCK_SEEN)
    }

    companion object {
        private const val KEY_INTRO_SEEN = "intro_seen"
        private const val KEY_TUTORIAL_BLOCK_SEEN = "tutorial_block_seen"
        private const val KEY_HINT_DRAW_MODE_SEEN = "hint_draw_mode_seen"
    }
}
