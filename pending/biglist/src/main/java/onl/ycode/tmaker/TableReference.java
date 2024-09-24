// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.tmaker;

/**
 * Represents a reference to a table in the query.
 * <p>
 * The reference is represented by a node in the tree of joined tables, which is used to
 * build the SQL query.
 * When constraints are added to the query, the reference can be used to get the
 * alias of the table, to create the actual custom constraint.
 */
public class TableReference {
    private final Node node;

    TableReference(Node node) {
        this.node = node;
    }

    /**
     * Gets the table alias of this reference. The table alias is used in the SQL
     * query to reference the table, when creating custom filters.
     *
     * @return The table alias
     * @see FilterReference#getTableAlias()
     */
    public String getTableAlias() {
        return node.getTableAlias();
    }

    void activate() {
        node.activate();
    }

    String getColumnName() {
        return node.getColumnName();
    }

    Class<?> getType() {
        return node.getType();
    }
}
