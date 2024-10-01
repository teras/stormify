package onl.ycode.stormify

import kotlin.reflect.KClass

internal actual val DataSource._connection: Connection
    get() = TODO("Not yet implemented")
internal actual val Connection._metaData: DatabaseMetaData
    get() = TODO("Not yet implemented")
internal actual val DatabaseMetaData._databaseProductName: String
    get() = TODO("Not yet implemented")
internal actual val DatabaseMetaData._databaseProductVersion: String
    get() = TODO("Not yet implemented")
internal actual val DatabaseMetaData._databaseMajorVersion: Int
    get() = TODO("Not yet implemented")
internal actual val DatabaseMetaData._databaseMinorVersion: Int
    get() = TODO("Not yet implemented")

internal actual fun Connection._prepareStatement(
    sql: String,
    generatedKeys: Boolean
): PreparedStatement {
    TODO("Not yet implemented")
}

internal actual fun Connection._rollback(savepoint: Savepoint?) {
    TODO("Not yet implemented")
}

internal actual fun Connection._setSavepoint(sp: String): Savepoint {
    TODO("Not yet implemented")
}

internal actual fun Connection._releaseSavepoint(savepoint: Savepoint) {
    TODO("Not yet implemented")
}

internal actual fun Connection._commit() {
    TODO("Not yet implemented")
}

internal actual fun PreparedStatement._executeUpdate(): Int {
    TODO("Not yet implemented")
}

internal actual fun PreparedStatement._executeQuery(): ResultSet {
    TODO("Not yet implemented")
}

internal actual fun ResultSet._next(): Boolean {
    TODO("Not yet implemented")
}

internal actual val ResultSet._columnCount: Int
    get() = TODO("Not yet implemented")

internal actual fun ResultSet._getColumnName(index: Int): String {
    TODO("Not yet implemented")
}

internal actual fun ResultSet._getObject(index: Int, type: KClass<*>): Any? {
    TODO("Not yet implemented")
}

internal actual fun PreparedStatement._setObject(i: Int, any: Any?) {
    TODO("Not yet implemented")
}

internal actual fun Connection._disableAutoCommit() {
    TODO("Not yet implemented")
}

internal actual fun Connection._enableAutoCommit() {
    TODO("Not yet implemented")
}

internal actual fun Connection._prepareCall(s: String): CallableStatement {
    TODO("Not yet implemented")
}

internal actual fun CallableStatement._registerOutParameter(i: Int, sqlType: Int) {
    TODO("Not yet implemented")
}

internal actual fun CallableStatement._getObject(idx: Int, type: KClass<*>): Any? {
    TODO("Not yet implemented")
}

internal actual fun CallableStatement._execute(): Boolean {
    TODO("Not yet implemented")
}

internal actual fun PreparedStatement._getGeneratedKeys(): ResultSet {
    TODO("Not yet implemented")
}