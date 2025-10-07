package onl.ycode.kdbc.mariadb

import onl.ycode.kdbc.Savepoint

/**
 * MariaDB/MySQL Savepoint implementation.
 */
class MariadbSavepoint(override val savepointName: String) : Savepoint
