# KDBC TODO List

This document tracks missing features and planned improvements for the Kotlin Database Connectivity (KDBC) native driver framework.

## High Priority Features

### 1. Batch Operations
**Effort**: ★★★☆☆

Add batch operation support to `PreparedStatement`:

```kotlin
interface PreparedStatement : Statement {
    // Existing methods...

    // New batch methods
    fun addBatch()
    fun executeBatch(): IntArray
    fun clearBatch()
}
```

**Benefits**:
- Significant performance improvement for bulk inserts/updates
- Reduced network roundtrips
- Essential for ORM bulk operations

**Implementation Notes**:
- Each driver needs native batch support or client-side batching
- PostgreSQL: Use `PQexecPrepared` in a loop with transaction
- MariaDB/MySQL: Use `mysql_stmt_execute` in batch mode
- Oracle: Use array binding with ODPI-C
- SQLite: Use transaction-wrapped multiple executes
- FreeTDS: Use db-lib batch commands or manual batching

---

### 2. Transaction Isolation Levels
**Effort**: ★★☆☆☆

Add transaction isolation control to `Connection`:

```kotlin
enum class TransactionIsolation {
    NONE,
    READ_UNCOMMITTED,
    READ_COMMITTED,
    REPEATABLE_READ,
    SERIALIZABLE
}

interface Connection : AutoCloseable {
    // Existing methods...

    fun setTransactionIsolation(level: TransactionIsolation)
    fun getTransactionIsolation(): TransactionIsolation
}
```

**Implementation Notes**:
- PostgreSQL: `SET TRANSACTION ISOLATION LEVEL`
- MariaDB/MySQL: `SET SESSION TRANSACTION ISOLATION LEVEL`
- Oracle: `SET TRANSACTION ISOLATION LEVEL`
- SQLite: PRAGMA read_uncommitted, etc.
- FreeTDS/SQL Server: `SET TRANSACTION ISOLATION LEVEL`

---

### 3. Connection State & Health Checks
**Effort**: ★★☆☆☆

Add connection state methods:

```kotlin
interface Connection : AutoCloseable {
    // Existing methods...

    fun isClosed(): Boolean
    fun isValid(timeout: Duration): Boolean
    fun setReadOnly(readOnly: Boolean)
    fun isReadOnly(): Boolean
}
```

**Implementation Notes**:
- Track closed state in each connection implementation
- `isValid()` should execute a simple query (e.g., SELECT 1)
- `setReadOnly()` varies by database (hints vs. enforcement)

---

### 4. Advanced DatabaseMetaData
**Effort**: ★★★★★

Expand `DatabaseMetaData` interface:

```kotlin
interface DatabaseMetaData {
    // Existing properties...

    // Schema information
    fun getTables(catalog: String? = null, schema: String? = null,
                  tableNamePattern: String? = null, types: List<String>? = null): ResultSet
    fun getColumns(catalog: String? = null, schema: String? = null,
                   tableNamePattern: String? = null, columnNamePattern: String? = null): ResultSet
    fun getPrimaryKeys(catalog: String? = null, schema: String? = null,
                       table: String): ResultSet
    fun getForeignKeys(catalog: String? = null, schema: String? = null,
                       table: String): ResultSet
    fun getIndexInfo(catalog: String? = null, schema: String? = null,
                     table: String, unique: Boolean = false): ResultSet

    // Catalog/Schema information
    fun getSchemas(): ResultSet
    fun getCatalogs(): ResultSet

    // Type information
    fun getTypeInfo(): ResultSet

    // Feature detection
    fun supportsTransactions(): Boolean
    fun supportsSavepoints(): Boolean
    fun supportsBatchUpdates(): Boolean
    fun supportsTransactionIsolationLevel(level: TransactionIsolation): Boolean
}
```

**Benefits**:
- Essential for Stormify ORM to discover table structure
- Enables schema migration tools
- Allows runtime feature detection

**Implementation Notes**:
- Use database-specific system catalogs/views:
  - PostgreSQL: `information_schema` and `pg_catalog`
  - MariaDB/MySQL: `information_schema`
  - Oracle: `USER_TABLES`, `ALL_TABLES`, `DBA_TABLES`, etc.
  - SQLite: `sqlite_master`, `PRAGMA table_info()`
  - FreeTDS/SQL Server: `INFORMATION_SCHEMA` and system views

