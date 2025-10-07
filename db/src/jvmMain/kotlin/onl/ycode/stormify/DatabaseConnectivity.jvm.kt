package onl.ycode.stormify

import java.sql.Types
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

internal actual fun Connection._prepareStatement(sql: String, generatedKeys: Boolean): PreparedStatement =
    if (generatedKeys) prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)
    else prepareStatement(sql)

internal actual val DatabaseMetaData._databaseProductName get() = databaseProductName
internal actual val DatabaseMetaData._databaseProductVersion get() = databaseProductVersion
internal actual val DatabaseMetaData._databaseMajorVersion get() = databaseMajorVersion
internal actual val DatabaseMetaData._databaseMinorVersion get() = databaseMinorVersion

internal actual fun PreparedStatement._executeUpdate() = executeUpdate()
internal actual fun PreparedStatement._executeQuery(): ResultSet = executeQuery()
internal actual fun PreparedStatement._setObject(i: Int, any: Any?) = setObject(i, any)
internal actual fun PreparedStatement._getGeneratedKeys(): ResultSet = generatedKeys

internal actual fun CallableStatement._execute() = execute()
internal actual fun CallableStatement._getObject(idx: Int, type: KClass<*>): Any? = getObject(idx, type.java)

internal actual fun ResultSet._next() = next()
internal actual val ResultSet._columnCount get() = metaData.columnCount
internal actual fun ResultSet._getColumnName(index: Int) = metaData.getColumnName(index)
internal actual fun ResultSet._getObject(index: Int, type: KClass<*>) = getObject(index, type.java)

internal actual fun CallableStatement._registerOutParameter(i: Int, type: KClass<*>) {
    val sqlType = when (type) {
        Byte::class -> Types.TINYINT
        Short::class -> Types.SMALLINT
        Int::class -> Types.INTEGER
        Long::class -> Types.BIGINT
        Float::class -> Types.FLOAT
        Double::class -> Types.DOUBLE
        String::class, StringBuilder::class -> Types.VARCHAR
        Char::class -> Types.CHAR
        Boolean::class -> Types.BOOLEAN
        ByteArray::class -> Types.BLOB
        CharArray::class -> Types.CLOB
        Number::class, java.math.BigInteger::class -> Types.NUMERIC
        java.math.BigDecimal::class -> Types.DECIMAL
        java.sql.Date::class, java.time.LocalDate::class -> Types.DATE
        java.sql.Time::class, java.time.LocalTime::class -> Types.TIME
        java.sql.Timestamp::class, java.util.Date::class, java.time.LocalDateTime::class -> Types.TIMESTAMP
        else -> when (type.qualifiedName) {
            "com.ionspin.kotlin.bignum.decimal.BigDecimal" -> Types.DECIMAL
            "com.ionspin.kotlin.bignum.integer.BigInteger" -> Types.NUMERIC
            "kotlinx.datetime.LocalDate" -> Types.DATE
            "kotlinx.datetime.LocalTime" -> Types.TIME
            "kotlinx.datetime.LocalDateTime" -> Types.TIMESTAMP
            else -> Types.OTHER
        }
    }
    registerOutParameter(i, sqlType)
}
