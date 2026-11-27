package io.github.caffelatte23.futaba.feature.detail

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dateTime = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
private val time = DateTimeFormatter.ofPattern("HH:mm")

/** `2026/11/25 12:40 · 12:52 編集`; the edit part only when updated at least a minute after creation. */
fun detailHeader(
    createdAt: Instant,
    updatedAt: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val created = createdAt.atZone(zone).format(dateTime)
    if (Duration.between(createdAt, updatedAt) < Duration.ofMinutes(1)) return created
    return "$created · ${updatedAt.atZone(zone).format(time)} 編集"
}