---

## Medium Priority Features

### 5. SQL Warnings
**Effort**: ★★★☆☆

Add warning support:

```kotlin
data class SQLWarning(
    val message: String,
    val sqlState: String? = null,
    val vendorCode: Int = 0
)

interface Connection : AutoCloseable {
    fun getWarnings(): List<SQLWarning>
    fun clearWarnings()
}

interface Statement : AutoCloseable {
    fun getWarnings(): List<SQLWarning>
    fun clearWarnings()
}
```

**Implementation Notes**:
- PostgreSQL: `PQresultErrorField` with severity
- MariaDB/MySQL: `mysql_warning_count`, `SHOW WARNINGS`
- Oracle: ODPI-C warning retrieval
- SQLite: Limited warning support
- FreeTDS: db-lib message callbacks

---

### 6. LOB Streaming APIs
**Effort**: ★★★★☆

Add dedicated LOB interfaces:

```kotlin
interface Blob : AutoCloseable {
    fun length(): Long
    fun getBytes(pos: Long, length: Int): ByteArray
    fun getBinaryStream(): InputStream
    fun setBinaryStream(pos: Long): OutputStream
    fun truncate(len: Long)
}

interface Clob : AutoCloseable {
    fun length(): Long
    fun getSubString(pos: Long, length: Int): String
    fun getCharacterStream(): Reader
    fun setCharacterStream(pos: Long): Writer
    fun truncate(len: Long)
}

interface ResultSet : AutoCloseable {
    // Existing methods...

    fun getBlob(columnIndex: Int): Blob?
    fun getClob(columnIndex: Int): Clob?
}

interface PreparedStatement : Statement {
    // Existing methods...

    fun setBlob(parameterIndex: Int, blob: Blob?)
    fun setClob(parameterIndex: Int, clob: Clob?)
}
```

**Benefits**:
- Efficient handling of large binary/text data
- Streaming reduces memory usage
- Essential for file storage in databases

**Note**: Kotlin/Native doesn't have `InputStream`/`OutputStream`/`Reader`/`Writer`. Consider creating native equivalents or using byte/char sequences.

---

### 7. Enhanced CallableStatement Support
**Effort**: ★★★☆☆

Improvements needed:

- **Named parameters**: `setObject("paramName", value)`
- **Better output parameter support**: Multiple OUT parameters, INOUT parameters
- **Result set returns**: Stored procedures returning cursors
- **FreeTDS**: Improve basic implementation (see kdbc-freetds/README.md:50)

**Driver-Specific Notes**:
- SQLite: Intentionally unsupported (no stored procedures)
- Oracle: Enhance with REF CURSOR support
- PostgreSQL: Add support for returning TABLE
- MariaDB/MySQL: Test with complex stored procedures
- FreeTDS: Full stored procedure parameter mapping

---

## Low Priority Features

### 8. Scrollable & Updatable ResultSets
**Effort**: ★★★★★

Add ResultSet types and concurrency:

```kotlin
enum class ResultSetType {
    FORWARD_ONLY,
    SCROLL_INSENSITIVE,
    SCROLL_SENSITIVE
}

enum class ResultSetConcurrency {
    READ_ONLY,
    UPDATABLE
}

interface Connection : AutoCloseable {
    fun prepareStatement(
        sql: String,
        returnGeneratedKeys: Boolean = false,
        resultSetType: ResultSetType = ResultSetType.FORWARD_ONLY,
        resultSetConcurrency: ResultSetConcurrency = ResultSetConcurrency.READ_ONLY
    ): PreparedStatement
}

interface ResultSet : AutoCloseable {
    // Navigation
    fun first(): Boolean
    fun last(): Boolean
    fun previous(): Boolean
    fun absolute(row: Int): Boolean
    fun relative(rows: Int): Boolean
    fun getRow(): Int

    // Updates
    fun updateObject(columnIndex: Int, value: Any?)
    fun updateRow()
    fun insertRow()
    fun deleteRow()
    fun refreshRow()
    fun cancelRowUpdates()
}
```

**Note**: Complex feature, not essential for ORM use cases.

---

### 9. SQL Array Support
**Effort**: ★★★☆☆

Add array type support:

