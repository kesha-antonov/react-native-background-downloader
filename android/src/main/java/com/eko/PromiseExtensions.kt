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
inline fun Promise.resolveCatching(errorCode: String, block: () -> Unit) {
    try {
        block()
        resolve(null)
    } catch (e: Exception) {
        reject(errorCode, e.message, e)
    }
}

/**
 * Runs [block], which is responsible for resolving the promise itself (typically by
 * handing it to the impl). Rejects with [errorCode] only if [block] throws before it
 * gets a chance to resolve. Use for methods that return data through the promise.
 */
inline fun Promise.rejectOnThrow(errorCode: String, block: () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        reject(errorCode, e.message, e)
    }
}
