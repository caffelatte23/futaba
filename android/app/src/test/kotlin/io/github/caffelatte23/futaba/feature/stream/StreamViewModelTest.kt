package io.github.caffelatte23.futaba.feature.stream

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.github.caffelatte23.futaba.core.data.NoteRepository
import io.github.caffelatte23.futaba.core.data.UserPreferencesRepository
import io.github.caffelatte23.futaba.core.domain.labelUsage
import io.github.caffelatte23.futaba.core.domain.suggestLabels
import io.github.caffelatte23.futaba.core.model.Label
import io.github.caffelatte23.futaba.core.model.Note
import io.github.caffelatte23.futaba.core.model.NoteFilter
import io.github.caffelatte23.futaba.testing.FakeNoteRepository
import io.github.caffelatte23.futaba.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val repo = FakeNoteRepository()
    private val uid = MutableStateFlow<String?>("u1")

    // In-memory DataStore: the file-backed one races on Windows renames under test dispatchers.
    private fun prefsWith(gate: CompletableDeferred<Unit>) =
        UserPreferencesRepository(
            object : DataStore<Preferences> {
                private val state = MutableStateFlow(emptyPreferences())
                override val data: Flow<Preferences> =
                    flow {
                        gate.await()
                        emitAll(state)
                    }

                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                    transform(state.value).also { state.value = it }
            },
        )

    private val prefs = prefsWith(CompletableDeferred(Unit))

    private fun vm(filter: NoteFilter = NoteFilter.All) =
        StreamViewModel(repo, prefs, uid, CoroutineScope(main.dispatcher), filter)

    @Test
    fun sendIgnoresBlankText() =
        runTest {
            val vm = vm()
            vm.state.test {
                vm.onDraftChange("  \n ")
                assertThat(expectMostRecentItem().canSend).isFalse()
                assertThat(vm.send("  ")).isFalse()
                assertThat(repo.notes.first()).isEmpty()
            }
        }

    @Test
    fun sendDisabledWithoutUid() =
        runTest {
            uid.value = null
            val vm = vm()
            vm.state.test {
                vm.onDraftChange("idea")
                val state = expectMostRecentItem()
                assertThat(state.canSend).isFalse()
                assertThat(state.draft).isEqualTo("idea")
                assertThat(vm.send("idea")).isFalse()
                assertThat(repo.notes.first()).isEmpty()
                assertThat(vm.state.value.draft).isEqualTo("idea")
            }
        }

    @Test
    fun sendClearsDraftAndAdds() =
        runTest {
            val vm = vm()
            vm.state.test {
                vm.onDraftChange("idea")
                advanceUntilIdle()
                assertThat(vm.send("idea")).isTrue()
                advanceUntilIdle()
                val state = expectMostRecentItem()
                assertThat(state.draft).isEmpty()
                assertThat(
                    state.sections
                        .single()
                        .notes
                        .single()
                        .text,
                ).isEqualTo("idea")
                assertThat(prefs.draft.first()).isEmpty()
            }
        }

    @Test
    fun sendInLabelViewAttachesLabel() =
        runTest {
            val id = repo.createLabel("work")
            val vm = vm(NoteFilter.ByLabel(id))
            vm.state.test {
                vm.onDraftChange("idea")
                vm.send("idea")
                assertThat(
                    repo.notes
                        .first()
                        .single()
                        .labelIds,
                ).containsExactly(id)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun suggestionsAppearAfterHash() =
        runTest {
            repo.createLabel("アプリ案")
            repo.createLabel("買い物")
            val vm = vm()
            vm.state.test {
                vm.onDraftChange("メモ #ア")
                val expected = suggestLabels("ア", repo.labels.first(), labelUsage(emptyList()))
                assertThat(expected).isNotEmpty()
                assertThat(expectMostRecentItem().suggestions).isEqualTo(expected)
            }
        }

    @Test
    fun restoredDraftIsHydrated() =
        runTest {
            prefs.setDraft("saved")
            assertThat(vm().draftNow()).isEqualTo("saved")
        }

    @Test
    fun typingBeforeHydrationIsNotOverwritten() =
        runTest {
            val slow = CompletableDeferred<Unit>()
            val vm = StreamViewModel(repo, prefsWith(slow), uid, CoroutineScope(main.dispatcher), NoteFilter.All)
            vm.onDraftChange("early")
            vm.state.test {
                assertThat(expectMostRecentItem().draftLoaded).isFalse()
                slow.complete(Unit)
                val loaded = expectMostRecentItem()
                assertThat(loaded.draftLoaded).isTrue()
                assertThat(loaded.draft).isEmpty()
            }
        }

    @Test
    fun sendRejectedKeepsDraft() =
        runTest {
            val vm = vm()
            vm.state.test {
                vm.onDraftChange("idea")
                uid.value = null
                assertThat(vm.send("idea")).isFalse()
                assertThat(expectMostRecentItem().draft).isEqualTo("idea")
            }
        }

    @Test
    fun flushAdoptsCurrentFieldText() =
        runTest {
            val vm = vm()
            vm.onDraftChange("old") // the ViewModel has not yet seen the field's latest edit
            vm.flushDraft("newer")
            assertThat(vm.draftNow()).isEqualTo("newer")
            assertThat(prefs.draft.first()).isEqualTo("newer")
        }

    @Test
    fun staleFlushCannotOverwriteClearAfterSend() =
        runTest {
            val vm = vm()
            vm.onDraftChange("idea")
            vm.flushDraft("idea")
            assertThat(vm.send("idea")).isTrue()
            vm.flushDraft("") // a late flush must persist the latest intent, not older text
            advanceUntilIdle()
            assertThat(prefs.draft.first()).isEmpty()
        }

    @Test
    fun composerReadyWithoutNotesOrLabels() =
        runTest {
            val silent =
                object : NoteRepository by repo {
                    override val notes: Flow<List<Note>> = emptyFlow()
                    override val labels: Flow<List<Label>> = emptyFlow()
                }
            val vm = StreamViewModel(silent, prefs, uid, CoroutineScope(main.dispatcher), NoteFilter.All)
            vm.state.test { assertThat(expectMostRecentItem().draftLoaded).isTrue() }
        }

    @Test
    fun startupFocusIsTakenOnce() =
        runTest {
            val vm = vm()
            assertThat(vm.takeStartupFocus()).isTrue()
            assertThat(vm.takeStartupFocus()).isFalse()
        }
}
