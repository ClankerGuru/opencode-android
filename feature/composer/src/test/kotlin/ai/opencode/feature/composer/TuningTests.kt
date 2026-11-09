package ai.opencode.feature.composer

import ai.opencode.core.tuning.TuningStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** iOS `TuningTests`: the composer's tunables (the rest is `:core`'s, `:feature:drawer`'s and transcript's). */
class TuningTests {
    @Test fun composerKeyboardTunablesRegisterInComposerGroup() {
        val store = TuningStore(null)
        store.setEnabled(true)
        assertEquals(6f, store.value(ComposerKeyboardTunables.keyboardChipTravel))
        assertEquals(3f, store.value(ComposerKeyboardTunables.keyboardChipBlur))
        assertEquals(
            listOf("composer.keyboard.chipTravel", "composer.keyboard.chipBlur"),
            store.descriptors.map { it.key },
        )
        assertTrue(store.descriptors.all { it.group == "composer" })
    }
}
