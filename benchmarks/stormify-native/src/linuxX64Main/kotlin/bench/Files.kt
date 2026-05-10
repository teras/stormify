package bench

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.cinterop.ByteVar
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen

@OptIn(ExperimentalForeignApi::class)
fun readFileText(path: String): String = memScoped {
    val fp = fopen(path, "r") ?: error("cannot open $path")
    try {
        val buf = allocArray<ByteVar>(8192)
        val sb = StringBuilder()
        while (fgets(buf, 8192, fp) != null) sb.append(buf.toKString())
        sb.toString()
    } finally {
        fclose(fp)
    }
}
