package onl.ycode.logger

internal fun format(message: String, vararg args: Any?): String {
    var msg = message
    for (i in args.indices)
        msg = msg.replaceFirst("\\{}".toRegex(), "{${i + 1}}")
    return messageFormat(msg, args)
}

internal enum class LogLevel {
    DEBUG, INFO, WARN, ERROR, FATAL
}

internal expect fun messageFormat(message: String, args: Array<out Any?>): String
internal expect fun nowFormat(): String
