// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.fuse.dbfile;

import onl.ycode.fuse.meta.ColumnMetaData;
import onl.ycode.fuse.DirEntry;
import onl.ycode.fuse.PathEntry;
import ru.serce.jnrfuse.struct.FuseContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

class TableColumnsEntry extends DirEntry {
    private final List<ColumnMetaData> columns;
    private Collection<PathEntry> entries;

    TableColumnsEntry(List<ColumnMetaData> columns, FuseContext context) {
        super("Columns", context);
        this.columns = columns;
    }

    @Override
    public Iterable<PathEntry> getEntries() {
        if (entries == null) {
            entries = new ArrayList<>();
            for (ColumnMetaData column : columns)
                entries.add(new ColumnEntry(column, getContext()));
        }
        return entries;
    }
}

