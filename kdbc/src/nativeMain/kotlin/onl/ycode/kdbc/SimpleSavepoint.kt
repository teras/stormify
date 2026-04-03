package onl.ycode.kdbc

/**
 * Simple Savepoint implementation used by all KDBC drivers.
 *
 * This class is used by Oracle, MariaDB, PostgreSQL, and SQLite drivers
 * to represent database savepoints for nested transaction support.
 *
 * The savepoint name is validated on construction to prevent SQL injection.
 */
class SimpleSavepoint(override val savepointName: String) : Savepoint {
    init {
        if (savepointName.isEmpty()) {
            throw SQLException("Savepoint name cannot be empty")
        }

        if (savepointName.length > 30) {
            throw SQLException(
                "Savepoint name cannot exceed 30 characters. " +
                "Got ${savepointName.length} characters in: '$savepointName'"
            )
        }

        if (!savepointName[0].isLetter()) {
            throw SQLException(
                "Savepoint name must start with a letter. " +
                "Got: '${savepointName[0]}' in '$savepointName'"
            )
        }

        val invalidChars = savepointName.filter { !it.isLetterOrDigit() && it != '_' }
        if (invalidChars.isNotEmpty()) {
            throw SQLException(
                "Invalid savepoint name: '$savepointName'. " +
                "Contains invalid characters: '${invalidChars.toSet().joinToString("")}'. " +
                "Only letters, digits, and underscore are allowed."
            )
        }
    }
}
