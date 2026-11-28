package io.github.caffelatte23.futaba.entry.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.SizeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CaptureWidgetSizeTest {
    @Test
    fun `uses one wide layout even at the shortest supported wide size`() {
        assertThat(CaptureWidget().sizeMode)
            .isEqualTo(SizeMode.Responsive(setOf(DpSize(40.dp, 40.dp), DpSize(180.dp, 88.dp))))
    }
}
