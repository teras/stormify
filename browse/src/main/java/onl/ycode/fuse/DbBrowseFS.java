// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.fuse;

import jnr.ffi.Pointer;
import onl.ycode.fuse.dbfile.RootEntry;
import ru.serce.jnrfuse.ErrorCodes;
import ru.serce.jnrfuse.FuseFillDir;
import ru.serce.jnrfuse.FuseStubFS;
import ru.serce.jnrfuse.struct.FileStat;
import ru.serce.jnrfuse.struct.FuseFileInfo;
import ru.serce.jnrfuse.struct.Statvfs;

public class DbBrowseFS extends FuseStubFS {
    private final String packg;
    private DirEntry root;

    public DbBrowseFS(String packg) {
        this.packg = packg;
    }

    @Override
    public int create(String path, long mode, FuseFileInfo fi) {
        System.err.println("create: " + path);
        return super.create(path, mode, fi);
    }

    @Override
    public int getattr(String path, FileStat stat) {
        PathEntry entry = findFile(path);
        if (entry == null)
            return -ErrorCodes.ENOENT();
        else
            return entry.updateAttr(stat);
    }

    @Override
    public int mkdir(String path, long mode) {
        System.err.println("mkdir: " + path);
        return super.mkdir(path, mode);
    }

    @Override
    public int read(String path, Pointer buf, long size, long offset, FuseFileInfo fi) {
        PathEntry file = findFile(path);
        if (file == null)
            return -ErrorCodes.ENOENT();
        else if (!(file instanceof FileEntry))
            return -ErrorCodes.EISDIR();
        FileEntry entry = (FileEntry) file;
        byte[] data = entry.read();
        if (offset < data.length) {
            long length = Math.min(size, data.length - offset);
            buf.put(0, data, (int) offset, (int) length);
            return (int) length;
        } else
            return 0;
    }

    @Override
    public int readdir(String path, Pointer buf, FuseFillDir fillDir, long offset, FuseFileInfo fi) {
        PathEntry entry = findFile(path);
        if (entry == null)
            return -ErrorCodes.ENOENT();
        else if (!(entry instanceof DirEntry))
            return -ErrorCodes.ENOTDIR();
        DirEntry dir = (DirEntry) entry;
        fillDir.apply(buf, ".", null, 0);
        fillDir.apply(buf, "..", null, 0);
        for (PathEntry pathEntry : dir.getEntries())
            fillDir.apply(buf, pathEntry.getName(), null, 0);
        return 0;
    }

    @Override
    public int statfs(String path, Statvfs stbuf) {
        System.err.println("statfs: " + path);
        return super.statfs(path, stbuf);
    }

    @Override
    public int rename(String oldpath, String newpath) {
        System.err.println("rename: " + oldpath + " -> " + newpath);
        PathEntry oldEntry = findFile(oldpath);
        if (oldEntry == null)
            return -ErrorCodes.ENOENT();
        PathEntry newParent = findFile(getParent(newpath));
        if (newParent == null)
            return -ErrorCodes.ENOENT();
        if (!(newParent instanceof DirEntry))
            return -ErrorCodes.ENOTDIR();
        return -ErrorCodes.EPERM();
    }

    @Override
    public int rmdir(String path) {
        System.err.println("rmdir: " + path);
        return super.rmdir(path);
    }

    @Override
    public int truncate(String path, long size) {
        System.err.println("truncate: " + path);
        return super.truncate(path, size);
    }

    @Override
    public int unlink(String path) {
        System.err.println("unlink: " + path);
        return super.unlink(path);
    }

    @Override
    public int open(String path, FuseFileInfo fi) {
        System.err.println("open: " + path);
        return 0;
    }

    @Override
    public int write(String path, Pointer buf, long size, long offset, FuseFileInfo fi) {
        System.err.println("write: " + path);
        return super.write(path, buf, size, offset, fi);
    }

    private DirEntry getRoot() {
        if (root == null)
            root = new RootEntry(getContext(), packg);
        return root;
    }


    private String getParent(String path) {
        if (path.equals("/"))
            return null;
        int index = path.lastIndexOf("/");
        return index == 0 ? "/" : path.substring(0, index);
    }

    private PathEntry findFile(String path) {
        if (path.startsWith("/."))
            return null;
        if ("/".equals(path))
            return getRoot();
        PathEntry entry = getRoot();
        for (String part : path.split("/"))
            if (!part.isEmpty()) {
                if (entry instanceof DirEntry)
                    entry = ((DirEntry) entry).getEntry(part);
                else
                    return null;
            }
        return entry;
    }
}
