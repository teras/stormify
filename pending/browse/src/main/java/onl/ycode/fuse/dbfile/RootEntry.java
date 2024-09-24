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

import static onl.ycode.fuse.dbfile.CatalogEntry.getSchemasAsEntries;
import static onl.ycode.stormify.StormifyManager.stormify;

public class RootEntry extends DirEntry {
    private final String packg;
    private Collection<PathEntry> entries;

    public RootEntry(FuseContext context, String packg) {
        super("/", context);
        this.packg = packg;
    }

    @Override
    public Iterable<PathEntry> getEntries() {
        if (entries == null)
            entries = getCatalogsAsEntries(getContext());
        return entries;
    }

    List<PathEntry> getCatalogsAsEntries(FuseContext context) {
        List<String> catalogs = getCatalogs();
        if (catalogs.isEmpty())
            return getSchemasAsEntries(null, context, packg);
        else {
            List<PathEntry> e = new ArrayList<>();
            for (String catalog : catalogs)
                e.add(new CatalogEntry(catalog, context, packg));
            return e;
        }
    }

    static List<String> getCatalogs() {
        List<String> catalogs = new ArrayList<>();
        try (Connection conn = stormify().getDataSource().getConnection()) {
            ResultSet catalogsRs = conn.getMetaData().getCatalogs();
            while (catalogsRs.next())
                catalogs.add(catalogsRs.getString("TABLE_CAT"));
            catalogsRs.close();
        } catch (Exception e) {
            stormify().getLogger().error("Unable to get catalogs", e);
        }
        return catalogs;
    }
}
