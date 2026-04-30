package onl.ycode.stormify.schemasync.db

/** Quick smoke test: dumps all classified columns from a JDBC URL passed on stdin/CLI. */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("usage: introspectorDemo <jdbc-url> [user] [password]")
        return
    }
    val url = args[0]
    val user = args.getOrNull(1)
    val password = args.getOrNull(2)
    DbIntrospector.connect(url, user, password).use { conn ->
        val columns = DbIntrospector(conn).listColumns()
        println("Found ${columns.size} columns to classify in $url")
        columns.forEach {
            val cat = it.category?.name ?: "(det)"
            println("  ${it.key.padEnd(40)} ${cat.padEnd(8)} ${it.dbType}")
        }
    }
}
