package demo

import onl.ycode.stormify.generated.Tables

fun linuxPaths(): List<String> = listOf(Tables.EL_.toString(), Tables.EL_.linuxOnly.toString())
