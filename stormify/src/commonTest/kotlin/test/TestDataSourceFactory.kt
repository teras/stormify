// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import onl.ycode.kdbc.DataSource

data class TestDatabase(val name: String, val dataSource: DataSource)

expect fun createTestDatabases(): List<TestDatabase>
