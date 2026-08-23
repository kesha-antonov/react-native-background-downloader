package com.eko

import com.facebook.react.bridge.Promise

/**
 * Bridge helpers shared by the old- and new-architecture module wrappers so the
 * try/resolve/reject boilerplate lives in exactly one place instead of being
 * copy-pasted per method across both source sets.
 */

/**
 * Runs [block] and resolves the promise with `null` on success; rejects with
 * [errorCode] (plus the exception message and cause) if it throws. Use for
 * fire-and-forget bridge methods that only need to acknowledge completion.
 */
inline fun Promise.resolveCatching(errorCode: String, isRuntimeActive: () -> Boolean, block: () -> Unit) {
    if (!isRuntimeActive()) return
    try {
        block()
        if (isRuntimeActive()) resolve(null)
    } catch (e: Exception) {
        if (isRuntimeActive()) reject(errorCode, e.message, e)
    }
}

inline fun Promise.resolveValueCatching(errorCode: String, isRuntimeActive: () -> Boolean, block: () -> Any?) {
    if (!isRuntimeActive()) return
    try {
        val value = block()
        if (isRuntimeActive()) resolve(value)
    } catch (e: Exception) {
        if (isRuntimeActive()) reject(errorCode, e.message, e)
    }
}
