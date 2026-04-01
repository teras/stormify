package onl.ycode.stormify;

// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

import onl.ycode.stormify.pojos.TestC;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static onl.ycode.stormify.StormifyManager.stormify;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests that Stormify works with Spring JDBC DataSource wrappers.
 * Only runs when -Dstormify.test.db=spring-jdbc
 */
class SpringJdbcTest {

    private static boolean available = false;

    @BeforeAll
    static void init() {
        String testDb = System.getProperty("stormify.test.db");
        if (!"spring-jdbc".equals(testDb)) return;

        try {
            // Use Spring's SimpleDriverDataSource wrapping SQLite
            Class<?> dsClass = Class.forName("org.springframework.jdbc.datasource.SimpleDriverDataSource");
            Class<?> driverClass = Class.forName("org.sqlite.JDBC");
            Object driver = driverClass.getDeclaredConstructor().newInstance();
            Object springDs = dsClass.getDeclaredConstructor().newInstance();
            dsClass.getMethod("setDriver", java.sql.Driver.class).invoke(springDs, driver);
            java.io.File tempDb = java.io.File.createTempFile("stormify-spring-test", ".db");
            tempDb.deleteOnExit();
            dsClass.getMethod("setUrl", String.class).invoke(springDs, "jdbc:sqlite:" + tempDb.getAbsolutePath());

            // Spring's SimpleDriverDataSource implements javax.sql.DataSource
            stormify().setDataSource((javax.sql.DataSource) springDs);
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("Spring JDBC not available");
            return;
        }
        stormify().registerPrimaryKeyResolver(0, (a, c) -> c.toLowerCase().startsWith("id"));
        available = true;
        System.out.println("Connected via Spring JDBC DataSource, dialect: " + stormify().getSqlDialect());
    }

    @BeforeEach
    void check() {
        assumeTrue(available, "Spring JDBC test not active");
    }

    @Test
    void testCrudViaSpringDataSource() {
        StormifyManager s = stormify();

        // TestC maps to table "test"
        s.executeUpdate("CREATE TABLE test (id INT PRIMARY KEY, name TEXT)");

        TestC item = new TestC(1, "SpringTest");
        item.create();
        assertEquals("[TestC(id=1, name=SpringTest)]", s.findAll(TestC.class, null).toString());

        item.setName("Updated");
        item.update();
        TestC found = s.findById(TestC.class, 1);
        assertEquals("Updated", found.getName());

        item.delete();
        assertTrue(s.findAll(TestC.class, null).isEmpty());
    }
}
