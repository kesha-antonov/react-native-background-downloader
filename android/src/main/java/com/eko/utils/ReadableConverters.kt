package com.eko.utils

import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.ReadableType
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stateless conversion of React Native's ReadableMap/ReadableArray bridge types into
 * org.json structures. Pure helpers with no module state - extracted from the module
 * so the conversion logic is reusable and unit-testable on its own.
 */
object ReadableConverters {

  /**
   * Convert a ReadableMap to JSONObject, handling nested maps and arrays.
   */
  fun toJsonObject(map: ReadableMap?): JSONObject? {
    if (map == null) return null
    val json = JSONObject()
    val iterator = map.keySetIterator()
    while (iterator.hasNextKey()) {
      val key = iterator.nextKey()
      when (map.getType(key)) {
        ReadableType.Null -> json.put(key, JSONObject.NULL)
        ReadableType.Boolean -> json.put(key, map.getBoolean(key))
        ReadableType.Number -> json.put(key, map.getDouble(key))
        ReadableType.String -> json.put(key, map.getString(key))
        ReadableType.Map -> json.put(key, toJsonObject(map.getMap(key)))
        ReadableType.Array -> json.put(key, toJsonArray(map.getArray(key)))
      }
    }
    return json
  }

  /**
   * Convert a ReadableArray to JSONArray.
   */
  fun toJsonArray(array: ReadableArray?): JSONArray? {
    if (array == null) return null
    val json = JSONArray()
    for (i in 0 until array.size()) {
      when (array.getType(i)) {
        ReadableType.Null -> json.put(JSONObject.NULL)
        ReadableType.Boolean -> json.put(array.getBoolean(i))
        ReadableType.Number -> json.put(array.getDouble(i))
        ReadableType.String -> json.put(array.getString(i))
        ReadableType.Map -> json.put(toJsonObject(array.getMap(i)))
        ReadableType.Array -> json.put(toJsonArray(array.getArray(i)))
      }
    }
    return json
  }
}
