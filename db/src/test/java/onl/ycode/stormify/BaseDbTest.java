package onl.ycode.stormify;

// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

import ch.qos.logback.classic.Level;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.LoggerFactory;

import java.sql.Connection;

import static onl.ycode.stormify.StormifyManager.stormify;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

abstract class BaseDbTest {

    static boolean dbAvailable = false;

    @BeforeAll
    public static void initDatabase() {
        if (dbAvailable) return; // already initialized by another test class
        String configPath = System.getProperty("stormify.test.config");
        if (configPath == null || configPath.isEmpty()) configPath = null;
        try {
            HikariConfig config = configPath != null ? new HikariConfig(configPath) : new HikariConfig();
            if (configPath == null) config.setJdbcUrl("jdbc:sqlite::memory:");
            ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger("com.zaxxer.hikari")).setLevel(Level.ERROR);
            stormify().setDataSource(new HikariDataSource(config));
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }
        try (Connection c = stormify().getDataSource().getConnection()) {
            if (!c.isValid(10)) return;
        } catch (Exception e) {
            return;
        }
        stormify().registerPrimaryKeyResolver(0, (a, col) -> col.toLowerCase().startsWith("id"));
        dbAvailable = true;
        System.out.println("Connected to database, dialect: " + stormify().getSqlDialect());
    }

    @BeforeEach
    public void checkDb() {
        assumeTrue(dbAvailable, "Database not available");
    }
}
