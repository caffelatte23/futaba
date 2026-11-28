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

/** Shares go to the draft until a Google session exists (a v1 anonymous uid does not count). */
internal fun shareGoesToDraft(signedIn: Boolean): Boolean = !signedIn
