package io.github.caffelatte23.futaba.feature.detail

import com.google.common.truth.Truth.assertThat
import io.github.caffelatte23.futaba.core.data.UndoBus
import io.github.caffelatte23.futaba.core.model.Note
import io.github.caffelatte23.futaba.testing.FakeNoteRepository
import io.github.caffelatte23.futaba.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class DetailViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val repo = FakeNoteRepository()
    private val bus = UndoBus()

    @Suppress("RedundantSuspendModifier") // detekt false positive: Flow.first is a suspend call
    private suspend fun note(id: String): Note = repo.notes.first().single { it.id == id }

    @Test
    fun autosaveDebounces() =
        runTest {
            val id = repo.add("a")
            val vm = DetailViewModel(repo, bus, id)
            vm.onTextChange("b")
            advanceTimeBy(100)
            vm.onTextChange("bc")
            advanceTimeBy(100)
            vm.onTextChange("bcd")
            advanceTimeBy(499)
            assertThat(note(id).text).isEqualTo("a")
            advanceTimeBy(2)
            assertThat(note(id).text).isEqualTo("bcd")
        }

    @Test
    fun emptyTextDeletes() =
        runTest {
            val id = repo.add("a")
            val vm = DetailViewModel(repo, bus, id)
            assertThat(vm.leave("")).isTrue()
            assertThat(note(id).deletedAt).isNotNull()
            assertThat(vm.events.first()).isEqualTo(DetailEvent.Deleted(id))
            assertThat(bus.deletions.first()).containsExactly(id)
        }

    @Test
    fun flushOnStopPersistsCurrentText() =
        runTest {
            val id = repo.add("a")
            val vm = DetailViewModel(repo, bus, id)
            vm.onTextChange("b")
            vm.flush("bc")
            assertThat(note(id).text).isEqualTo("bc")
            advanceTimeBy(1_000)
            runCurrent()
            assertThat(note(id).text).isEqualTo("bc")
        }

    @Test
    fun unchangedTextDoesNotTouchNote() =
        runTest {
            val id = repo.add("a")
            val before = note(id)
            DetailViewModel(repo, bus, id).leave("a")
            assertThat(note(id)).isEqualTo(before)
        }

    @Test
    fun undoRestoresDeletedNote() =
        runTest {
            val id = repo.add("a")
            DetailViewModel(repo, bus, id).delete()
            assertThat(note(id).deletedAt).isNotNull()
            repo.restore(bus.deletions.first())
            assertThat(note(id).deletedAt).isNull()
        }

    @Test
    fun headerShowsEditTimeOnlyAfterAMinute() {
        val created = Instant.parse("2026-11-25T12:40:00Z")
        val utc = ZoneOffset.UTC
        assertThat(detailHeader(created, created.plusSeconds(59), utc)).isEqualTo("2026/11/25 12:40")
        assertThat(detailHeader(created, Instant.parse("2026-11-25T12:52:30Z"), utc))
            .isEqualTo("2026/11/25 12:40 · 12:52 編集")
    }
}
