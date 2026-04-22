// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.biglist

/**
 * A handle to a node in the query's JOIN tree — either the root entity table
 * or an FK-traversed table reached through a dotted path. Exposes the
 * engine-assigned SQL alias so raw SQL expressions (in [Facet]s built via
 * `addSqlFacet`, or in `setConstraints`) can reference the right table
 * regardless of how the engine numbers its joined aliases.
 *
 * Obtain a `TableRef` via [PagedList.addTableRef][AbstractPagedList.addTableRef] or
 * [PagedQuery.addTableRef][AbstractPagedQuery.addTableRef]. The library guarantees that the
 * corresponding JOIN is active in every SQL build for which [isActive] is
 * `true`, without the caller having to reference the table elsewhere.
 *
 * ## Example
 *
 * ```kotlin
 * val query = PagedQuery<Customer>().apply {
 *     val address = addTableRef("address")
 *     addFacet("name", "name")
 *     addSqlFacet("cityDisplay", "${address}.city", Facet.TEXT)
 *     //                          ^ toString() returns alias
 * }
 * ```
 *
 * String interpolation (`"${address}.city"`) reads the alias via
 * [toString]. The explicit [alias] property is also available for Java
 * callers: `"${address.getAlias()}.city"`.
 *
 * ## Activation
 *
 * By default, every registered `TableRef` keeps its JOIN active on every
 * SQL build. Toggle [isActive] to `false` to disable the ref without
 * removing it — useful when a ref is referenced by a raw facet that is
 * conditionally present. Note that in a stateless [PagedQuery][AbstractPagedQuery],
 * toggling [isActive] after publishing the query to concurrent callers
 * is undefined behavior; set the flag during setup only.
 */
class TableRef internal constructor(
    private val core: PagedQueryCore<*>,
    private val node: NodeTable,
) {
    /**
     * The SQL alias of this table as it will appear in the generated query.
     * For the root entity's table: the actual table name (e.g. `"customer"`).
     * For an FK-traversed table: the engine-assigned alias (e.g. `"t2"`).
     *
     * The alias is stable for the lifetime of this ref — it is assigned
     * when the underlying node is first created and never changes.
     */
    val alias: String get() = node.tableHandler

    /** Returns [alias], so `TableRef` works directly in string interpolation. */
    override fun toString(): String = alias

    /**
     * Whether this ref's JOIN is included in the generated SQL. Defaults
     * to `true`. Set to `false` to suppress the JOIN without removing the
     * ref — useful when a ref is referenced only by a facet that may be
     * conditionally inactive.
     */
    var isActive: Boolean = true
        set(value) {
            core.checkMutable()
            field = value
        }

    internal fun tryActivate() {
        if (isActive) node.activate()
    }
}
