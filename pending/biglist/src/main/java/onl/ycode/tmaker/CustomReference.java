// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.tmaker;

/**
 * Represents a custom reference to a table in the query. A custom reference is a reference
 * that is not automatically resolved by the SQL query builder, but is manually added by the user.
 * <p>
 * The reference is represented by a node in the tree of joined tables, which is used to
 * build the SQL query.
 * When custom constraints are added to the query, the reference can be used to get the
 * alias of the table, to create the actual custom constraint.
 * <p>
 * The reference can be activated or deactivated, i.e. a join will be made in the SQL query.
 */
public class CustomReference extends TableReference {
    private boolean isActivated = false;

    CustomReference(Node node) {
        super(node);
    }

    /**
     * Determines whether this reference is activated, i.e. a join will be made in the SQL query.
     *
     * @param activated True if the reference should be activated, false otherwise
     */
    public void setActivated(boolean activated) {
        isActivated = activated;
    }

    /**
     * Inform whether this reference is activated, i.e. a join will be made in the SQL query.
     *
     * @return True if the reference is activated, false otherwise
     */
    public boolean isActivated() {
        return isActivated;
    }
}
