// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify;

import java.util.List;

import static java.util.Objects.requireNonNull;
import static onl.ycode.stormify.Utils.map;

class EntityData<T> {
    static final int NO_ID_FIELDS = 1;
    static final int NULL_ID_FIELDS = 2;
    static final int ID_FOUND = 3;

    final Class<T> itemClass;
    final TableInfo tableInfo;
    final String table;
    final List<FieldInfo> idFields;
    final List<Object> idValues;
    final int status;

    @SuppressWarnings("unchecked")
    EntityData(T entity, ClassRegistry registry) {
        requireNonNull(entity, "Object cannot be null");
        this.itemClass = (Class<T>) entity.getClass();
        this.tableInfo = registry.getTableInfo(itemClass);
        this.table = tableInfo.getTableName();
        this.idFields = tableInfo.getPrimaryKeys();
        this.idValues = map(idFields, it -> it.getValue(entity));
        if (idValues.isEmpty())
            status = NO_ID_FIELDS;
        else {
            boolean idMissing = false;
            for (Object idField : idValues)
                if (idField == null) {
                    idMissing = true;
                    break;
                }
            if (idMissing) status = NULL_ID_FIELDS;
            else status = ID_FOUND;
        }
    }
}
