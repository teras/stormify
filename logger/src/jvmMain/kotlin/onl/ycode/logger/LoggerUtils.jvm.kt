package onl.ycode.logger

import java.text.MessageFormat
import java.text.SimpleDateFormat
import java.util.*

private val dateFormat: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

internal actual fun messageFormat(message: String, args: Array<out Any?>): String =
    MessageFormat.format(message, *args)

internal actual fun nowFormat(): String = dateFormat.format(Date())
