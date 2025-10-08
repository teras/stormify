package onl.ycode.kdbc.oracle

import onl.ycode.kdbc.Savepoint

/**
 * Oracle Savepoint implementation.
 */
class OracleSavepoint(override val savepointName: String) : Savepoint
