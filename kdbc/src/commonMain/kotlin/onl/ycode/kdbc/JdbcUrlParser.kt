// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.kdbc

/**
 * Supported database kinds. Corresponds 1:1 with the `kdbc_driver` C enum.
 */
enum class KdbcDriverKind(val cValue: Int) {
    SQLITE(0),
    POSTGRES(1),
    MARIADB(2),
    ORACLE(3),
    FREETDS(4);
}

/**
 * Parsed representation of a JDBC URL, normalized to what the kdbc C library expects.
 *
 * @property kind Which C driver to use.
 * @property nativeUrl URL in the simplified format consumed by `kdbc_connect`:
 *   - SQLite: file path or `:memory:` (with optional SQLite URI parameters)
 *   - PostgreSQL / MariaDB / MySQL / FreeTDS: `host:port/database`
 *   - Oracle: `host:port/service_name` (SID form is translated to service-name form)
 * @property user Explicit user (may come from URL query params or explicit override).
 * @property password Explicit password (same sources as [user]).
 * @property extraParams Unused key=value pairs from the original URL. These are logged
 *   as warnings by [KdbcDataSource] so the user can see what the native layer ignored.
 */
data class ParsedJdbcUrl(
    val kind: KdbcDriverKind,
    val nativeUrl: String,
    val user: String?,
    val password: String?,
    val extraParams: Map<String, String>
)

/**
 * Parses a standard JDBC URL into the form expected by the kdbc C library.
 *
 * Supported prefixes (fully JDBC-compatible):
 * ```
 * jdbc:sqlite:/path/to/file.db
 * jdbc:sqlite::memory:
 * jdbc:sqlite:file::memory:?cache=shared
 * jdbc:postgresql://host:port/database?user=x&password=y
 * jdbc:mariadb://host:port/database?user=x&password=y
 * jdbc:mysql://host:port/database?user=x&password=y                         // → MARIADB driver
 * jdbc:oracle:thin:@host:port/service_name                                  // modern
 * jdbc:oracle:thin:@//host:port/service_name                                // modern (// form)
 * jdbc:oracle:thin:@host:port:SID                                           // legacy SID
 * jdbc:sqlserver://host:port;databaseName=db;user=x;password=y
 * ```
 *
 * Explicit [user] / [password] arguments override any values in the URL.
 * Unknown URL parameters are returned in [ParsedJdbcUrl.extraParams].
 *
 * @throws SQLException for malformed or unsupported URLs.
 */
object JdbcUrlParser {

    private const val JDBC_PREFIX = "jdbc:"

    fun parse(url: String, user: String? = null, password: String? = null): ParsedJdbcUrl {
        if (!url.startsWith(JDBC_PREFIX))
            throw SQLException("JDBC URL must start with 'jdbc:': $url")

        val rest = url.substring(JDBC_PREFIX.length)

        // Detect sub-scheme (the token before the next ':')
        val colonIdx = rest.indexOf(':')
        if (colonIdx < 0)
            throw SQLException("Malformed JDBC URL: $url")
        val scheme = rest.substring(0, colonIdx).lowercase()
        val body = rest.substring(colonIdx + 1)

        return when (scheme) {
            "sqlite" -> parseSqlite(body, user, password)
            "postgresql", "postgres" -> parseHostBased(KdbcDriverKind.POSTGRES, body, defaultPort = 5432, user, password)
            "mariadb" -> parseHostBased(KdbcDriverKind.MARIADB, body, defaultPort = 3306, user, password)
            "mysql" -> parseHostBased(KdbcDriverKind.MARIADB, body, defaultPort = 3306, user, password)
            "oracle" -> parseOracle(body, user, password)
            "sqlserver", "microsoft" -> parseSqlServer(body, user, password, url)
            else -> throw SQLException("Unsupported JDBC sub-scheme '$scheme' in URL: $url")
        }
    }

    // ---------- SQLite ----------

