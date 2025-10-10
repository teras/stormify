// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.biglist

import onl.ycode.stormify.*
import onl.ycode.stormify.biglist.DefaultDataConverter.guessConverter

abstract class GenericReference internal constructor(internal open val node: NodeField)

class SortReference internal constructor(node: NodeField) : GenericReference(node) {
    /**
     * Determines whether this reference is activated, i.e. a join will be made in the SQL query.
     */
    var isActivated: Boolean = false
    var isAscending = true
}

class FilterReference internal constructor(node: NodeField) : GenericReference(node) {

    /**
     * For text filters, sets whether the search should be case-sensitive or not. If not set, the default is case-insensitive.
     */
    var isCaseSensitive = false

    /**
     * The converter to use, to transform the textual value of the filter to a database value. If not set,
     * a default converter is used based on the data type of the column. Note that this method should return the
     * full query part, including the column name and the "?" symbols.
     *
     * The lambda function has as parameters the column name, the input value, and a consumer that will receive
     * possible arguments for the query. It will return the query part that corresponds to the filter value.
     */
    var valueConverter: (column: String, input: String, args: (Any) -> Unit) -> String =
        guessConverter(node.type) { isCaseSensitive }

    /**
     * Sets the value of the filter. If the value is null, the filter is disabled.
     * If the value is [FilteredList.NULL], the filter is set to search for NULL values.
     *
     *
     * For numeric and temporal data types, the default parser supports ranges and comparisons.
     * To define a range, use the syntax `'min ... max'`, where min and max are inclusive.
     * To define a comparison, use the syntax `'operator value'`, where `operator` is one of
     *
     *  * `<` for less than
     *  * `<=` for less than or equal
     *  * `>` for greater than
     *  * `>=` for greater than or equal
     *
     * For example:
     *
     *  * for values between 10 and 20: `'10 ... 20'`
     *  * for values greater than 10: `'> 10'`
     *  * for values less than or equal to 20: `'<= 20'`
     *
     *
     * @param value The value to set
     * @return An error message, or null if the value was set successfully
     */
    fun setValue(value: String?) = when (value) {
        null -> parseSetValue(emptyList(), "")
        PagedList.NULL -> parseSetValue(emptyList(), "${node.columnHandler} IS NULL")
        else -> runCatching {
            val newValue = mutableListOf<Any>()
            parseSetValue(newValue, valueConverter(node.columnHandler, value, newValue::add))
        }.exceptionOrNull()?.message
    }

    internal fun valueExists() = query.isNotEmpty()

    internal fun appendConstraint(out: StringBuilder, args: MutableList<Any>) = if (valueExists()) {
        if (out.isNotEmpty())
            out.append(" OR ")
        out.append(query)
        args.addAll(this.args)
        true
    } else false

    private fun parseSetValue(newArgs: Collection<Any>, newQuery: String): String? {
        if (args != newArgs || query != newQuery) node.invalidate()
        args = newArgs
        query = newQuery
        return null
    }

    private var args: Collection<Any> = emptyList()
    private var query = ""

}

/**
 * Represents a custom reference to a table in the query. A custom reference is a reference
 * that is not automatically resolved by the SQL query builder, but is manually added by the user.
 *
 *
 * The reference is represented by a node in the tree of joined tables, which is used to
 * build the SQL query.
 * When custom constraints are added to the query, the reference can be used to get the
 * alias of the table, to create the actual custom constraint.
 *
 *
 * The reference can be activated or deactivated, i.e. a join will be made in the SQL query.
 */
class CustomReference internal constructor(node: NodeField) : GenericReference(node) {
    /**
     * Determines whether this reference is activated, i.e. a join will be made in the SQL query.
     */
    var isActivated: Boolean = false
}
