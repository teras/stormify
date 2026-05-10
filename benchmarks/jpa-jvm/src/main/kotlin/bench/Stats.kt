package bench

import java.io.File

fun nowNanos(): Long = System.nanoTime()

fun rssKb(): Long = try {
    File("/proc/self/status").useLines { lines ->
        for (line in lines) if (line.startsWith("VmRSS:"))
            return line.substringAfter(":").trim().split(" ")[0].toLongOrNull() ?: -1L
        -1L
    }
} catch (_: Throwable) { -1L }

class CsvWriter(private val path: String, private val append: Boolean) {
    private val sb = StringBuilder()
    init { if (!append) sb.append("db,impl,scenario,kind,iter,duration_ns,rows,rss_kb\n") }
    fun row(db: String, impl: String, scenario: String, kind: String, iter: Int, durationNs: Long, rows: Int, rssKb: Long) {
        sb.append(db).append(',').append(impl).append(',').append(scenario).append(',')
            .append(kind).append(',').append(iter).append(',').append(durationNs).append(',')
            .append(rows).append(',').append(rssKb).append('\n')
    }
    fun close() {
        File(path).appendText(sb.toString())
        sb.clear()
    }
}
