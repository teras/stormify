// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify.gradle

import groovy.json.JsonSlurper

/**
 * Plugin-side mirror of the JSON contract emitted by the `annproc`
 * processor. Parsed via Gradle's bundled `groovy.json.JsonSlurper` so
 * we add zero new runtime dependencies.
 */
internal data class EntityMeta(
    val qualifiedName: String,
    val simpleName: String,
    val tableName: String,
    val typeParameterCount: Int,
    val sourceSet: String,
    val sourceFile: String,
    val properties: List<PropertyMeta>,
    val enumTypes: List<String>,
)

internal data class PropertyMeta(
    val name: String,
    val dbName: String,
    val type: String,
    val fullType: String,
    val nullable: Boolean,
    val primary: Boolean,
    val sequence: String,
    val autoIncrement: Boolean,
    val insertable: Boolean,
    val updatable: Boolean,
    val isReference: Boolean,
    val isEnum: Boolean,
    val enumAsString: Boolean,
)

private fun Map<String, Any?>.str(key: String, default: String = "") = this[key] as? String ?: default
private fun Map<String, Any?>.bool(key: String, default: Boolean = false) = this[key] as? Boolean ?: default
private fun Map<String, Any?>.int(key: String) = (this[key] as? Number)?.toInt() ?: 0
private fun Map<String, Any?>.strList(key: String) =
    (this[key] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()

@Suppress("UNCHECKED_CAST")
internal fun parseEntityJson(text: String): EntityMeta {
    val raw = JsonSlurper().parseText(text) as Map<String, Any?>
    val propsRaw = (raw["properties"] as? List<Map<String, Any?>>) ?: emptyList()
    val props = propsRaw.map { p ->
        PropertyMeta(
            name = p.str("name"),
            dbName = p.str("dbName"),
            type = p.str("type", "kotlin.Any"),
            fullType = p.str("fullType", "kotlin.Any?"),
            nullable = p.bool("nullable"),
            primary = p.bool("primary"),
            sequence = p.str("sequence"),
            autoIncrement = p.bool("autoIncrement"),
            insertable = p.bool("insertable", default = true),
            updatable = p.bool("updatable", default = true),
            isReference = p.bool("isReference"),
            isEnum = p.bool("isEnum"),
            enumAsString = p.bool("enumAsString"),
        )
    }
    return EntityMeta(
        qualifiedName = raw.str("qualifiedName"),
        simpleName = raw.str("simpleName"),
        tableName = raw.str("tableName"),
        typeParameterCount = raw.int("typeParameterCount"),
        sourceSet = raw.str("sourceSet"),
        sourceFile = raw.str("sourceFile"),
        properties = props,
        enumTypes = raw.strList("enumTypes"),
    )
}
