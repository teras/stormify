package com.example.demo

import onl.ycode.stormify.DbField
import onl.ycode.stormify.DbTable

// Case 1 — only in the DB. There is no Kotlin entity for `case1_db_only`,
// so schema-sync should propose creating one.

// Case 2 — only in Kotlin. The DB has no `case2_kt_only` table, so
// schema-sync should propose a CREATE TABLE in the migration.
@DbTable
data class Case2KtOnly(
    @DbField(primaryKey = true, autoIncrement = true)
    var id: Long = 0,
    var name: String = "",
    var note: String? = null,
)

// Case 3 — same table; the entity has extras on top of the DB columns. One of
// those extras (`parent`) is an entity reference, so the migration must emit a
// REFERENCES clause when adding the FK column.
@DbTable
data class Case3KtExtras(
    @DbField(primaryKey = true, autoIncrement = true)
    var id: Long = 0,
    var name: String = "",
    var code: String? = null,
    var extra1: String = "",
    var extra2: Int = 0,
    var parent: Case7Synced? = null,
)

// Case 4 — same table; the DB has extras the entity does not declare.
// Expected: schema-sync proposes adding `dbExtra1` and `dbExtra2` to the entity.
@DbTable
data class Case4DbExtras(
    @DbField(primaryKey = true, autoIncrement = true)
    var id: Long = 0,
    var name: String = "",
    var code: String? = null,
)

// Case 5 — same table; both sides have their own columns and (apart from the
// PK) no shared field names. ALTERs on the DB side, INSERTs on the entity side.
@DbTable
data class Case5NoOverlap(
    @DbField(primaryKey = true, autoIncrement = true)
    var id: Long = 0,
    var ktAlpha: String = "",
    var ktBeta: Int = 0,
)

// Case 6 — same table; some shared fields, plus extras on each side.
@DbTable
data class Case6PartialOverlap(
    @DbField(primaryKey = true, autoIncrement = true)
    var id: Long = 0,
    var sharedA: String = "",
    var sharedB: Int = 0,
    var ktOnlyM: String = "",
    var ktOnlyN: Int = 0,
)

// Case 7 — fully synced. Schema-sync should report "in sync" and produce no
// migration statement for this table.
@DbTable
data class Case7Synced(
    @DbField(primaryKey = true, autoIncrement = true)
    var id: Long = 0,
    var name: String = "",
    var flag: Int = 0,
)

// Case 7b — a second, leaner entity mapping the same `case7_synced` table.
// Demonstrates the multi-entity slot scenario: the slot list shows one row
// flagged with `*`, and the right pane lets the user pick which entity
// receives any newly-INSERTed columns.
@DbTable(name = "case7_synced")
data class Case7View(
    @DbField(primaryKey = true)
    var id: Long = 0,
    var name: String = "",
)

// Case 3b — second leaner entity on `case3_kt_extras`. Combined with
// Case3KtExtras this slot is multi-entity AND DIFF (extras on the entity side
// vs. the DB), exercising the cardinality + status filters together.
@DbTable(name = "case3_kt_extras")
data class Case3Summary(
    @DbField(primaryKey = true)
    var id: Long = 0,
    var name: String = "",
)

// Case 4b — second leaner entity on `case4_db_extras`. Multi-entity DIFF row
// where the DB has columns neither entity declares; useful for verifying that
// the union diff is computed across all claimants.
@DbTable(name = "case4_db_extras")
data class Case4Compact(
    @DbField(primaryKey = true)
    var id: Long = 0,
    var name: String = "",
)

// Case 8 — type conflict. DB column `score` is INTEGER, entity declares it as
// String → TYPE_MISMATCH. Drives the "Type conflicts" status filter.
@DbTable
data class Case8Typed(
    @DbField(primaryKey = true, autoIncrement = true)
    var id: Long = 0,
    var name: String = "",
    var score: String = "",
)

// Case 9 — entity backed by a VIEW (`case9_active_view`) rather than a table.
// Drives the "Views" kind filter; the slot list flags it as a view.
@DbTable(name = "case9_active_view")
data class Case9ActiveView(
    @DbField(primaryKey = true)
    var id: Long = 0,
    var name: String = "",
)

// Case 10 — two entities claim a non-existent table. Demonstrates the
// ENTITY_ONLY × MULTI_ENTITY combination for the AND-across-groups filter
// (status: Entity-only tables; cardinality: Multi-entity).
@DbTable(name = "case10_phantom")
data class Case10Phantom(
    @DbField(primaryKey = true, autoIncrement = true)
    var id: Long = 0,
    var label: String = "",
)

@DbTable(name = "case10_phantom")
data class Case10Ghost(
    @DbField(primaryKey = true)
    var id: Long = 0,
    var note: String? = null,
)

// Case 11 — multi-entity DIFF with deltas on **both** sides simultaneously.
// DB has `db_extra` that neither entity declares (→ INSERT splice into the
// entity preview, right pane), and `Case11Primary` declares `kt_extra` that
// DB lacks (→ ALTER TABLE preview, left pane). Useful for verifying that the
// targets picker drives the right-pane content while the left-pane ALTER is
// independent of the entity selection.
@DbTable(name = "case11_mixed")
data class Case11Primary(
    @DbField(primaryKey = true, autoIncrement = true)
    var id: Long = 0,
    var name: String = "",
    var ktExtra: String = "",
)

@DbTable(name = "case11_mixed")
data class Case11Lean(
    @DbField(primaryKey = true)
    var id: Long = 0,
    var name: String = "",
)
