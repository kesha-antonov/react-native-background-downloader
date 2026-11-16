package com.kesha_antonov.interfaces

import com.facebook.react.bridge.WritableMap

fun interface BeginCallback {
    fun onBegin(configId: String, headers: WritableMap, expectedBytes: Long)
}
