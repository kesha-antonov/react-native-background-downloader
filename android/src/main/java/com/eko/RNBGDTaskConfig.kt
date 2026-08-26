package com.eko

data class RNBGDTaskConfig(
  val id: String,
  val url: String,
  val destination: String,
  val metadata: String = "{}",
  val headers: Map<String, String> = emptyMap(),
  val expectedSha256: String? = null,
  var state: Int = DownloadConstants.TASK_RUNNING,
  var errorCode: Int = 0
)
