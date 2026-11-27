package io.github.caffelatte23.futaba.entry

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShareTextTest {
    @Test
    fun combinesSubjectAndText() {
        assertThat(buildShareText("Page title", "https://example.com"))
            .isEqualTo("Page title\nhttps://example.com")
    }

    @Test
    fun omitsSubjectAlreadyInText() {
        assertThat(buildShareText(" Page title ", "Read Page title here")).isEqualTo("Read Page title here")
    }

    @Test
    fun ignoresEmptyShares() {
        assertThat(buildShareText(null, null)).isEmpty()
        assertThat(buildShareText("  ", "\n")).isEmpty()
    }

    @Test
    fun trimsFieldsAndOmitsBlankLinesBetweenMissingFields() {
        assertThat(buildShareText("  Title ", "\n Body\nSecond line  ")).isEqualTo("Title\nBody\nSecond line")
        assertThat(buildShareText("  Title ", null)).isEqualTo("Title")
        assertThat(buildShareText("  ", "  Body ")).isEqualTo("Body")
    }
}
