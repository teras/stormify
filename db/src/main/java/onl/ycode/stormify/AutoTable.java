// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static onl.ycode.stormify.StormifyManager.stormify;

/**
 * A common abstract class to support auto-populating of fields.
 * <p>
 * This class supports the method {@link #autoPopulate()} which is able to fetch the data from the database when
 * triggered. It is still important to add the call to {@link #autoPopulate()} before accessing (setting or getting)
 * any of the fields that should be auto-populated.
 * <p>
 * The idea is, to trigger the call to the {@link #autoPopulate()} method early, before accessing any fields, so
 * when the fields are accessed, they are already populated.
 * <p>
 * The primary key fields are required to pre-exist, when the population action takes place. These properties
 * should never be used together with the {@link #autoPopulate()} method.
 * <p>
 * In addition, this class supports the common Object methods {@link #equals(Object)}, {@link #hashCode()} and
 * {@link #toString()}. They use the primary keys of the table to calculate the hash code and the equality, while
 * the {@link #toString()} method prints the primary keys and their values together with the class name.
 * <p>
 * An example of a class that extends this class is:
 * <pre>
 *  public class MyTable extends AutoTable {
 *      private Integer id = null;
 *      private String name = null;
 *      ...
 *
 *      public Integer getId() {
 *          return id;
 *      }
 *
 *      public void setId(Integer id) {
 *          this.id = id;
 *      }
 *
 *      public String getName() {
 *          autoPopulate();
 *          return name;
 *      }
 *
 *      public void setName(String name) {
 *          autoPopulate();
 *          this.name = name;
 *      }
 *      ...
 * }
 * </pre>
 * Note that the {@link #autoPopulate()} method should be called before accessing the fields itself. This method is similar to what JPA
 * does with the lazy loading of an object. The main difference is, this method needs to be defined explicitly by the developer, instead
 * of adding arbitrary code to pojo classes.
 */
public abstract class AutoTable {

    private volatile boolean isDirty = true;
    private final TableInfo tableInfo = stormify().getTableInfo(getClass());

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" +
                tableInfo.getPrimaryKeys().stream()
                        .map(it -> it.getName() + "=" + it.getValue(this))
                        .collect(Collectors.joining(", ")) + "]";
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (other == null || getClass() != other.getClass()) return false;
        return tableInfo.getPrimaryKeys().stream()
                .allMatch(it -> Objects.equals(it.getValue(this), it.getValue(other)));
    }

    @Override
    public int hashCode() {
        List<FieldInfo> primaryKeys = tableInfo.getPrimaryKeys();
        if (primaryKeys.size() == 1) {
            Object value = primaryKeys.get(0).getValue(this);
            return value != null ? value.hashCode() : 0;
        } else {
            return primaryKeys.stream()
                    .map(column -> column.getValue(this))
                    .filter(Objects::nonNull)
                    .map(Object::hashCode)
                    .reduce(0, (acc, hash) -> acc ^ hash);
        }
    }

    /**
     * Automatically populates the fields of this object. The ID field should already have been set.
     */
    protected void autoPopulate() {
        if (isDirty)
            synchronized (this) {
                if (isDirty) {
                    isDirty = false;
                    stormify().forcePopulate(this);
                }
            }
    }

    /**
     * Marks this object as already populated, so no further population needs to be done.
     */
    protected synchronized void markPopulated() {
        isDirty = false;
    }
}
