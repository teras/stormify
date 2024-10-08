// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import onl.ycode.stormify.AutoTable
import onl.ycode.stormify.DbField

internal class Master(var type: String) : AutoTable() {
    @DbField(primaryKey = true)
    var id: Int = 0
    var value: Int = 0
//    var timestamp: Timestamp? = null
}
