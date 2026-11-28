package io.github.caffelatte23.futaba.feature.stream

import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.github.caffelatte23.futaba.core.data.ComposerFocusRequests
import io.github.caffelatte23.futaba.core.data.DraftStore
import io.github.caffelatte23.futaba.core.data.NoteRepository
import io.github.caffelatte23.futaba.core.data.UndoBus
import io.github.caffelatte23.futaba.core.data.UserPreferencesRepository
import io.github.caffelatte23.futaba.core.domain.labelUsage
import io.github.caffelatte23.futaba.core.domain.suggestLabels
import io.github.caffelatte23.futaba.core.model.Label
import io.github.caffelatte23.futaba.core.model.Note
import io.github.caffelatte23.futaba.core.model.NoteFilter
import io.github.caffelatte23.futaba.feature.labels.LabelCheck
import io.github.caffelatte23.futaba.feature.labels.labelChanges
import io.github.caffelatte23.futaba.feature.labels.labelChecks
import io.github.caffelatte23.futaba.testing.FakeNoteRepository
import io.github.caffelatte23.futaba.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass") // one test class per ViewModel
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
    private val focus = ComposerFocusRequests()

    private fun vm(
        filter: NoteFilter = NoteFilter.All,
        appScope: CoroutineScope = CoroutineScope(main.dispatcher),
        undo: UndoBus = UndoBus(),
        repository: NoteRepository = repo,
    ) = StreamViewModel(repository, prefs, drafts, uid, appScope, undo, focus, filter)

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
    fun shareWhileStreamOpenUpdatesComposerDraft() =
        runTest {
            uid.value = null
            val vm = vm()
            vm.state.test {
                val composer = vm.newComposer(enabled = true)
                composer.text.edit { append("existing") }
                vm.syncComposer(composer)
                drafts.append("shared")
                vm.syncComposer(composer)
                advanceUntilIdle()

                assertThat(composer.textIfReady()).isEqualTo("existing\n\nshared")
                assertThat(expectMostRecentItem().draft).isEqualTo("existing\n\nshared")
                assertThat(vm.draftNow()).isEqualTo("existing\n\nshared")
                assertThat(prefs.draft.first()).isEqualTo("existing\n\nshared")
            }
        }

    @Test
    fun shareDuringUnsyncedTypingKeepsBoth() =
        runTest {
            prefs.setDraft("existing")
            val vm = vm()
            val composer = vm.newComposer(enabled = true)
            composer.text.edit { append(" typed") }
            drafts.append("shared")
            drafts.append("another share")

            vm.syncComposer(composer)
            vm.syncComposer(composer)
            advanceUntilIdle()

            val combined = "existing typed\n\nshared\n\nanother share"
            assertThat(composer.textIfReady()).isEqualTo(combined)
            assertThat(vm.draftNow()).isEqualTo(combined)
            assertThat(prefs.draft.first()).isEqualTo(combined)
        }

    @Test
    fun shareDuringCompositionKeepsBoth() =
        runTest {
            val vm = vm()
            val composer = vm.newComposer(enabled = true)
            composer.text.appendComposingText("typing")
            assertThat(composer.text.composition).isEqualTo(TextRange(0, 6))
            vm.syncComposer(composer)
            drafts.append("shared")

            vm.syncComposer(composer)
            assertThat(composer.textIfReady()).isEqualTo("typing\n\nshared")
            assertThat(composer.text.composition).isNull()
            assertThat(composer.text.selection).isEqualTo(TextRange("typing\n\nshared".length))

            composer.text.edit { append(" next") }
            vm.syncComposer(composer)
            advanceUntilIdle()
            assertThat(prefs.draft.first()).isEqualTo("typing\n\nshared next")
        }

    @Test
    fun shareAppliedOnceAfterRehydration() =
        runTest {
            prefs.setDraft("saved")
            val unmounted = vm()
            drafts.append("shared")

            val remounted = unmounted.newComposer(enabled = true)
            val rehydrated = vm()
            val composer = rehydrated.newComposer(enabled = true)
            assertThat(remounted.textIfReady()).isEqualTo("saved\n\nshared")
            assertThat(composer.textIfReady()).isEqualTo("saved\n\nshared")
            val shareIntent = drafts.latestIntent.value
            rehydrated.syncComposer(composer)
            rehydrated.syncComposer(composer)
            advanceUntilIdle()

            assertThat(composer.textIfReady()).isEqualTo("saved\n\nshared")
            assertThat(prefs.draft.first()).isEqualTo("saved\n\nshared")
            assertThat(drafts.latestIntent.value).isEqualTo(shareIntent)
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
                StreamViewModel(
                    repo,
                    prefs,
                    drafts,
                    uid,
                    CoroutineScope(main.dispatcher),
                    UndoBus(),
                    focus,
                    NoteFilter.All,
                )
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
                    focus,
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
                StreamViewModel(
                    silent,
                    prefs,
                    drafts,
                    uid,
                    CoroutineScope(main.dispatcher),
                    UndoBus(),
                    focus,
                    NoteFilter.All,
                )
            vm.state.test { assertThat(expectMostRecentItem().draftLoaded).isTrue() }
        }

    @Test
    fun focusNotRepeatedAfterFilterSwitch() =
        runTest {
            focus.request()
            vm().focusRequested.test { awaitItem() }
            vm(NoteFilter.ByLabel("a")).focusRequested.test { expectNoEvents() }
            focus.request() // e.g. a new intent re-tapping the tile
            vm(NoteFilter.ByLabel("a")).focusRequested.test { awaitItem() }
        }

    @Test
    fun newRequestWhileMountedRefocuses() =
        runTest {
            vm().focusRequested.test {
                focus.request()
                awaitItem()
                focus.request()
                awaitItem()
            }
        }

    @Test
    fun byLabelSendDisabledUntilLabelsLoaded() =
        runTest {
            val late = MutableSharedFlow<List<Label>>()
            val slow =
                object : NoteRepository by repo {
                    override val labels: Flow<List<Label>> = late
                }
            val vm =
                StreamViewModel(
                    slow,
                    prefs,
                    drafts,
                    uid,
                    CoroutineScope(main.dispatcher),
                    UndoBus(),
                    focus,
                    NoteFilter.ByLabel("w"),
                )
            vm.state.test {
                vm.onDraftChange("x")
                advanceUntilIdle()
                assertThat(expectMostRecentItem().canSend).isFalse()
                assertThat(vm.send("x")).isFalse()
                late.emit(listOf(Label("w", "work", "", 0)))
                advanceUntilIdle()
                assertThat(expectMostRecentItem().canSend).isTrue()
                assertThat(vm.send("x")).isTrue()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun emptyLabelsWhileSignedOutDoesNotFallBack() =
        runTest {
            uid.value = null
            val vm = vm(NoteFilter.ByLabel("w"))
            vm.state.test {
                advanceUntilIdle()
                assertThat(expectMostRecentItem().filterMissing).isFalse()
                uid.value = "u1" // signed in: the (really empty) label list now counts
                advanceUntilIdle()
                assertThat(expectMostRecentItem().filterMissing).isTrue()
            }
        }

    @Test
    fun deletedActiveLabelFallsBackToAll() =
        runTest {
            val id = repo.createLabel("idea")
            val vm = vm(NoteFilter.ByLabel(id))
            vm.state.test {
                vm.onDraftChange("x")
                advanceUntilIdle()
                assertThat(expectMostRecentItem().filterMissing).isFalse()
                repo.deleteLabel(id)
                advanceUntilIdle()
                assertThat(expectMostRecentItem().filterMissing).isTrue()
                assertThat(vm.send("x")).isFalse()
            }
            assertThat(repo.notes.first()).isEmpty()
        }

    @Test
    fun switchRightAfterEditKeepsLatestText() =
        runTest {
            val first = vm()
            runCurrent() // hydrate only; the edit below is not debounced or observed yet
            first.onDraftChange("fresh")
            first.flushDraft("fresher") // the field is ahead of the ViewModel at switch time
            val second = vm(NoteFilter.ByLabel("a"))
            runCurrent()
            assertThat(second.draftNow()).isEqualTo("fresher")
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

    @Test
    fun applyLabelsToSelection() =
        runTest {
            val a = repo.add("a")
            val b = repo.add("b")
            val l1 = repo.createLabel("l1")
            val vm = vm()
            vm.state.test {
                vm.toggleSelect(a)
                vm.toggleSelect(b)
                assertThat(expectMostRecentItem().selection).containsExactly(a, b)
                vm.applyLabels(listOf(l1), emptyList())
                val call = repo.setLabelsCalls.single()
                assertThat(call.ids).containsExactly(a, b)
                assertThat(call.add).containsExactly(l1)
                assertThat(call.remove).isEmpty()
                val s = expectMostRecentItem()
                assertThat(s.selection).isEmpty()
                assertThat(s.sections.flatMap { it.notes }.map { it.labelIds }).containsExactly(listOf(l1), listOf(l1))
            }
        }

    @Test
    fun pinToggleUnpinsWhenAllPinned() =
        runTest {
            val a = repo.add("a")
            val b = repo.add("b")
            repo.setPinned(a, true)
            val vm = vm()
            vm.state.test {
                vm.toggleSelect(a)
                vm.toggleSelect(b)
                expectMostRecentItem()
                vm.pinSelected() // mixed -> pin all
                assertThat(expectMostRecentItem().pinned.map { it.id }).containsExactly(a, b)
                vm.toggleSelect(a)
                vm.toggleSelect(b)
                expectMostRecentItem()
                vm.pinSelected() // all pinned -> unpin
                assertThat(expectMostRecentItem().pinned).isEmpty()
            }
        }

    @Test
    fun deleteSelectedPostsUndo() =
        runTest {
            val a = repo.add("a")
            val b = repo.add("b")
            val undo = UndoBus()
            val vm = vm(undo = undo)
            vm.state.test {
                vm.toggleSelect(a)
                vm.toggleSelect(b)
                expectMostRecentItem()
                vm.deleteSelected()
                val s = expectMostRecentItem()
                assertThat(s.selection).isEmpty()
                assertThat(s.count).isEqualTo(0)
                assertThat(undo.pending.value!!.ids).containsExactly(a, b)
            }
        }

    @Test
    fun labelChangesAreDiffedAgainstBaseline() {
        val a = Note("a", "a", listOf("x", "y"), false, java.time.Instant.EPOCH, java.time.Instant.EPOCH, null)
        val b = a.copy(id = "b", labelIds = listOf("x"))
        val labels = listOf("x", "y", "z").mapIndexed { i, id -> Label(id, id, "", i) }
        val base = labelChecks(listOf(a, b), labels)
        assertThat(base).containsExactly("x", LabelCheck.All, "y", LabelCheck.Some, "z", LabelCheck.None)
        val overrides = mapOf("y" to LabelCheck.All, "x" to LabelCheck.None, "z" to LabelCheck.None)
        assertThat(labelChanges(overrides, base)).isEqualTo(listOf("y") to listOf("x"))
    }

    private fun current(s: StreamUiState) = labelChecks(s.selectedNotes, s.labels)

    @Test
    fun restoredNoteDoesNotReenterSelection() =
        runTest {
            val a = repo.add("a")
            repo.add("b")
            val vm = vm()
            vm.state.test {
                vm.toggleSelect(a)
                assertThat(expectMostRecentItem().selection).containsExactly(a)
                repo.delete(listOf(a))
                assertThat(expectMostRecentItem().selection).isEmpty()
                repo.restore(listOf(a))
                assertThat(expectMostRecentItem().selection).isEmpty()
            }
        }

    @Test
    fun sheetClosesWhenSelectionEmpties() =
        runTest {
            val a = repo.add("a")
            val vm = vm()
            vm.state.test {
                vm.toggleSelect(a)
                vm.labelSheet.open()
                assertThat(expectMostRecentItem().sheet).isNotNull()
                repo.delete(listOf(a))
                assertThat(expectMostRecentItem().sheet).isNull()
                repo.restore(listOf(a))
                vm.toggleSelect(a)
                assertThat(expectMostRecentItem().sheet).isNull() // a new selection does not revive the old sheet
            }
        }

    @Test
    fun lateLabelReflectsCurrentMembership() =
        runTest {
            val a = repo.add("a")
            val vm = vm()
            vm.state.test {
                vm.toggleSelect(a)
                vm.labelSheet.open()
                expectMostRecentItem()
                val l = repo.createLabel("late") // arrives after the sheet opened
                repo.setLabels(listOf(a), listOf(l), emptyList())
                val s = expectMostRecentItem()
                assertThat(current(s)[l]).isEqualTo(LabelCheck.All)
                assertThat(s.sheet!!.overrides).isEmpty()
                repo.setLabelsCalls.clear()
                vm.labelSheet.finish() // untouched: nothing to write
                assertThat(repo.setLabelsCalls).isEmpty()
                vm.toggleSelect(a)
                vm.toggleSelect(a)
                vm.labelSheet.open()
                vm.labelSheet.toggle(l, LabelCheck.All)
                vm.labelSheet.finish()
                assertThat(repo.setLabelsCalls.single().remove).containsExactly(l)
                cancelAndIgnoreRemainingEvents()
            }
        }

    private fun failingCreate(
        gate: CompletableDeferred<Unit>? = null,
        fail: Boolean = false,
    ) = object : NoteRepository by repo {
        override suspend fun createLabel(
            name: String,
            reuseExisting: Boolean,
        ): String {
            gate?.await()
            check(!fail) { "signed out" }
            return repo.createLabel(name, reuseExisting)
        }
    }

    @Test
    fun createWhileSignedOutShowsErrorAndKeepsQuery() =
        runTest {
            val a = repo.add("a")
            val vm = vm(repository = failingCreate(fail = true))
            vm.state.test {
                vm.toggleSelect(a)
                vm.labelSheet.open()
                vm.labelSheet.setQuery("idea")
                vm.labelSheet.create("idea")
                val sheet = expectMostRecentItem().sheet!!
                assertThat(sheet.createFailed).isTrue()
                assertThat(sheet.query).isEqualTo("idea")
                assertThat(sheet.creating).isFalse()
            }
        }

    @Test
    fun dismissBlockedWhileCreating() =
        runTest {
            val a = repo.add("a")
            val gate = CompletableDeferred<Unit>()
            val vm = vm(repository = failingCreate(gate))
            vm.state.test {
                vm.toggleSelect(a)
                vm.labelSheet.open()
                vm.labelSheet.create("new")
                vm.labelSheet.finish() // scrim tap / 完了 / Back while the create is suspended
                vm.labelSheet.cancel()
                runCurrent()
                assertThat(vm.labelSheet.state.value).isNotNull()
                assertThat(repo.setLabelsCalls).isEmpty()
                gate.complete(Unit)
                runCurrent()
                vm.labelSheet.finish() // create done: the new label is staged, finish attaches it
                val id =
                    repo.labels
                        .first()
                        .single { it.name == "new" }
                        .id
                val call = repo.setLabelsCalls.single()
                assertThat(call.ids).containsExactly(a)
                assertThat(call.add).containsExactly(id)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun dismissDuringReloadKeepsSheetAndChanges() =
        runTest {
            val a = repo.add("a")
            val l = repo.createLabel("l")
            repo.setLabels(listOf(a), listOf(l), emptyList())
            repo.setLabelsCalls.clear()
            var hold: CompletableDeferred<Unit>? = null
            val slow =
                object : NoteRepository by repo {
                    override val notes: Flow<List<Note>> =
                        flow {
                            hold?.await()
                            emitAll(repo.notes)
                        }
                }
            val vm = vm(repository = slow)
            vm.state.test {
                vm.toggleSelect(a)
                vm.labelSheet.open()
                vm.labelSheet.toggle(l, LabelCheck.All) // stage removal of l
                expectMostRecentItem()
                cancelAndIgnoreRemainingEvents()
            }
            hold = CompletableDeferred()
            advanceTimeBy(6_000)
            runCurrent()
            vm.state.test {
                runCurrent() // placeholder: targets are empty
                assertThat(vm.state.value.notesLoaded).isFalse()
                assertThat(vm.labelSheet.canClose()).isFalse()
                vm.labelSheet.finish()
                vm.labelSheet.cancel()
                runCurrent()
                assertThat(
                    vm.labelSheet.state.value
                        ?.overrides,
                ).containsExactly(l, LabelCheck.None)
                assertThat(repo.setLabelsCalls).isEmpty()
                hold.complete(Unit)
                runCurrent()
                assertThat(vm.state.value.notesLoaded).isTrue()
                assertThat(vm.labelSheet.canClose()).isTrue()
                vm.labelSheet.finish()
                runCurrent()
                val call = repo.setLabelsCalls.single()
                assertThat(call.ids).containsExactly(a)
                assertThat(call.add).isEmpty()
                assertThat(call.remove).containsExactly(l)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun cancelDiscardsStagedChanges() =
        runTest {
            val a = repo.add("a")
            val l = repo.createLabel("l")
            val vm = vm()
            vm.state.test {
                vm.toggleSelect(a)
                vm.labelSheet.open()
                vm.labelSheet.toggle(l, LabelCheck.None)
                vm.labelSheet.cancel()
                val s = expectMostRecentItem()
                assertThat(s.sheet).isNull()
                assertThat(s.selection).containsExactly(a)
                assertThat(repo.setLabelsCalls).isEmpty()
            }
        }

    @Test
    fun canCloseFalseImmediatelyAfterCreateStarts() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val a = repo.add("a")
            val l = repo.createLabel("l")
            val gate = CompletableDeferred<Unit>()
            val vm = vm(repository = failingCreate(gate, fail = true))
            vm.state.test {
                runCurrent()
                vm.toggleSelect(a)
                vm.labelSheet.open()
                vm.labelSheet.toggle(l, LabelCheck.None) // another staged change
                vm.labelSheet.setQuery("new")
                runCurrent()
                assertThat(vm.labelSheet.canClose()).isTrue()
                vm.labelSheet.create("new")
                assertThat(
                    vm.state.value.sheet
                        ?.creating,
                ).isFalse()
                assertThat(vm.labelSheet.canClose()).isFalse()
                vm.labelSheet.finish() // the dispatcher has not run the create yet
                vm.labelSheet.cancel()
                gate.complete(Unit)
                runCurrent()
                val s = expectMostRecentItem()
                assertThat(s.sheet).isNotNull()
                assertThat(s.sheet!!.createFailed).isTrue()
                assertThat(s.sheet.query).isEqualTo("new")
                assertThat(s.sheet.overrides).containsExactly(l, LabelCheck.All)
                assertThat(s.selection).containsExactly(a)
                assertThat(repo.setLabelsCalls).isEmpty()
                assertThat(vm.labelSheet.canClose()).isTrue()
                vm.labelSheet.finish()
                val call = repo.setLabelsCalls.single()
                assertThat(call.ids).containsExactly(a)
                assertThat(call.add).containsExactly(l)
                assertThat(call.remove).isEmpty()
            }
        }

    @Test
    fun placeholderEmptyListDoesNotClearSelection() =
        runTest {
            val a = repo.add("a")
            var hold: CompletableDeferred<Unit>? = null
            val slow =
                object : NoteRepository by repo {
                    override val notes: Flow<List<Note>> =
                        flow {
                            hold?.await() // Firestore's snapshot arrives late after a resubscribe
                            emitAll(repo.notes)
                        }
                }
            val vm = vm(repository = slow)
            vm.state.test {
                vm.toggleSelect(a)
                vm.labelSheet.open()
                assertThat(expectMostRecentItem().selection).containsExactly(a)
                cancelAndIgnoreRemainingEvents()
            }
            hold = CompletableDeferred()
            advanceTimeBy(6_000) // subscription timeout: upstream stops
            runCurrent()
            vm.state.test {
                runCurrent() // placeholder emission, real snapshot still pending
                hold.complete(Unit)
                runCurrent()
                cancelAndIgnoreRemainingEvents()
            }
            assertThat(vm.state.value.selection).containsExactly(a)
            assertThat(vm.labelSheet.state.value).isNotNull()
        }
}

// Compose's IME edit API is internal. Use it here to exercise real composition in the JVM test.
private fun TextFieldState.appendComposingText(text: String) {
    val edit = javaClass.methods.single { it.name.startsWith("editAsUser\$") && it.parameterCount == 4 }
    val undoBehavior =
        checkNotNull(edit.parameterTypes[2].enumConstants).single {
            (it as Enum<*>).name ==
                "MergeIfPossible"
        }
    val apply: (TextFieldBuffer) -> Unit = { buffer ->
        val start = buffer.length
        buffer.append(text)
        val compose =
            buffer.javaClass.methods.single {
                it.name.startsWith(
                    "setComposition\$",
                ) && it.parameterCount == 3
            }
        compose.invoke(buffer, start, buffer.length, emptyList<Any>())
    }
    edit.invoke(this, null, true, undoBehavior, apply)
}
