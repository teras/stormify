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

// --- Data types ---

@DbTable(name = "all_types")
data class AllTypesEntity(
    @DbField(primaryKey = true) var id: Int = 0,
    var byteVal: Byte = 0,
    var shortVal: Short = 0,
    var intVal: Int = 0,
    var longVal: Long = 0,
    var floatVal: Float = 0f,
    var doubleVal: Double = 0.0,
    var boolVal: Boolean = false,
    var stringVal: String? = null
)

@DbTable(name = "nullable_test")
data class NullableEntity(
    @DbField(primaryKey = true) var id: Int = 0,
    var name: String? = null,
    var nullableInt: Int? = null,
    var nullableString: String? = null
)

@DbTable(name = "blob_test")
data class BlobEntity(
    @DbField(primaryKey = true) var id: Int = 0,
    var blobData: ByteArray? = null,
    var clobAsChars: CharArray? = null,
    var clobAsString: String? = null
)

// --- Annotations ---

@DbTable(name = "annotated_test")
data class AnnotatedEntity(
    @DbField(primaryKey = true) var id: Int = 0,
    @DbField(name = "custom_col") var renamedField: String? = null,
    @DbField(creatable = false) var readOnlyOnCreate: String? = null,
    @DbField(updatable = false) var readOnlyOnUpdate: String? = null
)

@DbTable(name = "blacklist_test")
data class BlacklistEntity(
    @DbField(primaryKey = true) var id: Int = 0,
    var name: String? = null,
    var secret: String? = null
)

// --- Naming policy ---

data class CamelEntity(
    @DbField(primaryKey = true) var id: Int = 0,
    var firstName: String? = null,
    var lastName: String? = null
)

data class CamelPolicyEntity(
    @DbField(primaryKey = true) var id: Int = 0,
    var firstName: String? = null,
    var lastName: String? = null
)

data class UpperPolicyEntity(
    @DbField(primaryKey = true) var id: Int = 0,
    var firstName: String? = null,
    var lastName: String? = null
)

// --- Inheritance ---

open class BaseEntity(
    @DbField(primaryKey = true) var id: Int = 0,
    var createdBy: String? = null
)

@DbTable(name = "user_entity")
class UserEntity(
    var name: String? = null,
    var email: String? = null
) : BaseEntity() {
    override fun toString() = "UserEntity(id=$id, name=$name, email=$email, createdBy=$createdBy)"
}

// --- Generics ---

@DbTable(name = "generic_test")
data class GenericHolder<T>(
    @DbField(primaryKey = true) var id: Int = 0,
    var value: T? = null
)

// --- Enums ---

enum class PlainStatus { ACTIVE, INACTIVE, BANNED }

enum class CustomStatus(override val dbValue: Int) : DbValue {
    ACTIVE(10),
    INACTIVE(20),
    BANNED(99)
}

@DbTable(name = "enum_test")
data class EnumEntity(
    @DbField(primaryKey = true) var id: Int = 0,
    var plainStatus: PlainStatus? = null,
    var customStatus: CustomStatus? = null
)

@DbTable(name = "enum_notnull_test")
data class EnumNotNullEntity(
    @DbField(primaryKey = true) var id: Int = 0,
    var status: PlainStatus = PlainStatus.ACTIVE
)

@DbTable(name = "enum_string_test")
data class EnumStringEntity(
    @DbField(primaryKey = true) var id: Int = 0,
    @DbField(enumAsString = true) var status: PlainStatus? = null,
    var priority: PlainStatus? = null  // ordinal for comparison
)
