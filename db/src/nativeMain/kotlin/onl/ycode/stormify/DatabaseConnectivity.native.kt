package onl.ycode.stormify

import kotlin.reflect.KClass

internal actual val DataSource._connection get() = getConnection()

internal actual val Connection._metaData get() = metaData
internal actual fun Connection._setSavepoint(sp: String): Savepoint = setSavepoint(sp)
internal actual fun Connection._releaseSavepoint(savepoint: Savepoint) = releaseSavepoint(savepoint)
internal actual fun Connection._commit() = commit()
internal actual fun Connection._prepareCall(s: String): CallableStatement = prepareCall(s)
internal actual fun Connection._disableAutoCommit() = setAutoCommit(false)
internal actual fun Connection._enableAutoCommit() = setAutoCommit(true)
internal actual fun Connection._rollback(savepoint: Savepoint?) = rollback(savepoint)
internal actual fun Connection._prepareStatement(sql: String, generatedKeys: Boolean) = prepareStatement(sql, generatedKeys)

internal actual val DatabaseMetaData._databaseProductName get() = databaseProductName
internal actual val DatabaseMetaData._databaseProductVersion get() = databaseProductVersion
internal actual val DatabaseMetaData._databaseMajorVersion get() = databaseMajorVersion
internal actual val DatabaseMetaData._databaseMinorVersion get() = databaseMinorVersion

internal actual fun PreparedStatement._executeUpdate() = executeUpdate()
internal actual fun PreparedStatement._executeQuery(): ResultSet = executeQuery()
internal actual fun PreparedStatement._setObject(i: Int, any: Any?) = setObject(i, any)
internal actual fun PreparedStatement._getGeneratedKeys(): ResultSet = getGeneratedKeys()

internal actual fun CallableStatement._execute() = execute()
internal actual fun CallableStatement._getObject(idx: Int, type: KClass<*>): Any? = getObject(idx, type)

internal actual fun ResultSet._next() = next()
internal actual val ResultSet._columnCount get() = getMetaData().columnCount
internal actual fun ResultSet._getColumnName(index: Int) = getMetaData().getColumnName(index)
internal actual fun ResultSet._getObject(index: Int, type: KClass<*>) = getObject(index, type)

internal actual fun CallableStatement._registerOutParameter(i: Int, type: KClass<*>) =
    registerOutParameter(i, type)
