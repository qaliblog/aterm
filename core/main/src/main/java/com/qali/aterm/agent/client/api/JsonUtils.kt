package com.qali.aterm.agent.client.api

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON utility functions for converting between JSON and Kotlin types
 */
object JsonUtils {
    
    /**
     * Convert JSONObject to Map
     */
    fun jsonObjectToMap(json: JSONObject): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        json.keys().forEach { key ->
            val value = json.get(key)
            map[key] = when (value) {
                is JSONObject -> jsonObjectToMap(value)
                is JSONArray -> jsonArrayToList(value)
                else -> value
            }
        }
        return map
    }
    
    /**
     * Convert JSONArray to List
     */
    fun jsonArrayToList(json: JSONArray): List<Any> {
        val list = mutableListOf<Any>()
        for (i in 0 until json.length()) {
            val value = json.get(i)
            list.add(
                when (value) {
                    is JSONObject -> jsonObjectToMap(value)
                    is JSONArray -> jsonArrayToList(value)
                    else -> value
                }
            )
        }
        return list
    }
}