    private fun parseSqlite(body: String, user: String?, password: String?): ParsedJdbcUrl {
        // SQLite JDBC: jdbc:sqlite:<path>
        //   <path> can be:
        //     /absolute/path.db
        //     relative.db
        //     :memory:
        //     file::memory:?cache=shared
        //     file:/path/to.db?mode=ro
        //   We forward the whole body verbatim — the C driver enables SQLITE_OPEN_URI
        //   and sqlite3_open_v2 handles the URI form natively.
        if (body.isEmpty())
            throw SQLException("SQLite JDBC URL requires a path: 'jdbc:sqlite:<path>'")
        return ParsedJdbcUrl(KdbcDriverKind.SQLITE, body, user, password, emptyMap())
    }

    // ---------- PostgreSQL / MariaDB / MySQL (network, '?' params) ----------

    private fun parseHostBased(
        kind: KdbcDriverKind,
        body: String,
        defaultPort: Int,
        explicitUser: String?,
        explicitPassword: String?
    ): ParsedJdbcUrl {
        // body looks like: //host:port/database?user=x&password=y&ssl=true
        if (!body.startsWith("//"))
            throw SQLException("JDBC URL body must start with '//': jdbc:${kind.name.lowercase()}:$body")
        val afterSlashes = body.substring(2)

        val queryIdx = afterSlashes.indexOf('?')
        val hostAndDb = if (queryIdx < 0) afterSlashes else afterSlashes.substring(0, queryIdx)
        val query = if (queryIdx < 0) "" else afterSlashes.substring(queryIdx + 1)

        val slashIdx = hostAndDb.indexOf('/')
        val hostPortPart: String
        val database: String
        if (slashIdx < 0) {
            hostPortPart = hostAndDb
            database = ""
        } else {
            hostPortPart = hostAndDb.substring(0, slashIdx)
            database = hostAndDb.substring(slashIdx + 1)
        }

        val (host, port) = splitHostPort(hostPortPart, defaultPort)
        val params = parseQueryString(query)

        val user = explicitUser ?: params["user"]
        val password = explicitPassword ?: params["password"]
        val extras = params.filterKeys { it != "user" && it != "password" }

        return ParsedJdbcUrl(kind, "$host:$port/$database", user, password, extras)
    }

    // ---------- Oracle ----------

    private fun parseOracle(body: String, explicitUser: String?, explicitPassword: String?): ParsedJdbcUrl {
        // body begins with the driver flavor: "thin:@..." (we only support "thin")
        // Supported forms after the '@':
        //   host:port/service
        //   //host:port/service
        //   host:port:SID                 (legacy)
        val atIdx = body.indexOf('@')
        if (atIdx < 0)
            throw SQLException("Oracle JDBC URL is missing '@': jdbc:oracle:$body")
        val flavor = body.substring(0, atIdx).trimEnd(':').lowercase()
        if (flavor.isNotEmpty() && flavor != "thin")
            throw SQLException("Only 'thin' Oracle driver is supported, got: '$flavor'")
        var conn = body.substring(atIdx + 1)
        if (conn.startsWith("//")) conn = conn.substring(2)

        // Strip JDBC query params if any (rare on Oracle URLs but handle them)
        val queryIdx = conn.indexOf('?')
        val connPart = if (queryIdx < 0) conn else conn.substring(0, queryIdx)
        val query = if (queryIdx < 0) "" else conn.substring(queryIdx + 1)
        val params = parseQueryString(query)

        // Decide between legacy "host:port:SID" and modern "host:port/service"
        val slashIdx = connPart.indexOf('/')
        val nativeUrl: String
        if (slashIdx >= 0) {
            // host:port/service_name — already the form kdbc_connect expects
            val hostPort = connPart.substring(0, slashIdx)
            val service = connPart.substring(slashIdx + 1)
            val (host, port) = splitHostPort(hostPort, defaultPort = 1521)
            nativeUrl = "$host:$port/$service"
        } else {
            // Possibly legacy host:port:SID — exactly two colons
            val parts = connPart.split(":")
            when (parts.size) {
                3 -> {
                    // host:port:SID → treat SID as service (Oracle XE accepts both)
                    val host = parts[0].ifEmpty { "localhost" }
                    val port = parts[1].toIntOrNull() ?: throw SQLException("Invalid Oracle port: ${parts[1]}")
                    val sid = parts[2]
                    nativeUrl = "$host:$port/$sid"
                }
                2 -> {
                    // host:port — missing service name
                    throw SQLException("Oracle JDBC URL is missing service name or SID: $connPart")
                }
                else -> throw SQLException("Malformed Oracle JDBC URL body: $connPart")
            }
        }

        val user = explicitUser ?: params["user"]
        val password = explicitPassword ?: params["password"]
        val extras = params.filterKeys { it != "user" && it != "password" }

        return ParsedJdbcUrl(KdbcDriverKind.ORACLE, nativeUrl, user, password, extras)
    }

