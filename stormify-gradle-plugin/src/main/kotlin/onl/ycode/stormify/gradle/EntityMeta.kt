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

@Suppress("UNCHECKED_CAST")
internal fun parseEntityJson(text: String): EntityMeta {
    val raw = JsonSlurper().parseText(text) as Map<String, Any?>
    fun str(key: String) = raw[key] as? String ?: ""
    fun bool(key: String) = raw[key] as? Boolean ?: false
    fun int(key: String) = (raw[key] as? Number)?.toInt() ?: 0
    fun strList(key: String) = (raw[key] as? List<Any?>)?.mapNotNull { it as? String } ?: emptyList()

    val propsRaw = (raw["properties"] as? List<Map<String, Any?>>) ?: emptyList()
    val props = propsRaw.map { p ->
        PropertyMeta(
            name = p["name"] as? String ?: "",
            dbName = p["dbName"] as? String ?: "",
            type = p["type"] as? String ?: "kotlin.Any",
            fullType = p["fullType"] as? String ?: "kotlin.Any?",
            nullable = p["nullable"] as? Boolean ?: false,
            primary = p["primary"] as? Boolean ?: false,
            sequence = p["sequence"] as? String ?: "",
            autoIncrement = p["autoIncrement"] as? Boolean ?: false,
            insertable = p["insertable"] as? Boolean ?: true,
            updatable = p["updatable"] as? Boolean ?: true,
            isReference = p["isReference"] as? Boolean ?: false,
            isEnum = p["isEnum"] as? Boolean ?: false,
            enumAsString = p["enumAsString"] as? Boolean ?: false,
        )
    }
    return EntityMeta(
        qualifiedName = str("qualifiedName"),
        simpleName = str("simpleName"),
        tableName = str("tableName"),
        typeParameterCount = int("typeParameterCount"),
        sourceSet = str("sourceSet"),
        sourceFile = str("sourceFile"),
        properties = props,
        enumTypes = strList("enumTypes"),
    )
}
