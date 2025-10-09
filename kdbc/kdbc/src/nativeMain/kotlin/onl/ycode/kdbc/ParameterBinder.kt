package onl.ycode.kdbc

import kotlinx.cinterop.*

/**
 * Unified interface for binding parameters to native database statements.
 *
 * This interface provides a common abstraction for the "store value → allocate → bind"
 * pattern used across all native drivers (PostgreSQL, MariaDB, Oracle).
 *
 * Drivers implement this interface to provide driver-specific buffer allocation
 * and binding logic while sharing the common ParameterStorage value container.
 *
 * Memory management pattern:
 * 1. Store value in ParameterStorage (no allocation)
 * 2. Call allocateAndBind() within memScoped block (temporary allocation)
 * 3. Execute statement (uses temporary buffers)
 * 4. memScoped exits, buffers automatically freed
 *
 * Example usage:
 * ```kotlin
 * // Step 1: Store value
 * paramStorage.store(42)
 *
 * // Step 2-4: Allocate, bind, execute, cleanup (all in memScoped)
 * memScoped {
 *     val binder = createDriverSpecificBinder()
 *     binder.allocateAndBind(this, paramStorage, paramIndex)
 *     executeStatement()
 * } // Automatic cleanup!
 * ```
 */
@OptIn(ExperimentalForeignApi::class)
interface ParameterBinder {

    /**
     * Allocates native buffers for the parameter value and binds them to the statement.
     *
     * This method must:
     * 1. Read the value from ParameterStorage
     * 2. Allocate native buffers using MemScope.alloc/allocArray
     * 3. Copy/encode the value into the buffers
     * 4. Bind the buffers to the native statement (driver-specific)
     *
     * Buffers allocated with MemScope will be automatically freed when the scope exits.
     *
     * @param scope The MemScope to use for allocations
     * @param storage The ParameterStorage containing the value to bind
     * @param parameterIndex The 1-based parameter index
     * @throws SQLException if binding fails
     */
    fun allocateAndBind(scope: MemScope, storage: ParameterStorage, parameterIndex: Int)
}

/**
 * Factory interface for creating ParameterBinder instances.
 * Each driver provides its own implementation.
 */
interface ParameterBinderFactory {
    /**
     * Creates a ParameterBinder for the given statement.
     *
     * @param statement The native statement handle (driver-specific type)
     * @return A ParameterBinder instance configured for this statement
     */
    fun createBinder(statement: Any): ParameterBinder
}
