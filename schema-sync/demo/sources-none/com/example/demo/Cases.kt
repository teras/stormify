package com.example.demo

import onl.ycode.stormify.DbField
import onl.ycode.stormify.DbTable

// Variant: nothing in the primary constructor; every property is declared in
// the class body. Schema-sync should detect this style and continue adding new
// properties as body declarations on apply.

@DbTable
class Case2KtOnly {
    @DbField(primaryKey = true, autoIncrement = true)
    var id: Long = 0
    var name: String = ""
    var note: String? = null
}

@DbTable
class Case3KtExtras {
    @DbField(primaryKey = true, autoIncrement = true)
    var id: Long = 0
    var name: String = ""
    var code: String? = null
    var extra1: String = ""
    var extra2: Int = 0
    var parent: Case7Synced? = null
}

@DbTable
class Case4DbExtras {
    @DbField(primaryKey = true, autoIncrement = true)
    var id: Long = 0
    var name: String = ""
    var code: String? = null
}

@DbTable
class Case5NoOverlap {
    @DbField(primaryKey = true, autoIncrement = true)
    var id: Long = 0
    var ktAlpha: String = ""
    var ktBeta: Int = 0
}

@DbTable
class Case6PartialOverlap {
    @DbField(primaryKey = true, autoIncrement = true)
    var id: Long = 0
    var sharedA: String = ""
    var sharedB: Int = 0
    var ktOnlyM: String = ""
    var ktOnlyN: Int = 0
}

@DbTable
class Case7Synced {
    @DbField(primaryKey = true, autoIncrement = true)
    var id: Long = 0
    var name: String = ""
    var flag: Int = 0
}
