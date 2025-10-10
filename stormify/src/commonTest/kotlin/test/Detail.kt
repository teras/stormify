// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import onl.ycode.stormify.AutoTable
import onl.ycode.stormify.DbField

internal class Detail(var type: String, parent1: test.Master, parent2: test.Master) : AutoTable() {
    @DbField(primaryKey = true)
    var id: Int = 0
    private var toMaster1: test.Master
    private var toMaster2: test.Master

    init {
        this.toMaster1 = parent1
        this.toMaster2 = parent2
    }

    fun getToMaster1(): test.Master {
        return toMaster1
    }

    fun setToMaster1(toMaster1: test.Master) {
        this.toMaster1 = toMaster1
    }

    fun getToMaster2(): test.Master {
        return toMaster2
    }

    fun setToMaster2(toMaster2: test.Master) {
        this.toMaster2 = toMaster2
    }
}
