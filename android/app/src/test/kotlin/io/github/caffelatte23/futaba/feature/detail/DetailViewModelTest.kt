package io.github.caffelatte23.futaba.feature.detail

import com.google.common.truth.Truth.assertThat
import io.github.caffelatte23.futaba.core.data.NoteRepository
import io.github.caffelatte23.futaba.core.data.UndoBus
import io.github.caffelatte23.futaba.core.model.Note
import io.github.caffelatte23.futaba.testing.FakeNoteRepository
import io.github.caffelatte23.futaba.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/** Records `updateText` calls on top of the fake. */
private class CountingRepo(
    private val inner: FakeNoteRepository,
) : NoteRepository by inner {
    val updates = mutableListOf<String>()

    override fun updateText(
        id: String,
        text: String,
    ) {
        updates += text
        inner.updateText(id, text)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DetailViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val fake = FakeNoteRepository()
    private val repo = CountingRepo(fake)
    private var now = 0L
    private val bus = UndoBus { now }

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
            assertThat(repo.updates).isEmpty()
            advanceTimeBy(2)
            assertThat(repo.updates).containsExactly("bcd")
        }

    @Test
    fun emptyTextDeletes() =
        runTest {
            val id = repo.add("a")
            val vm = DetailViewModel(repo, bus, id)
            assertThat(vm.leave("")).isTrue()
            assertThat(note(id).deletedAt).isNotNull()
            assertThat(note(id).text).isEqualTo("a")
            assertThat(vm.events.first()).isEqualTo(DetailEvent.Deleted(id))
            assertThat(bus.pending.value?.ids).containsExactly(id)
        }

    @Test
    fun backBeforeLoadDoesNotDelete() =
        runTest {
            val slow =
                object : NoteRepository by repo {
                    override val notes: Flow<List<Note>> = MutableStateFlow(emptyList())
                }
            val id = repo.add("a")
            val vm = DetailViewModel(slow, bus, id)
            assertThat(vm.leave("")).isFalse()
            vm.flush("")
            assertThat(note(id).deletedAt).isNull()
            assertThat(bus.pending.value).isNull()
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
            assertThat(repo.updates).containsExactly("bc")
        }

    @Test
    fun recreatedViewModelKeepsPendingText() =
        runTest {
            val id = repo.add("a")
            val vm = DetailViewModel(repo, bus, id)
            vm.flush("B")
            // The repository may still expose "a" to a recreated screen; the ViewModel is the owner.
            assertThat(vm.textNow()).isEqualTo("B")
            vm.onTextChange("")
            assertThat(vm.textNow()).isEmpty()
            vm.flush("")
            assertThat(vm.textNow()).isEmpty()
            assertThat(note(id).text).isEqualTo("B")
        }

    @Test
    fun unchangedTextDoesNotTouchNote() =
        runTest {
            val id = repo.add("a")
            val before = note(id)
            DetailViewModel(repo, bus, id).leave("a")
            assertThat(note(id)).isEqualTo(before)
            assertThat(repo.updates).isEmpty()
        }

    @Test
    fun editThenDeleteFlushesEdit() =
        runTest {
            val id = repo.add("a")
            val vm = DetailViewModel(repo, bus, id)
            vm.onTextChange("edited")
            vm.delete("edited")
            assertThat(note(id).text).isEqualTo("edited")
            assertThat(note(id).deletedAt).isNotNull()
        }

    @Test
    fun undoRestoresDeletedNote() =
        runTest {
            val id = repo.add("a")
            DetailViewModel(repo, bus, id).delete("a")
            val undo = bus.pending.value!!
            repo.restore(undo.ids)
            assertThat(note(id).deletedAt).isNull()
        }

    @Test
    fun undoSurvivesStreamRecreationAndExpires() {
        bus.postDeleted(listOf("x"))
        // A recreated Stream reads the same singleton state until it is acted on or expired.
        assertThat(bus.pending.value?.ids).containsExactly("x")
        assertThat(bus.pending.value?.expiresAt).isEqualTo(5_000L)
        now = 5_000L
        assertThat(bus.pending.value?.isExpired(now)).isTrue()
        bus.clear(bus.pending.value!!)
        assertThat(bus.pending.value).isNull()
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
