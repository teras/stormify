// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.fuse.meta;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.*;

import static onl.ycode.stormify.StormifyManager.stormify;

public class TableMetaData {
    private String tableName;
    private String tableType;
    private String remarks;
    private String catalog;
    private String schema;

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getTableType() {
        return tableType;
    }

    public void setTableType(String tableType) {
        this.tableType = tableType;
    }

    public String getRemarks() {
        return remarks;
    }

    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }

    public String getCatalog() {
        return catalog;
    }

    public void setCatalog(String catalog) {
        this.catalog = catalog;
    }

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public List<ColumnMetaData> getColumns() {
        List<ColumnMetaData> columns = new ArrayList<>();
        try (Connection conn = stormify().getDataSource().getConnection()) {
            Collection<String> primaryKeys = getPrimaryKeys(conn, catalog, schema, tableName);
            Map<String, ForeignTable> foreignKeys = getForeignKeys(conn, catalog, schema, tableName);
            try (ResultSet columnsRs = conn.getMetaData().getColumns(catalog, schema, tableName, null)) {
                while (columnsRs.next())
                    columns.add(new ColumnMetaData(
                            columnsRs.getString("COLUMN_NAME"),
                            columnsRs.getInt("DATA_TYPE"),
                            columnsRs.getString("TYPE_NAME"),
                            columnsRs.getInt("COLUMN_SIZE"),
                            columnsRs.getInt("NULLABLE") == 1,
                            columnsRs.getString("REMARKS"),
                            columnsRs.getString("COLUMN_DEF"),
                       false,//     columnsRs.getString("IS_AUTOINCREMENT").equals("YES"),
                            columnsRs.getInt("ORDINAL_POSITION"),
                            primaryKeys.contains(columnsRs.getString("COLUMN_NAME")),
                            foreignKeys.get(columnsRs.getString("COLUMN_NAME"))
                    ));
            }
        } catch (Exception e) {
            stormify().getLogger().error("Unable to get columns", e);
        }
        return columns;
    }

    private static Collection<String> getPrimaryKeys(Connection conn, String catalog, String schema, String table) {
        Collection<String> primaryKeys = new LinkedHashSet<>();
        try (ResultSet primaryKeysRS = conn.getMetaData().getPrimaryKeys(catalog, schema, table)) {
            while (primaryKeysRS.next())
                primaryKeys.add(primaryKeysRS.getString("COLUMN_NAME"));
        } catch (Exception e) {
            stormify().getLogger().error("Unable to get primary keys", e);
        }
        return primaryKeys;
    }

    private static Map<String, ForeignTable> getForeignKeys(Connection conn, String catalog, String schema, String table) {
        Map<String, ForeignTable> foreignKeys = new HashMap<>();
        try (ResultSet foreignKeysRS = conn.getMetaData().getImportedKeys(catalog, schema, table)) {
            while (foreignKeysRS.next())
                foreignKeys.put(foreignKeysRS.getString("FKCOLUMN_NAME"),
                        new ForeignTable(
                                foreignKeysRS.getString("PKTABLE_NAME"),
                                foreignKeysRS.getString("PKCOLUMN_NAME")));
        } catch (Exception e) {
            stormify().getLogger().error("Unable to get foreign keys", e);
        }
        return foreignKeys;
    }
}
