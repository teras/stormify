package onl.ycode.logger

import kotlinx.cinterop.*
import platform.posix.localtime
import platform.posix.time
import platform.posix.time_tVar

@OptIn(ExperimentalForeignApi::class)
internal actual fun nowFormat(): String {
    val time = time(null)
    val localTime = memScoped {
        localtime(alloc<time_tVar> { value = time }.ptr)?.pointed
            ?: throw Error("Failed to get local time")
    }
    val year = localTime.tm_year + 1900
    val month = localTime.tm_mon + 1
    val day = localTime.tm_mday
    val hour = localTime.tm_hour
    val minute = localTime.tm_min
    val second = localTime.tm_sec
    require(year < 9999) { "Year $year is too big" }
    require(year >= 1000) { "Year $year is too small" }
    require(month <= 12) { "Month $month is too big" }
    require(month >= 1) { "Month $month is too small" }
    require(day <= 31) { "Day $day is too big" }
    require(day >= 1) { "Day $day is too small" }
    require(hour <= 24) { "Hour $hour is too big" }
    require(hour >= 0) { "Hour $hour is too small" }
    require(minute <= 60) { "Minute $minute is too big" }
    require(minute >= 0) { "Minute $minute is too small" }
    require(second <= 60) { "Second $second is too big" }
    require(second >= 0) { "Second $second is too small" }
    return "$year-${if (month < 10) "0" else ""}$month-${if (day < 10) "0" else ""}$day ${if (hour < 10) "0" else ""}$hour:${if (minute < 10) "0" else ""}$minute:${if (second < 10) "0" else ""}$second"
}

internal actual fun messageFormat(message: String, args: Array<out Any?>): String {
    val regex = "\\{(\\d+)}".toRegex()
    return regex.replace(message) { matchResult ->
        // Extract the number inside the curly braces
        val index = matchResult.groupValues[1].toInt() - 1
        // Replace with the corresponding array element or keep original if index is out of bounds
        if (index in args.indices) (args[index]?.toString() ?: "null") else matchResult.value
    }
}
