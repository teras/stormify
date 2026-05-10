package bench

import onl.ycode.stormify.DbField
import onl.ycode.stormify.DbTable

/**
 * DTO for projection queries (does not map to a real table). The @DbTable annotation
 * triggers annproc/TableInfo generation needed on native, but the table name is unused
 * because we never create/update/delete instances — only read into them.
 */
@DbTable(name = "__dto_child_parent")
data class ChildParentDto(
    var cid: Int = 0,
    var value: Double = 0.0,
    var name: String? = null,
)

@DbTable(name = "bench_warmup")
data class BenchWarmup(
    @DbField(primaryKey = true) var id: Int = 0,
    var payload: String? = null,
)

@DbTable(name = "bench_parent")
data class BenchParent(
    @DbField(primaryKey = true) var id: Int = 0,
    var name: String? = null,
    var createdAt: Long = 0L,
)

@DbTable(name = "bench_child")
data class BenchChild(
    @DbField(primaryKey = true) var id: Int = 0,
    @DbField(name = "parent_id") var parentId: Int = 0,
    var status: String? = null,
    var value: Double = 0.0,
    var payload: String? = null,
    var createdAt: Long = 0L,
)
