// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.fuse.dbfile;

import onl.ycode.fuse.DirEntry;
import onl.ycode.fuse.PathEntry;
import ru.serce.jnrfuse.struct.FuseContext;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static onl.ycode.fuse.dbfile.SchemaEntry.getTablesAsEntry;
import static onl.ycode.stormify.StormifyManager.stormify;

class CatalogEntry extends DirEntry {
    private final String packg;
    private Collection<PathEntry> entries;

    CatalogEntry(String name, FuseContext context, String packg) {
        super(name, context);
        this.packg = packg;
    }

    @Override
    public Iterable<PathEntry> getEntries() {
        if (entries == null)
            entries = getSchemasAsEntries(getName(), getContext(), packg);
        return entries;
    }

    static List<PathEntry> getSchemasAsEntries(String catalog, FuseContext context, String packg) {
        List<String> schemas = getSchemas(catalog);
        if (schemas.isEmpty())
            return getTablesAsEntry(catalog, null, context, packg);
        else {
            List<PathEntry> entries = new ArrayList<>();
            for (String schema : schemas)
                entries.add(new SchemaEntry(schema, context, catalog, packg));
            return entries;
        }
    }

    static List<String> getSchemas(String catalog) {
        List<String> schemas = new ArrayList<>();
        try (Connection conn = stormify().getDataSource().getConnection()) {
            ResultSet schemasRs = conn.getMetaData().getSchemas(catalog, null);
            while (schemasRs.next())
                schemas.add(schemasRs.getString("TABLE_SCHEM"));
            schemasRs.close();
        } catch (Exception e) {
            stormify().getLogger().error("Unable to get schemas", e);
        }
        return schemas;
    }
}
