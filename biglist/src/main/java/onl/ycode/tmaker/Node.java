// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.tmaker;

import onl.ycode.stormify.FieldInfo;
import onl.ycode.stormify.TableInfo;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Collections.emptyMap;
import static onl.ycode.stormify.StormifyManager.stormify;
import static onl.ycode.tmaker.DefaultDataConverter.validate;

class Node {
    private final String tableAlias;
    private final Node parent;
    private final TableInfo tableInfo;
    private final FieldInfo fieldInfo;
    private final Map<String, Node> children;
    private boolean active = false;

    Node(String tableAlias, Node parent, TableInfo tableInfo, FieldInfo fieldInfo) {
        this.tableAlias = tableAlias;
        this.parent = parent;
        this.tableInfo = tableInfo;
        this.fieldInfo = fieldInfo;
        this.children = tableInfo == null ? emptyMap() : new LinkedHashMap<>();
    }

    Node findChild(String fieldName, AtomicInteger tableCounter) {
        if (isLeaf())
            throw new IllegalStateException("Field " + fieldInfo.getName() + " is not a reference field");
        validate(fieldName);
        Node child = children.get(fieldName);
        if (child == null) {
            FieldInfo childField = tableInfo.getField(fieldName);
            if (childField == null)
                throw new IllegalArgumentException("Field " + fieldName + " not found in table " + tableInfo.getClassType().getName());
            TableInfo childTable;
            String tableName;
            if (childField.isReference()) {
                childTable = stormify().getTableInfo(childField.getType());
                tableName = "t" + tableCounter.incrementAndGet();
            } else {
                childTable = null;
                tableName = null;
            }
            child = new Node(tableName, this, childTable, childField);
            children.put(fieldName, child);
        }
        return child;
    }

    boolean isLeaf() {
        return tableInfo == null;
    }

    // BotToM-up activation
    void activate() {
        active = true;
        if (parent != null)
            parent.activate();
    }

    // Top-down deactivation
    void deactivate() {
        active = false;
        for (Node child : children.values())
            child.deactivate();
    }

    void getForeignKeys(StringBuilder out) {
        if (isLeaf())
            return;
        for (Node child : children.values()) {
            if (!child.active || child.isLeaf())
                continue;
            out
                    .append(" JOIN ")
                    .append(child.tableInfo.getTableName())
                    .append(" ")
                    .append(child.tableAlias)
                    .append(" ON ")
                    .append(child.tableAlias)
                    .append(".")
                    .append(child.tableInfo.getPrimaryKey().getDbName())
                    .append(" = ")
                    .append(tableAlias.isEmpty() ? tableInfo.getTableName() : tableAlias)
                    .append(".")
                    .append(child.fieldInfo.getDbName());
        }
        for (Node child : children.values())
            child.getForeignKeys(out);
    }

    String getTableAlias() {
        return isLeaf() ? parent.tableAlias : tableAlias;
    }

    String getTableName() {
        String alias = getTableAlias();
        if (!alias.isEmpty())
            return alias;
        return isLeaf() ? parent.tableInfo.getTableName() : tableInfo.getTableName();
    }

    String getColumnName() {
        if (!isLeaf())
            throw new IllegalStateException("Node is not a leaf");
        return getTableName() + "." + fieldInfo.getDbName();
    }

    Class<?> getType() {
        return fieldInfo.getType();
    }
}
