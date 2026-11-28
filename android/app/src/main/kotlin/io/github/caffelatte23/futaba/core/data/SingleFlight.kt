package io.github.caffelatte23.futaba.core.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Lets one operation run at a time across callers (e.g. recreated ViewModels). */
class SingleFlight {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active

    /** Runs [block], or returns [whenBusy] without running it if another run is in progress. */
    suspend fun <T> run(
        whenBusy: T,
        block: suspend () -> T,
    ): T {
        if (!_active.compareAndSet(expect = false, update = true)) return whenBusy
        try {
            return block()
        } finally {
            _active.value = false
        }
    }
}
