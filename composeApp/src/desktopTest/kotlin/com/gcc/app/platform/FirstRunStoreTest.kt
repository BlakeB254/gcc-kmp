package com.gcc.app.platform

import com.gcc.shared.offline.MemorySettings
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FirstRunStoreTest {

    @Test
    fun flagsDefaultToUnseen() {
        val store = FirstRunStore(MemorySettings())

        assertFalse(store.introSeen)
        assertFalse(store.tutorialBlockSeen)
        assertFalse(store.hintDrawModeSeen)
    }

    @Test
    fun flagsPersistAcrossInstancesSharingSettings() {
        val settings = MemorySettings()
        FirstRunStore(settings).introSeen = true

        assertTrue(FirstRunStore(settings).introSeen)
        assertFalse(FirstRunStore(settings).tutorialBlockSeen)
    }

    @Test
    fun resetIntroAndTipsRearmsIntroAndTutorialButKeepsHints() {
        val settings = MemorySettings()
        val store = FirstRunStore(settings)
        store.introSeen = true
        store.tutorialBlockSeen = true
        store.hintDrawModeSeen = true

        store.resetIntroAndTips()

        assertFalse(store.introSeen)
        assertFalse(store.tutorialBlockSeen)
        assertTrue(store.hintDrawModeSeen)
    }
}
