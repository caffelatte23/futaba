package io.github.caffelatte23.futaba.core.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Focus the composer and show the keyboard" requests, as a monotonic token (0 = none). Lives above the
 * replaceable Stream entry: a mounted composer reacts to every newer token, and a ViewModel created by a
 * filter switch finds the token already [serve]d, so nothing repeats.
 */
@Singleton
class ComposerFocusRequests
    @Inject
    constructor() {
        private val token = MutableStateFlow(0L)
        val requests: StateFlow<Long> = token
        private val served = AtomicLong(0)

        fun request() = token.update { it + 1 }

        /** True once per token: whoever gets it applies the focus. */
        fun serve(requested: Long): Boolean = served.getAndAccumulate(requested, ::maxOf) < requested
    }
