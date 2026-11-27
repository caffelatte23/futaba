package io.github.caffelatte23.futaba.entry

internal fun buildShareText(
    subject: CharSequence?,
    text: CharSequence?,
): String {
    val title = subject?.toString()?.trim().orEmpty()
    val body = text?.toString()?.trim().orEmpty()
    return when {
        title.isEmpty() || title in body -> body
        body.isEmpty() -> title
        else -> "$title\n$body"
    }
}
