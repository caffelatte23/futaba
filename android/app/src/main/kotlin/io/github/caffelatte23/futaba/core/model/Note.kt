package io.github.caffelatte23.futaba.core.model

import java.time.Instant

data class Note(
    val id: String,
    val text: String,
    val labelIds: List<String>,
    val pinned: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
)
