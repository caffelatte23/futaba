package io.github.caffelatte23.futaba.feature.search

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.github.caffelatte23.futaba.testing.FakeNoteRepository
import io.github.caffelatte23.futaba.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val repo = FakeNoteRepository()

    private fun vm() = SearchViewModel(repo, main.dispatcher)

    @Test
    fun labelChipAndQueryCombine() =
        runTest {
            repo.add("写真を貼れる #アプリ案")
            repo.add("写真だけ")
            repo.add("音声 #アプリ案")
            val vm = vm()
            vm.state.test {
                vm.onQueryChange("写真")
                advanceUntilIdle()
                val label = expectMostRecentItem().chips.single { it.name == "アプリ案" }
                assertThat(label.count).isEqualTo(2)
                vm.toggleLabel(label.id)
                advanceUntilIdle()
                val state = expectMostRecentItem()
                assertThat(state.results.map { it.text }).containsExactly("写真を貼れる #アプリ案")
                assertThat(state.selectedLabelId).isEqualTo(label.id)
                vm.toggleLabel(label.id)
                advanceUntilIdle()
                assertThat(expectMostRecentItem().results).hasSize(2)
            }
        }

    @Test
    fun emptyQueryShowsLabelChipsOnly() =
        runTest {
            repo.add("a #x")
            repo.add("b #x #y")
            val vm = vm()
            vm.state.test {
                advanceUntilIdle()
                val state = expectMostRecentItem()
                assertThat(state.results).isEmpty()
                assertThat(state.chips.map { it.name to it.count }).containsExactly("x" to 2, "y" to 1).inOrder()
            }
        }

    @Test
    fun resultsAreDebouncedWhileTyping() =
        runTest {
            repo.add("alpha")
            repo.add("alphabet")
            val vm = vm()
            vm.state.test {
                advanceUntilIdle()
                vm.onQueryChange("alpha")
                vm.onQueryChange("alphab")
                advanceTimeBy(100)
                assertThat(expectMostRecentItem().results).isEmpty()
                advanceTimeBy(100)
                assertThat(expectMostRecentItem().results.map { it.text }).containsExactly("alphabet")
            }
        }

    @Test
    fun separatorOnlyQueryIsNotASearch() =
        runTest {
            repo.add("anything")
            val vm = vm()
            vm.state.test {
                vm.onQueryChange("\u0085")
                advanceUntilIdle()
                assertThat(expectMostRecentItem().results).isEmpty()
            }
        }

    private fun ranges(
        text: String,
        query: String,
    ) = highlight(text, query, SpanStyle(background = Color.Yellow)).spanStyles.map { it.start to it.end }

    @Test
    fun highlightMarksAllCaseInsensitiveMatches() {
        val result = highlight("Photo の photo と写真", "PHOTO 写真", SpanStyle(background = Color.Yellow))
        assertThat(result.text).isEqualTo("Photo の photo と写真")
        assertThat(result.spanStyles.map { it.start to it.end }).containsExactly(0 to 5, 8 to 13, 15 to 17)
    }

    @Test
    fun highlightHandlesOverlapFullWidthSpaceAndEmoji() {
        assertThat(ranges("abcd", "abc bcd")).containsExactly(0 to 4)
        assertThat(ranges("写真と音声", "写真　音声")).containsExactly(0 to 2, 3 to 5)
        assertThat(ranges("😀a😀", "a")).containsExactly(2 to 3)
        assertThat(ranges("x😀y", "😀")).containsExactly(1 to 3)
    }
}
