// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.fuse;

import ru.serce.jnrfuse.struct.FileStat;
import ru.serce.jnrfuse.struct.FuseContext;

public abstract class PathEntry {
    private final String name;
    private final long nowSec;
    private final long nowNsec;
    private final FuseContext context;

    protected PathEntry(String name, FuseContext context) {
        this.name = name;
        this.context = context;
        long now = System.currentTimeMillis();
        this.nowSec = now / 1000;
        this.nowNsec = (now % 1000) * 1000000;
    }

    public String getName() {
        return name;
    }

    protected int updateAttr(FileStat stat) {
        stat.st_uid.set(context.uid.get());
        stat.st_gid.set(context.gid.get());

        stat.st_birthtime.tv_sec.set(nowSec);
        stat.st_birthtime.tv_nsec.set(nowNsec);
        stat.st_mtim.tv_sec.set(nowSec);
        stat.st_mtim.tv_nsec.set(nowNsec);
        return 0;
    }

    protected FuseContext getContext() {
        return context;
    }
}
