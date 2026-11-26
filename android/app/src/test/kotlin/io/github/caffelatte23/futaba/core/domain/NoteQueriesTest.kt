package io.github.caffelatte23.futaba.core.domain

import com.google.common.truth.Truth.assertThat
import io.github.caffelatte23.futaba.core.model.Label
import io.github.caffelatte23.futaba.core.model.Note
import io.github.caffelatte23.futaba.core.model.NoteFilter
import kotlinx.serialization.json.Json
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class NoteQueriesTest {
    private val older =
        Note("older", "写真", emptyList(), false, Instant.EPOCH, Instant.EPOCH.plusSeconds(120), null)
    private val newer =
        older.copy(
            id = "newer",
            text = "写真と献立 Photo",
            labelIds = listOf("work"),
            pinned = true,
            createdAt = Instant.EPOCH.plusSeconds(60),
        )
    private val deleted = newer.copy(id = "deleted", deletedAt = Instant.EPOCH.plusSeconds(180))
    private val notes = listOf(older, deleted, newer)

    @Test
    fun allExcludesDeleted() {
        assertThat(filterNotes(notes, NoteFilter.All)).containsExactly(newer, older).inOrder()
    }

    @Test
    fun trashOnlyDeleted() {
        assertThat(filterNotes(notes, NoteFilter.Trash)).containsExactly(deleted)
    }

    @Test
    fun unlabeledOnlyEmptyLabelIds() {
        val deletedUnlabeled = deleted.copy(labelIds = emptyList())
        assertThat(filterNotes(notes + deletedUnlabeled, NoteFilter.Unlabeled)).containsExactly(older)
    }

    @Test
    fun byLabel() {
        assertThat(filterNotes(notes, NoteFilter.ByLabel("work"))).containsExactly(newer)
    }

    @Test
    fun pinned() {
        assertThat(filterNotes(notes, NoteFilter.Pinned)).containsExactly(newer)
    }

    @Test
    fun queryIsAndOfTerms() {
        assertThat(filterNotes(notes, NoteFilter.All, "写真 献立")).containsExactly(newer)
        assertThat(filterNotes(notes, NoteFilter.All, "  献立　pHO\t写真\n")).containsExactly(newer)
        assertThat(filterNotes(notes, NoteFilter.Trash, "献立")).containsExactly(deleted)
    }

    @Test
    fun usageCountsEachLiveNoteOncePerLabel() {
        val alsoLabeled = older.copy(labelIds = listOf("home", "work"))
        val duplicateLabels = newer.copy(labelIds = listOf("work", "work"))
        val deletedOnly = deleted.copy(labelIds = listOf("deleted"))

        assertThat(labelUsage(listOf(alsoLabeled, duplicateLabels, deletedOnly)))
            .containsExactly("work", 2, "home", 1)
    }

    @Test
    fun suggestOrdersByUsageThenName() {
        val idea = Label("idea", "アイデア整理", "#ffc83d", 0)
        val app = idea.copy(id = "app", name = "アプリ案")
        val icon = idea.copy(id = "icon", name = "アイコン")
        val album = idea.copy(id = "album", name = "アルバム")
        val other = idea.copy(id = "other", name = "仕事")
        val usage = mapOf(app.id to 41, idea.id to 3)

        assertThat(suggestLabels("ア", listOf(idea, app), usage)).containsExactly(app, idea).inOrder()
        assertThat(suggestLabels("ア", listOf(album, idea, app, icon, other), usage))
            .containsExactly(app, idea, icon, album)
            .inOrder()
        assertThat(suggestLabels("wo", listOf(other.copy(name = "Work")), emptyMap()))
            .containsExactly(other.copy(name = "Work"))
    }

    @Test
    fun groupByDayUsesLocalCreationDateAndNewestFirst() {
        val beforeMidnight = older.copy(createdAt = Instant.parse("2026-11-24T14:59:00Z"))
        val atMidnight = newer.copy(createdAt = Instant.parse("2026-11-24T15:00:00Z"))
        val later = older.copy(id = "later", createdAt = Instant.parse("2026-11-24T16:00:00Z"))

        assertThat(groupByDay(listOf(beforeMidnight, atMidnight, later), ZoneId.of("Asia/Tokyo")))
            .containsExactly(
                LocalDate.of(2026, 11, 25) to listOf(later, atMidnight),
                LocalDate.of(2026, 11, 24) to listOf(beforeMidnight),
            ).inOrder()
    }

    @Test
    fun filterVariantsRoundTripThroughSerialization() {
        val filters =
            listOf(
                NoteFilter.All,
                NoteFilter.Pinned,
                NoteFilter.Unlabeled,
                NoteFilter.ByLabel("work"),
                NoteFilter.Trash,
            )

        for (filter in filters) {
            assertThat(Json.decodeFromString<NoteFilter>(Json.encodeToString(filter))).isEqualTo(filter)
        }
    }
}
