package io.github.caffelatte23.futaba.core.domain

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.caffelatte23.futaba.core.model.Label
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.File

class HashtagsTest {
    @Test
    fun sharedCases() {
        val spec = File(System.getProperty("specDir"), "hashtags.json")
        val cases = Json.decodeFromString<List<HashtagCase>>(spec.readText())
        for (case in cases) {
            assertWithMessage(case.text).that(parseHashtags(case.text)).isEqualTo(case.tags)
        }
    }

    @Test
    fun keepsFirstSpelling() {
        assertThat(parseHashtags("#Work ＃work #私用")).containsExactly("Work", "私用").inOrder()
    }

    @Test
    fun resolveMatchesCaseInsensitively() {
        val existing = listOf(Label("l1", "Work", "#ffc83d", 0))

        assertThat(resolveLabels(listOf("work", "新規"), existing))
            .isEqualTo(ResolvedLabels(listOf("l1"), listOf("新規")))
    }

    @Serializable
    private data class HashtagCase(
        val text: String,
        val tags: List<String>,
    )
}
