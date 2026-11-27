package io.github.caffelatte23.futaba.feature.trash

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.github.caffelatte23.futaba.testing.FakeNoteRepository
import io.github.caffelatte23.futaba.testing.MainDispatcherRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class TrashViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test
    fun trashRestoreRemovesFromTrash() =
        runTest {
            val repo = FakeNoteRepository()
            val active = repo.add("Active")
            val deleted = repo.add("Deleted")
            repo.delete(listOf(deleted))
            val vm = TrashViewModel(repo, main.dispatcher)

            vm.notes.test {
                val initial = awaitItem()
                val inTrash = if (initial.isEmpty()) awaitItem() else initial
                assertThat(inTrash.map { it.id }).containsExactly(deleted)
                vm.restore(deleted)
                assertThat(awaitItem()).isEmpty()
            }
            assertThat(
                repo.notes
                    .first()
                    .filter { it.deletedAt == null }
                    .map { it.id },
            ).containsExactly(active, deleted)
        }
}
