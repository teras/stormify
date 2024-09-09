// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.tmaker;

import onl.ycode.stormify.SqlDialect;
import onl.ycode.stormify.TableInfo;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import static onl.ycode.stormify.StormifyManager.stormify;
import static onl.ycode.stormify.TypeUtils.castTo;
import static onl.ycode.tmaker.Utils.silence;

/**
 * A paged list that retrieves its data from a database. The list is created based
 * on a table entity and can be filtered using a constraint clause.
 *
 * @param <T> The type of the entity
 */
public class PagedDBList<T> extends PagedList<T> {

    final TableInfo info;
    private final Class<T> classType;
    private volatile Integer size;
    private String constraintClause = "";
    private BigDecimal selectedID;
    private boolean isDistinct = false;

    /**
     * Creates a new paged list based on the given entity.
     *
     * @param entity The entity class
     */
    public PagedDBList(Class<T> entity) {
        super();
        classType = entity;
        info = stormify().getTableInfo(entity);
    }

    @Override
    public int size() {
        if (size == null) {
            synchronized (this) {
                if (size == null) {
                    List<Object> args = new ArrayList<>();
                    String query = "SELECT " + constrDistinct() + "COUNT(*) FROM " + getTableName() + constrConstraint(args::add);
                    Integer sizeFound = silence(() -> stormify().readOne(Integer.class, query, args.toArray()));
                    size = sizeFound == null ? 0 : sizeFound;
                }
            }
        }
        return size;
    }

    @Override
    protected List<T> getFragment(int lowBound, int upperBound) {
        // Overridable methods
        // Create query
        List<Object> args = new ArrayList<>();
        String query = stormify().getSqlDialect().queryFormatter.
                apply(constrDistinct(), getTableName(), constrConstraint(args::add), constrSorting(), lowBound, upperBound);
        List<T> result = silence(() -> stormify().read(classType, query, args.toArray()));
        return result == null ? Collections.emptyList() : result;
    }

    private String constrDistinct() {
        return isDistinct ? "DISTINCT " : "";
    }

    private String constrConstraint(Consumer<Object> args) {
        String constraint = getConstraintClause(args);
        return constraint.isEmpty() ? "" : " WHERE " + constraint;
    }

    private String constrSorting() {
        SqlDialect sqlDialect = stormify().getSqlDialect();
        String sorting = getSorting();
        String sortById = sqlDialect.orderByIdDialect.apply(info.getPrimaryKey().getDbName(), selectedID);
        return (sortById == null ? "" : sortById + ", ") + sorting;
    }

    /**
     * Retrieve the class type that the elements of the list are based on.
     *
     * @return The class type
     */
    public Class<T> getClassType() {
        return classType;
    }

    /**
     * Retrieve the table name, that the query will be based on.
     *
     * @return The table name
     */
    protected String getTableName() {
        return info.getTableName();
    }

    /**
     * Retrieve the constraint clause that will be used in the query. This clause is
     * appended to the WHERE keyword.
     *
     * @param args The arguments for the constraint clause should be deposited here
     * @return The constraint clause
     */
    public String getConstraintClause(Consumer<Object> args) {
        return constraintClause;
    }

    /**
     * Set the constraint clause that will be used in the query
     *
     * @param constraintClause The constraint clause to use. If the constraint is on the main table,
     *                         it is optional to use the name of the table as a prefix for the column name.
     *                         If other tables are required, either use constructs like {@code REFERENCE_ID IN (...)}
     *                         or might get a {@link CustomReference} to get a reference to any table in the table tree.
     * @return The constraint clause
     */
    public PagedDBList<T> setConstraintClause(String constraintClause) {
        Objects.requireNonNull(constraintClause, "Constraint clause cannot be null");
        this.constraintClause = constraintClause.trim();
        invalidate();
        return this;
    }

    /**
     * Retrieve the sorting field(s) that will be used in the query. This field(s) is
     * appended to the ORDER BY keyword.
     *
     * @return The sorting field(s)
     */
    public String getSorting() {
        return info.getTableName() + "." + info.getPrimaryKey().getDbName();
    }

    /**
     * Check if the list will have unique results.
     *
     * @return true if distinct
     */
    public boolean isDistinct() {
        return isDistinct;
    }

    /**
     * Set whether the list will have unique results.
     *
     * @param distinct true if distinct
     */
    public void setDistinct(boolean distinct) {
        if (this.isDistinct != distinct) {
            invalidate();
            this.isDistinct = distinct;
        }
    }

    /**
     * Set an entity as selected. The entity must be part of the list. The selected entity will
     * appear first in the list, followed by the rest of the entities.
     *
     * @param selected The entity to appear first in the list
     */
    public synchronized void setSelected(T selected) {
        selectedID = selected == null ? null
                : castTo(BigDecimal.class, silence(() -> info.getPrimaryKey().getValue(selected)));
        invalidate();
    }

    @Override
    protected synchronized void invalidate() {
        super.invalidate();
        size = null;
    }
}
