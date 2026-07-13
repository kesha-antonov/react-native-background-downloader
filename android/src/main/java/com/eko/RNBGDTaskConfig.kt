package com.eko

import java.io.Serializable

data class RNBGDTaskConfig(
    var id: String,
    var url: String,
    var destination: String,
    var metadata: String = "{}",
    var reportedBegin: Boolean = false,
    // Nullable so configs persisted by older versions (key absent in JSON)
    // load as null and fall back to true instead of Gson's default false.
    var isAllowedOverMetered: Boolean? = null
) : Serializable
