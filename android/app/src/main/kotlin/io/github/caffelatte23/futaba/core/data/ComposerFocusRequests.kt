package io.github.caffelatte23.futaba.core.data

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot "focus the composer and show the keyboard" request. Lives above the replaceable Stream
 * entry, so switching filters (a new ViewModel) never repeats it; a launcher or a new intent posts another.
 * The value is whether the request overrides the 「起動時にキーボードを開く」 setting; null means none pending.
 */
@Singleton
class ComposerFocusRequests
    @Inject
    constructor() {
        // The first launch is pending, subject to the setting.
        private val pending = AtomicReference<Boolean?>(false)

        fun request(forced: Boolean = true) = pending.set(forced)

        /** Consumes the pending request: null if none, else whether it is forced. */
        fun take(): Boolean? = pending.getAndSet(null)
    }
