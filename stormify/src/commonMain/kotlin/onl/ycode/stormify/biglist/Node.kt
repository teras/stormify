// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.biglist

import onl.ycode.stormify.TableInfo
import onl.ycode.stormify.isScalarClass
import kotlin.reflect.KClass


internal sealed class Node(val type: KClass<*>, internal val invalidate: () -> Unit, private val parent: Node?) {
    abstract val children: Map<String, Node>
    abstract fun findChild(fieldName: String, tableCounter: () -> Int): Node
    abstract fun getForeignKeys(out: StringBuilder)

    var active = false

    // Bottom-up activation
    fun activate() {
        active = true
        parent?.activate()
    }

    // Top-down deactivation
    fun deactivate() {
        active = false
        for (child in children.values)
            child.deactivate()
    }

}

internal class NodeField(
    internal val columnHandler: String,
    type: KClass<*>,
    invalidate: () -> Unit,
    parent: Node?
) : Node(type, invalidate, parent) {
    override val children = emptyMap<String, Node>()
    override fun findChild(fieldName: String, tableCounter: () -> Int) =
        throw UnsupportedOperationException("Field node does not have children")

    override fun getForeignKeys(out: StringBuilder) = Unit
}

internal class NodeTable(
    tableAlias: String,
    private val parentReference: String,
    type: KClass<*>,
    invalidate: () -> Unit,
    parent: Node?
) :
    Node(type, invalidate, parent) {
    override val children = mutableMapOf<String, Node>()
    private val tableInfo = TableInfo.retrieve(type)

    private val tableDefinition = if (tableAlias.isEmpty()) tableInfo.table else "${tableInfo.table} AS $tableAlias"
    private val tableHandler = tableAlias.ifEmpty { tableInfo.table }

    override fun findChild(fieldName: String, tableCounter: () -> Int) = children[fieldName] ?: run {
        val childType = tableInfo.getType(fieldName)
        val node = if (isScalarClass(childType))
            NodeField("$tableHandler.$fieldName", childType, invalidate, this)
        else
            NodeTable("t${tableCounter()}", fieldName, childType, invalidate, this)
        children[fieldName] = node
        return node
    }

    override fun getForeignKeys(out: StringBuilder) {
        for (child in children.values) {
            if (!child.active || child !is NodeTable) continue
            out
                .append(" JOIN ")
                .append(child.tableDefinition)
                .append(" ON ")
                .append(tableHandler)
                .append(".")
                .append(child.tableInfo.table)
                .append(" = ")
                .append(child.tableHandler)
                .append(".")
                .append(child.parentReference)
        }
        for (child in children.values)
            child.getForeignKeys(out)
    }
}