```kotlin
interface Array : AutoCloseable {
    fun getBaseType(): Int
    fun getBaseTypeName(): String
    fun getArray(): kotlin.Array<Any?>
}

interface ResultSet : AutoCloseable {
    fun getArray(columnIndex: Int): Array?
}

interface PreparedStatement : Statement {
    fun setArray(parameterIndex: Int, array: Array?)
}
```

**Databases Supporting Arrays**:
- PostgreSQL: Native array support
- Oracle: VARRAY and nested tables
- MariaDB/MySQL: Limited/no support
- SQLite: No support
- FreeTDS/SQL Server: Limited support via table-valued parameters

---

### 10. Custom Type Mapping
**Effort**: ★★★★☆

```kotlin
interface Connection : AutoCloseable {
    fun getTypeMap(): Map<String, KClass<*>>
    fun setTypeMap(map: Map<String, KClass<*>>)
}
```

**Note**: Kotlin/Native reflection limitations may complicate this feature.

---

## Driver-Specific TODOs

### kdbc-sqlite
- [ ] Improve error messages
- [ ] Add PRAGMA support for configuration
- [ ] SQLite-specific optimizations (prepared statement caching)

### kdbc-postgres
- [ ] Test with PostgreSQL-specific types (JSON, JSONB, arrays, hstore)
- [ ] Add COPY command support
- [ ] Test with UUID type
- [ ] Add support for LISTEN/NOTIFY

### kdbc-mariadb
- [ ] Test with MySQL 8.x specific features
- [ ] Verify SSL/TLS configuration works correctly
- [ ] Test with large result sets (streaming)

### kdbc-oracle
- [ ] Test with Oracle-specific types (TIMESTAMP WITH TIME ZONE, INTERVAL, etc.)
- [ ] Add REF CURSOR support for CallableStatement
- [ ] Test with Oracle Wallet for SSL
- [ ] Verify NUMBER encoding/decoding edge cases

### kdbc-freetds
- [ ] Improve CallableStatement implementation (currently basic)
- [ ] Test with Azure SQL Database
- [ ] Add better error handling and diagnostics
- [ ] Test with complex data types
- [ ] Verify Windows authentication support

---

## Testing & Quality

### General Testing
- [ ] Create comprehensive integration test suite for each driver
- [ ] Add performance benchmarks
- [ ] Test connection pooling under load
- [ ] Test concurrent access patterns
- [ ] Add fuzzing tests for SQL injection prevention

### Driver-Specific Tests
- [ ] PostgreSQL: Test against versions 12, 13, 14, 15, 16
- [ ] MariaDB: Test against MariaDB 10.x and MySQL 8.x
- [ ] Oracle: Test against 12c, 18c, 19c, 21c
- [ ] SQLite: Test with different journal modes
- [ ] FreeTDS: Test against SQL Server 2017, 2019, 2022

---

## Documentation

- [ ] Create comprehensive API documentation (KDoc)
- [ ] Add examples for each driver
- [ ] Document connection string formats
- [ ] Create migration guide from JDBC
- [ ] Add troubleshooting guide
- [ ] Document SSL/TLS configuration for each driver
- [ ] Create performance tuning guide

---

## Build & Distribution

- [ ] Set up CI/CD pipeline
- [ ] Publish to Maven Central
- [ ] Add version compatibility matrix
- [ ] Create changelog
- [ ] Add example projects

---

## Future Considerations

### Reactive/Async Support
Consider adding coroutine-based async APIs:
```kotlin
suspend fun Connection.prepareStatementAsync(sql: String): PreparedStatement
suspend fun PreparedStatement.executeQueryAsync(): ResultSet
```

### Connection Pool Enhancements
- Add JMX/monitoring support
- Add connection leak detection
- Add slow query logging
- Add prepared statement cache

### Database-Specific Extensions
Create extension modules for database-specific features:
- `kdbc-postgres-extensions`: PostGIS, full-text search, etc.
- `kdbc-oracle-extensions`: Advanced queuing, spatial, etc.

---

## Notes

- Features marked with ❌ in the analysis are completely missing
- Features marked with ⚠️ have partial implementation
- Prioritization based on Stormify ORM requirements
- Some JDBC features may not be applicable to Kotlin/Native architecture
- Consider memory management carefully in native implementations
