package io.github.caffelatte23.futaba.core.domain

import com.google.common.truth.Truth.assertThat
import io.github.caffelatte23.futaba.core.model.Label
import io.github.caffelatte23.futaba.core.model.Note
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class MarkdownTest {
    @Test
    fun groupsByDayAndAppendsLabels() {
        val notes =
            listOf(
                note("older", "2026-11-24T14:59:00Z"),
                note("already ＃IDEA", "2026-11-24T16:00:00Z", listOf("idea")),
                note("冷蔵庫の…やつ", "2026-11-24T17:00:00Z", listOf("app", "missing")),
                note("trashed", "2026-11-25T17:00:00Z").copy(deletedAt = Instant.EPOCH),
            )
        val labels = listOf(Label("app", "アプリ案", "", 0), Label("idea", "Idea", "", 1))

        assertThat(toMarkdown(notes, labels, ZoneId.of("Asia/Tokyo")))
            .isEqualTo("## 2026-11-25\n\n冷蔵庫の…やつ #アプリ案\n\n---\n\nalready ＃IDEA\n\n## 2026-11-24\n\nolder\n")
    }

    private fun note(
        text: String,
        timestamp: String,
        labelIds: List<String> = emptyList(),
    ): Note {
        val createdAt = Instant.parse(timestamp)
        return Note(text, text, labelIds, false, createdAt, createdAt, null)
    }
}
