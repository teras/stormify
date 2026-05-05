package onl.ycode.stormify.schemasync.mockup

import onl.ycode.stormify.Stormify
import onl.ycode.stormify.schemasync.db.Dialect
import onl.ycode.stormify.schemasync.db.DriverManagerDataSource
import java.io.File

/**
 * Command-line entry point for re-seeding a target database with the
 * full synthetic mockup. Run via:
 *
 *   gradle :schema-sync:seedMockup --args="--jdbc-url=... \
 *       --user=... --password=... --entities-dir=... [--shape=full|smoke] \
 *       [--rows-per-table=N] [--seed=42]"
 *
 * After seeding, point schema-sync at the same JDBC URL and the
 * `--entities-dir` to exercise the diff pipeline end-to-end.
 */
object MockupCli {
    @JvmStatic
    fun main(args: Array<String>) {
        val parsed = parse(args) ?: return
        val ds = DriverManagerDataSource(parsed.url, parsed.user, parsed.password)
        val stormify = Stormify(ds)
        val dialect = Dialect.detect(stormify)
        val shape = if (parsed.shape == "smoke") MockupShape.SMOKE else MockupShape.FULL
        val rows = parsed.rowsPerTable ?: if (shape === MockupShape.SMOKE) 5 else 1000
        val spec = MockupBuilder.build(shape = shape, rowsPerTable = rows, seed = parsed.seed)
        MockupSeeder(stormify, dialect, parsed.entitiesDir).seed(spec)
    }

    private data class Args(
        val url: String,
        val user: String?,
        val password: String?,
        val entitiesDir: File,
        val shape: String,
        val rowsPerTable: Int?,
        val seed: Long,
    )

    private fun parse(args: Array<String>): Args? {
        val map = HashMap<String, String>()
        for (a in args) {
            val eq = a.indexOf('=')
            require(a.startsWith("--") && eq > 2) { "expected --key=value, got: $a" }
            map[a.substring(2, eq)] = a.substring(eq + 1)
        }
        val url = map["jdbc-url"] ?: run {
            System.err.println(USAGE)
            return null
        }
        val entitiesDir = File(map["entities-dir"] ?: error("--entities-dir is required"))
        return Args(
            url = url,
            user = map["user"],
            password = map["password"],
            entitiesDir = entitiesDir,
            shape = map["shape"] ?: "full",
            rowsPerTable = map["rows-per-table"]?.toInt(),
            seed = map["seed"]?.toLong() ?: 42L,
        )
    }

    private const val USAGE = """
Usage:
  --jdbc-url=<url>           required, e.g. jdbc:postgresql://localhost:15432/stormify_test
  --user=<user>              optional
  --password=<pwd>           optional
  --entities-dir=<path>      required; Kotlin entity files written here
  --shape=full|smoke         default: full (800 tables); smoke = 16
  --rows-per-table=<n>       default: 1000 for full, 5 for smoke
  --seed=<long>              default: 42
"""
}
