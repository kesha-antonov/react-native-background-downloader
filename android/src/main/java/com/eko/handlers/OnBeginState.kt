package com.eko.handlers

import com.facebook.react.bridge.WritableMap
import java.io.Serializable

data class OnBeginState(
    val id: String,
    val headers: WritableMap,
    val expectedBytes: Long
) : Serializable
