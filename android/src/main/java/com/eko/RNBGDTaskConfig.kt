package com.eko

import java.io.Serializable

data class RNBGDTaskConfig(
    var id: String,
    var url: String,
    var destination: String,
    var metadata: String = "{}",
    var reportedBegin: Boolean = false,
    var compressValue: Float = 0f
) : Serializable
