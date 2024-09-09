package onl.ycode.stormify

import onl.ycode.logger.SilentLogger
import onl.ycode.logger.WatchLogger
import onl.ycode.stormify.StormifyManager.stormify

class TestLogger : WatchLogger.Watcher {
    private val origLogger = stormify().logger

    init {
        stormify().logger = WatchLogger(SilentLogger(), this)
    }

    private val buffer = StringBuilder()
    override fun watch(level: String, message: String, throwable: Throwable?) {
        buffer.append("$message\n")
    }

    operator fun invoke() = buffer.toString().also {
        buffer.clear()
    }

    fun close() {
        stormify().logger = origLogger
    }
}