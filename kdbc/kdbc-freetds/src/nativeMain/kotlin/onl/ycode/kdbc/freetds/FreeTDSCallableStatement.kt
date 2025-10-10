package onl.ycode.kdbc.freetds

import kotlinx.cinterop.*
import onl.ycode.kdbc.*
import freetds.*
import kotlin.reflect.KClass

/**
 * FreeTDS CallableStatement implementation for MS SQL Server stored procedures.
 *
 * Supports:
 * - Input parameters via setObject()
 * - Output parameters via registerOutParameter() and getObject()
 * - Return status via RETURN statement
 *
 * Uses FreeTDS RPC API (dbrpcinit, dbrpcparam, dbrpcsend).
 */
@OptIn(ExperimentalForeignApi::class)
class FreeTDSCallableStatement(
    private val dbContext: CPointer<DBPROCESS>,
    private val sql: String
) : CallableStatement {
    private data class Parameter(
        var value: Any? = null,
        var isOut: Boolean = false,
        var outType: KClass<*>? = null,
        var outValue: Any? = null
    )

    private val parameters = mutableMapOf<Int, Parameter>()
    private val procedureName: String
    private var hasExecuted = false
    private var returnStatus: Int? = null

    init {
        // Extract procedure name from SQL
        // Supports: "CALL proc_name", "EXEC proc_name", "{CALL proc_name}", or just "proc_name"
        procedureName = extractProcedureName(sql)
    }

    override fun setObject(parameterIndex: Int, value: Any?) {
        val param = parameters.getOrPut(parameterIndex) { Parameter() }
        param.value = value
    }

    override fun registerOutParameter(parameterIndex: Int, type: KClass<*>) {
        val param = parameters.getOrPut(parameterIndex) { Parameter() }
        param.isOut = true
        param.outType = type
    }

    override fun execute(): Boolean {
        hasExecuted = false
        returnStatus = null

        // Clear previous OUT values
        parameters.values.forEach { it.outValue = null }

        memScoped {
            // Initialize RPC call
            if (dbrpcinit(dbContext, procedureName.cstr.ptr, 0.toShort()) == FAIL) {
                throw SQLException("Failed to initialize RPC call for procedure: $procedureName")
            }
        }

        // Bind parameters
        parameters.entries.sortedBy { it.key }.forEach { (index, param) ->
            bindParameter(index, param)
        }

        // Execute RPC
        if (dbrpcsend(dbContext) == FAIL) {
            throw SQLException("Failed to execute stored procedure: $procedureName")
        }

        // Process results
        var hasResultSet = false
        while (true) {
            val result = dbresults(dbContext)
            when (result) {
                SUCCEED -> {
                    // Check if this is a result set
                    val numCols = dbnumcols(dbContext)
                    if (numCols > 0) {
                        hasResultSet = true
                        // Skip rows for now (executeQuery() should be used for result sets)
                        while (dbnextrow(dbContext) != NO_MORE_ROWS) {
                            // Consume rows
                        }
                    }
                }
                NO_MORE_RESULTS -> break
                FAIL -> throw SQLException("Error processing stored procedure results")
                else -> break
            }
        }

        // Retrieve return status
        if (dbhasretstat(dbContext).toInt() != 0) {
            returnStatus = dbretstatus(dbContext)
        }

        // Retrieve OUT parameter values
        val numRets = dbnumrets(dbContext)
        if (numRets > 0) {
            for (i in 1..numRets) {
                val paramName = dbretname(dbContext, i)?.toKString()
                val paramType = dbrettype(dbContext, i)
                val dataLen = dbretlen(dbContext, i)
                val data = dbretdata(dbContext, i)

                // Find which parameter index this corresponds to
                // Note: SQL Server returns OUT parameters in order
                val paramIndex = findOutParameterIndex(i)
                if (paramIndex != null && data != null && dataLen > 0) {
                    val param = parameters[paramIndex] ?: throw SQLException("Parameter at index $paramIndex is null")
                    val valueStr = data.reinterpret<ByteVar>().toKString()
                    param.outValue = param.outType?.let { FreeTDSTypeHelper.readValue(valueStr, it) }
                }
            }
        }

        hasExecuted = true
        return hasResultSet
    }

    override fun executeUpdate(): Int {
        execute()
        return 0  // Stored procedures don't return row counts in the same way
    }

    override fun executeQuery(): ResultSet {
        // For procedures that return result sets, use regular SQL execution
        val finalSql = buildCallSql()

        memScoped {
            dbfreebuf(dbContext)

            if (dbcmd(dbContext, finalSql.cstr.ptr) == FAIL) {
                throw SQLException("Failed to set command")
            }

            if (dbsqlexec(dbContext) == FAIL) {
                throw SQLException("Failed to execute query")
            }
        }

        val result = dbresults(dbContext)
        if (result != SUCCEED) {
            throw SQLException("Failed to get query results")
        }

        return FreeTDSResultSet(dbContext)
    }

    override fun getObject(parameterIndex: Int, type: KClass<*>): Any? {
        if (!hasExecuted) {
            throw SQLException("Statement must be executed before retrieving OUT parameters")
        }

        val param = parameters[parameterIndex]
            ?: throw SQLException("Parameter $parameterIndex not registered")

        if (!param.isOut) {
            throw SQLException("Parameter $parameterIndex is not an OUT parameter")
        }

        return param.outValue
    }

    override fun getGeneratedKeys(): ResultSet {
        return EmptyResultSet()
    }

    override fun close() {
        // Nothing to clean up - RPC resources are freed by dbrpcsend
    }

    private fun bindParameter(index: Int, param: Parameter) {
        memScoped {
            val status = if (param.isOut) DBRPCRETURN.toUByte() else 0.toUByte()
            val data = FreeTDSTypeHelper.ParamData()
            FreeTDSTypeHelper.bindParameter(param.value, data)

            when (data.type) {
                onl.ycode.kdbc.ParameterType.NULL -> {
                    dbrpcparam(dbContext, null, status, SYBVARCHAR, -1, 0, null)
                }
                onl.ycode.kdbc.ParameterType.INT -> {
                    val intVar = alloc<IntVar>().apply { value = data.value.safeCast<Int>() }
                    dbrpcparam(dbContext, null, status, SYBINT4, -1, sizeOf<IntVar>().toInt(), intVar.ptr.reinterpret())
                }
                onl.ycode.kdbc.ParameterType.LONG -> {
                    val longVar = alloc<LongVar>().apply { value = data.value.safeCast<Long>() }
                    dbrpcparam(dbContext, null, status, SYBINT8, -1, sizeOf<LongVar>().toInt(), longVar.ptr.reinterpret())
                }
                onl.ycode.kdbc.ParameterType.DOUBLE -> {
                    val doubleVar = alloc<DoubleVar>().apply { value = data.value.safeCast<Double>() }
                    dbrpcparam(dbContext, null, status, SYBFLT8, -1, sizeOf<DoubleVar>().toInt(), doubleVar.ptr.reinterpret())
                }
                onl.ycode.kdbc.ParameterType.FLOAT -> {
                    val floatVar = alloc<FloatVar>().apply { value = data.value.safeCast<Float>() }
                    dbrpcparam(dbContext, null, status, SYBREAL, -1, sizeOf<FloatVar>().toInt(), floatVar.ptr.reinterpret())
                }
                onl.ycode.kdbc.ParameterType.STRING -> {
                    val str = data.value.safeCast<String>()
                    val bytes = str.encodeToByteArray()
                    val buffer = allocArray<ByteVar>(bytes.size + 1)
                    bytes.usePinned { pinned ->
                        platform.posix.memcpy(buffer, pinned.addressOf(0), bytes.size.toULong())
                    }
                    buffer[bytes.size] = 0
                    dbrpcparam(dbContext, null, status, SYBVARCHAR, -1, bytes.size, buffer.reinterpret())
                }
                else -> {
                    // For other types, convert to string
                    val str = data.value.toString()
                    val bytes = str.encodeToByteArray()
                    val buffer = allocArray<ByteVar>(bytes.size + 1)
                    bytes.usePinned { pinned ->
                        platform.posix.memcpy(buffer, pinned.addressOf(0), bytes.size.toULong())
                    }
                    buffer[bytes.size] = 0
                    dbrpcparam(dbContext, null, status, SYBVARCHAR, -1, bytes.size, buffer.reinterpret())
                }
            }
        }
    }

    private fun extractProcedureName(sql: String): String {
        val trimmed = sql.trim()

        // Remove JDBC escape syntax {CALL ...}
        val withoutBraces = if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            trimmed.substring(1, trimmed.length - 1).trim()
        } else trimmed

        // Remove CALL or EXEC keyword
        val withoutKeyword = when {
            withoutBraces.startsWith("CALL ", ignoreCase = true) ->
                withoutBraces.substring(5).trim()
            withoutBraces.startsWith("EXEC ", ignoreCase = true) ->
                withoutBraces.substring(5).trim()
            withoutBraces.startsWith("EXECUTE ", ignoreCase = true) ->
                withoutBraces.substring(8).trim()
            else -> withoutBraces
        }

        // Extract just the procedure name (before parameters or whitespace)
        val procName = withoutKeyword.takeWhile { it != '(' && it != ' ' && it != '\t' }

        if (procName.isEmpty()) {
            throw SQLException("Could not extract procedure name from: $sql")
        }

        return procName
    }

    private fun findOutParameterIndex(returnIndex: Int): Int? {
        // OUT parameters are returned in order
        val outParams = parameters.entries.filter { it.value.isOut }.sortedBy { it.key }
        return outParams.getOrNull(returnIndex - 1)?.key
    }

    private fun buildCallSql(): String {
        // Build EXEC statement with parameters
        val paramList = parameters.entries.sortedBy { it.key }.joinToString(", ") { (_, param) ->
            when (val value = param.value) {
                null -> "NULL"
                is String -> "'${value.replace("'", "''")}'"
                else -> value.toString()
            }
        }

        return if (paramList.isEmpty()) {
            "EXEC $procedureName"
        } else {
            "EXEC $procedureName $paramList"
        }
    }
}
