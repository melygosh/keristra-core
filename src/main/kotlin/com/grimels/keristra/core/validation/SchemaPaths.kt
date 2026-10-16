package com.grimels.keristra.core.validation

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Dotted property paths (`application.creditScore`) declared by a JSON schema; array item properties hang off the array path. */
object SchemaPaths {

    fun fromJsonSchema(schemaJson: String): Set<String> {
        val paths = linkedSetOf<String>()
        collect(JsonParser.parseString(schemaJson).asJsonObject, prefix = null, paths)
        return paths
    }

    private fun collect(node: JsonObject, prefix: String?, paths: MutableSet<String>) {
        node.getAsJsonObject("properties")?.entrySet()?.forEach { (name, child) ->
            val path = if (prefix == null) name else "$prefix.$name"
            paths += path
            if (child.isJsonObject) collect(child.asJsonObject, path, paths)
        }
        node.get("items")?.takeIf { it.isJsonObject }?.let { collect(it.asJsonObject, prefix, paths) }
    }
}
