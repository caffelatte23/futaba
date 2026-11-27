package io.github.caffelatte23.futaba.feature.stream

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.github.caffelatte23.futaba.core.data.UserPreferencesRepository
import io.github.caffelatte23.futaba.core.domain.labelUsage
import io.github.caffelatte23.futaba.core.domain.suggestLabels
import io.github.caffelatte23.futaba.core.model.NoteFilter
import io.github.caffelatte23.futaba.testing.FakeNoteRepository
import io.github.caffelatte23.futaba.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
    private val prefs =
        UserPreferencesRepository(
            object : DataStore<Preferences> {
                private val state = MutableStateFlow(emptyPreferences())
                override val data: Flow<Preferences> = state

                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                    transform(state.value).also { state.value = it }
            },
        )

    private fun vm(filter: NoteFilter = NoteFilter.All) = StreamViewModel(repo, prefs, uid, filter)

    @Test
    fun sendIgnoresBlankText() =
        runTest {
            val vm = vm()
            vm.state.test {
                vm.onDraftChange("  \n ")
                assertThat(expectMostRecentItem().canSend).isFalse()
                vm.send()
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
                vm.send()
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
                vm.send()
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
                vm.send()
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
    fun restoredDraftReturnsSavedDraft() =
        runTest {
            prefs.setDraft("saved")
            assertThat(vm().restoredDraft()).isEqualTo("saved")
        }
}
