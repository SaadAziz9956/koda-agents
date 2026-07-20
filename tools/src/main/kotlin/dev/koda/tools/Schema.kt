package dev.koda.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Minimal JSON Schema builder for tool parameter objects. */
internal fun objectSchema(
    required: List<String> = emptyList(),
    properties: Map<String, Pair<String, String>>, // name -> (type, description)
): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        properties.forEach { (name, spec) ->
            putJsonObject(name) {
                put("type", spec.first)
                put("description", spec.second)
            }
        }
    }
    if (required.isNotEmpty()) {
        putJsonArray("required") { required.forEach { add(it) } }
    }
}
