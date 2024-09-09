// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.fuse.dbfile;

import onl.ycode.fuse.FileEntry;
import ru.serce.jnrfuse.struct.FuseContext;

public abstract class InfoEntry extends FileEntry {
    private byte[] data;

    public InfoEntry(String name, FuseContext context) {
        super(name, context);
    }

    @Override
    public byte[] read() {
        if (data == null)
            return getInfo().getBytes();
        return data;
    }

    abstract String getInfo();
}
