// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.fuse;

import com.panayotis.arjs.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.regex.Pattern;

import static onl.ycode.stormify.StormifyManager.stormify;

public class Main {
    public static void main(String[] args) throws IOException, ClassNotFoundException, NoSuchMethodException, InvocationTargetException, IllegalAccessException, SQLException, InstantiationException {
        StringArg jdbcUrl = new StringArg();
        StringArg uname = new StringArg("");
        StringArg password = new StringArg();
        FileArg driver = new FileArg();
        StringArg packg = new StringArg("org.pack.age");
        MultiStringArg extraDbOptions = new MultiStringArg();
        BoolArg debug = new BoolArg();

        List<String> freeArgs = new Args("DbBrowseFS", "DbBrowseFS is a FUSE filesystem that browses a database as regular files.")
                .def("-j", jdbcUrl, "The JDBC connection string")
                .def("-u", uname, "The database username")
                .def("-p", password, "The database password")
                .def("-x", extraDbOptions, "Extra database options")
                .def("-d", driver, "The JDBC driver JAR file")
                .def("--debug", debug, "Debug FileSystem operations")
                .def("--package", packg, "onl.ycode.fuse")
                .defhelp("-h", "--help")
                .alias("-j", "--jdbc")
                .alias("-u", "--username")
                .alias("-p", "--password")
                .alias("-x", "--db-options")
                .alias("-d", "--driver")
                .req("-j")
                .req("-d")
                .execName("java -jar ./dbbrowse.jar")
                .freeArgs("MOUNT_DIR")
                .parse(args);

        DbDriver.load(driver.getValue());
        start(jdbcUrl.getValue(), uname.getValue(), password.getValue(), freeArgs.get(0), debug.getValue(), extraDbOptions.getValue(), packg.getValue());
    }

    public static void start(String jdbcUrl, String username, String password, String mountPoint, boolean debug, List<String> extraDbOptions, String packg) {
        File mountDir = new File(mountPoint);
        mountDir.mkdirs();
        if (!mountDir.isDirectory())
            throw new IllegalArgumentException("Mount point must be a directory");
        String[] mdfiles = mountDir.list();
        if (mdfiles != null && mdfiles.length > 0)
            throw new IllegalArgumentException("Mount point must be empty");
        Path path = mountDir.toPath();

        Pattern pattern = Pattern.compile("^[a-zA-Z_]\\w*+(\\.[a-zA-Z_]\\w*+)*+$");
        if (!pattern.matcher(packg).matches())
            throw new IllegalArgumentException("Invalid package name");

        if (username.isEmpty())
            username = System.getProperty("user.name");

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(jdbcUrl);
        hikariConfig.setUsername(username);
        hikariConfig.setPassword(password);
        extraDbOptions.forEach(opt -> {
            String[] kv = opt.split("=", 2);
            hikariConfig.addDataSourceProperty(kv[0], kv[1]);
        });
        stormify().setDataSource(new HikariDataSource(hikariConfig));

        DbBrowseFS fs = new DbBrowseFS(packg);

        Runtime.getRuntime().addShutdownHook(new Thread(fs::umount));

        try {
            fs.mount(path, true, debug);
        } finally {
            fs.umount();
        }
    }
}