    // ---------- SQL Server / FreeTDS ----------

    private fun parseSqlServer(
        body: String,
        explicitUser: String?,
        explicitPassword: String?,
        originalUrl: String
    ): ParsedJdbcUrl {
        // Expected body forms:
        //   //host:port;databaseName=db;user=x;password=y
        //   //host;databaseName=db
        //   //host\INSTANCE;databaseName=db          (named instance — not supported, we throw)
        // Microsoft legacy prefix jdbc:microsoft:sqlserver:// also routes here.
        var b = body
        if (b.startsWith("sqlserver:")) b = b.substring("sqlserver:".length)
        if (!b.startsWith("//"))
            throw SQLException("SQL Server JDBC URL body must start with '//': $originalUrl")
        b = b.substring(2)

        // Parameters after ';'
        val semi = b.indexOf(';')
        val hostPart = if (semi < 0) b else b.substring(0, semi)
        val paramsStr = if (semi < 0) "" else b.substring(semi + 1)

        if (hostPart.contains('\\'))
            throw SQLException("Named SQL Server instances are not supported (got '$hostPart' in $originalUrl)")

        val (host, port) = splitHostPort(hostPart, defaultPort = 1433)

        val params = mutableMapOf<String, String>()
        paramsStr.split(';').forEach { seg ->
            if (seg.isBlank()) return@forEach
            val eq = seg.indexOf('=')
            if (eq > 0) params[seg.substring(0, eq).lowercase()] = seg.substring(eq + 1)
        }

        val database = params["databasename"] ?: params["database"] ?: ""
        val user = explicitUser ?: params["user"] ?: params["username"]
        val password = explicitPassword ?: params["password"]

        val recognized = setOf("databasename", "database", "user", "username", "password")
        val extras = params.filterKeys { it !in recognized }

        return ParsedJdbcUrl(KdbcDriverKind.FREETDS, "$host:$port/$database", user, password, extras)
    }

    // ---------- helpers ----------

    private fun splitHostPort(hostPort: String, defaultPort: Int): Pair<String, Int> {
        if (hostPort.isEmpty()) return "localhost" to defaultPort
        val idx = hostPort.indexOf(':')
        return if (idx < 0) {
            hostPort to defaultPort
        } else {
            val host = hostPort.substring(0, idx).ifEmpty { "localhost" }
            val portStr = hostPort.substring(idx + 1)
            val port = portStr.toIntOrNull() ?: throw SQLException("Invalid port '$portStr' in '$hostPort'")
            host to port
        }
    }

    private fun parseQueryString(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, String>()
        query.split('&').forEach { pair ->
            if (pair.isBlank()) return@forEach
            val eq = pair.indexOf('=')
            if (eq > 0) {
                val key = urlDecode(pair.substring(0, eq))
                val value = urlDecode(pair.substring(eq + 1))
                result[key] = value
            } else {
                result[urlDecode(pair)] = ""
            }
        }
        return result
    }

    private fun urlDecode(s: String): String {
        if ('%' !in s && '+' !in s) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '+' -> { out.append(' '); i++ }
                c == '%' && i + 2 < s.length -> {
                    val hex = s.substring(i + 1, i + 3)
                    val b = hex.toIntOrNull(16)
                    if (b != null) {
                        out.append(b.toChar())
                        i += 3
                    } else {
                        out.append(c); i++
                    }
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }
}
