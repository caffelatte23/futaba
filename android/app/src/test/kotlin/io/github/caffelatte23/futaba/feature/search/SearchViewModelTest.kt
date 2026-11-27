package io.github.caffelatte23.futaba.feature.search

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.github.caffelatte23.futaba.testing.FakeNoteRepository
import io.github.caffelatte23.futaba.testing.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class SearchViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val repo = FakeNoteRepository()

    @Test
    fun labelChipAndQueryCombine() =
        runTest {
            repo.add("写真を貼れる #アプリ案")
            repo.add("写真だけ")
            repo.add("音声 #アプリ案")
            val vm = SearchViewModel(repo)
            vm.state.test {
                vm.onQueryChange("写真")
                val label = expectMostRecentItem().chips.single { it.name == "アプリ案" }
                assertThat(label.count).isEqualTo(2)
                vm.toggleLabel(label.id)
                val state = expectMostRecentItem()
                assertThat(state.results.map { it.text }).containsExactly("写真を貼れる #アプリ案")
                assertThat(state.selectedLabelId).isEqualTo(label.id)
                vm.toggleLabel(label.id)
                assertThat(expectMostRecentItem().results).hasSize(2)
            }
        }

    @Test
    fun emptyQueryShowsLabelChipsOnly() =
        runTest {
            repo.add("a #x")
            repo.add("b #x #y")
            val vm = SearchViewModel(repo)
            vm.state.test {
                val state = expectMostRecentItem()
                assertThat(state.results).isEmpty()
                assertThat(state.chips.map { it.name to it.count }).containsExactly("x" to 2, "y" to 1).inOrder()
            }
        }

    @Test
    fun highlightMarksAllCaseInsensitiveMatches() {
        val style = SpanStyle(background = Color.Yellow)
        val result: AnnotatedString = highlight("Photo の photo と写真", "PHOTO 写真", style)
        assertThat(result.text).isEqualTo("Photo の photo と写真")
        assertThat(result.spanStyles.map { it.start to it.end }).containsExactly(0 to 5, 8 to 13, 15 to 17)
    }
}
