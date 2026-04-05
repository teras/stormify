// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.kdbc

import onl.ycode.logger.LogManager

private val logger = LogManager.getLogger("onl.ycode.kdbc.KdbcDataSource")

actual fun KdbcDataSource(
    url: String,
    user: String?,
    password: String?,
    poolConfig: PoolConfig
): DataSource {
    val parsed = JdbcUrlParser.parse(url, user, password)
    if (parsed.extraParams.isNotEmpty()) {
        logger.warn(
            "KdbcDataSource: the following URL parameters are not supported by the native " +
                    "kdbc driver for ${parsed.kind} and will be ignored: " +
                    parsed.extraParams.entries.joinToString(", ") { "${it.key}=${it.value}" }
        )
    }
    return NativeKdbcDataSource(
        kind = parsed.kind,
        nativeUrl = parsed.nativeUrl,
        user = parsed.user,
        password = parsed.password,
        poolConfig = poolConfig
    )
}
