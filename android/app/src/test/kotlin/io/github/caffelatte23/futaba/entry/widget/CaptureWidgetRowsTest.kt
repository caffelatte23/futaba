package io.github.caffelatte23.futaba.entry.widget

import com.google.common.truth.Truth.assertThat
import io.github.caffelatte23.futaba.core.model.Note
import org.junit.Test
import java.time.Instant

class CaptureWidgetRowsTest {
    @Test
    fun `uses three newest live notes in repository order`() {
        val notes =
            listOf(
                note("deleted", 5, deletedAt = Instant.EPOCH),
                note("newest", 4),
                note("second", 3),
                note("third", 2),
                note("older pinned", 1).copy(pinned = true, updatedAt = Instant.ofEpochSecond(6)),
            )

        assertThat(captureWidgetRows(notes)).containsExactly("newest", "second", "third").inOrder()
    }

    @Test
    fun `uses the first line with platform line endings`() {
        val notes = listOf(note("first\r\nhidden", 3), note("second\nhidden", 2), note("third\rhidden", 1))

        assertThat(captureWidgetRows(notes)).containsExactly("first", "second", "third").inOrder()
    }

    @Test
    fun `has no rows when every note is deleted`() {
        assertThat(captureWidgetRows(listOf(note("deleted", 1, deletedAt = Instant.EPOCH)))).isEmpty()
    }

    private fun note(
        text: String,
        seconds: Long,
        deletedAt: Instant? = null,
    ): Note =
        Note(
            id = seconds.toString(),
            text = text,
            labelIds = emptyList(),
            pinned = false,
            createdAt = Instant.ofEpochSecond(seconds),
            updatedAt = Instant.ofEpochSecond(seconds),
            deletedAt = deletedAt,
        )
}
