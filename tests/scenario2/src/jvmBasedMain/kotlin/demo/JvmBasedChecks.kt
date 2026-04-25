package demo

import onl.ycode.stormify.generated.Tables

fun jvmBasedPaths(): List<String> = listOf(Tables.EJ_.toString(), Tables.EJ_.payload.toString())
