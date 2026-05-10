package bench

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.posix.CLOCK_MONOTONIC
import platform.posix.clock_gettime
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.timespec

@OptIn(ExperimentalForeignApi::class)
fun nowNanos(): Long = memScoped {
    val ts = alloc<timespec>()
    clock_gettime(CLOCK_MONOTONIC, ts.ptr)
    ts.tv_sec * 1_000_000_000L + ts.tv_nsec
}

@OptIn(ExperimentalForeignApi::class)
fun rssKb(): Long = memScoped {
    val fp = fopen("/proc/self/status", "r") ?: return@memScoped -1L
    try {
        val buf = allocArray<kotlinx.cinterop.ByteVar>(256)
        while (fgets(buf, 256, fp) != null) {
            val line = buf.toKString()
            if (line.startsWith("VmRSS:")) {
                return@memScoped line.substringAfter(":").trim().split(" ")[0].toLongOrNull() ?: -1L
            }
        }
        -1L
    } finally {
        fclose(fp)
    }
}

class CsvWriter(private val path: String, append: Boolean) {
    private val sb = StringBuilder()
    private val appendMode = append
    init {
        if (!append) sb.append("db,impl,scenario,kind,iter,duration_ns,rows,rss_kb\n")
    }
    fun row(db: String, impl: String, scenario: String, kind: String, iter: Int, durationNs: Long, rows: Int, rssKb: Long) {
        sb.append(db).append(',').append(impl).append(',').append(scenario).append(',')
            .append(kind).append(',').append(iter).append(',').append(durationNs).append(',')
            .append(rows).append(',').append(rssKb).append('\n')
    }
    @OptIn(ExperimentalForeignApi::class)
    fun close() {
        val mode = if (appendMode) "a" else "w"
        val fp = fopen(path, mode) ?: error("cannot open $path")
        fputs(sb.toString(), fp)
        fclose(fp)
        sb.clear()
    }
}
