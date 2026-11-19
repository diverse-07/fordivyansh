package com.librevision.domain.alerts

/** One automatic store visit per activity session, including recreation and return from the store. */
class SpeechSetupGate(var storeOpened: Boolean = false) {
    fun shouldOpenStore(engineUnavailable: Boolean): Boolean {
        if (!engineUnavailable || storeOpened) return false
        storeOpened = true
        return true
    }
}
