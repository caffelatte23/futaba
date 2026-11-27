package io.github.caffelatte23.futaba.core.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AccountMergeTest {
    @Test
    fun remapsLabelsAndKeepsBothNotesWhenIdsOverlap() {
        val source =
            MergeDocuments(
                notes =
                    mapOf(
                        "same" to
                            mapOf(
                                "text" to "anonymous",
                                "labelIds" to listOf("shared", "duplicate", "new", "stale"),
                            ),
                    ),
                labels = mapOf("shared" to label("IDEA"), "duplicate" to label("idea"), "new" to label("Work")),
            )
        val destination =
            MergeDocuments(
                notes = mapOf("same" to mapOf("text" to "Google")),
                labels = mapOf("existing" to label("Idea"), "new" to label("Unrelated")),
            )

        val plan = planAccountMerge("anonymous", source, destination)
        val newLabel = plan.labels.entries.single()
        assertThat(newLabel.key).isNotEqualTo("new")
        assertThat(newLabel.value["name"]).isEqualTo("Work")
        val copiedNote = plan.notes.entries.single()
        assertThat(copiedNote.key).isNotEqualTo("same")
        assertThat(copiedNote.value["text"]).isEqualTo("anonymous")
        assertThat(copiedNote.value["labelIds"]).isEqualTo(listOf("existing", newLabel.key))

        val afterCopy = MergeDocuments(destination.notes + plan.notes, destination.labels + plan.labels)
        val retry = planAccountMerge("anonymous", source, afterCopy)
        assertThat(retry.labels).isEmpty()
        assertThat(retry.notes).isEmpty()
        assertThat(retry.noteCount).isEqualTo(1)

        val afterPartialCleanup = source.copy(notes = emptyMap())
        assertThat(planAccountMerge("anonymous", afterPartialCleanup, afterCopy).noteCount).isEqualTo(1)
    }

    private fun label(name: String) = mapOf("name" to name, "color" to "", "order" to 0L)
}
