package io.github.caffelatte23.futaba.feature.search

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

private val termSeparator = Regex("""[\s\p{Z}\u0085]+""")

fun queryTerms(query: String): List<String> = query.split(termSeparator).filter(String::isNotEmpty)

/** [text] with every case-insensitive occurrence of each term in [query] styled with [style]. */
fun highlight(
    text: String,
    query: String,
    style: SpanStyle,
): AnnotatedString {
    val marked = BooleanArray(text.length)
    for (term in queryTerms(query)) {
        var from = text.indexOf(term, ignoreCase = true)
        while (from >= 0) {
            marked.fill(true, from, from + term.length)
            from = text.indexOf(term, from + 1, ignoreCase = true)
        }
    }
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            var j = i
            while (j < text.length && marked[j] == marked[i]) j++
            if (marked[i]) withStyle(style) { append(text.substring(i, j)) } else append(text.substring(i, j))
            i = j
        }
    }
}
