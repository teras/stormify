// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.tmaker;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;
import static onl.ycode.stormify.StormifyManager.stormify;

/**
 * A filtered list that retrieves its data from a database. The list is created based
 * on a table entity, can be filtered using a field path and a constraint clause, and
 * can be sorted using another field path.
 *
 * @param <T> The type of the entity
 */
public class FilteredList<T> extends PagedDBList<T> {

    /**
     * The string representation of a null value in the database. If we want to search for NULL values,
     * it is important to use this constant, instead of the regular null value.
     */
    public static final String NULL = "―";

    private final AtomicInteger tableCounter = new AtomicInteger(0);
    private final List<CustomReference> custom = new ArrayList<>();
    private final List<List<FilterReference>> where = new ArrayList<>();
    private final List<TableReference> sort = new ArrayList<>();
    private final List<Boolean> ascending = new ArrayList<>();
    private final Node root;
    private boolean treeIsDirty = true;

    /**
     * Creates a new filtered list based on the given entity.
     *
     * @param entity The entity class
     */
    public FilteredList(Class<T> entity) {
        super(entity);
        root = new Node("", null, stormify().getTableInfo(entity), null);
    }

    @Override
    protected String getTableName() {
        resolveCurrentTree();
        StringBuilder out = new StringBuilder(super.getTableName());
        root.getForeignKeys(out);
        return out.toString();
    }

    @Override
    public String getConstraintClause(Consumer<Object> args) {
        resolveCurrentTree();
        StringBuilder andOut = new StringBuilder(super.getConstraintClause(args));
        for (List<FilterReference> group : where) {
            StringBuilder orOut = new StringBuilder();
            int found = 0;
            for (FilterReference value : group)
                if (value.appendConstraint(orOut, args))
                    found++;
            if (found > 0) {
                if (andOut.length() > 0)
                    andOut.append(" AND ");
                andOut.append(found > 1 ? "(" : "").append(orOut).append(found > 1 ? ")" : "");
            }
        }
        return andOut.toString();
    }

    @Override
    public String getSorting() {
        resolveCurrentTree();
        if (sort.isEmpty())
            return super.getSorting();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < sort.size(); i++) {
            if (i > 0)
                out.append(", ");
            TableReference filter = sort.get(i);
            out.append(filter.getColumnName());
            if (Boolean.FALSE.equals(ascending.get(i)))
                out.append(" DESC");
        }
        return out.toString();
    }

    /**
     * Adds a custom reference to the list. The custom reference is based on the given field path.
     *
     * @param fields The field path, based on the object property names. It is possible to use foreign keys, which
     *               will be automatically joined in the SQL query. The last field in the path must be a table.
     * @return The custom reference, which can be used to access the specifix table.
     */
    public CustomReference addCustomReference(String... fields) {
        CustomReference result = new CustomReference(findNode(fields, false));
        custom.add(result);
        invalidate();
        return result;
    }

    /**
     * Adds a sorting order to the list. The sorting order is based on the given field path.
     *
     * @param ascending True if the sorting order is ascending, false if it is descending
     * @param fields    The field path, based on the object property names. It is possible to use foreign keys, which
     *                  will be automatically joined in the SQL query. The last field in the path must be a primitive.
     * @return The table reference, which can be used to access the specific table.
     */
    public TableReference addSortingOrder(boolean ascending, String... fields) {
        FilterReference result = new FilterReference(this, findNode(fields, true));
        sort.add(result);
        this.ascending.add(ascending);
        invalidate();
        return result;
    }

    /**
     * Clears the sorting order of the list.
     */
    public void clearSortingOrder() {
        sort.clear();
        invalidate();
    }

    /**
     * Adds a filter to the list. The filter is based on the given field path.
     * Each filter is combined with the previous filters using the AND operator.
     *
     * @param fields The field path, based on the object property names. It is possible to use foreign keys, which
     *               will be automatically joined in the SQL query. The last field in the path must be a primitive.
     * @return The filter reference, which can be used to set the filter value and manipulate the specific filter.
     */
    public FilterReference addFilter(String... fields) {
        FilterReference value = new FilterReference(this, findNode(fields, true));
        List<FilterReference> group = new ArrayList<>();
        group.add(value);
        where.add(group);
        invalidate();
        return value;
    }

    /**
     * Adds a filter to the list. The filter is based on the given field path.
     * This filter is combined with the previous filter using the OR operator.
     *
     * @param fields The field path, based on the object property names. It is possible to use foreign keys, which
     *               will be automatically joined in the SQL query. The last field in the path must be a primitive.
     * @return The filter reference, which can be used to set the filter value and manipulate the specific filter.
     */
    public FilterReference addAlsoWithFilter(String... fields) {
        if (where.isEmpty())
            throw new IllegalStateException("No filter to append to; this is the first filter ever");
        FilterReference value = new FilterReference(this, findNode(fields, true));
        where.get(where.size() - 1).add(value);
        invalidate();
        return value;
    }


    private Node findNode(String[] fields, boolean lastIsLeaf) {
        requireNonNull(fields, "Fields cannot be null");
        if (fields.length == 0)
            throw new IllegalArgumentException("Fields cannot be empty");
        Node last = root;
        for (String fieldName : fields)
            last = last.findChild(fieldName, tableCounter);
        if (lastIsLeaf && !last.isLeaf())
            throw new IllegalArgumentException("Last field in path must be primitive");
        else if (!lastIsLeaf && last.isLeaf())
            throw new IllegalArgumentException("Last field in path must be a table");
        return last;
    }

    private void resolveCurrentTree() {
        if (treeIsDirty) {
            treeIsDirty = false;
            root.deactivate();
            root.activate();
            for (List<FilterReference> list : where)
                for (FilterReference t : list)
                    if (t.valueExists())
                        t.activate();
            for (TableReference t : sort)
                t.activate();
            for (CustomReference t : custom)
                if (t.isActivated())
                    t.activate();
        }
    }

    @Override
    protected synchronized void invalidate() {
        super.invalidate();
        treeIsDirty = true;
    }
}
