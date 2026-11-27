package io.github.caffelatte23.futaba.feature.labels

import com.google.common.truth.Truth.assertThat
import io.github.caffelatte23.futaba.core.data.NoteRepository
import io.github.caffelatte23.futaba.testing.FakeNoteRepository
import io.github.caffelatte23.futaba.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LabelEditViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val repo = FakeNoteRepository()

    @Test
    fun addRejectsBlankAndDuplicateName() =
        runTest {
            repo.createLabel("Work")
            val vm = LabelEditViewModel(repo, main.dispatcher)

            vm.changeNewName("  ")
            vm.addLabel()
            runCurrent()
            assertThat(vm.newLabel.value.error).isEqualTo(LabelEditError.Blank)

            vm.changeNewName(" wORK ")
            vm.addLabel()
            runCurrent()
            assertThat(vm.newLabel.value.error).isEqualTo(LabelEditError.Duplicate)
            assertThat(repo.labels.first().map { it.name }).containsExactly("Work")

            vm.changeNewName(" Personal ")
            vm.addLabel()
            runCurrent()
            assertThat(repo.labels.first().map { it.name }).containsExactly("Work", "Personal")
            assertThat(vm.newLabel.value.text).isEmpty()
        }

    @Test
    fun reorderPersistsOrder() =
        runTest {
            val first = repo.createLabel("First")
            val second = repo.createLabel("Second")
            val third = repo.createLabel("Third")
            val writes = mutableListOf<List<String>>()
            val recording =
                object : NoteRepository by repo {
                    override fun reorderLabels(idsInOrder: List<String>) {
                        writes += idsInOrder
                        repo.reorderLabels(idsInOrder)
                    }
                }
            val vm = LabelEditViewModel(recording, main.dispatcher)

            val expected = listOf(third, first, second)
            vm.reorder(expected)
            assertThat(writes).isEqualTo(listOf(expected))
            assertThat(repo.labels.first().map { it.id }).containsExactlyElementsIn(expected).inOrder()
        }

    @Test
    fun renameRejectsDuplicateIgnoringCase() =
        runTest {
            val work = repo.createLabel("Work")
            val other = repo.createLabel("Other")
            val note = repo.add("Original #Other")
            val vm = LabelEditViewModel(repo, main.dispatcher)

            vm.changeName(other, " wORK ")
            vm.commitName(other)
            runCurrent()
            assertThat(vm.edits.value[other]?.error).isEqualTo(LabelEditError.Duplicate)
            assertThat(repo.labels.first().associate { it.id to it.name }).containsExactly(work, "Work", other, "Other")

            vm.changeName(other, " OTHER ")
            vm.commitName(other)
            runCurrent()
            assertThat(
                repo.labels
                    .first()
                    .single { it.id == other }
                    .name,
            ).isEqualTo("OTHER")
            assertThat(
                repo.notes
                    .first()
                    .single { it.id == note }
                    .text,
            ).isEqualTo("Original #Other")
        }

    @Test
    fun deleteLabelUndoRestoresAttachments() =
        runTest {
            val label = repo.createLabel("Work")
            val other = repo.createLabel("Other")
            repo.setLabelColor(label, "#FFC83D")
            val original = repo.labels.first().single { it.id == label }
            val active = repo.add("Linked #Work", listOf(other))
            val trashed = repo.add("Also linked", listOf(label))
            val unrelated = repo.add("Unrelated", listOf(other))
            repo.delete(listOf(trashed))
            val vm = LabelEditViewModel(repo, main.dispatcher)

            vm.deleteLabel(label)
            runCurrent()
            assertThat(repo.labels.first().map { it.id }).containsExactly(other)
            assertThat(repo.notes.first().flatMap { it.labelIds }).doesNotContain(label)

            vm.undoDelete(checkNotNull(vm.pendingUndo.value))
            runCurrent()
            assertThat(repo.labels.first()).contains(original)
            val notes = repo.notes.first().associateBy { it.id }
            assertThat(notes.getValue(active).labelIds).containsExactly(label, other)
            assertThat(notes.getValue(active).text).isEqualTo("Linked #Work")
            assertThat(notes.getValue(trashed).labelIds).containsExactly(label)
            assertThat(notes.getValue(trashed).deletedAt).isNotNull()
            assertThat(notes.getValue(unrelated).labelIds).containsExactly(other)
            assertThat(vm.pendingUndo.value).isNull()
        }
}
