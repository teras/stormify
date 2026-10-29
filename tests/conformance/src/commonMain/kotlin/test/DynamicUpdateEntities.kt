// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import onl.ycode.stormify.DbField
import onl.ycode.stormify.DbTable
import onl.ycode.stormify.StormifyEntity

/** A [StormifyEntity] for the dynamic update tests: scalars, a blob and a reference. */
@DbTable(name = "dyn_entity")
class DynEntity : StormifyEntity() {
    @DbField(primaryKey = true)
    var id: Int = 0
    var name: String? = null
    var amount: Int? = null
    var payload: ByteArray? = null
    var parent: TestC? = null
}
