@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package onl.ycode.stormify

expect interface Statement : AutoCloseable
expect interface PreparedStatement : Statement
expect interface CallableStatement : PreparedStatement
expect interface DataSource
expect interface Connection : AutoCloseable
expect interface DatabaseMetaData
expect interface Savepoint
expect interface ResultSet : AutoCloseable
