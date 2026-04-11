// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.biglist

/**
 * A single aggregation slot (one column in the generated `SELECT` list).
 * [expression] is the pre-built SQL fragment for the aggregate function
 * (e.g. `"SUM(company.revenue)"`), and [alias] is the column alias used to
 * key the value in [MultiAggregator.execute]'s result map.
 */
internal data class AggregateEntry(val expression: String, val alias: String)
