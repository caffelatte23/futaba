package io.github.caffelatte23.futaba.feature.stream

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.github.caffelatte23.futaba.core.data.DraftStore
import io.github.caffelatte23.futaba.core.data.NoteRepository
import io.github.caffelatte23.futaba.core.data.UndoBus
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val repo = FakeNoteRepository()
    private val uid = MutableStateFlow<String?>("u1")

    // In-memory DataStore: the file-backed one races on Windows renames under test dispatchers.
    private fun prefsWith(
        gate: CompletableDeferred<Unit>,
        onWrite: () -> Unit = {},
    ) = UserPreferencesRepository(
        object : DataStore<Preferences> {
            private val state = MutableStateFlow(emptyPreferences())
            override val data: Flow<Preferences> =
                flow {
                    gate.await()
                    emitAll(state)
                }

            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                transform(state.value).also {
                    onWrite()
                    state.value = it
                }
        },
    )

    private val prefs = prefsWith(CompletableDeferred(Unit))
    private val drafts = DraftStore(prefs)

    private fun vm(
        filter: NoteFilter = NoteFilter.All,
        appScope: CoroutineScope = CoroutineScope(main.dispatcher),
    ) = StreamViewModel(repo, prefs, drafts, uid, appScope, UndoBus(), filter)

    @Test
    fun switchingFilterKeepsDraft() =
        runTest {
            val first = vm()
            first.state.test {
                first.onDraftChange("keep me")
                advanceUntilIdle()
                cancelAndIgnoreRemainingEvents()
            }
            val second = vm(NoteFilter.ByLabel("a"))
            second.state.test {
                advanceUntilIdle()
                assertThat(expectMostRecentItem().draft).isEqualTo("keep me")
            }
        }

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
    fun replacementViewModelHydratesFromPendingIntentNotDisk() =
        runTest {
            prefs.setDraft("saved")
            val appScope = CoroutineScope(StandardTestDispatcher(testScheduler))
            val old = vm(appScope = appScope)
            old.flushDraft("pending")
            assertThat(prefs.draft.first()).isEqualTo("saved")
            val replacement = vm(appScope = appScope)
            assertThat(replacement.draftNow()).isEqualTo("pending")
            runCurrent()
            assertThat(prefs.draft.first()).isEqualTo("pending")

            assertThat(replacement.send("pending")).isTrue()
            assertThat(prefs.draft.first()).isEqualTo("pending")
            assertThat(vm().draftNow()).isEmpty()
            advanceTimeBy(301)
            assertThat(prefs.draft.first()).isEmpty()
        }

    @Test
    fun hydrationNeverWritesOrMintsSeq() =
        runTest {
            var writes = 0
            val prefs = prefsWith(CompletableDeferred(Unit)) { writes++ }
            prefs.setDraft("saved")
            val drafts = DraftStore(prefs)
            val vm =
                StreamViewModel(repo, prefs, drafts, uid, CoroutineScope(main.dispatcher), UndoBus(), NoteFilter.All)
            assertThat(vm.draftNow()).isEqualTo("saved")
            advanceTimeBy(301)

            val nextSeq = drafts.nextSeq()
            assertThat(listOf(writes.toLong(), nextSeq)).containsExactly(1L, 1L)
        }

    @Test
    fun typingBeforeHydrationIsNotOverwritten() =
        runTest {
            val slow = CompletableDeferred<Unit>()
            val vm =
                StreamViewModel(
                    repo,
                    prefsWith(slow),
                    DraftStore(prefs),
                    uid,
                    CoroutineScope(main.dispatcher),
                    UndoBus(),
                    NoteFilter.All,
                )
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
            val vm =
                StreamViewModel(silent, prefs, drafts, uid, CoroutineScope(main.dispatcher), UndoBus(), NoteFilter.All)
            vm.state.test { assertThat(expectMostRecentItem().draftLoaded).isTrue() }
        }

    @Test
    fun startupFocusIsTakenOnce() =
        runTest {
            val vm = vm()
            assertThat(vm.takeStartupFocus()).isTrue()
            assertThat(vm.takeStartupFocus()).isFalse()
        }

    @Test
    fun flushFromClearedViewModelCannotOverwriteNewerDraft() =
        runTest {
            val old = vm()
            val oldSeq = drafts.nextSeq() // the old ViewModel's flush captured its intent earlier...
            val replacement = vm()
            replacement.onDraftChange("idea")
            assertThat(replacement.send("idea")).isTrue() // ...then the replacement cleared after send
            drafts.write("stale", oldSeq) // ...and the old flush finally runs
            assertThat(prefs.draft.first()).isEmpty()
            assertThat(old.draftNow()).isNotNull()
        }

    @Test
    fun draftStoreDropsOlderSeq() =
        runTest {
            val a = drafts.nextSeq()
            val b = drafts.nextSeq()
            drafts.write("b", b)
            drafts.write("a", a)
            assertThat(prefs.draft.first()).isEqualTo("b")
        }

    @Test
    fun stopBeforeComposerReadyDoesNotEraseDraft() =
        runTest {
            prefs.setDraft("saved")
            val vm = vm()
            val notReady = ComposerState("", enabled = false) // hydrated VM, composer not yet rebuilt
            notReady.textIfReady()?.let(vm::flushDraft)
            assertThat(notReady.textIfReady()).isNull()
            assertThat(prefs.draft.first()).isEqualTo("saved")
            assertThat(ComposerState("x", enabled = true).textIfReady()).isEqualTo("x")
        }
}
