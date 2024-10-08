// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.fuse;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.*;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Logger;

public class DbDriver implements Driver {
    private final Driver driver;

    static void load(File jar) throws IOException, ClassNotFoundException, NoSuchMethodException, InvocationTargetException, InstantiationException, IllegalAccessException, SQLException {
        URLClassLoader loader = new URLClassLoader(new URL[]{jar.toURI().toURL()}, Main.class.getClassLoader());
        String className;
        try (JarFile jarFile = new JarFile(jar)) {
            JarEntry entry = jarFile.getJarEntry("META-INF/services/java.sql.Driver");
            if (entry == null)
                throw new IllegalArgumentException("JAR file " + jar.getAbsolutePath() + " does not contain a driver");
            className = new String(readAllBytes(jarFile.getInputStream(entry))).trim();
            System.out.println("Loading driver " + className);
        }
        Driver driver = (Driver) Class.forName(className, true, loader)
                .getDeclaredConstructor()
                .newInstance();
        DriverManager.registerDriver(new DbDriver(driver));
    }

    public DbDriver(Driver driver) {
        this.driver = driver;
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        return driver.connect(url, info);
    }

    @Override
    public boolean acceptsURL(String url) throws SQLException {
        return driver.acceptsURL(url);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        return driver.getPropertyInfo(url, info);
    }

    @Override
    public int getMajorVersion() {
        return driver.getMajorVersion();
    }

    @Override
    public int getMinorVersion() {
        return driver.getMinorVersion();
    }

    @Override
    public boolean jdbcCompliant() {
        return driver.jdbcCompliant();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return driver.getParentLogger();
    }

    @Override
    public String toString() {
        return driver.toString();
    }

    @Override
    public int hashCode() {
        return driver.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return driver.equals(obj);
    }

    private static byte[] readAllBytes(InputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int bytesRead;
        byte[] data = new byte[16384]; // Buffer size of 16KB
        while ((bytesRead = inputStream.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, bytesRead);
        }
        return buffer.toByteArray();
    }
}