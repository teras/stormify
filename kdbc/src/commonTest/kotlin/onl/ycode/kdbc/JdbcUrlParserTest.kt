// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.kdbc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JdbcUrlParserTest {

    // ---------- SQLite ----------

    @Test fun sqliteAbsolutePath() {
        val r = JdbcUrlParser.parse("jdbc:sqlite:/tmp/test.db")
        assertEquals(KdbcDriverKind.SQLITE, r.kind)
        assertEquals("/tmp/test.db", r.nativeUrl)
        assertNull(r.user)
        assertNull(r.password)
    }

    @Test fun sqliteInMemory() {
        val r = JdbcUrlParser.parse("jdbc:sqlite::memory:")
        assertEquals(KdbcDriverKind.SQLITE, r.kind)
        assertEquals(":memory:", r.nativeUrl)
    }

    @Test fun sqliteSharedMemoryUri() {
        val r = JdbcUrlParser.parse("jdbc:sqlite:file::memory:?cache=shared")
        assertEquals(KdbcDriverKind.SQLITE, r.kind)
        assertEquals("file::memory:?cache=shared", r.nativeUrl)
    }

    @Test fun sqliteMissingPathFails() {
        assertFailsWith<SQLException> { JdbcUrlParser.parse("jdbc:sqlite:") }
    }

    // ---------- PostgreSQL ----------

    @Test fun postgresFullUrl() {
        val r = JdbcUrlParser.parse("jdbc:postgresql://localhost:5432/mydb?user=alice&password=secret")
        assertEquals(KdbcDriverKind.POSTGRES, r.kind)
        assertEquals("localhost:5432/mydb", r.nativeUrl)
        assertEquals("alice", r.user)
        assertEquals("secret", r.password)
        assertTrue(r.extraParams.isEmpty())
    }

    @Test fun postgresDefaultPort() {
        val r = JdbcUrlParser.parse("jdbc:postgresql://host/db")
        assertEquals("host:5432/db", r.nativeUrl)
    }

    @Test fun postgresLocalhostShortform() {
        val r = JdbcUrlParser.parse("jdbc:postgresql:///db")
        assertEquals("localhost:5432/db", r.nativeUrl)
    }

    @Test fun postgresExplicitCredentialsOverrideUrl() {
        val r = JdbcUrlParser.parse(
            "jdbc:postgresql://host/db?user=alice&password=x",
            user = "bob",
            password = "y"
        )
        assertEquals("bob", r.user)
        assertEquals("y", r.password)
    }

    @Test fun postgresExtraParamsPreserved() {
        val r = JdbcUrlParser.parse("jdbc:postgresql://host/db?ssl=true&connectTimeout=10&currentSchema=public")
        assertEquals(mapOf("ssl" to "true", "connectTimeout" to "10", "currentSchema" to "public"), r.extraParams)
    }

    @Test fun postgresAltPrefix() {
        val r = JdbcUrlParser.parse("jdbc:postgres://host/db")
        assertEquals(KdbcDriverKind.POSTGRES, r.kind)
    }

    // ---------- MariaDB / MySQL ----------

    @Test fun mariadbFullUrl() {
        val r = JdbcUrlParser.parse("jdbc:mariadb://localhost:3306/stormify_test?user=u&password=p")
        assertEquals(KdbcDriverKind.MARIADB, r.kind)
        assertEquals("localhost:3306/stormify_test", r.nativeUrl)
        assertEquals("u", r.user)
        assertEquals("p", r.password)
    }

    @Test fun mysqlRoutesToMariadbDriver() {
        val r = JdbcUrlParser.parse("jdbc:mysql://host:3306/db")
        assertEquals(KdbcDriverKind.MARIADB, r.kind)
        assertEquals("host:3306/db", r.nativeUrl)
    }

    @Test fun mariadbDefaultPort() {
        val r = JdbcUrlParser.parse("jdbc:mariadb://host/db")
        assertEquals("host:3306/db", r.nativeUrl)
    }

    // ---------- Oracle ----------

    @Test fun oracleModernServiceName() {
        val r = JdbcUrlParser.parse("jdbc:oracle:thin:@localhost:1521/XEPDB1")
        assertEquals(KdbcDriverKind.ORACLE, r.kind)
        assertEquals("localhost:1521/XEPDB1", r.nativeUrl)
    }

    @Test fun oracleModernDoubleSlash() {
        val r = JdbcUrlParser.parse("jdbc:oracle:thin:@//localhost:1521/XEPDB1")
        assertEquals("localhost:1521/XEPDB1", r.nativeUrl)
    }

    @Test fun oracleLegacySid() {
        val r = JdbcUrlParser.parse("jdbc:oracle:thin:@localhost:1521:XE")
        assertEquals("localhost:1521/XE", r.nativeUrl)
    }

    @Test fun oracleMissingServiceFails() {
        assertFailsWith<SQLException> { JdbcUrlParser.parse("jdbc:oracle:thin:@host:1521") }
    }

    @Test fun oracleInvalidFlavorFails() {
        assertFailsWith<SQLException> { JdbcUrlParser.parse("jdbc:oracle:oci:@host:1521/svc") }
    }

    // ---------- SQL Server ----------

    @Test fun sqlserverBasic() {
        val r = JdbcUrlParser.parse("jdbc:sqlserver://localhost:1433;databaseName=mydb;user=sa;password=secret")
        assertEquals(KdbcDriverKind.MSSQL, r.kind)
        assertEquals("localhost:1433/mydb", r.nativeUrl)
        assertEquals("sa", r.user)
        assertEquals("secret", r.password)
    }

    @Test fun sqlserverDefaultPort() {
        val r = JdbcUrlParser.parse("jdbc:sqlserver://host;databaseName=db")
        assertEquals("host:1433/db", r.nativeUrl)
    }

    @Test fun sqlserverCaseInsensitiveParams() {
        val r = JdbcUrlParser.parse("jdbc:sqlserver://h:1433;DatabaseName=d;User=u;Password=p")
        assertEquals("u", r.user)
        assertEquals("p", r.password)
    }

    @Test fun sqlserverNamedInstanceFails() {
        assertFailsWith<SQLException> {
            JdbcUrlParser.parse("jdbc:sqlserver://host\\SQLEXPRESS;databaseName=db")
        }
    }

    // ---------- Error cases ----------

    @Test fun missingJdbcPrefixFails() {
        assertFailsWith<SQLException> { JdbcUrlParser.parse("postgresql://host/db") }
    }

    @Test fun unknownSchemeFails() {
        assertFailsWith<SQLException> { JdbcUrlParser.parse("jdbc:cassandra://host/db") }
    }

    @Test fun invalidPortFails() {
        assertFailsWith<SQLException> { JdbcUrlParser.parse("jdbc:postgresql://host:notaport/db") }
    }

    // ---------- URL decoding in query params ----------

    @Test fun urlEncodedPasswordIsDecoded() {
        val r = JdbcUrlParser.parse("jdbc:postgresql://host/db?user=u&password=p%40ss%20word")
        assertEquals("p@ss word", r.password)
    }
}
