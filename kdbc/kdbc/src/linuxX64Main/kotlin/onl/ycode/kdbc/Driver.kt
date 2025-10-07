package onl.ycode.kdbc

/**
 * KDBC Driver interface for database driver implementations.
 */
interface Driver {
    /**
     * Retrieves whether the driver thinks that it can open a connection to the given URL.
     */
    fun acceptsURL(url: String): Boolean

    /**
     * Attempts to make a database connection to the given URL.
     */
    fun connect(url: String, properties: Map<String, String> = emptyMap()): Connection?

    /**
     * Gets the driver's name.
     */
    val driverName: String

    /**
     * Gets the driver's version.
     */
    val driverVersion: String
}

/**
 * Driver manager for registering and managing database drivers.
 */
object DriverManager {
    private val drivers = mutableListOf<Driver>()

    /**
     * Registers a driver with the DriverManager.
     */
    fun registerDriver(driver: Driver) {
        if (!drivers.contains(driver)) {
            drivers.add(driver)
        }
    }

    /**
     * Removes a driver from the DriverManager's list of registered drivers.
     */
    fun deregisterDriver(driver: Driver) {
        drivers.remove(driver)
    }

    /**
     * Attempts to establish a connection to the given database URL.
     */
    fun getConnection(url: String, properties: Map<String, String> = emptyMap()): Connection {
        for (driver in drivers) {
            if (driver.acceptsURL(url)) {
                driver.connect(url, properties)?.let { return it }
            }
        }
        throw SQLException("No suitable driver found for $url")
    }

    /**
     * Retrieves a list of all currently registered drivers.
     */
    fun getDrivers(): List<Driver> = drivers.toList()
}

/**
 * Basic SQLException for KDBC.
 */
class SQLException(message: String, cause: Throwable? = null) : Exception(message, cause)
