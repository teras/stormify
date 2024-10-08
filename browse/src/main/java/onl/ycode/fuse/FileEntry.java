// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.fuse;

import ru.serce.jnrfuse.struct.FileStat;
import ru.serce.jnrfuse.struct.FuseContext;

public abstract class FileEntry extends PathEntry {
    public FileEntry(String name, FuseContext context) {
        super(name, context);
    }

    @Override
    @SuppressWarnings("OctalInteger")
    protected int updateAttr(FileStat stat) {
        stat.st_mode.set(FileStat.S_IFREG | 0664);
        stat.st_size.set(read().length * 2);
        return super.updateAttr(stat);
    }

    public abstract byte[] read();
}
