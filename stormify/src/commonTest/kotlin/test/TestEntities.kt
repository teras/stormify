// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import onl.ycode.stormify.*

@DbTable(name = "test")
data class TestC(
    var id: Int = 0,
    var name: String? = null
) {
    override fun toString() = "TestC(id=$id, name=$name)"
}

data class Child(
    var id: Int? = null,
    var name: String? = null,
    var parent: TestC? = null
) {
    override fun toString() = "Child(id=$id, name=$name, parent=$parent)"
}

data class DualKey(
    @DbField(primaryKey = true) var id1: Int = 0,
    @DbField(primaryKey = true) var id2: Int = 0,
    var data: String = ""
) {
    override fun toString() = "DualKey(id1=$id1, id2=$id2, data=$data)"
    override fun equals(other: Any?) = other is DualKey && id1 == other.id1 && id2 == other.id2
    override fun hashCode() = 31 * id1 + id2
}

@DbTable(name = "auto_increment")
data class AutoIncrementEntity(
    var name: String? = null,
    @DbField(primaryKey = true, autoIncrement = true) var id: Int = 0
) {
    override fun toString() = "AutoIncrementEntity(id=$id, name=$name)"
}

@DbTable(name = "fly_test")
data class FlyView(
    var id: Int = 0,
    var name: String? = null,
    var label: String? = null
) {
    override fun toString() = "FlyView(id=$id, name=$name, label=$label)"
}

data class StressTable(
    @DbField(primaryKey = true) var id: Int? = null,
    var data: String? = null
)

@DbTable(name = "auto_parent")
class AutoParentEntity : AutoTable() {
    @DbField(primaryKey = true)
    var id: Int? = null
    var other: String? by db(null)
    var data: String? by db(null)
    var children by lazyDetails<AutoChildEntity>()
}

@DbTable(name = "auto_child")
class AutoChildEntity : AutoTable() {
    @DbField(primaryKey = true)
    var id: Int? = null
    var data: String? by db(null)
    var parent: AutoParentEntity? by db(null)
}

@DbTable(name = "double_db_name")
data class DoubleDbName(
    var id: Int = 0,
    @DbField(name = "name", updatable = false) var name1: String = "",
    @DbField(name = "name", creatable = false) var name2: String = ""
)
