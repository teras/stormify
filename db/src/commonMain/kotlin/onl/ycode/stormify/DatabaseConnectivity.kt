package onl.ycode.stormify

internal expect val DataSource._connection: Connection

internal expect val Connection._metaData: DatabaseMetaData
internal expect fun Connection._prepareStatement(sql: String, generatedKeys: Boolean): PreparedStatement
internal expect fun Connection._disableAutoCommit()
internal expect fun Connection._enableAutoCommit()
internal expect fun Connection._setSavepoint(sp: String): Savepoint
internal expect fun Connection._releaseSavepoint(savepoint: Savepoint)
internal expect fun Connection._rollback(savepoint: Savepoint? = null)
internal expect fun Connection._commit()
internal expect fun Connection._prepareCall(s: String): CallableStatement

internal expect val DatabaseMetaData._databaseProductName: String
internal expect val DatabaseMetaData._databaseProductVersion: String
internal expect val DatabaseMetaData._databaseMajorVersion: Int
internal expect val DatabaseMetaData._databaseMinorVersion: Int

internal expect fun PreparedStatement._executeUpdate(): Int
internal expect fun PreparedStatement._executeQuery(): ResultSet
internal expect fun PreparedStatement._setObject(i: Int, any: Any?)
internal expect fun PreparedStatement._getGeneratedKeys(): ResultSet

internal expect fun CallableStatement._getObject(idx: Int): Any?
internal expect fun CallableStatement._registerOutParameter(i: Int, sqlType: Int)
internal expect fun CallableStatement._execute(): Boolean

internal expect fun ResultSet._next(): Boolean
internal expect val ResultSet._columnCount: Int
internal expect fun ResultSet._getColumnName(index: Int): String
internal expect fun ResultSet._getObject(index: Int): Any?
