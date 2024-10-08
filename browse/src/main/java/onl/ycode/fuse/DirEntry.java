// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.fuse;

import ru.serce.jnrfuse.struct.FileStat;
import ru.serce.jnrfuse.struct.FuseContext;

public abstract class DirEntry extends PathEntry {
    protected DirEntry(String name, FuseContext context) {
        super(name, context);
    }

    @Override
    @SuppressWarnings("OctalInteger")
    protected int updateAttr(FileStat stat) {
        stat.st_mode.set(FileStat.S_IFDIR | 0775);
        return super.updateAttr(stat);
    }

    public PathEntry getEntry(String name) {
        for (PathEntry entry : getEntries())
            if (entry.getName().equals(name))
                return entry;
        return null;
    }

    public abstract Iterable<PathEntry> getEntries();
}
