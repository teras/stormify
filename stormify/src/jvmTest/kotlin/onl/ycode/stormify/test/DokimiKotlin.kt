package onl.ycode.stormify.test

import onl.ycode.kdbc.Connection
import onl.ycode.kdbc.DataSource
import onl.ycode.stormify.Stormify
import java.io.PrintWriter
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
    override fun getConnection(): Connection {
        TODO("Not yet implemented")
    }
}