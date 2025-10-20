package com.eko.handlers

import java.io.Serializable

data class OnProgressState(
    val id: String,
    val bytesDownloaded: Long,
    val bytesTotal: Long
) : Serializable
