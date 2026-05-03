package com.example.demo

import onl.ycode.stormify.DbField
import onl.ycode.stormify.DbTable

// Variant: only the primary key sits in the constructor; body declarations are
// separated by a blank line. Schema-sync should detect both the constructor
// style AND the blank-line preference, and add new properties accordingly.

@DbTable
data class Case2KtOnly(
    @DbField(primaryKey = true, autoIncrement = true)
    var id: Long = 0,
) {
    var name: String = ""

    var note: String? = null
}

@DbTable
data class Case3KtExtras(
    @DbField(primaryKey = true, autoIncrement = true)
    var id: Long = 0,
) {
    var name: String = ""

    var code: String? = null

    var extra1: String = ""

    var extra2: Int = 0

    var parent: Case7Synced? = null
}

@DbTable
data class Case4DbExtras(
    @DbField(primaryKey = true, autoIncrement = true)
    var id: Long = 0,
) {
    var name: String = ""

    var code: String? = null
}

@DbTable
data class Case5NoOverlap(
    @DbField(primaryKey = true, autoIncrement = true)
    var id: Long = 0,
) {
    var ktAlpha: String = ""

    var ktBeta: Int = 0
}

@DbTable
data class Case6PartialOverlap(
    @DbField(primaryKey = true, autoIncrement = true)
    var id: Long = 0,
) {
    var sharedA: String = ""

    var sharedB: Int = 0

    var ktOnlyM: String = ""

    var ktOnlyN: Int = 0
}

@DbTable
data class Case7Synced(
    @DbField(primaryKey = true, autoIncrement = true)
    var id: Long = 0,
) {
    var name: String = ""

    var flag: Int = 0
}
