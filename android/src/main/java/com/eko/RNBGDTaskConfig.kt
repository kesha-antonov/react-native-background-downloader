package com.eko

import java.io.Serializable

data class RNBGDTaskConfig(
    var id: String,
    var url: String,
    var destination: String,
    var metadata: String = "{}",
    var notificationTitle: String?,
    var reportedBegin: Boolean = false
) : Serializable
