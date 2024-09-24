// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.fuse.dbfile;

import onl.ycode.fuse.DirEntry;
import onl.ycode.fuse.PathEntry;
import onl.ycode.fuse.meta.TableMetaData;
import ru.serce.jnrfuse.struct.FuseContext;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static onl.ycode.stormify.StormifyManager.stormify;

class SchemaEntry extends DirEntry {
    private final String catalog;
    private final String packg;
    private Collection<PathEntry> entries;

    SchemaEntry(String name, FuseContext context, String catalog, String packg) {
        super(name, context);
        this.catalog = catalog;
        this.packg = packg;
    }

    @Override
    public Iterable<PathEntry> getEntries() {
        if (entries == null)
            entries = getTablesAsEntry(catalog, getName(), getContext(), packg);
        return entries;
    }

    static List<PathEntry> getTablesAsEntry(String catalog, String schema, FuseContext context, String packg) {
        List<PathEntry> entries = new ArrayList<>();
        for (TableMetaData table : getTables(catalog, schema))
            entries.add(new TableEntry(table, context, catalog, schema, packg));
        return entries;
    }

    private static List<TableMetaData> getTables(String catalog, String schema) {
        List<TableMetaData> tables = new ArrayList<>();
        try (Connection conn = stormify().getDataSource().getConnection()) {
            ResultSet tablesRs = conn.getMetaData().getTables(catalog, schema, null, null);
            while (tablesRs.next()) {
                TableMetaData tableInfo = new TableMetaData();
                tables.add(tableInfo);
                tableInfo.setTableName(tablesRs.getString("TABLE_NAME"));
                tableInfo.setTableType(tablesRs.getString("TABLE_TYPE"));
                tableInfo.setRemarks(tablesRs.getString("REMARKS"));
                tableInfo.setCatalog(tablesRs.getString("TABLE_CAT"));
                tableInfo.setSchema(tablesRs.getString("TABLE_SCHEM"));
            }
            tablesRs.close();
        } catch (Exception e) {
            stormify().getLogger().error("Unable to get tables", e);
        }
        return tables;
    }
}
