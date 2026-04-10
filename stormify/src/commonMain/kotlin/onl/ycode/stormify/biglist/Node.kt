// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.biglist

import onl.ycode.stormify.Stormify
import onl.ycode.stormify.isScalarClass
import kotlin.reflect.KClass


internal sealed class Node(val type: KClass<*>, private val parent: Node?) {
    abstract val children: Map<String, Node>
    abstract fun findChild(fieldName: String, tableCounter: () -> Int): Node
    abstract fun appendJoins(out: StringBuilder)

    var active = false

    fun activate() {
        active = true
        parent?.activate()
    }

    fun deactivate() {
        active = false
        for (child in children.values)
            child.deactivate()
    }
}

internal class NodeField(
    internal val columnHandler: String,
    type: KClass<*>,
    parent: Node?,
    internal val isEnum: Boolean = false
) : Node(type, parent) {
    override val children = emptyMap<String, Node>()
    override fun findChild(fieldName: String, tableCounter: () -> Int) =
        throw UnsupportedOperationException("Field node does not have children")

    override fun appendJoins(out: StringBuilder) = Unit
}

internal class NodeTable(
    tableAlias: String,
    private val parentReference: String,
    type: KClass<*>,
    parent: Node?,
    private val stormify: Stormify
) : Node(type, parent) {
    override val children = mutableMapOf<String, Node>()
    private val tableInfo = stormify.resolveTableInfo(type)

    private val tableDefinition =
        if (tableAlias.isEmpty()) tableInfo.tableName else "${tableInfo.tableName} $tableAlias"
    internal val tableHandler = tableAlias.ifEmpty { tableInfo.tableName }

    override fun findChild(fieldName: String, tableCounter: () -> Int) = children[fieldName] ?: run {
        val field = tableInfo.getField(fieldName)
            ?: throw IllegalArgumentException("Field '$fieldName' not found in ${tableInfo.tableName}")
        val node = if (isScalarClass(field.type) || field.isEnum)
            NodeField("$tableHandler.${field.dbName}", field.type, this, field.isEnum)
        else
            NodeTable("t${tableCounter()}", field.dbName, field.type, this, stormify)
        children[fieldName] = node
        return node
    }

    override fun appendJoins(out: StringBuilder) {
        for (child in children.values) {
            if (!child.active || child !is NodeTable) continue
            out
                .append(" LEFT JOIN ")
                .append(child.tableDefinition)
                .append(" ON ")
                .append(tableHandler)
                .append(".")
                .append(child.parentReference)
                .append(" = ")
                .append(child.tableHandler)
                .append(".")
                .append(child.tableInfo.singleKeyDbName)
        }
        for (child in children.values)
            child.appendJoins(out)
    }
}
