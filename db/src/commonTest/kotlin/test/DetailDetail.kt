// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import onl.ycode.stormify.AutoTable
import onl.ycode.stormify.DbField

internal class DetailDetail(var type: String, toDetail1: Detail, toDetail2: Detail) : AutoTable() {
    @DbField(primaryKey = true)
    var id: Int = 0
    private var toDetail1: Detail
    private var toDetail2: Detail

    init {
        this.toDetail1 = toDetail1
        this.toDetail2 = toDetail2
    }

    fun getToDetail1(): Detail {
        return toDetail1
    }

    fun setToDetail1(toDetail1: Detail) {
        this.toDetail1 = toDetail1
    }

    fun getToDetail2(): Detail {
        return toDetail2
    }

    fun setToDetail2(toDetail2: Detail) {
        this.toDetail2 = toDetail2
    }
}
