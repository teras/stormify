// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import onl.ycode.kdbc.DataSource

data class TestDatabase(
    val name: String,
    val dataSource: DataSource,
    /** Max blob size the platform can read back via cursor/resultset. 0 = unlimited. */
    val maxBlobTestSize: Int = 0,
)

expect fun createTestDatabases(): List<TestDatabase>
