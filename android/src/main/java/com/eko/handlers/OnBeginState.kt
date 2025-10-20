package com.eko.handlers

import com.facebook.react.bridge.WritableMap
import java.io.Serializable


class OnBeginState(var id: String?, var headers: WritableMap?, var expectedBytes: Long) :
  Serializable
