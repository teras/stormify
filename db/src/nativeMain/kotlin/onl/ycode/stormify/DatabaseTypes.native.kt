@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package onl.ycode.stormify


actual interface PreparedStatement : Statement
actual interface DataSource
actual interface Connection : AutoCloseable
actual interface DatabaseMetaData
actual interface Savepoint
actual interface Statement : AutoCloseable
actual interface ResultSet : AutoCloseable
actual interface CallableStatement : PreparedStatement