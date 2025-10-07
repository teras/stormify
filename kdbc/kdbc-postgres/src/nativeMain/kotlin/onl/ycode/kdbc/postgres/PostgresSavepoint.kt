package onl.ycode.kdbc.postgres

import onl.ycode.kdbc.Savepoint

/**
 * PostgreSQL Savepoint implementation.
 */
class PostgresSavepoint(override val savepointName: String) : Savepoint
