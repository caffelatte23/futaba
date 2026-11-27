package io.github.caffelatte23.futaba.feature.stream

import com.google.common.truth.Truth.assertThat
import io.github.caffelatte23.futaba.core.model.Label
import org.junit.Test

class FutabaDrawerTest {
    @Test
    fun drawerLabelsAreOrderedWithCounts() {
        val labels = listOf(Label("b", "仕事", "", 1), Label("a", "料理", "", 0), Label("c", "空", "", 2))
        val items = drawerLabels(labels, mapOf("a" to 3, "b" to 1))
        assertThat(items.map { it.label.name }).containsExactly("料理", "仕事", "空").inOrder()
        assertThat(items.map { it.count }).containsExactly(3, 1, 0).inOrder()
    }
}
