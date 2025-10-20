package com.eko.handlers

import java.io.Serializable

class OnProgressState(var id: String?, var bytesDownloaded: Long, var bytesTotal: Long) :
  Serializable
