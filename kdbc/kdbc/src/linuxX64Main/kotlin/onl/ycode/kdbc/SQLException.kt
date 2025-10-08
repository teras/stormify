package onl.ycode.kdbc

/**
 * Basic SQLException for KDBC.
 */
class SQLException(message: String, cause: Throwable? = null) : Exception(message, cause)
