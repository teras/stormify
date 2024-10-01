package onl.ycode.stormify

import kotlin.reflect.KClass

internal actual val DataSource._connection get() = connection

internal actual val Connection._metaData get() = metaData
internal actual fun Connection._setSavepoint(sp: String): Savepoint = setSavepoint(sp)
internal actual fun Connection._releaseSavepoint(savepoint: Savepoint) = releaseSavepoint(savepoint)
internal actual fun Connection._commit() = commit()
internal actual fun Connection._prepareCall(s: String): CallableStatement = prepareCall(s)

internal actual fun Connection._disableAutoCommit() {
    autoCommit = false
}

internal actual fun Connection._enableAutoCommit() {
    autoCommit = true
}

internal actual fun Connection._rollback(savepoint: Savepoint?) =
    if (savepoint != null) rollback(savepoint) else rollback()

internal actual fun Connection._prepareStatement(sql: String, generatedKeys: Boolean) =
    (if (generatedKeys) prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)
    else prepareStatement(sql)) as PreparedStatement

internal actual val DatabaseMetaData._databaseProductName get() = databaseProductName
internal actual val DatabaseMetaData._databaseProductVersion get() = databaseProductVersion
internal actual val DatabaseMetaData._databaseMajorVersion get() = databaseMajorVersion
internal actual val DatabaseMetaData._databaseMinorVersion get() = databaseMinorVersion

internal actual fun PreparedStatement._executeUpdate() = executeUpdate()
internal actual fun PreparedStatement._executeQuery(): ResultSet = executeQuery()
internal actual fun PreparedStatement._setObject(i: Int, any: Any?) = setObject(i, any)
internal actual fun PreparedStatement._getGeneratedKeys(): ResultSet = generatedKeys

internal actual fun CallableStatement._registerOutParameter(i: Int, sqlType: Int) = registerOutParameter(i, sqlType)
internal actual fun CallableStatement._execute() = execute()
internal actual fun CallableStatement._getObject(idx: Int, type: KClass<*>): Any? = getObject(idx)

internal actual fun ResultSet._next() = next()
internal actual val ResultSet._columnCount get() = metaData.columnCount
internal actual fun ResultSet._getColumnName(index: Int) = metaData.getColumnName(index)
internal actual fun ResultSet._getObject(index: Int, type: KClass<*>) = getObject(index)
