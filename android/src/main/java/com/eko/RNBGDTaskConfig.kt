package com.eko

import java.io.Serializable


class RNBGDTaskConfig(
  var id: String?,
  var url: String?,
  var destination: String?,
  metadata: String?,
  notificationTitle: String?
) : Serializable {
  var metadata: String? = "{}"
  var notificationTitle: String?
  var reportedBegin: Boolean

  init {
    this.metadata = metadata
    this.notificationTitle = notificationTitle
    this.reportedBegin = false
  }
}
