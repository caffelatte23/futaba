package io.github.caffelatte23.futaba.core.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OnlineTrackerTest {
    private val seen = mutableListOf<Boolean>()
    private val tracker = OnlineTracker { seen += it }

    @Test
    fun lostThenRestoredChangesValue() {
        tracker.capabilities(validated = true)
        tracker.lost()
        tracker.capabilities(validated = true)
        assertThat(seen).containsExactly(true, false, true).inOrder()
    }

    @Test
    fun initialSnapshotNeverOverridesNewerCallback() {
        tracker.lost()
        tracker.initial(validated = true)
        assertThat(seen).containsExactly(false)
    }

    @Test
    fun initialSnapshotUsedWhenNoCallbackYet() {
        tracker.initial(validated = false)
        assertThat(seen).containsExactly(false)
    }
}
