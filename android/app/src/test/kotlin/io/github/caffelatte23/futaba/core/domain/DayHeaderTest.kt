package io.github.caffelatte23.futaba.core.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class DayHeaderTest {
    @Test
    fun formats() {
        val today = LocalDate.of(2026, 11, 25)

        assertThat(dayHeader(today, today)).isEqualTo("今日 · 11月25日（水）")
        assertThat(dayHeader(today.minusDays(1), today)).isEqualTo("昨日 · 11月24日（火）")
        assertThat(dayHeader(LocalDate.of(2026, 11, 20), today)).isEqualTo("11月20日（金）")
        assertThat(dayHeader(LocalDate.of(2025, 11, 20), today)).isEqualTo("2025年11月20日（木）")
    }

    @Test
    fun yesterdayCanBeInThePreviousYear() {
        val today = LocalDate.of(2026, 1, 1)

        assertThat(dayHeader(today.minusDays(1), today)).isEqualTo("昨日 · 2025年12月31日（水）")
    }
}
