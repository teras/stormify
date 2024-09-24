package onl.ycode.stormify.test

import onl.ycode.stormify.DataSource
import onl.ycode.stormify.Stormify
import java.io.PrintWriter
import java.sql.Connection
import java.util.logging.Logger

fun koko() {
    val mydb = Stormify(MyConnection())

    mydb.transaction {
        val q = read<Int>("")

    }
//
//    mydb {
//        val q = read<Int>("")
//        val q = read<Int>("")
//
////        create("create table test (id int primary key, name varchar(100))")de g;ine
//
//        "select count(*) from test".readCursor<Double>(33) { println(it) }
//
//        findAll<Double>()
//        read<String>("select count(*) from test")
//    }
//    mydb.readCursor(Double::class, "select count(*) from test", 33) { println(it) }
//    mydb.read<Double>("select count(*) from test")

}

class MyConnection : DataSource {
    override fun getLogWriter(): PrintWriter {
        TODO("Not yet implemented")
    }

    override fun setLogWriter(p0: PrintWriter?) {
        TODO("Not yet implemented")
    }

    override fun setLoginTimeout(p0: Int) {
        TODO("Not yet implemented")
    }

    override fun getLoginTimeout(): Int {
        TODO("Not yet implemented")
    }

    override fun getParentLogger(): Logger {
        TODO("Not yet implemented")
    }

    override fun <T : Any?> unwrap(p0: Class<T>?): T {
        TODO("Not yet implemented")
    }

    override fun isWrapperFor(p0: Class<*>?): Boolean {
        TODO("Not yet implemented")
    }

    override fun getConnection(): Connection {
        TODO("Not yet implemented")
    }

    override fun getConnection(p0: String?, p1: String?): Connection {
        TODO("Not yet implemented")
    }


}