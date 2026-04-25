package demo

import onl.ycode.stormify.generated.Tables

fun jvmPaths(): List<String> = listOf(Tables.EJvm_.toString(), Tables.EJvm_.jvmOnly.toString())
